import type { StepTrackerConfig } from './types';

export const MODULE_NAME = 'StepTrackerPro';

export const DEFAULT_CONFIG: Required<
  Pick<
    StepTrackerConfig,
    | 'height'
    | 'weight'
    | 'sex'
    | 'dailyGoal'
    | 'calorieCoefficient'
    | 'historyRetentionDays'
    | 'notificationActions'
    | 'notificationThrottleMs'
    | 'eventThrottleMs'
    | 'persistEveryNSteps'
    | 'healthConnectEnabled'
    | 'healthConnectSyncIntervalMinutes'
    | 'healthConnectReadEnabled'
    | 'healthConnectWriteEnabled'
    | 'healthConnectBackgroundRead'
    | 'healthConnectHistoryRead'
    | 'healthConnectIgnoreManualEntries'
    | 'healthConnectReadActiveCalories'
    | 'healthConnectReadTypes'
    | 'stepSource'
    | 'wearableTrust'
    | 'remoteSyncPayload'
    | 'remoteSyncAuth'
    | 'autoStartOnBoot'
    | 'gapRecovery'
    | 'gapRecoveryMaxSteps'
    | 'watchdogEnabled'
    | 'accelerometerFallback'
    | 'accelerometerWakeLock'
    | 'accelerometerThreshold'
    | 'motionSampling'
    | 'motionWindowRetention'
    | 'fraudDetection'
  >
> = {
  height: 170,
  weight: 70,
  sex: 'unspecified',
  dailyGoal: 10000,
  calorieCoefficient: 0.57,
  historyRetentionDays: 35,
  notificationActions: true,
  notificationThrottleMs: 1000,
  eventThrottleMs: 500,
  persistEveryNSteps: 10,
  healthConnectEnabled: true,
  healthConnectSyncIntervalMinutes: 30,
  healthConnectReadEnabled: true,
  healthConnectWriteEnabled: true,
  healthConnectBackgroundRead: false,
  healthConnectHistoryRead: false,
  healthConnectIgnoreManualEntries: false,
  healthConnectReadActiveCalories: false,
  healthConnectReadTypes: ['steps', 'distance', 'totalCalories'],
  stepSource: 'auto',
  wearableTrust: 'metadata',
  remoteSyncPayload: 'totals',
  remoteSyncAuth: 'headers',
  autoStartOnBoot: true,
  gapRecovery: 'split',
  gapRecoveryMaxSteps: 20000,
  watchdogEnabled: true,
  accelerometerFallback: true,
  accelerometerWakeLock: true,
  accelerometerThreshold: 0.9,
  motionSampling: { enabled: false, windowSeconds: 10, intervalMinutes: 5 },
  motionWindowRetention: 288,
  fraudDetection: {
    enabled: false,
    mode: 'flag',
    maxCadenceSpm: 200,
    steadyCadenceMinutes: 30,
    maxContinuousMinutes: 180,
    maxDailySteps: 50000,
    flagWhileCharging: true,
    activityRecognition: false,
  },
};

/** Stride length = height(cm) * coefficient / 100. */
export const STRIDE_COEFFICIENT = {
  male: 0.415,
  female: 0.413,
  unspecified: 0.414,
} as const;

/** Health Connect's own provider package. */
export const HEALTH_CONNECT_PACKAGE = 'com.google.android.apps.healthdata';

/**
 * Health Connect stops showing its permission sheet after this many refusals.
 * Past it, `HealthConnectStatus.shouldOpenSettings` turns true and the only
 * route left is `openHealthConnectSettings()`.
 */
export const HEALTH_CONNECT_MAX_PROMPTS = 2;
