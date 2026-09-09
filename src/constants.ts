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
    | 'healthConnectWriteEnabled'
    | 'healthConnectBackgroundRead'
    | 'healthConnectHistoryRead'
    | 'stepSource'
    | 'autoStartOnBoot'
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
  healthConnectWriteEnabled: true,
  healthConnectBackgroundRead: false,
  healthConnectHistoryRead: false,
  stepSource: 'auto',
  autoStartOnBoot: true,
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
