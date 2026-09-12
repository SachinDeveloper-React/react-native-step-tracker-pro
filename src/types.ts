/**
 * Public type definitions for react-native-step-tracker-pro.
 */

/** Biological sex is only used to pick a stride-length coefficient. */
export type Sex = 'male' | 'female' | 'unspecified';

/**
 * Which sensor the service ended up using. `'accelerometer'` is the software
 * pedometer used on phones with neither step sensor; it costs battery and
 * loses steps taken while the process is dead.
 */
export type SensorSource = 'step_counter' | 'step_detector' | 'accelerometer' | 'none';

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
   * Read other apps' steps back out of Health Connect. Default true.
   *
   * Set false for an app that only mirrors its own count out: the `READ_*`
   * permissions are then never requested, and every `stepSource` policy
   * behaves like `'device'`.
   */
  healthConnectReadEnabled?: boolean;
  /**
   * Write this device's counts into Health Connect. Default true.
   *
   * Set false for an app that only displays another source's data — it keeps
   * reads working while making sure nothing extra is added to the user's
   * Health Connect record, and the `WRITE_*` permissions are never requested.
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
  /**
   * What to do with steps the hardware counted while the service was dead
   * and the gap crossed midnight. Default `'split'`. See {@link GapRecovery}.
   */
  gapRecovery?: GapRecovery;
  /**
   * Re-launch the service from a 15-minute WorkManager job when an OEM task
   * killer has removed it. Default true. Needs the battery-optimisation
   * exemption on Android 12+ to actually succeed from the background; the
   * foreground restart on next app open works regardless.
   */
  watchdogEnabled?: boolean;
  /**
   * On a phone with neither `TYPE_STEP_COUNTER` nor `TYPE_STEP_DETECTOR`,
   * count with a software pedometer over the accelerometer instead of
   * refusing to start. Default true. Never used when a step sensor exists.
   *
   * The cost: the CPU has to stay awake to sample, which is several percent
   * of battery a day, and steps taken while the process is dead are lost —
   * there is no hardware counter to reconcile from.
   */
  accelerometerFallback?: boolean;
  /**
   * Hold a partial wake lock while the accelerometer is sampled, so counting
   * continues with the screen off on phones whose accelerometer is not a
   * wake-up sensor (most budget phones). Default true. Off trades battery
   * for steps only counted while the screen is on.
   */
  accelerometerWakeLock?: boolean;
  /**
   * Peak linear acceleration in m/s² that counts as a step for the software
   * pedometer. Default 0.9. Raise it if a user reports steps in a vehicle,
   * lower it if a gentle walk with the phone in a bag is missed.
   */
  accelerometerThreshold?: number;
}

/**
 * The hardware counter keeps counting while the process is dead, so the first
 * sample after an OEM kill carries every step since the last one seen. Inside
 * one day they all belong to today. When the gap crossed midnight there is no
 * per-step timestamp to place them with:
 *
 * - `'split'` (default) spreads them across the days in the gap in proportion
 *   to time. A service killed at 23:00 and revived at 09:00 gives one tenth
 *   to yesterday and the rest to today.
 * - `'today'` credits all of them to the current day.
 * - `'drop'` discards whatever cannot be placed on the current day. The
 *   choice for an app where step counts have monetary value and an
 *   over-credit is worse than a loss.
 */
export type GapRecovery = 'split' | 'today' | 'drop';

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
  /**
   * Everything *this app* needs is granted — the read set when
   * `healthConnectReadEnabled`, the write set when `healthConnectWriteEnabled`.
   */
  granted: boolean;
  /** Reads are part of what this app asks for (`healthConnectReadEnabled`). */
  readRequired: boolean;
  /** Writes are part of what this app asks for (`healthConnectWriteEnabled`). */
  writeRequired: boolean;
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
  /**
   * Health Connect's own on-device step count (Android 14, SDK extension
   * 20+), attributed to `android` or to `com.android.healthconnect.phone.<hash>`.
   * It comes from the same hardware counter this package reads, so it is
   * classified `'phone'`, never a wearable.
   */
  isPlatform: boolean;
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
  /**
   * True when `steps` is an external baseline plus this phone's live delta
   * on top — the `'auto'` policy's continuity mode. The other app reported
   * `deviceSteps + baselineSteps` at its last sync, and every step the phone
   * has counted since is added so the number keeps moving between syncs.
   */
  merged: boolean;
  /** How far ahead the external source was when the baseline was taken. */
  baselineSteps: number;
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
  /** The hardware cumulative counter. The best case: counts with the process dead. */
  hasStepCounter: boolean;
  /** The hardware per-step event. Loses steps while the process is dead. */
  hasStepDetector: boolean;
  hasAccelerometer: boolean;
  /**
   * The accelerometer keeps delivering with the CPU asleep. Without it the
   * software pedometer needs a wake lock, which is what costs battery.
   */
  hasWakeUpAccelerometer: boolean;
  /** `startTracking()` will work. Honours `accelerometerFallback`. */
  supported: boolean;
  /** The sensor the service will use, in order of preference. */
  bestSensor: SensorSource;
  sdkInt: number;
  manufacturer: string;
  model: string;
}

/**
 * Whether tracking is actually working, as opposed to merely marked running.
 * `looksDead` is the signal to act on: the user has tracking on, but no
 * service instance exists in the process — an OEM task killer removed it.
 */
export interface TrackingHealth {
  /** A service instance exists in this process right now. */
  serviceAlive: boolean;
  /** The user started tracking and never stopped it (paused counts). */
  shouldBeRunning: boolean;
  /** Epoch ms of the last heartbeat; 0 when the service has never run. */
  lastHeartbeatAt: number;
  /** Milliseconds since the last heartbeat; -1 when there is none. */
  heartbeatAgeMs: number;
  /** Epoch ms of the last sensor sample. */
  lastSensorEventAt: number;
  /** Epoch ms of the last time the service was brought back by something other than the user. */
  lastRecoveryAt: number;
  /** `'sticky' | 'boot' | 'watchdog' | 'foreground' | 'initialize'`, or null. */
  lastRecoveryReason: string | null;
  /** Recoveries since the user last pressed start. High means an OEM is killing the service. */
  recoveryCount: number;
  /** Should be running (started, never stopped — paused counts) but no service exists. */
  looksDead: boolean;
  batteryOptimizationEnabled: boolean;
  /** The manufacturer's skin is known to kill foreground services. */
  aggressiveOem: boolean;
  manufacturer: string;
}

/** Which background restrictions apply on this device, and which screens lift them. */
export interface BackgroundRestrictionStatus {
  manufacturer: string;
  brand: string;
  /** Xiaomi, Oppo, Vivo, Realme, Huawei, Tecno/Infinix/itel, Samsung and others known to kill services. */
  aggressiveOem: boolean;
  /** Doze restrictions still apply; `openBatteryOptimizationSettings()` or `requestDisableBatteryOptimization()`. */
  batteryOptimizationEnabled: boolean;
  /** `openManufacturerAutoStartSettings()` will land on an OEM screen rather than app info. */
  autoStartSettingsAvailable: boolean;
  /**
   * The OEM screen it will open, as `package/class`, or null when it will
   * fall back to app info. Known components are tried first; when none
   * resolves, the OEM's manager package is scanned for an exported activity
   * named like an autostart or battery screen, so unlisted firmware still
   * lands somewhere useful. Log it in support tickets.
   */
  autoStartTarget: string | null;
  /**
   * Android 12+: a foreground service cannot be started from the background
   * without an exemption, so the watchdog can only revive a killed service
   * once the battery exemption is granted.
   */
  backgroundStartNeedsExemption: boolean;
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
