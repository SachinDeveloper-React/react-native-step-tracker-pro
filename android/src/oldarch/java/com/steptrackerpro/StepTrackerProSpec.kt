package com.steptrackerpro

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReadableMap

/**
 * Old architecture shim. Mirrors the signatures codegen produces for the new
 * architecture so `StepTrackerProModule` compiles unchanged against both.
 */
abstract class StepTrackerProSpec internal constructor(context: ReactApplicationContext) :
    ReactContextBaseJavaModule(context) {

    abstract fun initialize(config: ReadableMap, promise: Promise)
    abstract fun updateConfig(config: ReadableMap, promise: Promise)
    abstract fun getConfig(promise: Promise)
    abstract fun startTracking(promise: Promise)
    abstract fun pauseTracking(promise: Promise)
    abstract fun resumeTracking(promise: Promise)
    abstract fun stopTracking(promise: Promise)
    abstract fun getTrackingState(promise: Promise)
    abstract fun isTracking(promise: Promise)
    abstract fun getTrackingHealth(promise: Promise)

    abstract fun getTodaySteps(promise: Promise)
    abstract fun getStepsForDate(date: String, promise: Promise)
    abstract fun getYesterdaySteps(promise: Promise)
    abstract fun getStatsForRange(startDate: String, endDate: String, promise: Promise)
    abstract fun getWeeklyStats(options: ReadableMap, promise: Promise)
    abstract fun getMonthlyStats(options: ReadableMap, promise: Promise)
    abstract fun getYearlyStats(options: ReadableMap, promise: Promise)
    abstract fun getHistory(startDate: String, endDate: String, promise: Promise)

    abstract fun resetToday(promise: Promise)
    abstract fun clearHistory(promise: Promise)
    abstract fun pruneHistory(retentionDays: Double, promise: Promise)

    abstract fun checkPermissions(promise: Promise)
    abstract fun requestPermissions(promise: Promise)
    abstract fun getDeviceCapabilities(promise: Promise)
    abstract fun openAppSettings(promise: Promise)

    abstract fun isBatteryOptimizationEnabled(promise: Promise)
    abstract fun requestDisableBatteryOptimization(promise: Promise)
    abstract fun openBatteryOptimizationSettings(promise: Promise)
    abstract fun openManufacturerAutoStartSettings(promise: Promise)
    abstract fun getBackgroundRestrictionStatus(promise: Promise)

    abstract fun getHealthConnectStatus(promise: Promise)
    abstract fun requestHealthConnectPermissions(options: ReadableMap, promise: Promise)
    abstract fun openHealthConnectSettings(promise: Promise)
    abstract fun installHealthConnect(promise: Promise)
    abstract fun revokeHealthConnectPermissions(promise: Promise)
    abstract fun readHealthConnectSteps(startIso: String, endIso: String, promise: Promise)
    abstract fun writeHealthConnectSteps(date: String, promise: Promise)
    abstract fun syncWithHealthConnect(promise: Promise)

    abstract fun getStepSources(startDate: String, endDate: String, promise: Promise)
    abstract fun getCurrentStepSource(promise: Promise)
    abstract fun setPreferredStepSource(packageName: String?, promise: Promise)
    abstract fun getInstalledCompanionApps(promise: Promise)

    abstract fun getPendingSyncCount(promise: Promise)
    abstract fun syncNow(promise: Promise)

    abstract fun addListener(eventName: String)
    abstract fun removeListeners(count: Double)
}
