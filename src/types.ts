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
  /**
   * Write this device's counts into Health Connect. Default true.
   *
   * Set false for an app that only displays another source's data — it keeps
   * reads working while making sure nothing extra is added to the user's
   * Health Connect record.
   */
  healthConnectWriteEnabled?: boolean;
  /**
   * Also request `READ_HEALTH_DATA_IN_BACKGROUND`. Default false.
   *
   * Needed for the background sync worker to see a watch's steps while the app
   * is closed; without it every background read comes back empty.
   */
  healthConnectBackgroundRead?: boolean;
  /**
   * Also request `READ_HEALTH_DATA_HISTORY`. Default false. Required to read
   * anything older than 30 days, which yearly stats need.
   */
  healthConnectHistoryRead?: boolean;
  /**
   * How to reconcile this phone's sensor with what other apps published to
   * Health Connect. Default 'auto'. See {@link StepSourcePolicy}.
   */
  stepSource?: StepSourcePolicy;
  /**
   * Pin one Health Connect origin (a package name) as the source of truth.
   * Overrides the policy's own choice whenever that origin has data.
   */
  preferredStepSourcePackage?: string;
  /**
   * Opened when Health Connect asks the user why the app wants health data.
   * Health Connect links to it from its permission sheet and Play review
   * requires it, so set it before shipping a build that requests health
   * permissions.
   */
  privacyPolicyUrl?: string;
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
  /** Tracking state of this device's foreground service. */
  state: TrackingState;
  /** This device's sensor, regardless of where `steps` came from. */
  source: SensorSource;
  /** Epoch ms of the last sensor sample. */
  timestamp: number;
  /** Which source the numbers above came from. */
  stepSource: ResolvedStepSource;
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
  /**
   * Which source the numbers came from. Absent on records returned by
   * `getHistory()`, which reports the on-device rows verbatim.
   */
  stepSource?: ResolvedStepSource;
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

export type HealthConnectAvailability =
  /** Ready to use. */
  | 'available'
  /** Installed but too old — send the user to `installHealthConnect()`. */
  | 'update_required'
  /** Not installed, and installable — `installHealthConnect()` fixes it. */
  | 'not_installed'
  /** This device cannot run Health Connect at all. Nothing to offer. */
  | 'not_supported';

export interface HealthConnectStatus {
  /** Health Connect is installed, current, and usable. */
  available: boolean;
  availability: HealthConnectAvailability;
  /** The user must update the Health Connect provider. */
  requiresUpdate: boolean;
  /** `installHealthConnect()` would lead somewhere useful. */
  installable: boolean;
  /** Every required read and write permission is granted. */
  granted: boolean;
  /** Reads are permitted. Enough to display a watch's steps. */
  canRead: boolean;
  /** Writes are permitted. Enough to mirror this device's steps. */
  canWrite: boolean;
  backgroundReadGranted: boolean;
  historyReadGranted: boolean;
  grantedPermissions: string[];
  missingPermissions: string[];
  /** How many times the sheet has been shown without a grant. */
  denialCount: number;
  /**
   * True once Health Connect has stopped showing the permission sheet, which
   * it does after two refusals. Requesting again does nothing visible at that
   * point — call `openHealthConnectSettings()` instead.
   */
  shouldOpenSettings: boolean;
}

export interface RequestHealthConnectOptions {
  /** Include `READ_HEALTH_DATA_IN_BACKGROUND`. Defaults to the config value. */
  backgroundRead?: boolean;
  /** Include `READ_HEALTH_DATA_HISTORY`. Defaults to the config value. */
  historyRead?: boolean;
}

/**
 * How to reconcile this phone's sensor with step data other apps published to
 * Health Connect.
 *
 * No policy ever adds two sources together. A user walking with a watch and a
 * phone has the same steps recorded twice, so summing them doubles the count;
 * every policy picks exactly one source per day.
 */
export type StepSourcePolicy =
  /** Phone sensor only. Health Connect is written to but never read back. */
  | 'device'
  /**
   * A watch, band or ring wins whenever one has data for the day, even if it
   * counted fewer steps. For users who treat the wearable as the truth.
   */
  | 'wearable'
  /** The best external Health Connect origin wins, wearable or not. */
  | 'health_connect'
  /**
   * Default. Whichever of the phone and the best external source counted more
   * for the day. A phone on a desk under-counts; a worn watch does not.
   */
  | 'auto';

/** What sort of hardware or app produced a set of step records. */
export type StepSourceKind =
  | 'self'
  | 'watch'
  | 'fitness_band'
  | 'ring'
  | 'chest_strap'
  | 'phone'
  | 'app'
  | 'unknown';

/** One app contributing steps to Health Connect, with what it contributed. */
export interface StepSource {
  packageName: string;
  /** Friendly name where the package is recognised, else the package name. */
  appName: string;
  kind: StepSourceKind;
  steps: number;
  /** Metres. 0 when the source published steps but no distance. */
  distance: number;
  /** Kilocalories. 0 when the source published no calorie records. */
  calories: number;
  /** Epoch ms of that source's most recent record. */
  lastRecordAt: number;
  /** Records this package wrote itself. */
  isSelf: boolean;
  /** Counted on the body rather than in a pocket. */
  isWearable: boolean;
}

/** The outcome of picking a source for one day. */
export interface ResolvedStepSource {
  date: string;
  /** The number being reported, from whichever source won. */
  steps: number;
  kind: StepSourceKind;
  /** Null when this device's own sensor won. */
  packageName: string | null;
  appName: string;
  /** What this phone's sensor counted, whether or not it won. */
  deviceSteps: number;
  /** What the best external source counted, whether or not it won. */
  externalSteps: number;
  /** True when `steps` came from Health Connect rather than this device. */
  usedExternal: boolean;
}

export interface CurrentStepSource extends ResolvedStepSource {
  policy: StepSourcePolicy;
  preferredPackage: string | null;
}

export interface StepSourceList {
  sources: StepSource[];
  /** At least one wearable other than this device published steps. */
  hasWearable: boolean;
}

/** A wearable companion app found installed on this phone. */
export interface CompanionApp {
  packageName: string;
  appName: string;
  kind: StepSourceKind;
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
  /**
   * Days left alone because a wearable already owns them. Writing this
   * device's parallel count of the same walk would leave every other Health
   * Connect reader with both copies.
   */
  skippedRecords?: number;
  success: boolean;
  error?: string;
  /** Worth another attempt. False for states only the user can change. */
  retryable?: boolean;
}

/** Fired when the app answering for the user's steps changes. */
export interface StepSourceChangedEvent extends ResolvedStepSource {}

/** Fired when Health Connect is installed, updated, granted or revoked. */
export interface HealthConnectStatusEvent extends HealthConnectStatus {}

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
  stepSourceChanged: StepSourceChangedEvent;
  healthConnectStatusChanged: HealthConnectStatusEvent;
  error: { code: string; message: string };
}

export type StepTrackerEvent = keyof StepTrackerEventMap;

export interface EventSubscription {
  remove(): void;
}
