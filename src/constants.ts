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
  autoStartOnBoot: true,
};

/** Stride length = height(cm) * coefficient / 100. */
export const STRIDE_COEFFICIENT = {
  male: 0.415,
  female: 0.413,
  unspecified: 0.414,
} as const;
