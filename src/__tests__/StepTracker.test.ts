// Imported by path rather than as 'react-native' so the test hooks type-check;
// the moduleNameMapper resolves both to the same module instance at runtime.
import {
  Platform,
  __emit,
  __listenerCount,
  setNativeModule,
} from '../__mocks__/react-native';
import StepTracker, { estimateStride, isSupported } from '../StepTracker';
import { DEFAULT_CONFIG } from '../constants';
import { StepTrackerError } from '../errors';
import type { HealthConnectStatus } from '../types';

/**
 * A scripted native module: every method resolves with whatever the test
 * queued, and records what it was called with. The JS layer's job is to
 * validate, shape and route — that is what is asserted here.
 */
function fakeNative() {
  const calls: Array<{ method: string; args: unknown[] }> = [];
  const results = new Map<string, unknown>();
  const handler: ProxyHandler<Record<string, unknown>> = {
    get(_target, method: string) {
      if (method === 'then') return undefined;
      return (...args: unknown[]) => {
        calls.push({ method, args });
        const queued = results.get(method);
        if (queued instanceof Error) return Promise.reject(queued);
        return Promise.resolve(queued);
      };
    },
  };
  const mod = new Proxy({}, handler) as Record<string, unknown>;
  return {
    mod,
    calls,
    calledWith: (method: string) =>
      calls.filter((c) => c.method === method).map((c) => c.args),
    when: (method: string, result: unknown) => results.set(method, result),
  };
}

const baseStatus: HealthConnectStatus = {
  available: true,
  availability: 'available',
  requiresUpdate: false,
  installable: false,
  granted: false,
  readRequired: true,
  writeRequired: true,
  canRead: false,
  canWrite: false,
  backgroundReadGranted: false,
  historyReadGranted: false,
  grantedPermissions: [],
  missingPermissions: ['android.permission.health.READ_STEPS'],
  denialCount: 0,
  shouldOpenSettings: false,
};

let native: ReturnType<typeof fakeNative>;

beforeEach(() => {
  Platform.OS = 'android';
  native = fakeNative();
  setNativeModule(native.mod);
});

afterEach(() => setNativeModule(null));

describe('initialize()', () => {
  it('sends only the keys the caller passed, never the JS defaults', async () => {
    await StepTracker.initialize({ dailyGoal: 8000 });
    const [config] = native.calledWith('initialize')[0]!;
    // A returning user's stored height must not be overwritten with 170.
    expect(config).toEqual({ dailyGoal: 8000, weeklyGoal: 56000, monthlyGoal: 240000 });
  });

  it('derives weekly and monthly goals only when a daily goal is given', async () => {
    await StepTracker.initialize({ height: 180 });
    const [config] = native.calledWith('initialize')[0]!;
    expect(config).not.toHaveProperty('weeklyGoal');
    expect(config).not.toHaveProperty('monthlyGoal');
  });

  it('keeps explicit weekly and monthly goals', async () => {
    await StepTracker.initialize({ dailyGoal: 8000, weeklyGoal: 50000 });
    const [config] = native.calledWith('initialize')[0]!;
    expect(config).toMatchObject({ weeklyGoal: 50000, monthlyGoal: 240000 });
  });

  it('passes an explicit stride through', async () => {
    await StepTracker.initialize({ strideLength: 0.8 });
    expect(native.calledWith('initialize')[0]![0]).toMatchObject({ strideLength: 0.8 });
  });

  it('sends stride 0 when height or sex changes, so the native side re-derives', async () => {
    await StepTracker.initialize({ sex: 'male' });
    expect(native.calledWith('initialize')[0]![0]).toEqual({
      sex: 'male',
      strideLength: 0,
    });
  });

  it('leaves stride alone when nothing it derives from was passed', async () => {
    await StepTracker.initialize({ dailyGoal: 10000 });
    expect(native.calledWith('initialize')[0]![0]).not.toHaveProperty('strideLength');
  });

  it.each([
    [{ dailyGoal: 0 }, /dailyGoal/],
    [{ height: 20 }, /height/],
    [{ weight: 5 }, /weight/],
    [{ persistEveryNSteps: 0 }, /persistEveryNSteps/],
    [{ calorieCoefficient: -1 }, /calorieCoefficient/],
    [{ historyRetentionDays: 0 }, /historyRetentionDays/],
    [{ strideLength: 0 }, /strideLength/],
    [{ accelerometerThreshold: 0.1 }, /accelerometerThreshold/],
    [{ gapRecoveryMaxSteps: -1 }, /gapRecoveryMaxSteps/],
    [{ motionSampling: { enabled: true, windowSeconds: 0 } }, /windowSeconds/],
    [{ motionSampling: { enabled: true, windowSeconds: 61 } }, /windowSeconds/],
    [{ motionSampling: { enabled: true, intervalMinutes: 0 } }, /intervalMinutes/],
    [{ motionWindowRetention: 0 }, /motionWindowRetention/],
    [{ wearableAllowlist: ['com.example.band', ''] }, /wearableAllowlist/],
    [
      { wearableAllowlist: 'com.example.band' as unknown as string[] },
      /wearableAllowlist/,
    ],
    [{ gapRecoveryMaxSteps: Number.NaN }, /gapRecoveryMaxSteps/],
    [{ remoteSyncUrl: 'http://api.example.com/steps' }, /https/],
    [{ remoteSyncUrl: 'ftp://api.example.com/steps' }, /https/],
    [{ privacyPolicyUrl: 'intent://evil' }, /privacyPolicyUrl/],
  ])('rejects %j with E_INVALID_CONFIG', async (config, message) => {
    await expect(StepTracker.initialize(config)).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
      message: expect.stringMatching(message),
    });
    expect(native.calls).toHaveLength(0);
  });

  it('allows a plain http remote endpoint only when opted in', async () => {
    await StepTracker.initialize({
      remoteSyncUrl: 'http://10.0.2.2:3000/steps',
      remoteSyncAllowHttp: true,
    });
    expect(native.calledWith('initialize')).toHaveLength(1);
  });

  it('passes wearableTrust and an allowlist through untouched', async () => {
    await StepTracker.initialize({
      wearableTrust: 'catalog',
      wearableAllowlist: ['com.example.band'],
    });
    expect(native.calledWith('initialize')[0]![0]).toEqual({
      wearableTrust: 'catalog',
      wearableAllowlist: ['com.example.band'],
    });
    expect(DEFAULT_CONFIG.wearableTrust).toBe('metadata');
  });

  it('passes today_capped and its cap through untouched', async () => {
    await StepTracker.initialize({ gapRecovery: 'today_capped', gapRecoveryMaxSteps: 0 });
    expect(native.calledWith('initialize')[0]![0]).toEqual({
      gapRecovery: 'today_capped',
      gapRecoveryMaxSteps: 0,
    });
    expect(DEFAULT_CONFIG.gapRecoveryMaxSteps).toBe(20000);
  });

  it('round-trips healthConnectIgnoreManualEntries through initialize() and getConfig()', async () => {
    // The flag is a plain pass-through: JS neither defaults it nor rewrites
    // it, so an app that never sets it sends nothing and the native default
    // (off) stands.
    await StepTracker.initialize({ healthConnectIgnoreManualEntries: true });
    expect(native.calledWith('initialize')[0]![0]).toEqual({
      healthConnectIgnoreManualEntries: true,
    });
    native.when('getConfig', {
      stepSource: 'auto',
      healthConnectIgnoreManualEntries: true,
    });
    await expect(StepTracker.getConfig()).resolves.toMatchObject({
      healthConnectIgnoreManualEntries: true,
    });
    expect(DEFAULT_CONFIG.healthConnectIgnoreManualEntries).toBe(false);
  });

  it('allows https remote endpoints and web privacy policies', async () => {
    await StepTracker.initialize({
      remoteSyncUrl: 'https://api.example.com/steps',
      privacyPolicyUrl: 'https://example.com/privacy',
    });
    expect(native.calledWith('initialize')).toHaveLength(1);
  });
});

describe('updateConfig()', () => {
  it('re-derives stride natively instead of fetching config first', async () => {
    await StepTracker.updateConfig({ sex: 'female' });
    expect(native.calledWith('getConfig')).toHaveLength(0);
    expect(native.calledWith('updateConfig')[0]![0]).toEqual({
      sex: 'female',
      strideLength: 0,
    });
  });
});

describe('estimateStride()', () => {
  it('matches the native formula', () => {
    expect(estimateStride(175, 'male')).toBeCloseTo(0.72625, 5);
    expect(estimateStride(170)).toBeCloseTo(0.7038, 4);
  });
});

describe('date validation', () => {
  it('rejects malformed or inverted ranges before touching native', async () => {
    await expect(
      StepTracker.getStatsForRange('2026-9-1', '2026-09-09')
    ).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
    });
    await expect(
      StepTracker.getHistory('2026-09-10', '2026-09-09')
    ).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
    });
    expect(native.calls).toHaveLength(0);
  });

  it('getVerificationSnapshot() rejects a malformed date before touching native', async () => {
    for (const bad of ['2026-9-1', '20260901', 'today', '']) {
      await expect(StepTracker.getVerificationSnapshot(bad)).rejects.toMatchObject({
        code: 'E_INVALID_CONFIG',
        message: expect.stringMatching(/yyyy-MM-dd/),
      });
    }
    expect(native.calls).toHaveLength(0);

    native.when('getVerificationSnapshot', { date: '2026-09-14', deviceSteps: 7 });
    await expect(
      StepTracker.getVerificationSnapshot('2026-09-14')
    ).resolves.toMatchObject({
      deviceSteps: 7,
    });
    expect(native.calledWith('getVerificationSnapshot')).toEqual([['2026-09-14']]);
  });

  it('getMotionWindows() validates the range and unwraps the list', async () => {
    await expect(
      StepTracker.getMotionWindows('2026-09-10', '2026-09-09')
    ).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
    });
    expect(native.calls).toHaveLength(0);
    native.when('getMotionWindows', {
      windows: [{ startedAt: 1, dominantFrequencyHz: 1.8 }],
    });
    await expect(
      StepTracker.getMotionWindows('2026-09-09', '2026-09-09')
    ).resolves.toEqual([{ startedAt: 1, dominantFrequencyHz: 1.8 }]);
  });

  it('passes motionSampling through as one object', async () => {
    await StepTracker.initialize({
      motionSampling: { enabled: true, windowSeconds: 15 },
    });
    expect(native.calledWith('initialize')[0]![0]).toEqual({
      motionSampling: { enabled: true, windowSeconds: 15 },
    });
    expect(DEFAULT_CONFIG.motionSampling.enabled).toBe(false);
  });

  it('unwraps history records', async () => {
    native.when('getHistory', { records: [{ date: '2026-09-09', steps: 1 }] });
    await expect(StepTracker.getHistory('2026-09-01', '2026-09-09')).resolves.toEqual([
      { date: '2026-09-09', steps: 1 },
    ]);
  });
});

describe('enableHealthConnect()', () => {
  it('does nothing on an unsupported device', async () => {
    native.when('getHealthConnectStatus', {
      ...baseStatus,
      availability: 'not_supported',
    });
    const status = await StepTracker.enableHealthConnect();
    expect(status.availability).toBe('not_supported');
    expect(native.calledWith('installHealthConnect')).toHaveLength(0);
    expect(native.calledWith('requestHealthConnectPermissions')).toHaveLength(0);
  });

  it('opens the Play Store when the provider is installable', async () => {
    native.when('getHealthConnectStatus', { ...baseStatus, installable: true });
    await StepTracker.enableHealthConnect();
    expect(native.calledWith('installHealthConnect')).toHaveLength(1);
    expect(native.calledWith('requestHealthConnectPermissions')).toHaveLength(0);
  });

  it('goes to settings once the sheet has stopped appearing', async () => {
    native.when('getHealthConnectStatus', {
      ...baseStatus,
      shouldOpenSettings: true,
      denialCount: 2,
    });
    await StepTracker.enableHealthConnect();
    expect(native.calledWith('openHealthConnectSettings')).toHaveLength(1);
    expect(native.calledWith('requestHealthConnectPermissions')).toHaveLength(0);
  });

  it('requests permissions otherwise, passing the options through', async () => {
    native.when('getHealthConnectStatus', baseStatus);
    native.when('requestHealthConnectPermissions', { ...baseStatus, granted: true });
    const status = await StepTracker.enableHealthConnect({ backgroundRead: true });
    expect(status.granted).toBe(true);
    expect(native.calledWith('requestHealthConnectPermissions')[0]).toEqual([
      { backgroundRead: true },
    ]);
  });

  it('is a no-op when already granted', async () => {
    native.when('getHealthConnectStatus', { ...baseStatus, granted: true });
    await StepTracker.enableHealthConnect();
    expect(native.calledWith('requestHealthConnectPermissions')).toHaveLength(0);
  });
});

describe('requestBackgroundPermissions()', () => {
  const restrictions = {
    manufacturer: 'Xiaomi',
    brand: 'Redmi',
    aggressiveOem: true,
    batteryOptimizationEnabled: true,
    autoStartSettingsAvailable: true,
    autoStartTarget: 'com.miui.securitycenter/x',
    backgroundStartNeedsExemption: true,
  };

  it('asks for the battery exemption first', async () => {
    native.when('getBackgroundRestrictionStatus', restrictions);
    native.when('requestDisableBatteryOptimization', true);
    await expect(StepTracker.requestBackgroundPermissions()).resolves.toBe('battery');
    expect(native.calledWith('openManufacturerAutoStartSettings')).toHaveLength(0);
  });

  it('uses the settings list when asked not to prompt directly', async () => {
    native.when('getBackgroundRestrictionStatus', restrictions);
    native.when('openBatteryOptimizationSettings', true);
    await expect(
      StepTracker.requestBackgroundPermissions({ directPrompt: false })
    ).resolves.toBe('battery');
    expect(native.calledWith('requestDisableBatteryOptimization')).toHaveLength(0);
  });

  it('moves on to the OEM screen once the exemption is granted', async () => {
    native.when('getBackgroundRestrictionStatus', {
      ...restrictions,
      batteryOptimizationEnabled: false,
    });
    native.when('openManufacturerAutoStartSettings', true);
    await expect(StepTracker.requestBackgroundPermissions()).resolves.toBe('autostart');
  });

  it('has nothing to show on stock Android', async () => {
    native.when('getBackgroundRestrictionStatus', {
      ...restrictions,
      aggressiveOem: false,
      batteryOptimizationEnabled: false,
      autoStartSettingsAvailable: false,
    });
    await expect(StepTracker.requestBackgroundPermissions()).resolves.toBe('none');
    expect(native.calls.map((c) => c.method)).toEqual(['getBackgroundRestrictionStatus']);
  });
});

describe('events', () => {
  it('prefixes event names and delivers payloads', () => {
    const seen: unknown[] = [];
    const sub = StepTracker.addListener('stepsChanged', (p) => seen.push(p));
    __emit('StepTrackerPro:stepsChanged', { steps: 12 });
    expect(seen).toEqual([{ steps: 12 }]);
    sub.remove();
    __emit('StepTrackerPro:stepsChanged', { steps: 13 });
    expect(seen).toHaveLength(1);
  });

  it('removeListener() with no argument clears every event', () => {
    StepTracker.addListener('goalReached', () => {});
    StepTracker.addListener('dayChanged', () => {});
    StepTracker.addListener('historyBackfilled', () => {});
    StepTracker.addListener('motionWindow', () => {});
    StepTracker.removeListener();
    expect(__listenerCount('StepTrackerPro:goalReached')).toBe(0);
    expect(__listenerCount('StepTrackerPro:dayChanged')).toBe(0);
    expect(__listenerCount('StepTrackerPro:historyBackfilled')).toBe(0);
    expect(__listenerCount('StepTrackerPro:motionWindow')).toBe(0);
  });

  it('delivers historyBackfilled with its payload', () => {
    const seen: unknown[] = [];
    StepTracker.addListener('historyBackfilled', (p) => seen.push(p));
    __emit('StepTrackerPro:historyBackfilled', {
      date: '2026-09-13',
      addedSteps: 200,
      totalSteps: 4200,
      reason: 'gap',
    });
    expect(seen).toEqual([
      { date: '2026-09-13', addedSteps: 200, totalSteps: 4200, reason: 'gap' },
    ]);
  });
});

describe('errors', () => {
  it('normalises native rejections into StepTrackerError with the code intact', async () => {
    const nativeError = Object.assign(new Error('ACTIVITY_RECOGNITION must be granted'), {
      code: 'E_PERMISSION_DENIED',
    });
    native.when('startTracking', nativeError);
    const error = await StepTracker.startTracking().catch((e) => e);
    expect(error).toBeInstanceOf(StepTrackerError);
    expect(error.code).toBe('E_PERMISSION_DENIED');
  });

  it('reports an unsupported platform without touching native', async () => {
    Platform.OS = 'ios';
    expect(isSupported()).toBe(false);
    await expect(StepTracker.getTodaySteps()).rejects.toMatchObject({
      code: 'E_UNSUPPORTED_PLATFORM',
    });
    expect(native.calls).toHaveLength(0);
  });

  it('reports a missing native module as a linking problem', async () => {
    setNativeModule(null);
    await expect(StepTracker.getTodaySteps()).rejects.toMatchObject({
      code: 'E_UNKNOWN',
      message: expect.stringMatching(/could not be found/),
    });
  });
});

describe('reads', () => {
  it('getTrackingState() returns the state string', async () => {
    native.when('getTrackingState', { state: 'paused', source: 'step_counter' });
    await expect(StepTracker.getTrackingState()).resolves.toBe('paused');
  });

  it('getInstalledCompanionApps() unwraps the list', async () => {
    native.when('getInstalledCompanionApps', { apps: [{ packageName: 'x' }] });
    await expect(StepTracker.getInstalledCompanionApps()).resolves.toEqual([
      { packageName: 'x' },
    ]);
  });
});
