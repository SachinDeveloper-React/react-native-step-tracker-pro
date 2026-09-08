package com.steptrackerpro.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {

    /** Runtime permissions this package needs, filtered by API level. */
    fun required(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    fun hasActivityRecognition(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            true
        }

    fun hasPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    /**
     * FOREGROUND_SERVICE_HEALTH is a normal permission, so it is granted at
     * install time whenever it is declared in the merged manifest.
     */
    fun hasForegroundServiceHealth(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            isGranted(context, "android.permission.FOREGROUND_SERVICE_HEALTH")
        } else {
            true
        }

    /**
     * POST_NOTIFICATIONS is deliberately excluded: a denied notification
     * permission downgrades the notification to invisible on Android 13+, but
     * the foreground service and the counting still work.
     */
    fun canStartTracking(context: Context): Boolean =
        hasActivityRecognition(context) && hasForegroundServiceHealth(context)

    fun status(context: Context): Map<String, Any?> = mapOf(
        "activityRecognition" to hasActivityRecognition(context),
        "postNotifications" to hasPostNotifications(context),
        "foregroundServiceHealth" to hasForegroundServiceHealth(context),
        "allGranted" to canStartTracking(context)
    )

    fun capabilities(context: Context): Map<String, Any?> {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val counter = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        val detector = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
        return mapOf(
            "hasStepCounter" to counter,
            "hasStepDetector" to detector,
            "supported" to (counter || detector),
            "sdkInt" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL
        )
    }

    fun openAppSettings(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
