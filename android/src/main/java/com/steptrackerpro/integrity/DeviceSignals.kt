package com.steptrackerpro.integrity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Cheap hints about the device a count came from. Every one of them can be
 * faked on a rooted phone, which is why they are reported as hints and never
 * acted on here: key attestation ([DeviceAttestation]) is the check a server
 * can trust, and these help explain a verdict or triage a support ticket.
 */
object DeviceSignals {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su",
        "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
        "/vendor/bin/su", "/system/bin/failsafe/su"
    )

    fun collect(context: Context): Map<String, Any?> = mapOf(
        "emulator" to looksLikeEmulator(
            fingerprint = Build.FINGERPRINT,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            hardware = Build.HARDWARE
        ),
        "testKeysBuild" to (Build.TAGS?.contains("test-keys") == true),
        "suBinary" to SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) },
        "adbEnabled" to globalFlag(context, Settings.Global.ADB_ENABLED),
        "developerOptions" to globalFlag(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
        "appDebuggable" to (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0),
        "stepCounter" to sensorInfo(context, Sensor.TYPE_STEP_COUNTER)
    )

    /**
     * The well-known fingerprints of the stock emulator, Genymotion and the
     * common x86 images. Pure, so the list is pinned by a JVM test.
     */
    fun looksLikeEmulator(
        fingerprint: String?,
        model: String?,
        manufacturer: String?,
        brand: String?,
        device: String?,
        product: String?,
        hardware: String?
    ): Boolean {
        val fp = fingerprint.orEmpty()
        val m = model.orEmpty()
        val hw = hardware.orEmpty().lowercase()
        val prod = product.orEmpty().lowercase()
        return fp.startsWith("generic") ||
            fp.startsWith("unknown") ||
            fp.contains("emulator") ||
            fp.contains("/sdk_gphone") ||
            m.contains("google_sdk") ||
            m.contains("Emulator") ||
            m.contains("Android SDK built for") ||
            manufacturer.orEmpty().contains("Genymotion") ||
            (brand.orEmpty().startsWith("generic") && device.orEmpty().startsWith("generic")) ||
            hw == "goldfish" || hw == "ranchu" || hw == "vbox86" ||
            prod.startsWith("sdk") || prod.contains("_sdk") || prod.contains("sdk_") ||
            prod.contains("emulator") || prod.contains("simulator") || prod == "vbox86p"
    }

    private fun globalFlag(context: Context, name: String): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, name, 0) == 1 }.getOrDefault(false)

    private fun sensorInfo(context: Context, type: Int): Map<String, Any?>? {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = manager.getDefaultSensor(type) ?: return null
        return mapOf(
            "name" to sensor.name,
            "vendor" to sensor.vendor,
            "version" to sensor.version,
            "wakeUp" to sensor.isWakeUpSensor
        )
    }
}
