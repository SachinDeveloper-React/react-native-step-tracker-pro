package com.steptrackerpro

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

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
    abstract fun getVerificationSnapshot(date: String, options: ReadableMap, promise: Promise)
    abstract fun getMotionWindows(startDate: String, endDate: String, promise: Promise)
    abstract fun getIntegrityReport(date: String, promise: Promise)
    abstract fun getIntegrityEvents(startDate: String, endDate: String, promise: Promise)
    abstract fun getStepMinutes(startDate: String, endDate: String, promise: Promise)
    abstract fun attestDevice(challenge: String, promise: Promise)
    abstract fun hasAttestationKey(promise: Promise)
    abstract fun getAttestationKeyInfo(promise: Promise)
    abstract fun requestIntegrityToken(options: ReadableMap, promise: Promise)
    abstract fun prepareIntegrity(cloudProjectNumber: Double, promise: Promise)
    abstract fun getHealthConnectRecords(startIso: String, endIso: String, options: ReadableMap, promise: Promise)
    abstract fun getHealthConnectChangesToken(options: ReadableMap, promise: Promise)
    abstract fun getHealthConnectChanges(token: String, promise: Promise)

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
    abstract fun readHealthConnectVitals(startIso: String, endIso: String, options: ReadableMap, promise: Promise)
    abstract fun deleteHealthConnectData(startDate: String, endDate: String, promise: Promise)

    abstract fun getStepSources(startDate: String, endDate: String, promise: Promise)
    abstract fun getCurrentStepSource(promise: Promise)
    abstract fun setPreferredStepSource(packageName: String?, promise: Promise)
    abstract fun getInstalledCompanionApps(promise: Promise)

    abstract fun getPendingSyncCount(promise: Promise)
    abstract fun getSyncStatus(promise: Promise)
    abstract fun syncNow(promise: Promise)

    abstract fun addListener(eventName: String)
    abstract fun removeListeners(count: Double)

    // The new architecture's codegen generates these from the `EventEmitter`
    // properties of the spec. On the old architecture there is no typed
    // emitter, so the same names go out through RCTDeviceEventEmitter under
    // the `StepTrackerPro:` prefix the JS side listens for there.
    protected fun emitOnStepsChanged(value: ReadableMap) = legacyEmit("stepsChanged", value)
    protected fun emitOnGoalReached(value: ReadableMap) = legacyEmit("goalReached", value)
    protected fun emitOnGoalProgressChanged(value: ReadableMap) = legacyEmit("goalProgressChanged", value)
    protected fun emitOnTrackingStateChanged(value: ReadableMap) = legacyEmit("trackingStateChanged", value)
    protected fun emitOnDayChanged(value: ReadableMap) = legacyEmit("dayChanged", value)
    protected fun emitOnHistoryBackfilled(value: ReadableMap) = legacyEmit("historyBackfilled", value)
    protected fun emitOnMotionWindow(value: ReadableMap) = legacyEmit("motionWindow", value)
    protected fun emitOnSuspiciousActivity(value: ReadableMap) = legacyEmit("suspiciousActivity", value)
    protected fun emitOnSyncCompleted(value: ReadableMap) = legacyEmit("syncCompleted", value)
    protected fun emitOnSyncAuthFailed(value: ReadableMap) = legacyEmit("syncAuthFailed", value)
    protected fun emitOnStepSourceChanged(value: ReadableMap) = legacyEmit("stepSourceChanged", value)
    protected fun emitOnHealthConnectStatusChanged(value: ReadableMap) =
        legacyEmit("healthConnectStatusChanged", value)
    protected fun emitOnError(value: ReadableMap) = legacyEmit("error", value)

    private fun legacyEmit(event: String, value: ReadableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("StepTrackerPro:$event", value)
    }
}
