/**
 * A drop-in Jest mock of the whole package, shipped as
 * `react-native-step-tracker-pro/jest`:
 *
 *   jest.mock('react-native-step-tracker-pro', () =>
 *     require('react-native-step-tracker-pro/jest')
 *   );
 *
 * Every method resolves with a plausible, empty-day value and is a `jest.fn`
 * when Jest is present, so a test can override one with
 * `mockResolvedValueOnce`. `__emit(event, payload)` fires a listener added
 * through `addListener`. The hooks return settled, static results. Nothing
 * touches a native module.
 */
import {
  DEFAULT_CONFIG,
  HEALTH_CONNECT_MAX_PROMPTS,
  HEALTH_CONNECT_PACKAGE,
  STRIDE_COEFFICIENT,
} from './constants';
import { StepTrackerError } from './errors';
import type {
  DayRecord,
  IntegrityReport,
  RangeStats,
  ResolvedStepSource,
  StepSnapshot,
  StepTrackerEvent,
} from './types';

type AnyFn = (...args: never[]) => unknown;

/**
 * `jest.fn(impl)` under Jest; the bare function anywhere else. Jest injects
 * `jest` into every module it loads rather than onto `globalThis`, so it is
 * referenced directly and guarded with `typeof`.
 */
function fn<T extends AnyFn>(impl: T): T {
  return (typeof jest !== 'undefined' ? jest.fn(impl) : impl) as T;
}

function today(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function resolved(date = today()): ResolvedStepSource {
  return {
    date,
    steps: 0,
    kind: 'self',
    packageName: null,
    appName: 'This device',
    deviceSteps: 0,
    externalSteps: 0,
    usedExternal: false,
    merged: false,
    baselineSteps: 0,
    manualStepsExcluded: 0,
    suspectStepsExcluded: 0,
  };
}

export function mockSnapshot(overrides: Partial<StepSnapshot> = {}): StepSnapshot {
  return {
    date: today(),
    steps: 0,
    distance: 0,
    calories: 0,
    dailyGoal: DEFAULT_CONFIG.dailyGoal,
    goalProgress: 0,
    goalReached: false,
    state: 'idle',
    source: 'step_counter',
    timestamp: 0,
    recoveredSteps: 0,
    suspectSteps: 0,
    stepSource: resolved(),
    ...overrides,
  };
}

function day(date = today()): DayRecord {
  return {
    date,
    steps: 0,
    distance: 0,
    calories: 0,
    synced: false,
    syncedRemote: false,
    recoveredSteps: 0,
    suspectSteps: 0,
    stepSource: resolved(date),
  };
}

function stats(): RangeStats {
  const date = today();
  return {
    startDate: date,
    endDate: date,
    totalSteps: 0,
    totalDistance: 0,
    totalCalories: 0,
    averageSteps: 0,
    activeDays: 0,
    bestDay: null,
    days: [day(date)],
  };
}

function integrityReport(date = today()): IntegrityReport {
  return {
    date,
    enabled: false,
    mode: 'flag',
    deviceSteps: 0,
    suspectSteps: 0,
    flags: [],
    events: [],
    minutes: {
      count: 0,
      timedSteps: 0,
      untimedSteps: 0,
      chargingSteps: 0,
      stillSteps: 0,
      vehicleSteps: 0,
    },
    evaluatedAt: 0,
    rules: {
      maxCadenceSpm: 200,
      steadyCadenceMinutes: 30,
      maxContinuousMinutes: 180,
      maxDailySteps: 50000,
      flagWhileCharging: true,
    },
    charging: false,
    activityRecognition: { requested: false, available: false, current: 'unknown' },
    device: {
      emulator: false,
      testKeysBuild: false,
      suBinary: false,
      adbEnabled: false,
      developerOptions: false,
      appDebuggable: false,
      stepCounter: null,
    },
  };
}

const healthConnectStatus = {
  available: false,
  availability: 'not_installed' as const,
  requiresUpdate: false,
  installable: true,
  granted: false,
  readRequired: true,
  writeRequired: true,
  canRead: false,
  canWrite: false,
  backgroundReadGranted: false,
  historyReadGranted: false,
  grantedPermissions: [] as string[],
  missingPermissions: [] as string[],
  undeclaredPermissions: [] as string[],
  denialCount: 0,
  shouldOpenSettings: false,
};

const permissions = {
  activityRecognition: true,
  postNotifications: true,
  foregroundServiceHealth: true,
  allGranted: true,
};

const listeners = new Map<StepTrackerEvent, Set<(payload: unknown) => void>>();

const done = <T>(value: T) => fn(async (..._args: never[]) => value);

export const StepTracker = {
  initialize: fn(async (..._args: never[]) => mockSnapshot()),
  updateConfig: fn(async (..._args: never[]) => ({ ...DEFAULT_CONFIG })),
  getConfig: fn(async () => ({ ...DEFAULT_CONFIG })),
  startTracking: fn(async () => mockSnapshot({ state: 'running' })),
  pauseTracking: fn(async () => mockSnapshot({ state: 'paused' })),
  resumeTracking: fn(async () => mockSnapshot({ state: 'running' })),
  stopTracking: fn(async () => mockSnapshot({ state: 'stopped' })),
  getTrackingState: done('idle'),
  isTracking: done(false),
  getTrackingHealth: fn(async () => ({
    serviceAlive: false,
    shouldBeRunning: false,
    lastHeartbeatAt: 0,
    heartbeatAgeMs: -1,
    lastSensorEventAt: 0,
    lastRecoveryAt: 0,
    lastRecoveryReason: null,
    recoveryCount: 0,
    looksDead: false,
    batteryOptimizationEnabled: false,
    aggressiveOem: false,
    manufacturer: 'mock',
  })),

  getTodaySteps: fn(async () => mockSnapshot()),
  getYesterdaySteps: fn(async () => day()),
  getStepsForDate: fn(async (date: string) => day(date)),
  getWeeklyStats: fn(async (..._args: never[]) => stats()),
  getMonthlyStats: fn(async (..._args: never[]) => stats()),
  getYearlyStats: fn(async (..._args: never[]) => stats()),
  getStatsForRange: fn(async (..._args: never[]) => stats()),
  getHistory: done([] as DayRecord[]),
  getVerificationSnapshot: fn(async (date: string) => ({
    schemaVersion: 2,
    libraryVersion: 'mock',
    date,
    deviceSteps: 0,
    recoveredSteps: 0,
    sensor: 'step_counter',
    coverageStartAt: 0,
    sources: [],
    resolved: resolved(date),
    capabilities: {
      hasStepCounter: true,
      hasStepDetector: true,
      manufacturer: 'mock',
      model: 'mock',
      sdkInt: 35,
    },
    health: {
      recoveryCount: 0,
      lastRecoveryReason: null,
      batteryOptimizationEnabled: false,
      aggressiveOem: false,
    },
    clock: { wallClockMs: 0, bootId: 0, timezone: 'UTC', utcOffsetMinutes: 0 },
    suspectSteps: 0,
    integrity: integrityReport(date),
  })),
  getMotionWindows: done([]),

  getIntegrityReport: fn(async (date: string) => integrityReport(date)),
  getIntegrityEvents: done([]),
  getStepMinutes: done([]),
  attestDevice: fn(async (..._args: never[]) => ({
    keyId: 'mock-key',
    algorithm: 'SHA256withECDSA' as const,
    publicKey: '',
    certificateChain: [] as string[],
    attested: false,
    securityLevel: 'software' as const,
    createdAt: 0,
  })),
  hasAttestationKey: done(false),
  getAttestationKeyInfo: done(null),
  requestIntegrityToken: fn(async (options: { requestHash: string }) => ({
    token: 'mock-integrity-token',
    requestHash: options.requestHash,
  })),

  resetToday: done(true),
  clearHistory: done(true),
  pruneHistory: done(0),

  checkPermissions: done(permissions),
  requestPermissions: done(permissions),
  getDeviceCapabilities: fn(async () => ({
    hasStepCounter: true,
    hasStepDetector: true,
    hasAccelerometer: true,
    hasWakeUpAccelerometer: false,
    supported: true,
    bestSensor: 'step_counter' as const,
    sdkInt: 35,
    manufacturer: 'mock',
    model: 'mock',
  })),
  openAppSettings: done(true),

  isBatteryOptimizationEnabled: done(false),
  requestDisableBatteryOptimization: done(true),
  openBatteryOptimizationSettings: done(true),
  openManufacturerAutoStartSettings: done(true),
  getBackgroundRestrictionStatus: fn(async () => ({
    manufacturer: 'mock',
    brand: 'mock',
    aggressiveOem: false,
    batteryOptimizationEnabled: false,
    directPromptAvailable: false,
    autoStartSettingsAvailable: false,
    autoStartTarget: null,
    backgroundStartNeedsExemption: true,
  })),
  requestBackgroundPermissions: done('none' as const),

  getHealthConnectStatus: done(healthConnectStatus),
  requestHealthConnectPermissions: done(healthConnectStatus),
  enableHealthConnect: done(healthConnectStatus),
  openHealthConnectSettings: done(false),
  installHealthConnect: done(false),
  revokeHealthConnectPermissions: done(false),
  readHealthConnectSteps: done({ totalSteps: 0, records: [] as DayRecord[] }),
  writeHealthConnectSteps: done(false),
  syncWithHealthConnect: done({
    target: 'health_connect' as const,
    syncedRecords: 0,
    failedRecords: 0,
    success: false,
    error: 'Health Connect unavailable',
  }),
  getHealthConnectRecords: done({ records: [], truncated: false }),
  getHealthConnectChangesToken: done('mock-changes-token'),
  getHealthConnectChanges: done({
    tokenExpired: false,
    upserted: [],
    deletedIds: [] as string[],
    nextToken: 'mock-changes-token',
    hasMore: false,
  }),

  getStepSources: done({ sources: [], hasWearable: false }),
  getCurrentStepSource: fn(async () => ({
    ...resolved(),
    policy: 'auto' as const,
    preferredPackage: null,
  })),
  setPreferredStepSource: fn(async (..._args: never[]) => resolved()),
  getInstalledCompanionApps: done([]),

  getPendingSyncCount: done(0),
  syncNow: done([]),

  addListener: fn((event: StepTrackerEvent, listener: (payload: never) => void) => {
    const set = listeners.get(event) ?? new Set();
    listeners.set(event, set);
    const cb = listener as (payload: unknown) => void;
    set.add(cb);
    return { remove: () => set.delete(cb) };
  }),
  removeAllListeners: fn((event?: StepTrackerEvent) => {
    if (event) listeners.delete(event);
    else listeners.clear();
  }),
  removeListener: fn((event?: StepTrackerEvent) => {
    if (event) listeners.delete(event);
    else listeners.clear();
  }),
};

/** Test hook: fires every listener added for `event` through `addListener`. */
export function __emit(event: StepTrackerEvent, payload: unknown): void {
  listeners.get(event)?.forEach((listener) => listener(payload));
}

export const isSupported = fn(() => true);

export function estimateStride(
  height: number,
  sex: keyof typeof STRIDE_COEFFICIENT = 'unspecified'
): number {
  return (height * STRIDE_COEFFICIENT[sex]) / 100;
}

export const useStepTracker = fn((..._args: never[]) => ({
  snapshot: mockSnapshot(),
  state: 'idle' as const,
  ready: true,
  error: null,
  start: async () => {},
  pause: async () => {},
  resume: async () => {},
  stop: async () => {},
  refresh: async () => {},
  requestPermissions: async () => true,
}));

export const useStepStats = fn((..._args: never[]) => ({
  stats: stats(),
  loading: false,
  error: null,
  reload: async () => {},
}));

export const useHealthConnect = fn((..._args: never[]) => ({
  status: healthConnectStatus,
  sources: [],
  current: null,
  companionApps: [],
  hasWearable: false,
  loading: false,
  error: null,
  enable: async () => healthConnectStatus,
  request: async () => healthConnectStatus,
  openSettings: async () => {},
  install: async () => {},
  revoke: async () => {},
  selectSource: async () => {},
  refresh: async () => {},
}));

export {
  DEFAULT_CONFIG,
  HEALTH_CONNECT_MAX_PROMPTS,
  HEALTH_CONNECT_PACKAGE,
  STRIDE_COEFFICIENT,
  StepTrackerError,
};

export default StepTracker;
