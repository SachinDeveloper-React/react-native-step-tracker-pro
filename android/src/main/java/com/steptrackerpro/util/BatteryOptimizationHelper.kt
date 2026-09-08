package com.steptrackerpro.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Doze and OEM task-killer helpers.
 *
 * A hardware step counter keeps ticking in the SoC sensor hub even in Doze, so
 * these are about the *service* surviving, not the sensor. On stock Android the
 * foreground service is enough. On aggressive OEM skins (Xiaomi, Oppo, Vivo,
 * Realme, Huawei, Samsung's "put unused apps to sleep") the user has to grant an
 * exemption, and there is no API to do it for them.
 */
object BatteryOptimizationHelper {

    fun isOptimizationEnabled(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return !power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Direct system dialog. Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which
     * Play restricts to apps whose core function genuinely breaks under Doze —
     * declare it in the Play Console or use [openSettings] instead.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context): Boolean {
        if (!isOptimizationEnabled(context)) return true
        return runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Policy-safe: opens the exemption list, the user picks the app. */
    fun openSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Best-effort deep link into OEM autostart screens; falls back to app info. */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> listOf(
                "com.miui.securitycenter" to
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo", "realme", "oneplus" -> listOf(
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
            "vivo", "iqoo" -> listOf(
                "com.vivo.permissionmanager" to
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "huawei", "honor" -> listOf(
                "com.huawei.systemmanager" to
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            "letv" -> listOf(
                "com.letv.android.letvsafe" to
                    "com.letv.android.letvsafe.AutobootManageActivity"
            )
            else -> emptyList()
        }

        for ((pkg, cls) in candidates) {
            val intent = Intent()
                .setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                val launched = runCatching { context.startActivity(intent); true }
                    .getOrDefault(false)
                if (launched) return true
            }
        }
        return PermissionHelper.openAppSettings(context)
    }
}
