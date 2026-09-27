package com.steptrackerpro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steptrackerpro.core.ConfigStore
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.IntegrityEvent
import com.steptrackerpro.core.IntegrityFlag
import com.steptrackerpro.core.JsonMaps
import com.steptrackerpro.core.StepStateStore
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.core.StepTrackerCore
import com.steptrackerpro.integrity.ActivityRecognitionBridge
import com.steptrackerpro.integrity.DeviceAttestation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The integrity layer end to end, through the real core, database and
 * Keystore: a swing gadget's metronomic count and a charger are fed in as
 * sensor samples, the verdict flags them, exclude mode takes them out of the
 * resolved number, the event log records what happened, and a signed
 * snapshot verifies against the attested key.
 */
@RunWith(AndroidJUnit4::class)
class IntegrityPipelineTest {

    private lateinit var context: Context
    private lateinit var core: StepTrackerCore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(ConfigStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        core = StepTrackerCore.get(context)
        core.updateConfig(StepTrackerConfig(fraudDetectionEnabled = true))
        core.clearHistory()
        core.integrity.flush()
        core.state.activeDate = DateKeys.today()
    }

    @Test
    fun aSwingGadgetIsFlaggedExcludedLoggedAndSigned() = runBlocking {
        val today = DateKeys.today()
        val now = System.currentTimeMillis()
        // 40 minutes of history are needed inside today.
        val start = (now - 45 * 60_000L) / 60_000L * 60_000L
        assumeTrue(DateKeys.of(start) == today)

        // An install's first reading is credited in one go and never observed.
        var raw = 1_000f
        core.engine.onCounterSample(raw, start - 1_000L)
        // 35 minutes at exactly 110 steps a minute, two samples a minute.
        for (i in 0 until 70) {
            raw += 55f
            core.engine.onCounterSample(raw, start + i * 30_000L + 29_000L)
        }
        // Then three minutes on a charger at a human pace.
        core.integrity.setCharging(true, announce = true)
        val charged = start + 36 * 60_000L
        for (i in 0 until 6) {
            raw += if (i % 2 == 0) 48f else 57f
            core.engine.onCounterSample(raw, charged + i * 30_000L + 29_000L)
        }
        core.integrity.setCharging(false, announce = true)

        val device = core.engine.snapshot().steps
        val report = core.integrity.report(today)
        @Suppress("UNCHECKED_CAST")
        val flags = report["flags"] as List<Map<String, Any?>>
        val steady = flags.single { it["type"] == IntegrityFlag.STEADY_CADENCE }
        assertEquals("strong", steady["severity"])
        assertTrue((steady["steps"] as Int) >= 30 * 110)
        assertTrue(flags.any { it["type"] == IntegrityFlag.CHARGING })
        val suspect = report["suspectSteps"] as Int
        assertTrue(suspect in 3_300..device)

        // Flag mode changes no number.
        assertEquals(device, core.resolveDay(today).totals.steps)
        assertEquals(suspect, core.resolveDay(today).totals.suspectSteps)

        // Exclude mode takes the suspect steps out of the resolved day and the live view.
        core.updateConfig(core.config().copy(fraudMode = "exclude"))
        val excluded = core.resolveDay(today)
        assertEquals(device - suspect, excluded.totals.steps)
        assertEquals(suspect, excluded.suspectStepsExcluded)
        assertEquals(device - suspect, core.displaySnapshot().steps)

        // The event log saw the charger and the mode change.
        val types = core.integrity.report(today)["events"].let { list ->
            @Suppress("UNCHECKED_CAST")
            (list as List<Map<String, Any?>>).map { it["type"] }
        }
        assertTrue(IntegrityEvent.CHARGING_STARTED in types)
        assertTrue(IntegrityEvent.CHARGING_STOPPED in types)
        assertTrue(IntegrityEvent.CONFIG_CHANGED in types)

        // Attest, sign, verify against the attested public key.
        val attestation = DeviceAttestation.attest(context, "server-challenge-1".toByteArray())
        @Suppress("UNCHECKED_CAST")
        assertFalse((attestation["certificateChain"] as List<String>).isEmpty())
        val snapshot = core.verificationSnapshot(today, sign = true, nonce = "n-42")
        @Suppress("UNCHECKED_CAST")
        val signature = snapshot["signature"] as Map<String, Any?>
        assertEquals(attestation["keyId"], signature["keyId"])
        val payload = signature["signedPayload"] as String
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(attestation["publicKey"] as String))
        )
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(payload.toByteArray(Charsets.UTF_8))
        }
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature["value"] as String)))
        // What was signed is the snapshot itself, nonce included.
        val signed = JsonMaps.parse(payload)
        assertEquals("n-42", signed["nonce"])
        assertEquals(device, (signed["deviceSteps"] as Number).toInt())
        assertEquals(suspect, (signed["suspectSteps"] as Number).toInt())
    }

    @Test
    fun withoutPlayServicesActivityRecognitionIsUnavailableNotACrash() = runBlocking {
        // The test APK does not ship play-services-location: the probe must
        // come back false, and asking for it must be a quiet no-op.
        assertFalse(ActivityRecognitionBridge.isAvailable())
        assertFalse(ActivityRecognitionBridge.start(context))
        ActivityRecognitionBridge.stop(context)
        core.updateConfig(core.config().copy(fraudActivityRecognition = true))
        @Suppress("UNCHECKED_CAST")
        val activity = core.integrity.report(DateKeys.today())["activityRecognition"] as Map<String, Any?>
        assertEquals(true, activity["requested"])
        assertEquals(false, activity["available"])
    }

    @Test
    fun withDetectionOffNothingIsRecordedOrChanged() = runBlocking {
        core.updateConfig(StepTrackerConfig())
        val today = DateKeys.today()
        val now = System.currentTimeMillis()
        core.engine.onCounterSample(5_000f, now - 60_000L)
        core.engine.onCounterSample(5_300f, now - 1_000L)
        core.integrity.flush()
        assertTrue(core.repository.minutes(today, today).isEmpty())
        val report = core.integrity.report(today)
        assertEquals(false, report["enabled"])
        assertEquals(0, report["suspectSteps"])
        assertEquals(core.engine.snapshot().steps, core.resolveDay(today).totals.steps)
    }
}
