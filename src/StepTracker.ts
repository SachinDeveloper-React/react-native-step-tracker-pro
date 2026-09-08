import { NativeEventEmitter, NativeModules, Platform } from 'react-native';
import NativeStepTrackerPro from './NativeStepTrackerPro';
import type { Spec } from './NativeStepTrackerPro';
import { DEFAULT_CONFIG, MODULE_NAME, STRIDE_COEFFICIENT } from './constants';
import { StepTrackerError, toStepTrackerError } from './errors';
import type {
  DayRecord,
  DeviceCapabilities,
  EventSubscription,
  HealthConnectStatus,
  PermissionStatus,
  RangeOptions,
  RangeStats,
  StepSnapshot,
  StepTrackerConfig,
  StepTrackerEvent,
  StepTrackerEventMap,
  SyncEvent,
  TrackingState,
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

function resolveStride(config: StepTrackerConfig): number {
  if (config.strideLength && config.strideLength > 0) return config.strideLength;
  const height = config.height ?? DEFAULT_CONFIG.height;
  const sex = config.sex ?? DEFAULT_CONFIG.sex;
  return (height * STRIDE_COEFFICIENT[sex]) / 100;
}

const DATE_KEY = /^\d{4}-\d{2}-\d{2}$/;

function assertRange(startDate: string, endDate: string): void {
  if (!DATE_KEY.test(startDate) || !DATE_KEY.test(endDate)) {
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      'Dates must be yyyy-MM-dd'
    );
  }
  if (startDate > endDate) {
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      `startDate (${startDate}) must be on or before endDate (${endDate})`
    );
  }
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
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      'persistEveryNSteps must be >= 1'
    );
  }
  if (config.calorieCoefficient != null && config.calorieCoefficient < 0) {
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      'calorieCoefficient must be >= 0'
    );
  }
  if (config.historyRetentionDays != null && config.historyRetentionDays < 1) {
    throw new StepTrackerError(
      'E_INVALID_CONFIG',
      'historyRetentionDays must be >= 1'
    );
  }
  const normalised: StepTrackerConfig = {
    ...DEFAULT_CONFIG,
    ...config,
    weeklyGoal: config.weeklyGoal ?? daily * 7,
    monthlyGoal: config.monthlyGoal ?? daily * 30,
  };

  // Only derive a stride when the caller actually supplied something it is
  // derived from. Deriving unconditionally meant that initialize({dailyGoal})
  // on a returning user recomputed their stride from the default 170 cm and
  // silently discarded the height they had configured; omitting the key leaves
  // whatever the native side already has.
  if (
    config.strideLength != null ||
    config.height != null ||
    config.sex != null
  ) {
    normalised.strideLength = resolveStride(config);
  } else {
    delete normalised.strideLength;
  }
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
    return call(() =>
      getNativeModule().initialize(merged)
    ) as Promise<StepSnapshot>;
  },

  /** Patches config at runtime. Notification and goals update immediately. */
  async updateConfig(config: StepTrackerConfig): Promise<StepTrackerConfig> {
    const patch: StepTrackerConfig = { ...config };
    if (config.height != null || config.sex != null || config.strideLength != null) {
      // Stride depends on height *and* sex, so a patch carrying only one of
      // them has to be combined with the stored value of the other. Passing the
      // bare patch fell back to the package defaults instead, which silently
      // reset a 190 cm user to 170 cm on updateConfig({ sex }).
      const current = await StepTracker.getConfig();
      patch.strideLength = resolveStride({
        height: config.height ?? current.height,
        sex: config.sex ?? current.sex,
        strideLength: config.strideLength,
      });
    }
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
      getNativeModule().pruneHistory(
        retentionDays ?? DEFAULT_CONFIG.historyRetentionDays
      )
    );
  },

  // ---- permissions -----------------------------------------------------

  async checkPermissions(): Promise<PermissionStatus> {
    return call(() => getNativeModule().checkPermissions()) as Promise<PermissionStatus>;
  },

  /** Shows the Android runtime dialogs. Must be called with an Activity attached. */
  async requestPermissions(): Promise<PermissionStatus> {
    return call(() => getNativeModule().requestPermissions()) as Promise<PermissionStatus>;
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

  /** Best-effort deep link into Xiaomi/Oppo/Vivo/Huawei autostart screens. */
  async openManufacturerAutoStartSettings(): Promise<boolean> {
    return call(() => getNativeModule().openManufacturerAutoStartSettings());
  },

  // ---- health connect --------------------------------------------------

  async getHealthConnectStatus(): Promise<HealthConnectStatus> {
    return call(() =>
      getNativeModule().getHealthConnectStatus()
    ) as Promise<HealthConnectStatus>;
  },

  async requestHealthConnectPermissions(): Promise<HealthConnectStatus> {
    return call(() =>
      getNativeModule().requestHealthConnectPermissions()
    ) as Promise<HealthConnectStatus>;
  },

  async openHealthConnectSettings(): Promise<boolean> {
    return call(() => getNativeModule().openHealthConnectSettings());
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
          'syncCompleted',
          'error',
        ] as StepTrackerEvent[]
      ).forEach((name) => em.removeAllListeners(`${EVENT_PREFIX}${name}`));
    }
  },
};

export default StepTracker;
