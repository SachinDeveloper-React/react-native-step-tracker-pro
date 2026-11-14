package com.steptrackerpro

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Tasks
import com.steptrackerpro.core.ActivityState
import com.steptrackerpro.core.ConfigStore
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.StepStateStore
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.integrity.ActivityRecognitionBridge
import com.steptrackerpro.integrity.ActivityTransitionReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The one seam the rest of the suite cannot reach: Play Services' own
 * classes. Everything else drives the motion gate with this package's
 * [com.steptrackerpro.core.ActivityEvent]; here a real
 * `ActivityTransitionResult` goes through the real receiver, and the real
 * registration is put to the Play Services on the device.
 *
 * Compiled and run only with `-PstpPlayServicesTests`, which also puts
 * `play-services-location` on the test APK - see docs/TESTING.md.
 *
 * Two things it does not do. It does not wait for Play Services to send a
 * transition of its own: on an emulator lying still that came one second
 * after registering on one run - "still", through the real PendingIntent
 * and the manifest's receiver - and not within half a minute on six
 * others, so a test of it would mostly be skipped. And it cannot show what
 * Activity Recognition reports in a moving vehicle; that takes a vehicle.
 */
@RunWith(AndroidJUnit4::class)
class ActivityRecognitionGlueTest {

    private lateinit var context: Context
    private lateinit var core: StepTrackerCore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(ConfigStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        core = StepTrackerCore.get(context)
        core.updateConfig(StepTrackerConfig(fraudDetectionEnabled = true, motionGateEnabled = true))
        core.onActivityStopped()
        core.state.activeDate = DateKeys.today()
    }

    /** A broadcast exactly as Play Services sends one: the result serialised into the intent's extras. */
    private fun broadcast(vararg transitions: Pair<Int, Int>, agoMs: Long = 0L): Intent {
        val at = SystemClock.elapsedRealtimeNanos() - agoMs * 1_000_000L
        val result = ActivityTransitionResult(
            transitions.map { (activity, transition) -> ActivityTransitionEvent(activity, transition, at) }
        )
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
            .setAction("com.steptrackerpro.ACTIVITY_TRANSITION")
        SafeParcelableSerializer.serializeToIntentExtra(
            result, intent, "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT"
        )
        return intent
    }

    @Test
    fun aRealTransitionResultGoesThroughTheReceiverToTheGate() = runBlocking {
        assertTrue(ActivityRecognitionBridge.isAvailable())
        val receiver = ActivityTransitionReceiver()
        val now = System.currentTimeMillis()
        assumeTrue(DateKeys.of(now - 600_000L) == DateKeys.today())

        // A first reading, then the usual pair for boarding a bus.
        var raw = 500f
        core.engine.onCounterSample(raw, now - 600_000L)
        receiver.onReceive(
            context,
            broadcast(
                DetectedActivity.STILL to ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                agoMs = 300_000L
            )
        )
        assertEquals(ActivityState.IN_VEHICLE, core.integrity.activity)
        assertTrue(core.motionWitnessWanted())

        // The ride's false steps are held...
        raw += 6f
        core.engine.onCounterSample(raw, now - 200_000L)
        assertEquals(6, core.engine.snapshot().gatedSteps)
        // ...and the walk away from it, until "left the vehicle, entered
        // walking" arrives and gives it back.
        for (i in 1..5) {
            raw += 10f
            core.engine.onCounterSample(raw, now - 40_000L + i * 6_000L)
        }
        assertEquals(56, core.engine.snapshot().gatedSteps)
        receiver.onReceive(
            context,
            broadcast(
                DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                DetectedActivity.WALKING to ActivityTransition.ACTIVITY_TRANSITION_ENTER
            )
        )
        assertEquals(ActivityState.WALKING, core.integrity.activity)
        assertFalse(core.motionWitnessWanted())
        assertEquals(6, core.engine.snapshot().gatedSteps)

        // A bus read as a bicycle in slow traffic stays one ride.
        receiver.onReceive(
            context, broadcast(DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        )
        receiver.onReceive(
            context,
            broadcast(
                DetectedActivity.IN_VEHICLE to ActivityTransition.ACTIVITY_TRANSITION_EXIT,
                DetectedActivity.ON_BICYCLE to ActivityTransition.ACTIVITY_TRANSITION_ENTER
            )
        )
        assertEquals(ActivityState.ON_BICYCLE, core.integrity.activity)
        assertTrue(core.motionWitnessWanted())

        @Suppress("UNCHECKED_CAST")
        val gate = core.integrityReport(DateKeys.today())["motionGate"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val recognition = gate["activityRecognition"] as Map<String, Any?>
        assertEquals(true, recognition["available"])
        assertEquals("on_bicycle", recognition["activity"])
        assertEquals(true, gate["holding"])
    }

    @Test
    fun anIntentWithNoResultInItChangesNothing() {
        ActivityTransitionReceiver().onReceive(
            context,
            Intent(context, ActivityTransitionReceiver::class.java).setAction("com.steptrackerpro.ACTIVITY_TRANSITION")
        )
        assertEquals(ActivityState.UNKNOWN, core.integrity.activity)
        assertFalse(core.motionWitnessWanted())
    }

    @Test
    fun playServicesAcceptsTheRegistrationThisPackageMakes() {
        assumeTrue(
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        )
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, "android.permission.ACTIVITY_RECOGNITION"
        )
        // With no PendingIntent - a reboot, a force stop - no request of an
        // earlier process stands, and nothing saved before is believed (2.10).
        ActivityRecognitionBridge.pendingIntent(context).cancel()
        assertFalse(ActivityRecognitionBridge.registrationSurvives(context))
        // The very request and PendingIntent the service registers: Play
        // Services answers asynchronously, and a refusal - a PendingIntent
        // flag it does not accept, a transition it does not know - would
        // otherwise only ever be a line in a log.
        val intent = ActivityRecognitionBridge.pendingIntent(context)
        Tasks.await(ActivityRecognitionBridge.Gms.request(context, intent), 20, TimeUnit.SECONDS)
        assertTrue(ActivityRecognitionBridge.start(context))
        // And the answer is kept, for the integrity report to say (2.10).
        val until = System.currentTimeMillis() + 20_000L
        while (ActivityRecognitionBridge.status == ActivityRecognitionBridge.STATUS_PENDING &&
            System.currentTimeMillis() < until
        ) {
            Thread.sleep(100)
        }
        assertEquals(ActivityRecognitionBridge.STATUS_REGISTERED, ActivityRecognitionBridge.status)
        assertFalse(ActivityRecognitionBridge.shouldRetry(System.currentTimeMillis()))
        assertTrue(ActivityRecognitionBridge.registrationSurvives(context))
        ActivityRecognitionBridge.stop(context)
        assertEquals(ActivityRecognitionBridge.STATUS_NOT_REQUESTED, ActivityRecognitionBridge.status)
    }
}
