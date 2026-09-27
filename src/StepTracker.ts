import { NativeEventEmitter, NativeModules, Platform } from 'react-native';
import NativeStepTrackerPro from './NativeStepTrackerPro';
import type { Spec } from './NativeStepTrackerPro';
import { DEFAULT_CONFIG, MODULE_NAME, STRIDE_COEFFICIENT } from './constants';
import { StepTrackerError, toStepTrackerError } from './errors';
import type {
  BackgroundRestrictionStatus,
  CompanionApp,
  CurrentStepSource,
  DayRecord,
  DeviceAttestation,
  DeviceCapabilities,
  EventSubscription,
  HealthConnectStatus,
  IntegrityEvent,
  IntegrityReport,
  MotionWindow,
  PermissionStatus,
  RangeOptions,
  RangeStats,
  RequestHealthConnectOptions,
  ResolvedStepSource,
  StepMinute,
  StepSnapshot,
  StepSourceList,
  StepTrackerConfig,
  StepTrackerEvent,
  StepTrackerEventMap,
  SyncEvent,
  TrackingHealth,
  TrackingState,
  VerificationSnapshot,
  VerificationSnapshotOptions,
} from './types';

const LINKING_ERROR =
  `The native module '${MODULE_NAME}' could not be found.\n` +
  '- Rebuild the app after installing (npx react-native run-android)\n' +
  '- Make sure autolinking picked up the package\n' +
  '- This package is Android only.';

function getNativeModule(): Spec {
  if (Platform.OS !== 'android') {
    throw new StepTrackerError(
      'E_UNSUPPORTED_PLATFORM',
      'react-native-step-tracker-pro only supports Android.'
    );
  }
  const mod = (NativeStepTrackerPro ??
    (NativeModules as Record<string, unknown>)[MODULE_NAME]) as Spec | undefined;
  if (!mod) throw new StepTrackerError('E_UNKNOWN', LINKING_ERROR);
  return mod;
}

/** Android is the only supported platform; call this before anything else. */
export function isSupported(): boolean {
  if (Platform.OS !== 'android') return false;
  return Boolean(
    NativeStepTrackerPro ?? (NativeModules as Record<string, unknown>)[MODULE_NAME]
  );
}

let emitter: NativeEventEmitter | null = null;
function getEmitter(): NativeEventEmitter {
  if (!emitter) {
    emitter = new NativeEventEmitter(
      (NativeModules as Record<string, never>)[MODULE_NAME] ?? undefined
    );
  }
  return emitter;
}

const EVENT_PREFIX = 'StepTrackerPro:';

async function call<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (error) {
    throw toStepTrackerError(error);
  }
}

/**
 * Decides what to send for `strideLength`. The native side derives stride
 * from its *stored* height and sex whenever the value is 0, so JS never has
 * to guess at values it does not have: an explicit stride is passed through,
 * a change to height or sex sends 0 to switch back to derivation, and a
 * patch touching neither leaves the key alone.
 */
function strideForPatch(config: StepTrackerConfig): number | undefined {
  if (config.strideLength != null) {
    if (!(config.strideLength > 0)) {
      throw new StepTrackerError('E_INVALID_CONFIG', 'strideLength must be > 0');
    }
    return config.strideLength;
  }
  if (config.height != null || config.sex != null) return 0;
  return undefined;
}

/** Stride in metres for a given height and sex, the same formula the native side uses. */
export function estimateStride(
  height: number,
  sex: keyof typeof STRIDE_COEFFICIENT = 'unspecified'
): number {
  return (height * STRIDE_COEFFICIENT[sex]) / 100;
}

const DATE_KEY = /^\d{4}-\d{2}-\d{2}$/;

function assertDate(date: string): void {
  if (!DATE_KEY.test(date)) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'Dates must be yyyy-MM-dd');
  }
}

function assertRange(startDate: string, endDate: string): void {
  if (!DATE_KEY.test(startDate) || !DATE_KEY.test(endDate)) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'Dates must be yyyy-MM-dd');
  }
  if (startDate > endDate) {
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      `startDate (${startDate}) must be on or before endDate (${endDate})`
    );
  }
}

/** 0 turns a check off; anything else must fall inside the range where it means something. */
function assertThreshold(
  name: string,
  value: number | undefined,
  min: number,
  max = Number.POSITIVE_INFINITY
): void {
  if (value == null) return;
  if (value === 0) return;
  if (!(Number.isInteger(value) && value >= min && value <= max)) {
    const range = Number.isFinite(max) ? `${min}–${max}` : `>= ${min}`;
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      `fraudDetection.${name} must be 0 (off) or an integer ${range}`
    );
  }
}

function assertFraudDetection(config: StepTrackerConfig): void {
  const fraud = config.fraudDetection;
  if (fraud == null) return;
  if (fraud.mode != null && fraud.mode !== 'flag' && fraud.mode !== 'exclude') {
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      "fraudDetection.mode must be 'flag' or 'exclude'"
    );
  }
  // The native side clamps as well - config is rebuilt from persisted JSON
  // too - but a value it would silently move is a mistake worth reporting.
  assertThreshold('maxCadenceSpm', fraud.maxCadenceSpm, 100, 400);
  assertThreshold('steadyCadenceMinutes', fraud.steadyCadenceMinutes, 5, 1440);
  assertThreshold('maxContinuousMinutes', fraud.maxContinuousMinutes, 30, 1440);
  assertThreshold('maxDailySteps', fraud.maxDailySteps, 1000);
}

/** UTF-8 byte length without TextEncoder, which older Hermes builds lack. */
function utf8Length(value: string): number {
  return encodeURIComponent(value).replace(/%[0-9A-F]{2}/gi, 'x').length;
}

function normaliseConfig(config: StepTrackerConfig): StepTrackerConfig {
  const daily = config.dailyGoal ?? DEFAULT_CONFIG.dailyGoal;
  if (daily <= 0) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'dailyGoal must be > 0');
  }
  if (config.height != null && (config.height < 50 || config.height > 260)) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'height must be 50–260 cm');
  }
  if (config.weight != null && (config.weight < 10 || config.weight > 400)) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'weight must be 10–400 kg');
  }
  if (config.persistEveryNSteps != null && config.persistEveryNSteps < 1) {
    // 0 makes the native `pendingCommit >= threshold` check true with nothing
    // pending, which turns every step into a database write.
    throw new StepTrackerError('E_INVALID_CONFIG', 'persistEveryNSteps must be >= 1');
  }
  if (config.calorieCoefficient != null && config.calorieCoefficient < 0) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'calorieCoefficient must be >= 0');
  }
  if (config.historyRetentionDays != null && config.historyRetentionDays < 1) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'historyRetentionDays must be >= 1');
  }
  if (config.remoteSyncUrl) {
    const url = config.remoteSyncUrl.toLowerCase();
    const https = url.startsWith('https://');
    const http = url.startsWith('http://');
    if (!https && !(http && config.remoteSyncAllowHttp)) {
      throw new StepTrackerError(
        'E_INVALID_CONFIG',
        'remoteSyncUrl must be an https:// URL (set remoteSyncAllowHttp for a dev server)'
      );
    }
  }
  if (config.privacyPolicyUrl) {
    const url = config.privacyPolicyUrl.toLowerCase();
    if (!url.startsWith('https://') && !url.startsWith('http://')) {
      throw new StepTrackerError(
        'E_INVALID_CONFIG',
        'privacyPolicyUrl must be a web URL'
      );
    }
  }
  if (config.gapRecoveryMaxSteps != null && !(config.gapRecoveryMaxSteps >= 0)) {
    // Zero is meaningful - "credit nothing from a recovery" - but a negative
    // or NaN cap has no reading, and the native clamp would silently turn
    // it into zero, which is a stricter policy than the caller wrote.
    throw new StepTrackerError('E_INVALID_CONFIG', 'gapRecoveryMaxSteps must be >= 0');
  }
  if (
    config.accelerometerThreshold != null &&
    !(config.accelerometerThreshold >= 0.3 && config.accelerometerThreshold <= 10)
  ) {
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      'accelerometerThreshold must be 0.3–10 m/s²'
    );
  }
  if (
    config.wearableAllowlist != null &&
    (!Array.isArray(config.wearableAllowlist) ||
      config.wearableAllowlist.some(
        (pkg) => typeof pkg !== 'string' || pkg.trim() === ''
      ))
  ) {
    // Native reads the array with getString(); a non-string entry would
    // reject from the bridge as E_UNKNOWN rather than as bad config.
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      'wearableAllowlist must be an array of package names'
    );
  }
  const motion = config.motionSampling;
  if (motion != null) {
    // The sampler holds a minute at 50 Hz and does not resample, and an
    // interval under a minute is a poll, not a signature.
    if (
      motion.windowSeconds != null &&
      !(motion.windowSeconds >= 1 && motion.windowSeconds <= 60)
    ) {
      throw new StepTrackerError(
        'E_INVALID_CONFIG',
        'motionSampling.windowSeconds must be 1–60'
      );
    }
    if (motion.intervalMinutes != null && !(motion.intervalMinutes >= 1)) {
      throw new StepTrackerError(
        'E_INVALID_CONFIG',
        'motionSampling.intervalMinutes must be >= 1'
      );
    }
  }
  if (config.motionWindowRetention != null && !(config.motionWindowRetention >= 1)) {
    throw new StepTrackerError('E_INVALID_CONFIG', 'motionWindowRetention must be >= 1');
  }
  assertFraudDetection(config);
  // Only the keys the caller supplied cross the bridge. The native side holds
  // the same defaults and, more importantly, holds whatever the user set last
  // session: spreading DEFAULT_CONFIG here sent `height: 170` on every
  // initialize({ dailyGoal }) and silently reset a 190 cm user's stride.
  const normalised: StepTrackerConfig = { ...config };
  if (config.dailyGoal != null) {
    normalised.weeklyGoal = config.weeklyGoal ?? daily * 7;
    normalised.monthlyGoal = config.monthlyGoal ?? daily * 30;
  }

  const stride = strideForPatch(config);
  if (stride === undefined) delete normalised.strideLength;
  else normalised.strideLength = stride;
  return normalised;
}

/**
 * The public API. All methods reject with a `StepTrackerError` carrying a
 * stable `code`, so callers can branch without string matching.
 */
export const StepTracker = {
  /**
   * Loads config into the native layer and restores any persisted state.
   * Safe to call on every app launch — it is idempotent.
   */
  async initialize(config: StepTrackerConfig = {}): Promise<StepSnapshot> {
    const merged = normaliseConfig(config);
    return call(() => getNativeModule().initialize(merged)) as Promise<StepSnapshot>;
  },

  /** Patches config at runtime. Notification and goals update immediately. */
  async updateConfig(config: StepTrackerConfig): Promise<StepTrackerConfig> {
    const patch: StepTrackerConfig = { ...config };
    const stride = strideForPatch(config);
    if (stride === undefined) delete patch.strideLength;
    else patch.strideLength = stride;
    return call(() =>
      getNativeModule().updateConfig(patch)
    ) as Promise<StepTrackerConfig>;
  },

  async getConfig(): Promise<StepTrackerConfig> {
    return call(() => getNativeModule().getConfig()) as Promise<StepTrackerConfig>;
  },

  /** Starts the foreground service. Requires permissions to be granted first. */
  async startTracking(): Promise<StepSnapshot> {
    return call(() => getNativeModule().startTracking()) as Promise<StepSnapshot>;
  },

  /** Keeps the service and notification alive but ignores sensor deltas. */
  async pauseTracking(): Promise<StepSnapshot> {
    return call(() => getNativeModule().pauseTracking()) as Promise<StepSnapshot>;
  },

  async resumeTracking(): Promise<StepSnapshot> {
    return call(() => getNativeModule().resumeTracking()) as Promise<StepSnapshot>;
  },

  /** Flushes to the database, unregisters sensors and kills the service. */
  async stopTracking(): Promise<StepSnapshot> {
    return call(() => getNativeModule().stopTracking()) as Promise<StepSnapshot>;
  },

  async getTrackingState(): Promise<TrackingState> {
    const result = (await call(() => getNativeModule().getTrackingState())) as {
      state: TrackingState;
    };
    return result.state;
  },

  async isTracking(): Promise<boolean> {
    return call(() => getNativeModule().isTracking());
  },

  /**
   * Whether tracking is actually working, as opposed to merely marked
   * running. `looksDead` means an OEM killed the service; calling this from
   * the foreground also restarts it. `recoveryCount` climbing is the cue to
   * walk the user to `openManufacturerAutoStartSettings()`.
   */
  async getTrackingHealth(): Promise<TrackingHealth> {
    return call(() => getNativeModule().getTrackingHealth()) as Promise<TrackingHealth>;
  },

  // ---- reads -----------------------------------------------------------

  async getTodaySteps(): Promise<StepSnapshot> {
    return call(() => getNativeModule().getTodaySteps()) as Promise<StepSnapshot>;
  },

  async getYesterdaySteps(): Promise<DayRecord> {
    return call(() => getNativeModule().getYesterdaySteps()) as Promise<DayRecord>;
  },

  /** @param date yyyy-MM-dd */
  async getStepsForDate(date: string): Promise<DayRecord> {
    return call(() => getNativeModule().getStepsForDate(date)) as Promise<DayRecord>;
  },

  async getWeeklyStats(options: RangeOptions = {}): Promise<RangeStats> {
    return call(() => getNativeModule().getWeeklyStats(options)) as Promise<RangeStats>;
  },

  async getMonthlyStats(options: RangeOptions = {}): Promise<RangeStats> {
    return call(() => getNativeModule().getMonthlyStats(options)) as Promise<RangeStats>;
  },

  async getYearlyStats(options: RangeOptions = {}): Promise<RangeStats> {
    return call(() => getNativeModule().getYearlyStats(options)) as Promise<RangeStats>;
  },

  /** Inclusive on both ends. Dates are yyyy-MM-dd, and start must precede end. */
  async getStatsForRange(startDate: string, endDate: string): Promise<RangeStats> {
    assertRange(startDate, endDate);
    return call(() =>
      getNativeModule().getStatsForRange(startDate, endDate)
    ) as Promise<RangeStats>;
  },

  async getHistory(startDate: string, endDate: string): Promise<DayRecord[]> {
    assertRange(startDate, endDate);
    const result = (await call(() =>
      getNativeModule().getHistory(startDate, endDate)
    )) as { records: DayRecord[] };
    return result.records;
  },

  /**
   * Everything a server needs to judge one day, nothing resolved for it:
   * this phone's own count and recovered share, every Health Connect origin
   * unresolved (manual entries marked, wearable trust decided), what the
   * policy chose for comparison, the sensor, the device, the service's
   * recovery history and the clock. Post this, not `steps`.
   *
   * @param date yyyy-MM-dd
   */
  async getVerificationSnapshot(
    date: string,
    options: VerificationSnapshotOptions = {}
  ): Promise<VerificationSnapshot> {
    assertDate(date);
    if (
      options.nonce != null &&
      (typeof options.nonce !== 'string' || options.nonce.length > 512)
    ) {
      throw new StepTrackerError(
        'E_INVALID_CONFIG',
        'nonce must be a string of at most 512 characters'
      );
    }
    return call(() =>
      getNativeModule().getVerificationSnapshot(date, options)
    ) as Promise<VerificationSnapshot>;
  },

  // ---- integrity -------------------------------------------------------

  /**
   * What the integrity checks found for one day: the flags and the numbers
   * behind them, `suspectSteps`, the events logged that day, the per-minute
   * totals and the device hints. Today is judged afresh on every call; a
   * past day reads its stored verdict. Everything is zero or empty unless
   * `fraudDetection.enabled`, except the device hints.
   *
   * @param date yyyy-MM-dd
   */
  async getIntegrityReport(date: string): Promise<IntegrityReport> {
    assertDate(date);
    return call(() =>
      getNativeModule().getIntegrityReport(date)
    ) as Promise<IntegrityReport>;
  },

  /** The integrity event log between two dates, inclusive, oldest first. */
  async getIntegrityEvents(
    startDate: string,
    endDate: string
  ): Promise<IntegrityEvent[]> {
    assertRange(startDate, endDate);
    const result = (await call(() =>
      getNativeModule().getIntegrityEvents(startDate, endDate)
    )) as { events: IntegrityEvent[] };
    return result.events;
  },

  /**
   * This phone's steps minute by minute between two dates, inclusive,
   * oldest first. Only minutes with steps are listed. Recorded only while
   * `fraudDetection.enabled`, and kept for `historyRetentionDays`.
   */
  async getStepMinutes(startDate: string, endDate: string): Promise<StepMinute[]> {
    assertRange(startDate, endDate);
    const result = (await call(() =>
      getNativeModule().getStepMinutes(startDate, endDate)
    )) as { minutes: StepMinute[] };
    return result.minutes;
  },

  /**
   * Generates a fresh Keystore key bound to a challenge from your server and
   * returns its attestation chain for the server to verify. Snapshots signed
   * afterwards, and every remote upload, carry signatures from this key.
   * Calling it again replaces the key.
   *
   * @param challenge 1–128 bytes of UTF-8, random and single-use, from your server.
   */
  async attestDevice(challenge: string): Promise<DeviceAttestation> {
    const bytes = typeof challenge === 'string' ? utf8Length(challenge) : 0;
    if (bytes < 1 || bytes > 128) {
      throw new StepTrackerError(
        'E_INVALID_CONFIG',
        'challenge must be 1–128 bytes of UTF-8'
      );
    }
    return call(() =>
      getNativeModule().attestDevice(challenge)
    ) as Promise<DeviceAttestation>;
  },

  /**
   * Motion signature windows that opened on the days between the two dates,
   * inclusive, oldest first. Empty unless `motionSampling.enabled`. Features
   * only — the samples were discarded on device.
   */
  async getMotionWindows(startDate: string, endDate: string): Promise<MotionWindow[]> {
    assertRange(startDate, endDate);
    const result = (await call(() =>
      getNativeModule().getMotionWindows(startDate, endDate)
    )) as { windows: MotionWindow[] };
    return result.windows;
  },

  // ---- writes ----------------------------------------------------------

  /** Zeroes today's counter without touching history. Useful in QA builds. */
  async resetToday(): Promise<boolean> {
    return call(() => getNativeModule().resetToday());
  },

  async clearHistory(): Promise<boolean> {
    return call(() => getNativeModule().clearHistory());
  },

  /** Deletes rows older than `retentionDays`; returns rows removed. */
  async pruneHistory(retentionDays?: number): Promise<number> {
    return call(() =>
      getNativeModule().pruneHistory(retentionDays ?? DEFAULT_CONFIG.historyRetentionDays)
    );
  },

  // ---- permissions -----------------------------------------------------

  async checkPermissions(): Promise<PermissionStatus> {
    return call(() => getNativeModule().checkPermissions()) as Promise<PermissionStatus>;
  },

  /** Shows the Android runtime dialogs. Must be called with an Activity attached. */
  async requestPermissions(): Promise<PermissionStatus> {
    return call(() =>
      getNativeModule().requestPermissions()
    ) as Promise<PermissionStatus>;
  },

  async getDeviceCapabilities(): Promise<DeviceCapabilities> {
    return call(() =>
      getNativeModule().getDeviceCapabilities()
    ) as Promise<DeviceCapabilities>;
  },

  async openAppSettings(): Promise<boolean> {
    return call(() => getNativeModule().openAppSettings());
  },

  // ---- battery ---------------------------------------------------------

  /** True when the OS is still applying doze restrictions to this app. */
  async isBatteryOptimizationEnabled(): Promise<boolean> {
    return call(() => getNativeModule().isBatteryOptimizationEnabled());
  },

  /**
   * Shows the system exemption dialog. Play policy requires an eligible use
   * case — see docs/PLAY_STORE_COMPLIANCE.md before shipping this.
   */
  async requestDisableBatteryOptimization(): Promise<boolean> {
    return call(() => getNativeModule().requestDisableBatteryOptimization());
  },

  /** Policy-safe alternative: opens the settings list, no direct prompt. */
  async openBatteryOptimizationSettings(): Promise<boolean> {
    return call(() => getNativeModule().openBatteryOptimizationSettings());
  },

  /**
   * Best-effort deep link into the OEM's autostart / background-launch screen
   * (Xiaomi, Oppo, Realme, OnePlus, Vivo, Huawei, Honor, Tecno, Infinix, itel,
   * Samsung, Asus and others), falling back to the app-info page.
   */
  async openManufacturerAutoStartSettings(): Promise<boolean> {
    return call(() => getNativeModule().openManufacturerAutoStartSettings());
  },

  /**
   * Which background restrictions apply on this device and which screens
   * exist to lift them. Drives an onboarding step that only appears on phones
   * that need it.
   */
  async getBackgroundRestrictionStatus(): Promise<BackgroundRestrictionStatus> {
    return call(() =>
      getNativeModule().getBackgroundRestrictionStatus()
    ) as Promise<BackgroundRestrictionStatus>;
  },

  /**
   * The whole "keep tracking alive on this phone" flow behind one call, for
   * an onboarding screen. Returns what was shown so the UI can explain it:
   *
   * - `'none'` — stock Android, nothing needed
   * - `'battery'` — the Doze exemption dialog (or settings list) was opened
   * - `'autostart'` — an OEM autostart / background screen was opened
   *
   * Only one screen opens per call; call again on next foreground until the
   * status comes back clean. Pass `directPrompt: false` to use the settings
   * list instead of the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog.
   */
  async requestBackgroundPermissions(
    options: { directPrompt?: boolean } = {}
  ): Promise<'none' | 'battery' | 'autostart'> {
    const status = await StepTracker.getBackgroundRestrictionStatus();
    if (status.batteryOptimizationEnabled) {
      const opened =
        options.directPrompt === false
          ? await StepTracker.openBatteryOptimizationSettings()
          : await StepTracker.requestDisableBatteryOptimization();
      if (opened) return 'battery';
    }
    if (status.aggressiveOem && status.autoStartSettingsAvailable) {
      await StepTracker.openManufacturerAutoStartSettings();
      return 'autostart';
    }
    return 'none';
  },

  // ---- health connect --------------------------------------------------

  async getHealthConnectStatus(): Promise<HealthConnectStatus> {
    return call(() =>
      getNativeModule().getHealthConnectStatus()
    ) as Promise<HealthConnectStatus>;
  },

  /**
   * Shows the Health Connect permission sheet and resolves with the status
   * afterwards. A denial is not a rejection — check `granted` on the result.
   *
   * Rejects with `E_HEALTH_CONNECT_NOT_INSTALLED` or
   * `E_HEALTH_CONNECT_UPDATE_REQUIRED` when there is no usable provider; both
   * are answered by `installHealthConnect()`.
   *
   * Health Connect stops showing the sheet after two refusals, so a call that
   * resolves with `shouldOpenSettings: true` did nothing visible — route the
   * user to `openHealthConnectSettings()` from there.
   */
  async requestHealthConnectPermissions(
    options: RequestHealthConnectOptions = {}
  ): Promise<HealthConnectStatus> {
    return call(() =>
      getNativeModule().requestHealthConnectPermissions(options)
    ) as Promise<HealthConnectStatus>;
  },

  /**
   * Runs the whole "turn Health Connect on" flow and reports where it got to.
   *
   * Install or update first when the provider is missing, then request, then
   * fall back to the settings screen once the sheet has stopped appearing.
   * Callers that want to drive each step themselves can use the individual
   * methods instead.
   */
  async enableHealthConnect(
    options: RequestHealthConnectOptions = {}
  ): Promise<HealthConnectStatus> {
    let status = await StepTracker.getHealthConnectStatus();
    if (status.availability === 'not_supported') return status;

    if (status.installable) {
      await StepTracker.installHealthConnect();
      // The Play Store is a separate task, so the user is gone for an unknown
      // length of time. Returning here rather than waiting lets the caller
      // re-run this on next foreground, where `healthConnectStatusChanged`
      // will already have fired.
      return status;
    }

    if (!status.granted) {
      if (status.shouldOpenSettings) {
        await StepTracker.openHealthConnectSettings();
        return status;
      }
      status = await StepTracker.requestHealthConnectPermissions(options);
    }
    return status;
  },

  async openHealthConnectSettings(): Promise<boolean> {
    return call(() => getNativeModule().openHealthConnectSettings());
  },

  /**
   * Opens the Play Store listing for Health Connect. The right answer to
   * `availability: 'not_installed'` or `'update_required'`.
   */
  async installHealthConnect(): Promise<boolean> {
    return call(() => getNativeModule().installHealthConnect());
  },

  /** Drops every Health Connect grant this app holds. */
  async revokeHealthConnectPermissions(): Promise<boolean> {
    return call(() => getNativeModule().revokeHealthConnectPermissions());
  },

  /** ISO-8601 instants, e.g. '2026-09-01T00:00:00Z'. */
  async readHealthConnectSteps(
    startIso: string,
    endIso: string
  ): Promise<{ totalSteps: number; records: DayRecord[] }> {
    return call(() =>
      getNativeModule().readHealthConnectSteps(startIso, endIso)
    ) as Promise<{ totalSteps: number; records: DayRecord[] }>;
  },

  /** Upserts one day's steps/distance/calories into Health Connect. */
  async writeHealthConnectSteps(date: string): Promise<boolean> {
    return call(() => getNativeModule().writeHealthConnectSteps(date));
  },

  async syncWithHealthConnect(): Promise<SyncEvent> {
    return call(() => getNativeModule().syncWithHealthConnect()) as Promise<SyncEvent>;
  },

  // ---- step sources ----------------------------------------------------

  /**
   * Every app that published steps to Health Connect over the range, with what
   * each one contributed.
   *
   * The totals do not add up to the range's step count and are not meant to:
   * a watch and a phone covering the same walk each report all of it. Use this
   * to show the user their options, then pin one with
   * `setPreferredStepSource()`.
   *
   * Returns an empty list when Health Connect is unavailable or reads are not
   * granted.
   */
  async getStepSources(startDate: string, endDate: string): Promise<StepSourceList> {
    assertRange(startDate, endDate);
    return call(() =>
      getNativeModule().getStepSources(startDate, endDate)
    ) as Promise<StepSourceList>;
  },

  /**
   * Which source is answering for today, what this phone counted, and what the
   * best external source counted.
   */
  async getCurrentStepSource(): Promise<CurrentStepSource> {
    return call(() =>
      getNativeModule().getCurrentStepSource()
    ) as Promise<CurrentStepSource>;
  },

  /**
   * Pins one Health Connect origin as the source of truth, or clears the pin
   * with `null`. Stored separately from config, so it survives an
   * `initialize()` that does not mention it.
   */
  async setPreferredStepSource(packageName: string | null): Promise<ResolvedStepSource> {
    return call(() =>
      getNativeModule().setPreferredStepSource(packageName)
    ) as Promise<ResolvedStepSource>;
  },

  /**
   * Wearable companion apps installed on this phone — Galaxy Wearable, Fitbit,
   * Garmin Connect and so on. Useful during onboarding, before Health Connect
   * has any data to look at.
   */
  async getInstalledCompanionApps(): Promise<CompanionApp[]> {
    const result = (await call(() => getNativeModule().getInstalledCompanionApps())) as {
      apps: CompanionApp[];
    };
    return result.apps;
  },

  // ---- sync ------------------------------------------------------------

  async getPendingSyncCount(): Promise<number> {
    return call(() => getNativeModule().getPendingSyncCount());
  },

  /** Runs Health Connect + remote sync immediately instead of waiting for WorkManager. */
  async syncNow(): Promise<SyncEvent[]> {
    const result = (await call(() => getNativeModule().syncNow())) as {
      results: SyncEvent[];
    };
    return result.results;
  },

  // ---- events ----------------------------------------------------------

  addListener<E extends StepTrackerEvent>(
    event: E,
    listener: (payload: StepTrackerEventMap[E]) => void
  ): EventSubscription {
    if (Platform.OS !== 'android') return { remove: () => {} };
    const sub = getEmitter().addListener(`${EVENT_PREFIX}${event}`, listener);
    return { remove: () => sub.remove() };
  },

  /** Removes every listener for one event, or all events when omitted. */
  removeListener(event?: StepTrackerEvent): void {
    if (Platform.OS !== 'android') return;
    const em = getEmitter();
    if (event) em.removeAllListeners(`${EVENT_PREFIX}${event}`);
    else {
      (
        [
          'stepsChanged',
          'goalReached',
          'goalProgressChanged',
          'trackingStateChanged',
          'dayChanged',
          'historyBackfilled',
          'motionWindow',
          'suspiciousActivity',
          'syncCompleted',
          'stepSourceChanged',
          'healthConnectStatusChanged',
          'error',
        ] as StepTrackerEvent[]
      ).forEach((name) => em.removeAllListeners(`${EVENT_PREFIX}${name}`));
    }
  },
};

export default StepTracker;
