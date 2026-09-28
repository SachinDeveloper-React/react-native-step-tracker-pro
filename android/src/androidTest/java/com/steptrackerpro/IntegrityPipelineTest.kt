package com.steptrackerpro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steptrackerpro.core.ConfigStore
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.SyncTarget
import com.steptrackerpro.core.IntegrityEvent
import com.steptrackerpro.core.IntegrityFlag
import com.steptrackerpro.core.JsonMaps
import com.steptrackerpro.core.MinuteSample
import com.steptrackerpro.core.RemoteSyncStatus
import com.steptrackerpro.core.SnapshotPart
import com.steptrackerpro.core.StepStateStore
import com.steptrackerpro.core.StepTrackerConfig
import com.steptrackerpro.core.StepTrackerCore
import android.content.Intent
import com.steptrackerpro.health.HealthConnectManager
import com.steptrackerpro.integrity.ActivityRecognitionBridge
import com.steptrackerpro.service.BootReceiver
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
        // What was signed is the snapshot itself, nonce included, versioned
        // so a server can pick its parser first.
        val signed = JsonMaps.parse(payload)
        assertEquals("n-42", signed["nonce"])
        assertEquals(StepTrackerCore.SNAPSHOT_SCHEMA_VERSION, (signed["schemaVersion"] as Number).toInt())
        assertEquals(BuildConfig.LIBRARY_VERSION, signed["libraryVersion"])
        assertEquals(snapshot.keys.first(), "schemaVersion")
        assertEquals(device, (signed["deviceSteps"] as Number).toInt())
        assertEquals(suspect, (signed["suspectSteps"] as Number).toInt())
        // A 2.0-shaped call carries none of the evidence parts.
        assertFalse("include" in signed || "minutes" in signed)
        // This test app holds no Health Connect grant: sources were not asked,
        // and the signed payload says so rather than just being empty.
        assertEquals("not_consulted", signed["sourcesStatus"])

        // With include, the evidence is inside the signed payload itself.
        val full = core.verificationSnapshot(
            today, sign = true, nonce = "n-43",
            include = SnapshotPart.entries.toSet(),
            recordTypes = HealthConnectManager.RecordType.entries.toSet()
        )
        @Suppress("UNCHECKED_CAST")
        val fullPayload = JsonMaps.parse((full["signature"] as Map<String, Any?>)["signedPayload"] as String)
        assertEquals(listOf("minutes", "motionWindows", "healthConnectRecords"), fullPayload["include"])
        @Suppress("UNCHECKED_CAST")
        val minutes = fullPayload["minutes"] as List<Map<String, Any?>>
        // The swing gadget's minutes, each with its steps, are what was signed.
        assertTrue(minutes.size >= 35)
        assertTrue(minutes.all { (it["steps"] as Number).toInt() + (it["untimedSteps"] as Number).toInt() > 0 })
        assertEquals(emptyList<Any>(), fullPayload["motionWindows"])
        // This test app declares no Health Connect permission, so the
        // records are absent - and the snapshot says why.
        @Suppress("UNCHECKED_CAST")
        val records = fullPayload["healthConnectRecords"] as Map<String, Any?>
        assertTrue(records["status"] in setOf("unavailable", "not_granted"))
        assertEquals(listOf("steps", "distance"), records["recordTypes"])
        assertEquals(emptyList<Any>(), records["records"])
        // Each evidence list says whether it is being recorded.
        assertEquals("enabled", fullPayload["minutesStatus"])
        assertEquals("disabled", fullPayload["motionWindowsStatus"])

        // Signed only: the signature block alone, the snapshot in it once.
        val only = core.verificationSnapshot(
            today, sign = true, nonce = "n-44", include = setOf(SnapshotPart.MINUTES), signedOnly = true
        )
        assertEquals(setOf("keyId", "algorithm", "value", "attested", "signedPayload", "payloadSha256"), only.keys)
        val onlyPayload = only["signedPayload"] as String
        val onlyVerifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(onlyPayload.toByteArray(Charsets.UTF_8))
        }
        assertTrue(onlyVerifier.verify(Base64.getDecoder().decode(only["value"] as String)))
        assertEquals("n-44", JsonMaps.parse(onlyPayload)["nonce"])
    }

    @Test
    fun aRefusedUploadIsKeptUntilTheCredentialsChange() {
        val state = core.state
        core.updateConfig(core.config().copy(remoteSyncUrl = "https://api.example.com/steps"))
        val first = state.beginRemoteAttempt()
        state.recordRemoteFailure(first, RemoteSyncStatus.UNAUTHORIZED, 401, "Upload refused: HTTP 401", retryable = false)
        var status = state.remoteSyncStatus()
        assertTrue(status.authFailed)
        assertEquals(1, status.consecutiveFailures)
        assertEquals(401, status.lastFailure?.status)

        // A server error after it is not an auth failure, and counts up.
        state.recordRemoteFailure(state.beginRemoteAttempt(), RemoteSyncStatus.HTTP_ERROR, 503, "Upload failed: HTTP 503", retryable = true)
        status = state.remoteSyncStatus()
        assertEquals(2, status.consecutiveFailures)
        assertFalse(status.authFailed)

        // Refused again, then new headers from JS clear it - no clock involved.
        state.recordRemoteFailure(state.beginRemoteAttempt(), RemoteSyncStatus.FORBIDDEN, 403, "Upload refused: HTTP 403", retryable = false)
        assertTrue(state.remoteSyncStatus().authFailed)
        core.updateConfig(core.config().copy(remoteSyncHeaders = mapOf("Authorization" to "Bearer new")))
        assertFalse(state.remoteSyncStatus().authFailed)

        // An attempt that read the old credentials and is refused after the
        // change is not the current state either.
        val stale = RemoteSyncStatus(
            lastFailure = RemoteSyncStatus.Failure(0L, RemoteSyncStatus.FORBIDDEN, 403, "", false, first.generation, 99L),
            credentialsGeneration = state.remoteSyncStatus().credentialsGeneration
        )
        assertFalse(stale.authFailed)

        // An accepted upload resets the count and keeps the failure as history.
        state.recordRemoteSuccess(state.beginRemoteAttempt())
        status = state.remoteSyncStatus()
        assertEquals(0, status.consecutiveFailures)
        assertEquals(RemoteSyncStatus.FORBIDDEN, status.lastFailure?.reason)
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
    fun aRebootIsLoggedOncePerBootCountNotPerBroadcast() = runBlocking {
        context.getSharedPreferences("StepTrackerProBoot", Context.MODE_PRIVATE).edit().clear().commit()
        val receiver = BootReceiver()
        // BOOT_COMPLETED, then the same boot again, then a forged QUICKBOOT_POWERON.
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent("android.intent.action.QUICKBOOT_POWERON"))
        @Suppress("UNCHECKED_CAST")
        val reboots = (core.integrity.report(DateKeys.today())["events"] as List<Map<String, Any?>>)
            .filter { it["type"] == IntegrityEvent.REBOOT }
        assertEquals(1, reboots.size)
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (reboots.single()["detail"] as Map<String, Any?>)["verified"])
    }

    @Test
    fun healthConnectPermissionsTheAppDoesNotDeclareAreReported() {
        // From 2.0 the library manifest declares none, and this test app adds none.
        val undeclared = core.healthConnect.undeclaredPermissions(HealthConnectManager.PermissionScope())
        assertTrue(HealthConnectManager.READ_PERMISSIONS.all { it in undeclared })
        assertTrue(HealthConnectManager.WRITE_PERMISSIONS.all { it in undeclared })
        // Nothing the sensor-only mode needs is missing.
        val declared = com.steptrackerpro.util.PermissionHelper.declaredPermissions(context)
        assertTrue("android.permission.ACTIVITY_RECOGNITION" in declared)
        assertFalse("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in declared)
    }

    @Test
    fun theRequestFitsTheManifestAndOnlyStepsCanFailIt() = runBlocking {
        // This test app declares no Health Connect permission at all.
        val scope = core.permissionScope()
        // Writes are on by default, but with no WRITE_STEPS they are not asked for.
        assertFalse(scope.write)
        assertEquals(setOf(HealthConnectManager.ReadType.STEPS), scope.readTypes)
        // Only READ_STEPS is left to fail the request.
        assertEquals(
            setOf(HealthConnectManager.ReadType.STEPS.permission),
            core.healthConnect.undeclaredPermissions(scope)
        )
        // The status still lists everything config would use, for the developer.
        @Suppress("UNCHECKED_CAST")
        val undeclared = core.healthConnect.status(scope)["undeclaredPermissions"] as List<String>
        assertTrue(undeclared.containsAll(HealthConnectManager.READ_PERMISSIONS + HealthConnectManager.WRITE_PERMISSIONS))
        assertEquals(false, core.healthConnect.status(scope)["stepsGranted"])
    }

    @Test
    fun aKeyASnapshotMadeIsNotTakenForAnAttestedOne() {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(DeviceAttestation.ALIAS)) store.deleteEntry(DeviceAttestation.ALIAS)
        context.getSharedPreferences("StepTrackerProIntegrity", Context.MODE_PRIVATE).edit().clear().commit()

        // A signed snapshot before any attestDevice() makes a key of its own...
        DeviceAttestation.sign(context, "payload".toByteArray())
        assertTrue(DeviceAttestation.hasKey())
        // ...which no server has seen: not "attested already", not for uploads.
        assertFalse(DeviceAttestation.hasAttestedKey(context))
        assertEquals(DeviceAttestation.CREATED_BY_SIGN, DeviceAttestation.describe(context)!!["createdBy"])

        DeviceAttestation.attest(context, "challenge-7".toByteArray())
        assertTrue(DeviceAttestation.hasAttestedKey(context))
        assertEquals(DeviceAttestation.CREATED_BY_ATTEST, DeviceAttestation.describe(context)!!["createdBy"])
    }

    @Test
    fun retentionKeepsDaysASyncTargetHasNotAccepted() = runBlocking {
        val repo = core.repository
        val today = DateKeys.today()
        val old = DateKeys.minusDays(today, 60)
        val older = DateKeys.minusDays(today, 61)
        val ancient = DateKeys.minusDays(today, 400)
        for (date in listOf(old, older, ancient)) repo.saveDay(DayTotals(date, 1_000, 700.0, 30.0))
        repo.markSynced(SyncTarget.REMOTE, listOf(older))
        // The evidence a full upload's integrity report is built from.
        val oldMinute = DateKeys.startOfDayMillis(old) + 9 * 3_600_000L
        val olderMinute = DateKeys.startOfDayMillis(older) + 9 * 3_600_000L
        repo.addMinutes(listOf(MinuteSample(oldMinute, 110), MinuteSample(olderMinute, 110)))
        repo.addEvent(IntegrityEvent(oldMinute, IntegrityEvent.REBOOT))
        repo.addEvent(IntegrityEvent(olderMinute, IntegrityEvent.REBOOT))

        // Remote in use: the day not yet uploaded survives a 35-day window,
        // the uploaded one does not, and nothing survives past a year.
        repo.prune(35, keepUnsyncedRemote = true)
        assertEquals(1_000, repo.getDay(old).steps)
        assertEquals(0, repo.getDay(older).steps)
        assertEquals(0, repo.getDay(ancient).steps)
        // The kept day keeps its minutes and events; the uploaded one does not.
        assertEquals(1, repo.minutes(old, old).size)
        assertEquals(0, repo.minutes(older, older).size)
        assertEquals(1, repo.events(DateKeys.startOfDayMillis(old), DateKeys.endOfDayMillis(old)).size)
        assertEquals(0, repo.events(DateKeys.startOfDayMillis(older), DateKeys.endOfDayMillis(older)).size)

        // No target in use: retention is retention.
        repo.prune(35)
        assertEquals(0, repo.getDay(old).steps)
        assertEquals(0, repo.minutes(old, old).size)
    }

    @Test
    fun theNotificationHidesTheCountOnALockedScreenByDefault() {
        val factory = com.steptrackerpro.service.NotificationFactory(context)
        val snapshot = core.displaySnapshot()
        val private = factory.build(snapshot, StepTrackerConfig().sanitised(), core.metrics)
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, private.visibility)
        val shown = private.publicVersion
        assertTrue(shown != null)
        val text = shown!!.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertFalse(text.any { it.isDigit() })

        val public = factory.build(
            snapshot, StepTrackerConfig(notificationLockScreen = "public").sanitised(), core.metrics
        )
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, public.visibility)
        assertTrue(public.publicVersion == null)
    }

    @Test
    fun theStatusReportsGrantsTypeByType() = runBlocking {
        // No grants in this test app: nothing to read or write, each type reported.
        val status = core.healthConnect.status(core.permissionScope())
        assertEquals(false, status["canReadSteps"])
        assertEquals(emptyList<String>(), status["grantedReadTypes"])
        assertEquals(false, status["canWriteSteps"])
        assertEquals(emptyList<String>(), status["grantedWriteTypes"])
        assertFalse(core.healthConnect.canReadSteps())
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
