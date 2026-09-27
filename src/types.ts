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

export type TrackingState = 'idle' | 'running' | 'paused' | 'stopped' | 'unsupported';

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
   * Subtract steps the user typed in by hand
   * (`RECORDING_METHOD_MANUAL_ENTRY`) from every Health Connect source
   * before a winner is picked. Default false.
   *
   * Off, a manual entry is ordinary data - for a display app a user's own
   * correction is legitimate. On, a hand-entered 20,000 can never become the
   * day's number under any policy or pin: each source competes on
   * `steps - manualSteps`, and `ResolvedStepSource.manualStepsExcluded`
   * says how much was taken out so the UI can explain why the number is
   * lower than Health Connect's own screen. The records are still read and
   * still listed per source by `getStepSources()`; the same permission
   * covers them. Sources answered from the aggregate API (windows over 35
   * days) carry no per-record split and are left as they are.
   */
  healthConnectIgnoreManualEntries?: boolean;
  /**
   * Also read Health Connect's active calories and report them per source as
   * `StepSource.activeCalories`. Default false. It is one more permission,
   * `android.permission.health.READ_ACTIVE_CALORIES_BURNED`, which your app
   * must declare and justify to Play.
   */
  healthConnectReadActiveCalories?: boolean;
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
   * What earns a Health Connect source the `'auto'` policy's full trust -
   * its whole margin over the phone, rather than the coverage-bound share a
   * phone-side app may add. Default `'metadata'`. See {@link WearableTrust}.
   */
  wearableTrust?: WearableTrust;
  /**
   * Packages trusted as wearables under `wearableTrust: 'catalog'` on top of
   * the built-in catalog - a companion app the catalog has not caught up
   * with, or a device your own users are known to wear. Ignored under
   * `'metadata'`.
   */
  wearableAllowlist?: string[];
  /**
   * Opened when Health Connect asks the user why the app wants health data.
   * Health Connect links to it from its permission sheet and Play review
   * requires it, so set it before shipping a build that requests health
   * permissions.
   */
  privacyPolicyUrl?: string;
  /** Optional HTTPS endpoint that unsynced day records get POSTed to. */
  remoteSyncUrl?: string;
  /**
   * Sent with every upload. Stored in the app's private SharedPreferences in
   * the clear, like the rest of config — use short-lived tokens, not
   * long-lived secrets. Never returned by `getConfig()`.
   */
  remoteSyncHeaders?: Record<string, string>;
  /**
   * Allow a plain `http://` `remoteSyncUrl`. Default false: the worker refuses
   * to send step data in the clear, and `initialize()` rejects such a URL
   * with `E_INVALID_CONFIG`. For a local development server only.
   */
  remoteSyncAllowHttp?: boolean;
  /**
   * What each record in the `remoteSyncUrl` upload carries. Default
   * `'totals'`: `{ date, steps, distance, calories }`, the shape every
   * earlier release sent, so existing endpoints keep working. `'full'` adds
   * this device's own `deviceSteps` and `recoveredSteps`, the `stepSource`
   * the policy resolved to, and the unresolved Health Connect `sources` for
   * the day (read at upload time; `[]` when reads are not permitted). Every
   * upload also carries an `Idempotency-Key` header, whichever shape.
   */
  remoteSyncPayload?: 'totals' | 'full';
  /**
   * How uploads authenticate. `'headers'` (default) sends
   * `remoteSyncHeaders`. `'signature'` sends none of them and relies on the
   * `Step-Tracker-Signature` header alone, signed by the key from
   * `attestDevice()` - no secret is stored on the device at all. Without a
   * key the worker does not upload and `syncAuthFailed` fires with
   * `reason: 'no_key'`.
   */
  remoteSyncAuth?: 'headers' | 'signature';
  /** Restart tracking automatically after device reboot. Default true. */
  autoStartOnBoot?: boolean;
  /**
   * What to do with steps the hardware counted while the service was dead
   * and the gap crossed midnight. Default `'split'`. See {@link GapRecovery}.
   */
  gapRecovery?: GapRecovery;
  /**
   * Under `gapRecovery: 'today_capped'`, the most one recovery may credit to
   * the active day; whatever is over it is dropped, not moved. Default
   * 20000 — twice a very active day. A genuine overnight gap on a phone that
   * kills services is a few thousand steps; tens of thousands after a long
   * dead period is a counter glitch or a week of walking that cannot
   * honestly be given to one day.
   */
  gapRecoveryMaxSteps?: number;
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
  /**
   * Motion signature windows. Default disabled. See {@link MotionSamplingConfig}.
   */
  motionSampling?: MotionSamplingConfig;
  /**
   * How many motion windows to keep on device. Default 288 — a day at
   * five-minute intervals. Older windows are dropped as new ones are stored,
   * so the table is bounded by construction.
   */
  motionWindowRetention?: number;
  /**
   * Integrity checks for apps that pay for steps. Default disabled. See
   * {@link FraudDetectionConfig}.
   */
  fraudDetection?: FraudDetectionConfig;
}

/**
 * Integrity checks: every step this phone counts is bucketed by minute, and a
 * detector flags the shapes fake steps take - a phone shaken by hand, swung
 * by a gadget, left on a charger, carried in a car - and totals no person
 * walks. Events that matter to a verdict (clock changes, reboots, resets,
 * charging, config changes) are logged next to them.
 *
 * **Nothing is removed unless `mode` is `'exclude'`.** Under the default
 * `'flag'`, every number stays as counted and the findings arrive as
 * `suspectSteps`, `getIntegrityReport()`, the `suspiciousActivity` event and
 * the verification snapshot, for a server to weigh. Only strong flags count
 * towards `suspectSteps`; weak ones are evidence.
 *
 * Each numeric threshold turns its check off at `0`. The defaults are
 * starting points: tune them on your own users' data before you let them cost
 * anybody anything.
 */
export interface FraudDetectionConfig {
  enabled: boolean;
  /**
   * `'flag'` (default) reports suspect steps and changes no number.
   * `'exclude'` also takes them out of every number this package shows,
   * syncs to Health Connect or uploads, and out of the device count before
   * sources are resolved. The raw count is still `deviceSteps`, and the
   * shown number can go down when a run is flagged after the fact.
   */
  mode?: IntegrityMode;
  /** Minutes with more timed steps than this are flagged. Default 200; 100–400, or 0 for off. */
  maxCadenceSpm?: number;
  /**
   * This many minutes in a row whose counts never move by more than one
   * step minute to minute, with no pause, is flagged as a machine. Default
   * 30; at least 5, or 0 for off.
   */
  steadyCadenceMinutes?: number;
  /** Walking past this many minutes without a pause flags the excess. Default 180; at least 30, or 0 for off. */
  maxContinuousMinutes?: number;
  /** Steps over this in a day are flagged. Default 50000; at least 1000, or 0 for off. */
  maxDailySteps?: number;
  /**
   * Flag steps counted while the phone is plugged in. Default true. A person
   * walking with a power bank in the same pocket is flagged too, so weigh it
   * accordingly.
   */
  flagWhileCharging?: boolean;
  /**
   * Tag steps with Google's Activity Recognition state, flagging steps taken
   * "in a vehicle" and noting ones taken "still". Default false. Needs the
   * host app to add `com.google.android.gms:play-services-location`; without
   * it this does nothing, and `IntegrityReport.activityRecognition.available`
   * says so. No permission beyond `ACTIVITY_RECOGNITION`.
   */
  activityRecognition?: boolean;
}

export type IntegrityMode = 'flag' | 'exclude';

/**
 * What a flag says about a stretch of the day.
 *
 * - `'cadence'` — minutes faster than people walk or run (strong)
 * - `'steady_cadence'` — a count that barely moves minute to minute for half
 *   an hour with no pause: a swing gadget or motor (strong)
 * - `'continuous'` — walking past `maxContinuousMinutes` without a pause; the
 *   excess only (strong)
 * - `'charging'` — steps counted while plugged in (strong)
 * - `'in_vehicle'` — steps counted while Activity Recognition said in a
 *   vehicle; strong from three minutes, weak below
 * - `'activity_still'` — steps counted while it said still (weak)
 * - `'night'` — half an hour or more of walking starting between midnight and
 *   5:00 (weak)
 * - `'shake'` — a motion window fast, hard and tonal like a hand shake (strong)
 * - `'swing'` — a motion window that is a near-pure tone at walking pace; a
 *   phone in a backpack can look like this too (weak)
 * - `'daily_volume'` — the day's count past `maxDailySteps`; the excess only
 *   (strong)
 */
export type IntegrityFlagType =
  | 'cadence'
  | 'steady_cadence'
  | 'continuous'
  | 'charging'
  | 'in_vehicle'
  | 'activity_still'
  | 'night'
  | 'shake'
  | 'swing'
  | 'daily_volume';

export interface IntegrityFlag {
  type: IntegrityFlagType;
  /** Only `'strong'` flags count towards `suspectSteps` and are excluded. */
  severity: 'strong' | 'weak';
  /** Epoch ms, inclusive. */
  from: number;
  /** Epoch ms, exclusive. */
  to: number;
  /** Steps this flag covers. Flags can overlap, so these do not sum to `suspectSteps`. */
  steps: number;
  /** The numbers behind the verdict: peak cadence, minutes, window features. */
  evidence: Record<string, unknown>;
}

export type IntegrityEventType =
  | 'clock_changed'
  | 'timezone_changed'
  | 'reboot'
  | 'reset_today'
  | 'history_cleared'
  | 'config_changed'
  | 'sensor_changed'
  | 'charging_started'
  | 'charging_stopped'
  | 'activity_changed'
  | 'service_recovered'
  | 'device_attested';

export interface IntegrityEvent {
  /** Epoch ms. */
  at: number;
  type: IntegrityEventType;
  /** e.g. `{ jumpMs }` for a clock change, `{ keys }` for a config change, `{ reason }` for a recovery. */
  detail: Record<string, unknown>;
}

/**
 * Cheap hints about the device. Each can be faked on a rooted phone; they
 * explain a verdict, and `attestDevice()` is the check a server can trust.
 */
export interface DeviceIntegritySignals {
  emulator: boolean;
  testKeysBuild: boolean;
  suBinary: boolean;
  adbEnabled: boolean;
  developerOptions: boolean;
  appDebuggable: boolean;
  /** The hardware step counter as the OS names it, or null when there is none. */
  stepCounter: { name: string; vendor: string; version: number; wakeUp: boolean } | null;
}

/** Everything the integrity checks know about one day. */
export interface IntegrityReport {
  date: string;
  enabled: boolean;
  mode: IntegrityMode;
  /** This phone's own count for the day, before any exclusion. */
  deviceSteps: number;
  /** Strong flags' minutes plus any excess over `maxDailySteps`. 0 when disabled. */
  suspectSteps: number;
  flags: IntegrityFlag[];
  events: IntegrityEvent[];
  /** Totals of the day's per-minute buckets. */
  minutes: {
    count: number;
    timedSteps: number;
    untimedSteps: number;
    chargingSteps: number;
    stillSteps: number;
    vehicleSteps: number;
  };
  /** Epoch ms of the verdict; today is judged afresh on every read. 0 when never. */
  evaluatedAt: number;
  /** The thresholds in force, after clamping. */
  rules: {
    maxCadenceSpm: number;
    steadyCadenceMinutes: number;
    maxContinuousMinutes: number;
    maxDailySteps: number;
    flagWhileCharging: boolean;
  };
  /** Plugged in right now; always false for a past day. */
  charging: boolean;
  activityRecognition: {
    requested: boolean;
    /** The host app ships Play Services' location library. */
    available: boolean;
    current: 'unknown' | 'still' | 'walking' | 'running' | 'on_bicycle' | 'in_vehicle';
  };
  device: DeviceIntegritySignals;
}

/** One minute of this phone's own steps. Only minutes with steps are listed. */
export interface StepMinute {
  /** Epoch ms of the minute's start. */
  minuteStart: number;
  /** Steps whose timing is known to within the minute. */
  steps: number;
  /**
   * Steps that arrived in one lump after a silence — a sensor batch that
   * overflowed, or a dead process — parked at the minute they arrived in.
   */
  untimedSteps: number;
  chargingSteps: number;
  stillSteps: number;
  vehicleSteps: number;
}

/**
 * A Keystore key bound to the server's challenge. Verify `certificateChain`
 * up to Google's hardware attestation root, check the challenge in the leaf's
 * attestation extension along with the verified boot state, then store
 * `publicKey` against the install.
 */
export interface DeviceAttestation {
  /** Hex SHA-256 of `publicKey`; every signature names it. */
  keyId: string;
  algorithm: 'SHA256withECDSA';
  /** Base64 X.509 SubjectPublicKeyInfo. */
  publicKey: string;
  /** Base64 DER certificates, leaf first. */
  certificateChain: string[];
  /** False when the device refused attestation and an unattested key was made instead. */
  attested: boolean;
  securityLevel: 'strongbox' | 'tee' | 'software' | 'unknown';
  /** Epoch ms the key was generated. */
  createdAt: number;
}

export interface VerificationSnapshotOptions {
  /**
   * Sign the snapshot with the install's Keystore key (made by
   * `attestDevice()`, or an unattested one on first use). Default false.
   */
  sign?: boolean;
  /** A server-issued value echoed inside the signed payload, so it cannot be replayed. */
  nonce?: string;
}

/**
 * Present when the snapshot was signed. Verify `value` over the UTF-8 bytes
 * of `signedPayload` with the key named by `keyId`, then parse
 * `signedPayload` and trust that - not the unsigned fields around it.
 */
export interface SnapshotSignature {
  keyId: string;
  algorithm: 'SHA256withECDSA';
  /** Base64 DER ECDSA signature. */
  value: string;
  attested: boolean;
  /** The exact JSON that was signed: this snapshot minus `signature`. */
  signedPayload: string;
  /** Hex SHA-256 of `signedPayload`, for Play Integrity's `requestHash`. */
  payloadSha256: string;
}

export interface IntegrityTokenOptions {
  /** What the token is bound to - a signed snapshot's `signature.payloadSha256`. At most 500 characters. */
  requestHash: string;
  /** Your Google Cloud project number, from the Play Console's app integrity page. */
  cloudProjectNumber: number;
}

export interface IntegrityToken {
  /** Opaque here; your server decrypts and verifies it with Google. */
  token: string;
  requestHash: string;
}

/** One step record as Health Connect stores it. */
export interface HealthConnectRecord {
  id: string;
  clientRecordId: string | null;
  clientRecordVersion: number;
  /** The app that wrote it. */
  packageName: string;
  recordingMethod: 'active' | 'automatic' | 'manual' | 'unknown';
  device: {
    type:
      | 'unknown'
      | 'watch'
      | 'phone'
      | 'scale'
      | 'ring'
      | 'head_mounted'
      | 'fitness_band'
      | 'chest_strap'
      | 'smart_display';
    manufacturer: string | null;
    model: string | null;
  } | null;
  /** Epoch ms. */
  startTime: number;
  endTime: number;
  /** Seconds east of UTC the writer recorded; null when it gave none. */
  startZoneOffsetSeconds: number | null;
  endZoneOffsetSeconds: number | null;
  /** Epoch ms of the last write to the record. */
  lastModifiedTime: number;
  count: number;
}

export interface HealthConnectRecordList {
  records: HealthConnectRecord[];
  /** More records exist than one call returns (10,000); narrow the window. */
  truncated: boolean;
}

export interface HealthConnectChanges {
  /**
   * Health Connect no longer has the changes since this token - it keeps
   * them 30 days. Take a new token and re-read with
   * `getHealthConnectRecords()`.
   */
  tokenExpired: boolean;
  /** Inserted or updated records. */
  upserted: HealthConnectRecord[];
  /** Ids of deleted records. */
  deletedIds: string[];
  /** The cursor for the next call; null when `tokenExpired`. */
  nextToken: string | null;
  /** Call again with `nextToken` straight away. */
  hasMore: boolean;
}

/**
 * The remote endpoint refused an upload, or signature auth had no key to
 * sign with. The batch is not retried with the same credentials: refresh
 * them with `updateConfig({ remoteSyncHeaders })`, or call `attestDevice()`,
 * then `syncNow()`.
 */
export interface SyncAuthFailedEvent {
  target: 'remote';
  /** The HTTP status, or null for `'no_key'`. */
  status: number | null;
  reason: 'unauthorized' | 'forbidden' | 'no_key';
  auth: 'headers' | 'signature';
}

/** Fired when the integrity checks find something new for a day. */
export interface SuspiciousActivityEvent {
  date: string;
  /** Only the flags not reported for this day before. */
  flags: IntegrityFlag[];
  deviceSteps: number;
  suspectSteps: number;
  mode: IntegrityMode;
}

/**
 * Every few minutes while tracking is running and steps have accrued since
 * the last window, sample the accelerometer for one short window and store a
 * handful of numbers describing the motion — **features only, never the
 * samples**: a dominant frequency, a variance, a zero-crossing rate, a peak
 * ratio and the steps counted meanwhile. Enough for a server to tell a 1.8 Hz
 * walk from a 4 Hz shake; not enough to reconstruct anything.
 *
 * Off by default. On, it is one extra sensor registration per window and, on
 * a non-wake-up accelerometer with `accelerometerWakeLock` on, a wake lock for
 * the window's length. Sampling stops while paused, and is skipped while the
 * app is in the background without the battery-optimisation exemption — a
 * background sample on a phone the user has not exempted is the cost Doze
 * exists to prevent. Results arrive on the `motionWindow` event and through
 * `getMotionWindows()`.
 */
export interface MotionSamplingConfig {
  enabled: boolean;
  /** Length of one window in seconds. Default 10, at most 60. */
  windowSeconds?: number;
  /** Minutes between windows. Default 5, at least 1. */
  intervalMinutes?: number;
}

/**
 * Features of one motion window. The numbers describe how the phone moved
 * for `durationMs`; the samples they came from were discarded on device.
 */
export interface MotionWindow {
  /** Epoch ms the window opened. */
  startedAt: number;
  /** How long it actually ran, ms. */
  durationMs: number;
  /** Accelerometer samples it saw. */
  sampleCount: number;
  /**
   * Where the motion's energy sits, Hz. Gait is 1.2–2.5 Hz at the stride; a
   * hand shake is 3–6 Hz; a still phone reads `0`.
   */
  dominantFrequencyHz: number;
  /** Of the mean-removed acceleration magnitude, (m/s²)². A pocketed walk is a few; a shake is tens. */
  variance: number;
  /** Mean crossings per second — about twice the dominant frequency for a clean oscillation. */
  zeroCrossingRate: number;
  /**
   * Share of in-band energy at `dominantFrequencyHz`, 0..1: near 1 for a
   * metronomic shake, lower for a walk with its harmonics, `0` when still.
   */
  peakRatio: number;
  /** Steps this device counted while the window was open. */
  stepsDuringWindow: number;
}

/**
 * The hardware counter keeps counting while the process is dead, so the first
 * sample after an OEM kill carries every step since the last one seen. Inside
 * one day they all belong to today. When the gap crossed midnight there is no
 * per-step timestamp to place them with:
 *
 * - `'split'` (default) spreads them across the days in the gap in proportion
 *   to time. A service killed at 23:00 and revived at 09:00 gives one tenth
 *   to yesterday and the rest to today. **Yesterday's stored total grows
 *   after the fact**, announced by `historyBackfilled`.
 * - `'today'` credits all of them to the current day. Closed days never
 *   change, but one day can be handed everything since the last reading.
 * - `'today_capped'` is `'today'` bounded by `gapRecoveryMaxSteps` (default
 *   20,000); the rest is dropped. Closed days never change, and a counter
 *   glitch after a week-long kill cannot mint 100,000 steps on one day.
 * - `'drop'` discards whatever cannot be placed on the current day.
 *
 * For an app where a day must never change once it has been settled — paid
 * for, uploaded, shown on a leaderboard — use `'today_capped'` or `'drop'`.
 * Both leave closed days alone; `'drop'` also refuses the current day
 * anything from a gap that started on another one. Under either, the share
 * a day did receive is reported as `recoveredSteps`, so a server can weigh
 * it differently from steps observed live.
 */
export type GapRecovery = 'split' | 'today' | 'today_capped' | 'drop';

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
  /**
   * Of this device's own count for the day, how many steps gap recovery
   * credited in one go rather than observing sample by sample. See
   * {@link DayRecord.recoveredSteps}. Never includes Health Connect, and
   * unchanged when a Health Connect source supplied `steps`.
   */
  recoveredSteps: number;
  /**
   * Of this device's count for the day, how many the integrity checks
   * flagged. `0` unless `fraudDetection.enabled`. Already taken out of
   * `steps` under `fraudDetection.mode: 'exclude'`.
   */
  suspectSteps: number;
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
   * Of this device's own count for the day, how many steps were credited in
   * one go by gap recovery — the share of an overnight kill apportioned to
   * the day, a reboot's since-boot steps, an install's since-boot claim —
   * rather than observed sample by sample. An apportionment is an estimate,
   * so a server judging the day wants it separately. Grows when a past day
   * is backfilled (`historyBackfilled`); `0` for a day counted live, and for
   * every day stored before 1.4.0, whose split was never recorded. Always
   * this device's figure; Health Connect never contributes to it.
   */
  recoveredSteps: number;
  /**
   * Of this device's own count for the day, how many the integrity checks
   * flagged: strong flags' minutes plus any excess over `maxDailySteps`. `0`
   * unless `fraudDetection.enabled`. On a resolved record under
   * `fraudDetection.mode: 'exclude'` they are already out of `steps`; on
   * `getHistory()` rows, which are stored counts, they are still in.
   */
  suspectSteps: number;
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

/**
 * Everything a server needs to judge one day, with nothing resolved for it.
 * An app that converts steps into anything of value should post this rather
 * than a single number: the phone's own count and every Health Connect origin
 * arrive separately, typed-in records are marked, the recovered share is
 * split out, and the clock is there to spot edits. `resolved` is what the
 * current policy chose, for comparison only.
 */
export interface VerificationSnapshot {
  /**
   * The snapshot's shape. Bumped when a field is removed, renamed or changes
   * meaning; a new field does not bump it. `2` from 1.5. A snapshot with no
   * `schemaVersion` is the 1.4 shape.
   */
  schemaVersion: number;
  /** The npm version of this package that produced it, e.g. `'2.0.0'`. */
  libraryVersion: string;
  /** yyyy-MM-dd in the device timezone. */
  date: string;
  /** What this phone's own sensor counted. Never includes Health Connect. */
  deviceSteps: number;
  /** Of `deviceSteps`, how many gap recovery credited in one go. See {@link DayRecord.recoveredSteps}. */
  recoveredSteps: number;
  /** This device's sensor. `'accelerometer'` and `'step_detector'` lose steps while the process is dead. */
  sensor: SensorSource;
  /**
   * Epoch ms from which this device covered the day; 0 = whole day. Only
   * set on an install day, and only known for today — a past day reads 0.
   */
  coverageStartAt: number;
  /**
   * Every Health Connect origin for the day, unresolved, including `self`
   * (this app's own mirror). Empty when Health Connect is unavailable,
   * reads are not granted, or `stepSource` is `'device'`. Each carries
   * `manualSteps`, `recordingMethods` and `trustedWearable`.
   */
  sources: StepSource[];
  /** What the current policy resolved to, for comparison only. */
  resolved: ResolvedStepSource;
  capabilities: Pick<
    DeviceCapabilities,
    'hasStepCounter' | 'hasStepDetector' | 'manufacturer' | 'model' | 'sdkInt'
  >;
  health: Pick<
    TrackingHealth,
    | 'recoveryCount'
    | 'lastRecoveryReason'
    | 'batteryOptimizationEnabled'
    | 'aggressiveOem'
  >;
  /**
   * Wall clock next to a boot id derived from `elapsedRealtime`. A clock
   * edit moves `wallClockMs` and `bootId` together and leaves the uptime
   * behind the boot id alone, so a server comparing two snapshots from the
   * same boot can see the seam.
   */
  clock: {
    wallClockMs: number;
    /** Approximate epoch ms of the device's boot; constant for one boot unless the clock is edited. */
    bootId: number;
    /** IANA zone id, e.g. `Asia/Kolkata`. */
    timezone: string;
    utcOffsetMinutes: number;
  };
  /** Of `deviceSteps`, what the integrity checks flagged. `0` unless enabled. */
  suspectSteps: number;
  /** The flags, events, minute totals and device hints behind `suspectSteps`. */
  integrity: IntegrityReport;
  /** Echoed from `options.nonce`. */
  nonce?: string;
  /** Epoch ms the snapshot was signed; only on a signed snapshot. */
  signedAt?: number;
  /** Only when `options.sign`. */
  signature?: SnapshotSignature;
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
  /**
   * Permissions config asks for that your app's manifest does not declare.
   * From 2.0 the library declares none; add them (see docs/PERMISSIONS.md)
   * or `requestHealthConnectPermissions()` rejects with
   * `E_HEALTH_CONNECT_NOT_DECLARED`.
   */
  undeclaredPermissions: string[];
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

/**
 * What makes a Health Connect source "a wearable" for the `'auto'` policy's
 * trust decision. `'auto'` is a display policy: it exists so a user with a
 * watch sees the watch's number. It is not a fraud control, because the
 * signal it trusts by default is one any app can stamp.
 */
export type WearableTrust =
  /**
   * Default, and the behaviour of every earlier release. The `Device.type`
   * the writing app stamped on its records decides: a source stamped
   * `TYPE_WATCH` is trusted for its whole margin. Right for display - a Wear
   * OS watch stamps it and no catalog keeps up with every band - and wrong
   * for an app paying per step, since any app can stamp it.
   */
  | 'metadata'
  /**
   * Only a package the built-in catalog knows as a wearable's companion app
   * (Fitbit, Garmin Connect, Galaxy Wearable, ...) or one on
   * `wearableAllowlist` is trusted for its whole margin. An unlisted package
   * that stamps a wearable type keeps `kind: 'watch'` for display but is
   * bound by the coverage rule like a phone-side app: it may fill the part
   * of the day before this device's coverage began, and nothing after.
   * `StepSource.trustedWearable` says which rule applied.
   */
  | 'catalog';

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

/**
 * How the steps behind a source's records were produced, as stamped by the
 * writing app in Health Connect's `Metadata.recordingMethod`. The four buckets
 * sum to `StepSource.steps`.
 */
export interface RecordingMethodBreakdown {
  /** Counted by a sensor while the writing app was in use. */
  active: number;
  /** Counted by a sensor in the background - a watch, a phone pedometer. */
  automatic: number;
  /** Typed in by the user. */
  manual: number;
  /**
   * Unstated. Every record written before the field existed carries this,
   * and so does one from an app that never sets it.
   */
  unknown: number;
}

/** One app contributing steps to Health Connect, with what it contributed. */
export interface StepSource {
  packageName: string;
  /** Friendly name where the package is recognised, else the package name. */
  appName: string;
  kind: StepSourceKind;
  /** The origin's full total, manual entries included. */
  steps: number;
  /** Metres. 0 when the source published steps but no distance. */
  distance: number;
  /** Kilocalories. 0 when the source published no calorie records. */
  calories: number;
  /** Epoch ms of that source's most recent record. */
  lastRecordAt: number;
  /** Records this package wrote itself. */
  isSelf: boolean;
  /** Counted on the body rather than in a pocket, going by `kind`. Display only. */
  isWearable: boolean;
  /**
   * Whether the `'auto'` policy trusts this source for its whole margin over
   * the phone. Under `wearableTrust: 'metadata'` (the default) it equals
   * `isWearable`; under `'catalog'` it is true only for a package the
   * catalog or `wearableAllowlist` knows as a wearable, whatever its records
   * were stamped with. A source that is `isWearable` but not
   * `trustedWearable` is being treated as a phone-side app by the coverage
   * rule.
   */
  trustedWearable: boolean;
  /**
   * Health Connect's own on-device step count (Android 14, SDK extension
   * 20+), attributed to `android` or to `com.android.healthconnect.phone.<hash>`.
   * It comes from the same hardware counter this package reads, so it is
   * classified `'phone'`, never a wearable.
   */
  isPlatform: boolean;
  /**
   * Of `steps`, how many came from records the writing app stamped
   * `RECORDING_METHOD_MANUAL_ENTRY` - typed in by the user rather than
   * counted by anything. The single easiest way to fake a day through a
   * third-party app, and the bucket `healthConnectIgnoreManualEntries`
   * subtracts.
   *
   * `-1` when not computed: windows over 35 days are answered through the
   * aggregate API, which returns totals with no per-record metadata, the
   * same way `stepsBeforeCoverage` is unavailable there. Every single-day
   * read is exact.
   */
  manualSteps: number;
  /** Of `steps`, how many carried `RECORDING_METHOD_UNKNOWN`. `-1` when not computed. */
  unknownMethodSteps: number;
  /** The full split of `steps` by recording method. `null` when not computed. */
  recordingMethods: RecordingMethodBreakdown | null;
  /**
   * Of `steps`, how many came from records the writing app last modified
   * more than a day after they ended — a history pushed in after the fact,
   * or a companion app that synced very late. Evidence, never subtracted:
   * a watch out of range for two days writes late too. `-1` when not
   * computed (aggregate reads over 35 days).
   */
  lateWrittenSteps: number;
  /**
   * Of `steps`, how many fell in each local hour of the day: 24 entries
   * summing to `steps`, a record spanning hours split by time. On a range
   * (`getStepSources`) it is the hour-of-day total across the range. Every
   * entry is `-1` when not computed - aggregate reads over 35 days.
   */
  hourlySteps: number[];
  /**
   * Kilocalories from this app's active-calorie records, with
   * `healthConnectReadActiveCalories` on and granted; `-1` otherwise and on
   * aggregate reads.
   */
  activeCalories: number;
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
  /**
   * Manual-entry steps subtracted from the external source that was
   * evaluated, under `healthConnectIgnoreManualEntries`. `0` when the flag
   * is off. `externalSteps + manualStepsExcluded` is what Health Connect's
   * own screen shows for that source, so a UI can say "20,000 typed in by
   * hand were not counted" instead of leaving the difference unexplained.
   */
  manualStepsExcluded: number;
  /**
   * Flagged steps taken out of this device's count before it competed,
   * under `fraudDetection.mode: 'exclude'`. `0` otherwise. `deviceSteps` is
   * the count after the exclusion.
   */
  suspectStepsExcluded: number;
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
  /**
   * Your app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, so
   * `requestDisableBatteryOptimization()` can show the direct dialog. False
   * by default from 2.0; the call then opens the settings list instead.
   */
  directPromptAvailable: boolean;
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

export type StepsChangedEvent = StepSnapshot;

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
export type StepSourceChangedEvent = ResolvedStepSource;

/** Fired when Health Connect is installed, updated, granted or revoked. */
export type HealthConnectStatusEvent = HealthConnectStatus;

export interface DayChangedEvent {
  previousDate: string;
  currentDate: string;
  previousDaySteps: number;
}

/**
 * A past day's stored total grew after the fact: gap recovery placed steps
 * on it — the part of an overnight kill that fell before midnight under
 * `'split'`, or a reboot's since-boot steps spread from the boot instant.
 * Fired once per affected day, after the write has committed, so an app that
 * has already settled that day knows to look again. `dayChanged` is
 * unrelated and unchanged. Never fires under `'today'`, `'today_capped'` or
 * `'drop'`, which leave closed days alone.
 */
export interface HistoryBackfilledEvent {
  /** yyyy-MM-dd of the day that changed. Never the current day. */
  date: string;
  /** Steps added to it by this recovery. */
  addedSteps: number;
  /** Its stored total afterwards. */
  totalSteps: number;
  /**
   * `'gap'` — nothing was listening between the last reading and this one.
   * `'reboot'` — the counter restarted, and these are the steps since boot.
   */
  reason: 'gap' | 'reboot';
}

export interface StepTrackerEventMap {
  stepsChanged: StepsChangedEvent;
  goalReached: GoalReachedEvent;
  goalProgressChanged: GoalProgressEvent;
  trackingStateChanged: TrackingStateEvent;
  dayChanged: DayChangedEvent;
  historyBackfilled: HistoryBackfilledEvent;
  /** One motion signature window was stored. See {@link MotionWindow}. */
  motionWindow: MotionWindow;
  /** The integrity checks found something new. See {@link SuspiciousActivityEvent}. */
  suspiciousActivity: SuspiciousActivityEvent;
  syncCompleted: SyncEvent;
  /** The endpoint refused the credentials. See {@link SyncAuthFailedEvent}. */
  syncAuthFailed: SyncAuthFailedEvent;
  stepSourceChanged: StepSourceChangedEvent;
  healthConnectStatusChanged: HealthConnectStatusEvent;
  error: { code: string; message: string };
}

export type StepTrackerEvent = keyof StepTrackerEventMap;

export interface EventSubscription {
  remove(): void;
}
