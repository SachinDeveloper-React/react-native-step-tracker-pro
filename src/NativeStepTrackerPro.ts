import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { UnsafeObject } from 'react-native/Libraries/Types/CodegenTypes';

/**
 * Codegen spec. Everything crossing the bridge is an UnsafeObject so that a
 * single native method can evolve without a codegen break; the strongly typed
 * surface lives in `StepTracker.ts`, which is what consumers import.
 */
export interface Spec extends TurboModule {
  // ---- lifecycle -------------------------------------------------------
  initialize(config: UnsafeObject): Promise<UnsafeObject>;
  updateConfig(config: UnsafeObject): Promise<UnsafeObject>;
  getConfig(): Promise<UnsafeObject>;
  startTracking(): Promise<UnsafeObject>;
  pauseTracking(): Promise<UnsafeObject>;
  resumeTracking(): Promise<UnsafeObject>;
  stopTracking(): Promise<UnsafeObject>;
  getTrackingState(): Promise<UnsafeObject>;
  isTracking(): Promise<boolean>;
  getTrackingHealth(): Promise<UnsafeObject>;

  // ---- reads -----------------------------------------------------------
  getTodaySteps(): Promise<UnsafeObject>;
  getStepsForDate(date: string): Promise<UnsafeObject>;
  getYesterdaySteps(): Promise<UnsafeObject>;
  getStatsForRange(startDate: string, endDate: string): Promise<UnsafeObject>;
  getWeeklyStats(options: UnsafeObject): Promise<UnsafeObject>;
  getMonthlyStats(options: UnsafeObject): Promise<UnsafeObject>;
  getYearlyStats(options: UnsafeObject): Promise<UnsafeObject>;
  getHistory(startDate: string, endDate: string): Promise<UnsafeObject>;
  getVerificationSnapshot(date: string): Promise<UnsafeObject>;

  // ---- writes ----------------------------------------------------------
  resetToday(): Promise<boolean>;
  clearHistory(): Promise<boolean>;
  pruneHistory(retentionDays: number): Promise<number>;

  // ---- permissions -----------------------------------------------------
  checkPermissions(): Promise<UnsafeObject>;
  requestPermissions(): Promise<UnsafeObject>;
  getDeviceCapabilities(): Promise<UnsafeObject>;
  openAppSettings(): Promise<boolean>;

  // ---- battery ---------------------------------------------------------
  isBatteryOptimizationEnabled(): Promise<boolean>;
  requestDisableBatteryOptimization(): Promise<boolean>;
  openBatteryOptimizationSettings(): Promise<boolean>;
  openManufacturerAutoStartSettings(): Promise<boolean>;
  getBackgroundRestrictionStatus(): Promise<UnsafeObject>;

  // ---- health connect --------------------------------------------------
  getHealthConnectStatus(): Promise<UnsafeObject>;
  requestHealthConnectPermissions(options: UnsafeObject): Promise<UnsafeObject>;
  openHealthConnectSettings(): Promise<boolean>;
  installHealthConnect(): Promise<boolean>;
  revokeHealthConnectPermissions(): Promise<boolean>;
  readHealthConnectSteps(startIso: string, endIso: string): Promise<UnsafeObject>;
  writeHealthConnectSteps(date: string): Promise<boolean>;
  syncWithHealthConnect(): Promise<UnsafeObject>;

  // ---- step sources ----------------------------------------------------
  getStepSources(startDate: string, endDate: string): Promise<UnsafeObject>;
  getCurrentStepSource(): Promise<UnsafeObject>;
  setPreferredStepSource(packageName: string | null): Promise<UnsafeObject>;
  getInstalledCompanionApps(): Promise<UnsafeObject>;

  // ---- sync ------------------------------------------------------------
  getPendingSyncCount(): Promise<number>;
  syncNow(): Promise<UnsafeObject>;

  // ---- event emitter plumbing -----------------------------------------
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.get<Spec>('StepTrackerPro');
