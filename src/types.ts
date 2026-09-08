/**
 * Public type definitions for react-native-step-tracker-pro.
 */

/** Biological sex is only used to pick a stride-length coefficient. */
export type Sex = 'male' | 'female' | 'unspecified';

/** Which hardware sensor the service ended up using. */
export type SensorSource = 'step_counter' | 'step_detector' | 'none';

export type TrackingState =
  | 'idle'
  | 'running'
  | 'paused'
  | 'stopped'
  | 'unsupported';

export interface StepTrackerConfig {
  /** Height in centimetres. Used for stride length. Default 170. */
  height?: number;
  /** Weight in kilograms. Used for calorie estimation. Default 70. */
  weight?: number;
  /**
   * Overrides the derived stride length, in metres. Leave unset to derive it
   * from `height` and `sex`.
   */
  strideLength?: number;
  sex?: Sex;
  /** Steps. Default 10000. */
  dailyGoal?: number;
  /** Steps. Defaults to dailyGoal * 7. */
  weeklyGoal?: number;
  /** Steps. Defaults to dailyGoal * 30. */
  monthlyGoal?: number;
  /** kcal per kg per km of walking. Default 0.57. */
  calorieCoefficient?: number;
  /** How many days of history to keep on device. Default 35. */
  historyRetentionDays?: number;
  /** Notification title. Supports {steps}, {goal}, {percent}, {distance}, {calories}. */
  notificationTitle?: string;
  notificationText?: string;
  /** Drawable name in the host app, e.g. 'ic_stat_steps'. Falls back to a bundled icon. */
  notificationIcon?: string;
  /** Notification channel name shown in Android settings. */
  notificationChannelName?: string;
  /** Show Pause/Resume actions on the notification. Default true. */
  notificationActions?: boolean;
  /** Minimum ms between notification redraws. Default 1000. */
  notificationThrottleMs?: number;
  /** Minimum ms between JS `stepsChanged` events. Default 500. */
  eventThrottleMs?: number;
  /** Persist to SQLite after this many steps. Default 10. */
  persistEveryNSteps?: number;
  /** Mirror data into Health Connect when permissions are granted. Default true. */
  healthConnectEnabled?: boolean;
  /** Auto-write today's totals to Health Connect every N minutes. Default 30. 0 disables. */
  healthConnectSyncIntervalMinutes?: number;
  /** Optional HTTPS endpoint that unsynced day records get POSTed to. */
  remoteSyncUrl?: string;
  remoteSyncHeaders?: Record<string, string>;
  /** Restart tracking automatically after device reboot. Default true. */
  autoStartOnBoot?: boolean;
}

export interface StepSnapshot {
  /** yyyy-MM-dd in the device timezone. */
  date: string;
  steps: number;
  /** Metres. */
  distance: number;
  /** Kilocalories. */
  calories: number;
  dailyGoal: number;
  /** 0..1, clamped. */
  goalProgress: number;
  goalReached: boolean;
  state: TrackingState;
  source: SensorSource;
  /** Epoch ms of the last sensor sample. */
  timestamp: number;
}

export interface DayRecord {
  date: string;
  steps: number;
  distance: number;
  calories: number;
  /** Mirrored into Health Connect. */
  synced: boolean;
  /** Uploaded to `remoteSyncUrl`. Always false when no endpoint is configured. */
  syncedRemote: boolean;
}

export interface RangeStats {
  /** Inclusive yyyy-MM-dd. */
  startDate: string;
  /** Inclusive yyyy-MM-dd. */
  endDate: string;
  totalSteps: number;
  totalDistance: number;
  totalCalories: number;
  averageSteps: number;
  /** Days with at least one recorded step. */
  activeDays: number;
  bestDay: DayRecord | null;
  /** Every day in the range, zero-filled, ascending. */
  days: DayRecord[];
  /** Present for weekly/monthly windows when a goal is configured. */
  goal?: number;
  goalProgress?: number;
}

export interface RangeOptions {
  /**
   * 'calendar' snaps to the current week (Mon–Sun), month or year.
   * 'rolling' uses the last 7 / 30 / 365 days ending today.
   * Default 'calendar'.
   */
  mode?: 'calendar' | 'rolling';
  /** 0 = current period, 1 = previous period, and so on. Only for 'calendar'. */
  offset?: number;
}

export interface PermissionStatus {
  activityRecognition: boolean;
  postNotifications: boolean;
  /** True on API < 34 where the permission does not exist. */
  foregroundServiceHealth: boolean;
  /** All permissions required to start tracking are granted. */
  allGranted: boolean;
}

export interface HealthConnectStatus {
  /** Health Connect SDK is available on the device. */
  available: boolean;
  /** The user must install or update the Health Connect provider. */
  requiresUpdate: boolean;
  granted: boolean;
  grantedPermissions: string[];
}

export interface DeviceCapabilities {
  hasStepCounter: boolean;
  hasStepDetector: boolean;
  supported: boolean;
  sdkInt: number;
  manufacturer: string;
  model: string;
}

export interface StepsChangedEvent extends StepSnapshot {}

export interface GoalReachedEvent {
  type: 'daily' | 'weekly' | 'monthly';
  goal: number;
  steps: number;
  date: string;
  timestamp: number;
}

export interface GoalProgressEvent {
  type: 'daily' | 'weekly' | 'monthly';
  goal: number;
  steps: number;
  progress: number;
  date: string;
}

export interface TrackingStateEvent {
  state: TrackingState;
  source: SensorSource;
  reason?: string;
}

export interface SyncEvent {
  target: 'health_connect' | 'remote';
  syncedRecords: number;
  failedRecords: number;
  success: boolean;
  error?: string;
}

export interface DayChangedEvent {
  previousDate: string;
  currentDate: string;
  previousDaySteps: number;
}

export interface StepTrackerEventMap {
  stepsChanged: StepsChangedEvent;
  goalReached: GoalReachedEvent;
  goalProgressChanged: GoalProgressEvent;
  trackingStateChanged: TrackingStateEvent;
  dayChanged: DayChangedEvent;
  syncCompleted: SyncEvent;
  error: { code: string; message: string };
}

export type StepTrackerEvent = keyof StepTrackerEventMap;

export interface EventSubscription {
  remove(): void;
}
