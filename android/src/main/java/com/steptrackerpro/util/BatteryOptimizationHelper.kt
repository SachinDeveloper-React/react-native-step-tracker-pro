package com.steptrackerpro.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * Realme, Huawei, Transsion's Tecno/Infinix/itel, Samsung's "sleeping apps")
 * the user has to grant an exemption, and there is no API to do it for them.
 *
 * Two exemptions matter, and they are different screens:
 *
 *  1. **Battery optimisation** (`isIgnoringBatteryOptimizations`). Stock
 *     Android, and the one that on Android 12+ also lets the watchdog start
 *     the service from the background.
 *  2. **Autostart / background launch** (`openAutoStartSettings`). OEM-only,
 *     no API, deep-linked by component name. Without it MIUI and ColorOS kill
 *     the process on swipe-away and refuse the START_STICKY restart.
 */
object BatteryOptimizationHelper {

    fun isOptimizationEnabled(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return !power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Direct system dialog. Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
     * Google Play allows the permission when the app's core function is
     * broken by Doze - a step tracker's is - but expects it to be asked for
     * in context, with an explanation on screen. See
     * docs/PLAY_STORE_COMPLIANCE.md.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context): Boolean {
        if (!isOptimizationEnabled(context)) return true
        // Not declared - the default from 2.0 - means the system would refuse
        // the direct dialog. The settings list needs no permission and gets
        // the user to the same switch.
        if (!directPromptAvailable(context)) return openSettings(context)
        return runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Whether the app declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which the direct dialog needs. */
    fun directPromptAvailable(context: Context): Boolean =
        PermissionHelper.isDeclared(context, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")

    /** Policy-safe: opens the exemption list, the user picks the app. */
    fun openSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * Manufacturers whose stock skins kill foreground services and drop the
     * START_STICKY restart unless the user whitelists the app. Keyed on
     * `Build.MANUFACTURER` and `Build.BRAND`, lower-cased.
     */
    private val AGGRESSIVE = setOf(
        "xiaomi", "redmi", "poco", "blackshark",
        "oppo", "realme", "oneplus",
        "vivo", "iqoo",
        "huawei", "honor", "hihonor",
        "tecno", "infinix", "itel", "transsion",
        "samsung",
        "meizu", "asus", "letv", "leeco", "nokia", "hmd global", "hmd", "lenovo",
        "unihertz", "wiko", "lava", "micromax", "gionee"
    )

    /** True on a skin known to need [openAutoStartSettings] or a whitelist step. */
    fun isAggressiveOem(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer in AGGRESSIVE || brand in AGGRESSIVE
    }

    /**
     * Which OEM screens exist for this device, in the order worth trying.
     * Component names come from the field; every one of them is verified
     * with the package manager before use, and an unresolvable entry simply
     * falls through, so a wrong or removed class costs nothing.
     */
    private fun autoStartCandidates(): List<Pair<String, String>> {
        val id = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        val out = ArrayList<Pair<String, String>>()
        fun add(pkg: String, cls: String) = out.add(pkg to cls)

        if (listOf("xiaomi", "redmi", "poco", "blackshark").any { it in id }) {
            add("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            add("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
            add("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        }
        if (listOf("oppo", "realme", "oneplus").any { it in id }) {
            add("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            add("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            add("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
            add("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            add("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
            add("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")
            add("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        }
        if (listOf("vivo", "iqoo").any { it in id }) {
            add("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            add("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            add("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            add("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        }
        if (listOf("huawei", "honor", "hihonor").any { it in id }) {
            add("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            add("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            add("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            add("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            add("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity")
        }
        if (listOf("tecno", "infinix", "itel", "transsion").any { it in id }) {
            add("com.transsion.phonemanager", "com.itel.autostart.AutostartActivity")
            add("com.transsion.phonemanager", "com.transsion.phonemanager.module.autostart.AutoStartActivity")
            add("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")
        }
        if ("samsung" in id) {
            add("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
            add("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            add("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            add("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity")
        }
        if ("asus" in id) {
            add("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")
            add("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
            add("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
        }
        if ("meizu" in id) {
            add("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
            add("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
        }
        if (listOf("nokia", "hmd").any { it in id }) {
            add("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")
        }
        if (listOf("letv", "leeco").any { it in id }) {
            add("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
        }
        if ("lenovo" in id) {
            add("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity")
        }
        return out
    }

    /**
     * Every OEM manager package the hard-coded list knows about, scanned at
     * runtime for a screen whose class name says what it does. Class names
     * on Transsion, vivo and older ColorOS builds move between firmware
     * versions faster than any list can follow; the package names do not,
     * and the word "autostart" in an activity name has stayed put for a
     * decade. Discovery runs after the hard-coded candidates, so a
     * known-good component still wins, and never runs a screen that is not
     * exported.
     */
    private val MANAGER_PACKAGES = listOf(
        "com.miui.securitycenter", "com.miui.powerkeeper",
        "com.coloros.safecenter", "com.coloros.oppoguardelf", "com.oppo.safe", "com.oneplus.security",
        "com.vivo.permissionmanager", "com.iqoo.secure", "com.vivo.abe",
        "com.huawei.systemmanager", "com.hihonor.systemmanager",
        "com.transsion.phonemanager", "com.transsion.phonemaster",
        "com.samsung.android.lool", "com.samsung.android.sm",
        "com.asus.mobilemanager", "com.meizu.safe", "com.evenwell.powersaving.g3",
        "com.letv.android.letvsafe", "com.lenovo.security"
    )

    /** Screens that grant background launch. First tier. */
    private val AUTOSTART_HINT = Regex(
        "(?i)(autostart|auto_start|autoboot|startup|bgstartup|chainlaunch|selfstart|" +
            "backgroundstart|applaunch|purebackground|smartbg|protectactivity)"
    )

    /** Screens that lift battery limits. Second tier: right area, less specific. */
    private val BATTERY_HINT = Regex("(?i)(battery|powersav|powerusage|sleeping|hiddenapps)")

    private fun discoveredCandidates(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val first = ArrayList<Pair<String, String>>()
        val second = ArrayList<Pair<String, String>>()
        for (pkg in MANAGER_PACKAGES) {
            val activities = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
            }.getOrNull() ?: continue
            for (activity in activities) {
                if (!activity.exported) continue
                val name = activity.name ?: continue
                when {
                    AUTOSTART_HINT.containsMatchIn(name) -> first.add(pkg to name)
                    BATTERY_HINT.containsMatchIn(name) -> second.add(pkg to name)
                }
            }
        }
        return first + second
    }

    /** Hard-coded first, then discovered, without duplicates. */
    private fun allCandidates(context: Context): List<Pair<String, String>> =
        (autoStartCandidates() + discoveredCandidates(context)).distinct()

    /** The first OEM screen that resolves, as `package/class`, or null. */
    fun autoStartTarget(context: Context): String? =
        allCandidates(context).firstOrNull { (pkg, cls) -> resolves(context, pkg, cls) }
            ?.let { (pkg, cls) -> "$pkg/$cls" }

    /** True when at least one OEM screen resolves on this device. */
    fun hasAutoStartSettings(context: Context): Boolean = autoStartTarget(context) != null

    /** Best-effort deep link into OEM autostart screens; falls back to app info. */
    fun openAutoStartSettings(context: Context): Boolean {
        for ((pkg, cls) in allCandidates(context)) {
            val intent = Intent()
                .setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // MIUI's power keeper screen wants to know which app.
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", appLabel(context))
            // resolveActivity() honours package visibility, so the OEM
            // packages are declared in <queries>. The direct start is still
            // attempted when resolution fails: on some builds the component is
            // reachable but not queryable, and a failed start costs nothing.
            val launched = runCatching { context.startActivity(intent); true }
                .getOrDefault(false)
            if (launched) return true
        }
        return PermissionHelper.openAppSettings(context)
    }

    private fun resolves(context: Context, pkg: String, cls: String): Boolean {
        val intent = Intent().setComponent(ComponentName(pkg, cls))
        return runCatching { context.packageManager.resolveActivity(intent, 0) != null }
            .getOrDefault(false)
    }

    private fun appLabel(context: Context): String = runCatching {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    }.getOrDefault(context.packageName)

    /**
     * Everything an onboarding screen needs in order to decide whether to
     * bother the user about background restrictions, and with which screen.
     */
    fun status(context: Context): Map<String, Any?> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "brand" to Build.BRAND,
        "aggressiveOem" to isAggressiveOem(),
        "batteryOptimizationEnabled" to isOptimizationEnabled(context),
        // False unless the app declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
        // requestDisableBatteryOptimization() then opens the settings list.
        "directPromptAvailable" to directPromptAvailable(context),
        "autoStartSettingsAvailable" to hasAutoStartSettings(context),
        // Which screen openManufacturerAutoStartSettings() will open, for
        // support tickets: "it opened X" is a bug report, "it opened app
        // info" is a firmware nobody has catalogued yet.
        "autoStartTarget" to autoStartTarget(context),
        // Android 12+ refuses background starts of a foreground service
        // without an exemption, so on those versions the battery exemption is
        // what lets the watchdog recover a killed service.
        "backgroundStartNeedsExemption" to (Build.VERSION.SDK_INT >= 31)
    )
}
