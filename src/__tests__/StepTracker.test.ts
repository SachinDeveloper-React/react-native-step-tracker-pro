// Imported by path rather than as 'react-native' so the test hooks type-check;
// the moduleNameMapper resolves both to the same module instance at runtime.
import {
  Platform,
  __emit,
  __listenerCount,
  __setAppState,
  setNativeModule,
} from '../__mocks__/react-native';
import StepTracker, { estimateStride, isSupported } from '../StepTracker';
import React from 'react';
import TestRenderer from 'react-test-renderer';
import type { ReactTestRenderer } from 'react-test-renderer';
import { useStepTracker } from '../hooks/useStepTracker';
import { useHealthConnect } from '../hooks/useHealthConnect';
import { STATS_LIVE_REFRESH_MS, useStepStats } from '../hooks/useStepStats';
import type { UseHealthConnectResult } from '../hooks/useHealthConnect';
import type { UseStepTrackerResult } from '../hooks/useStepTracker';
import { DEFAULT_CONFIG } from '../constants';
import { StepTrackerError } from '../errors';
import type { HealthConnectStatus, StepTrackerConfig } from '../types';

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
      // The old architecture: no codegen-typed emitters (`onStepsChanged`
      // and so on), so events go through NativeEventEmitter.
      if (/^on[A-Z]/.test(method)) return undefined;
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
  undeclaredPermissions: [],
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

/** Config every entry point must reject before touching native. */
const INVALID_CONFIGS: Array<[StepTrackerConfig, RegExp]> = [
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
  [{ wearableAllowlist: 'com.example.band' as unknown as string[] }, /wearableAllowlist/],
  [{ gapRecoveryMaxSteps: Number.NaN }, /gapRecoveryMaxSteps/],
  [{ remoteSyncUrl: 'http://api.example.com/steps' }, /https/],
  [{ remoteSyncUrl: 'ftp://api.example.com/steps' }, /https/],
  [{ privacyPolicyUrl: 'intent://evil' }, /privacyPolicyUrl/],
  [{ fraudDetection: { enabled: true, mode: 'remove' as 'flag' } }, /mode/],
  [{ fraudDetection: { enabled: true, maxCadenceSpm: 50 } }, /maxCadenceSpm/],
  [{ fraudDetection: { enabled: true, maxCadenceSpm: 150.5 } }, /maxCadenceSpm/],
  [
    { fraudDetection: { enabled: true, steadyCadenceMinutes: 2 } },
    /steadyCadenceMinutes/,
  ],
  [
    { fraudDetection: { enabled: true, maxContinuousMinutes: 10 } },
    /maxContinuousMinutes/,
  ],
  [{ fraudDetection: { enabled: true, maxDailySteps: -5 } }, /maxDailySteps/],
  [{ remoteSyncAuth: 'token' as 'headers' }, /remoteSyncAuth/],
  [{ weeklyGoal: -1 }, /weeklyGoal/],
  [{ eventThrottleMs: -5 }, /eventThrottleMs/],
  [{ strideLength: 5 }, /strideLength/],
  [{ stepSource: 'watch' as 'auto' }, /stepSource/],
  [{ gapRecovery: 'keep' as 'split' }, /gapRecovery/],
  [{ wearableTrust: 'always' as 'catalog' }, /wearableTrust/],
  [{ remoteSyncPayload: 'all' as 'full' }, /remoteSyncPayload/],
  [{ sex: 'other' as 'unspecified' }, /sex/],
  [{ dailyGoal: Number.NaN }, /dailyGoal/],
  [{ notificationLockScreen: 'hidden' as 'private' }, /notificationLockScreen/],
  [{ notificationDistanceUnit: 'miles' as 'mi' }, /notificationDistanceUnit/],
  [{ healthConnectReadTypes: [] }, /healthConnectReadTypes/],
  [
    { healthConnectReadTypes: ['steps', 'heartRate' as 'steps'] },
    /healthConnectReadTypes/,
  ],
  [{ healthConnectReadTypes: ['distance'] }, /include 'steps'/],
  [
    { healthConnectReadTypes: 'steps' as unknown as Array<'steps'> },
    /healthConnectReadTypes/,
  ],
];

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

  it.each(INVALID_CONFIGS)(
    'rejects %j with E_INVALID_CONFIG',
    async (config, message) => {
      await expect(StepTracker.initialize(config)).rejects.toMatchObject({
        code: 'E_INVALID_CONFIG',
        message: expect.stringMatching(message),
      });
      expect(native.calls).toHaveLength(0);
    }
  );

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

  it('passes fraudDetection through as one object, zero meaning off', async () => {
    await StepTracker.initialize({
      fraudDetection: {
        enabled: true,
        mode: 'exclude',
        maxDailySteps: 0,
        steadyCadenceMinutes: 45,
      },
    });
    expect(native.calledWith('initialize')[0]![0]).toEqual({
      fraudDetection: {
        enabled: true,
        mode: 'exclude',
        maxDailySteps: 0,
        steadyCadenceMinutes: 45,
      },
    });
    expect(DEFAULT_CONFIG.fraudDetection).toMatchObject({ enabled: false, mode: 'flag' });
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

  it('re-derives weekly and monthly goals from a new daily goal, as initialize does', async () => {
    await StepTracker.updateConfig({ dailyGoal: 12000 });
    expect(native.calledWith('updateConfig')[0]![0]).toEqual({
      dailyGoal: 12000,
      weeklyGoal: 84000,
      monthlyGoal: 360000,
    });
  });

  it('keeps an explicit weekly or monthly goal', async () => {
    await StepTracker.updateConfig({ dailyGoal: 12000, weeklyGoal: 50000 });
    expect(native.calledWith('updateConfig')[0]![0]).toMatchObject({
      weeklyGoal: 50000,
      monthlyGoal: 360000,
    });
  });

  it('leaves goals alone when the daily goal is not in the patch', async () => {
    await StepTracker.updateConfig({ notificationTitle: '{steps}' });
    expect(native.calledWith('updateConfig')[0]![0]).toEqual({
      notificationTitle: '{steps}',
    });
  });

  it('patches one key of fraudDetection or motionSampling without restating enabled', async () => {
    // Native patches these objects key by key, so the types must not demand
    // `enabled` - this compiling is half the test.
    await StepTracker.updateConfig({ fraudDetection: { mode: 'exclude' } });
    await StepTracker.updateConfig({ motionSampling: { intervalMinutes: 2 } });
    expect(native.calledWith('updateConfig')).toEqual([
      [{ fraudDetection: { mode: 'exclude' } }],
      [{ motionSampling: { intervalMinutes: 2 } }],
    ]);
  });

  it.each(INVALID_CONFIGS)('rejects %j like initialize does', async (config, message) => {
    await expect(StepTracker.updateConfig(config)).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
      message: expect.stringMatching(message),
    });
    expect(native.calledWith('updateConfig')).toHaveLength(0);
  });

  it('allows an http endpoint when an earlier call opted in', async () => {
    native.when('getConfig', { remoteSyncAllowHttp: true });
    await StepTracker.updateConfig({ remoteSyncUrl: 'http://10.0.2.2:3000/steps' });
    expect(native.calledWith('updateConfig')).toHaveLength(1);

    native.when('getConfig', { remoteSyncAllowHttp: false });
    await expect(
      StepTracker.updateConfig({ remoteSyncUrl: 'http://10.0.2.2:3000/steps' })
    ).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
      message: expect.stringMatching(/https/),
    });
    expect(native.calledWith('updateConfig')).toHaveLength(1);
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
    expect(native.calledWith('getVerificationSnapshot')).toEqual([['2026-09-14', {}]]);
  });

  it('getVerificationSnapshot() passes signing options through and bounds the nonce', async () => {
    await StepTracker.getVerificationSnapshot('2026-09-14', { sign: true, nonce: 'n-1' });
    expect(native.calledWith('getVerificationSnapshot')).toEqual([
      ['2026-09-14', { sign: true, nonce: 'n-1' }],
    ]);
    await expect(
      StepTracker.getVerificationSnapshot('2026-09-14', { nonce: 'x'.repeat(513) })
    ).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
      message: expect.stringMatching(/nonce/),
    });
  });

  it('getVerificationSnapshot() validates include and record types before native', async () => {
    for (const bad of [
      { include: ['minutes', 'steps'] },
      { include: 'minutes' },
      { healthConnectRecordTypes: [] },
      { healthConnectRecordTypes: ['heartRate'] },
    ]) {
      await expect(
        StepTracker.getVerificationSnapshot('2026-09-14', bad as never)
      ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG' });
    }
    expect(native.calls).toHaveLength(0);
  });

  it('getVerificationSnapshot() sends only the options that are set', async () => {
    await StepTracker.getVerificationSnapshot('2026-09-14', {
      sign: true,
      include: ['minutes', 'motionWindows', 'healthConnectRecords'],
      healthConnectRecordTypes: ['steps', 'distance'],
    });
    await StepTracker.getVerificationSnapshot('2026-09-14', {
      sign: undefined,
      nonce: undefined,
    });
    expect(native.calledWith('getVerificationSnapshot')).toEqual([
      [
        '2026-09-14',
        {
          sign: true,
          include: ['minutes', 'motionWindows', 'healthConnectRecords'],
          healthConnectRecordTypes: ['steps', 'distance'],
        },
      ],
      ['2026-09-14', {}],
    ]);
  });

  it('getSignedSnapshot() always signs, asks for the signature alone, and validates', async () => {
    const block = {
      keyId: 'k',
      signedPayload: '{"date":"2026-09-14"}',
      payloadSha256: 'ab',
    };
    native.when('getVerificationSnapshot', block);
    await expect(
      StepTracker.getSignedSnapshot('2026-09-14', { nonce: 'n', include: ['minutes'] })
    ).resolves.toEqual(block);
    // A stray sign: false cannot turn signing off.
    await StepTracker.getSignedSnapshot('2026-09-14', { sign: false } as never);
    expect(native.calledWith('getVerificationSnapshot')).toEqual([
      ['2026-09-14', { sign: true, nonce: 'n', include: ['minutes'], signedOnly: true }],
      ['2026-09-14', { sign: true, signedOnly: true }],
    ]);
    await expect(StepTracker.getSignedSnapshot('2026-9-14')).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
    });
    await expect(
      StepTracker.getSignedSnapshot('2026-09-14', { include: ['sources' as 'minutes'] })
    ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG' });
    expect(native.calledWith('getVerificationSnapshot')).toHaveLength(2);
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

  it('passes healthConnectReadTypes through, all three by default', async () => {
    await StepTracker.initialize({ healthConnectReadTypes: ['steps'] });
    await StepTracker.updateConfig({
      healthConnectReadTypes: ['steps', 'totalCalories'],
    });
    expect(native.calledWith('initialize')[0]![0]).toEqual({
      healthConnectReadTypes: ['steps'],
    });
    expect(native.calledWith('updateConfig')[0]![0]).toEqual({
      healthConnectReadTypes: ['steps', 'totalCalories'],
    });
    expect(DEFAULT_CONFIG.healthConnectReadTypes).toEqual([
      'steps',
      'distance',
      'totalCalories',
    ]);
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

  it('getIntegrityReport() validates the date before touching native', async () => {
    await expect(StepTracker.getIntegrityReport('yesterday')).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
    });
    expect(native.calls).toHaveLength(0);
    native.when('getIntegrityReport', {
      date: '2026-09-14',
      suspectSteps: 300,
      flags: [],
    });
    await expect(StepTracker.getIntegrityReport('2026-09-14')).resolves.toMatchObject({
      suspectSteps: 300,
    });
  });

  it('getIntegrityEvents() and getStepMinutes() validate the range and unwrap the list', async () => {
    await expect(
      StepTracker.getIntegrityEvents('2026-09-10', '2026-09-09')
    ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG' });
    await expect(
      StepTracker.getStepMinutes('2026-9-9', '2026-09-09')
    ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG' });
    expect(native.calls).toHaveLength(0);

    native.when('getIntegrityEvents', {
      events: [{ at: 1, type: 'reboot', detail: {} }],
    });
    await expect(
      StepTracker.getIntegrityEvents('2026-09-09', '2026-09-10')
    ).resolves.toEqual([{ at: 1, type: 'reboot', detail: {} }]);

    native.when('getStepMinutes', { minutes: [{ minuteStart: 60000, steps: 110 }] });
    await expect(StepTracker.getStepMinutes('2026-09-09', '2026-09-09')).resolves.toEqual(
      [{ minuteStart: 60000, steps: 110 }]
    );
  });

  it('attestDevice() bounds the challenge in UTF-8 bytes, not characters', async () => {
    for (const bad of ['', 'x'.repeat(129), 'é'.repeat(65)]) {
      await expect(StepTracker.attestDevice(bad)).rejects.toMatchObject({
        code: 'E_INVALID_CONFIG',
        message: expect.stringMatching(/challenge/),
      });
    }
    expect(native.calls).toHaveLength(0);
    native.when('attestDevice', { keyId: 'ab', attested: true });
    await expect(StepTracker.attestDevice('é'.repeat(64))).resolves.toMatchObject({
      attested: true,
    });
    expect(native.calledWith('attestDevice')).toEqual([['é'.repeat(64)]]);
  });

  it('requestIntegrityToken() validates the hash and project number before native', async () => {
    for (const bad of [
      { requestHash: '', cloudProjectNumber: 1 },
      { requestHash: 'x'.repeat(501), cloudProjectNumber: 1 },
      { requestHash: 'abc', cloudProjectNumber: 0 },
      { requestHash: 'abc', cloudProjectNumber: 1.5 },
    ]) {
      await expect(StepTracker.requestIntegrityToken(bad)).rejects.toMatchObject({
        code: 'E_INVALID_CONFIG',
      });
    }
    expect(native.calls).toHaveLength(0);
    native.when('requestIntegrityToken', { token: 't', requestHash: 'abc' });
    await expect(
      StepTracker.requestIntegrityToken({
        requestHash: 'abc',
        cloudProjectNumber: 123456789012,
      })
    ).resolves.toEqual({ token: 't', requestHash: 'abc' });
    expect(native.calledWith('requestIntegrityToken')).toEqual([
      [{ requestHash: 'abc', cloudProjectNumber: 123456789012 }],
    ]);
  });

  it('prepareIntegrity() validates the project number and passes it through', async () => {
    for (const bad of [0, -3, 1.5, Number.NaN, '123' as unknown as number]) {
      await expect(StepTracker.prepareIntegrity(bad)).rejects.toMatchObject({
        code: 'E_INVALID_CONFIG',
      });
    }
    expect(native.calls).toHaveLength(0);
    native.when('prepareIntegrity', true);
    await expect(StepTracker.prepareIntegrity(123456789012)).resolves.toBeUndefined();
    expect(native.calledWith('prepareIntegrity')).toEqual([[123456789012]]);
  });

  it("carries Play's error code and retryability in details", async () => {
    native.when(
      'prepareIntegrity',
      Object.assign(
        new Error('Play Integrity failed (error -3 NETWORK_ERROR): offline'),
        {
          code: 'E_INTEGRITY_FAILED',
          userInfo: { playErrorCode: -3, playError: 'NETWORK_ERROR', retryable: true },
        }
      )
    );
    const error = await StepTracker.prepareIntegrity(42).catch((e) => e);
    expect(error).toBeInstanceOf(StepTrackerError);
    expect(error.code).toBe('E_INTEGRITY_FAILED');
    expect(error.details).toEqual({
      playErrorCode: -3,
      playError: 'NETWORK_ERROR',
      retryable: true,
    });
  });

  it('the attestation key getters pass through', async () => {
    native.when('hasAttestationKey', true);
    await expect(StepTracker.hasAttestationKey()).resolves.toBe(true);
    native.when('getAttestationKeyInfo', null);
    await expect(StepTracker.getAttestationKeyInfo()).resolves.toBeNull();
  });

  it('raw Health Connect reads validate their inputs', async () => {
    await expect(
      StepTracker.getHealthConnectRecords('2026-09-01', '2026-09-02')
    ).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
      message: expect.stringMatching(/ISO/),
    });
    await expect(
      StepTracker.getHealthConnectRecords('2026-09-02T00:00:00Z', '2026-09-01T00:00:00Z')
    ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG' });
    await expect(StepTracker.getHealthConnectChanges('')).rejects.toMatchObject({
      code: 'E_INVALID_CONFIG',
    });
    expect(native.calls).toHaveLength(0);

    native.when('getHealthConnectRecords', { records: [], truncated: false });
    await expect(
      StepTracker.getHealthConnectRecords('2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z')
    ).resolves.toEqual({ records: [], truncated: false });
    expect(native.calledWith('getHealthConnectRecords')).toEqual([
      [
        '2026-09-01T00:00:00.000Z',
        '2026-09-02T00:00:00.000Z',
        { recordTypes: ['steps'] },
      ],
    ]);
    native.when('getHealthConnectChanges', { tokenExpired: true, nextToken: null });
    await expect(StepTracker.getHealthConnectChanges('tok')).resolves.toMatchObject({
      tokenExpired: true,
    });
  });

  it('raw Health Connect reads send UTC, the only form Android 8-13 parses', async () => {
    native.when('getHealthConnectRecords', { records: [], truncated: false });
    native.when('readHealthConnectSteps', { totalSteps: 0, records: [] });
    await StepTracker.getHealthConnectRecords(
      '2026-09-01T00:00:00+05:30',
      '2026-09-01T12:30:00.250-04:00'
    );
    await StepTracker.readHealthConnectSteps(
      '2026-09-01T00:00:00+05:30',
      '2026-09-02T00:00:00+05:30'
    );
    expect(native.calledWith('getHealthConnectRecords')).toEqual([
      [
        '2026-08-31T18:30:00.000Z',
        '2026-09-01T16:30:00.250Z',
        { recordTypes: ['steps'] },
      ],
    ]);
    expect(native.calledWith('readHealthConnectSteps')).toEqual([
      ['2026-08-31T18:30:00.000Z', '2026-09-01T18:30:00.000Z'],
    ]);
  });

  it('an instant with no zone, or a date alone, is refused before native', async () => {
    const bad: Array<[string, string]> = [
      ['2026-09-01T00:00:00', '2026-09-02T00:00:00Z'],
      ['2026-09-01T00:00:00Z', '2026-09-02T00:00:00'],
      ['2026-09-01', '2026-09-02'],
      ['Tue Sep 01 2026 00:00:00 GMT+0530', '2026-09-02T00:00:00Z'],
    ];
    for (const [start, end] of bad) {
      await expect(StepTracker.getHealthConnectRecords(start, end)).rejects.toMatchObject(
        {
          code: 'E_INVALID_CONFIG',
          message: expect.stringMatching(/with a zone/),
        }
      );
      await expect(StepTracker.readHealthConnectSteps(start, end)).rejects.toMatchObject({
        code: 'E_INVALID_CONFIG',
      });
    }
    await expect(
      StepTracker.readHealthConnectSteps('2026-09-02T00:00:00Z', '2026-09-01T00:00:00Z')
    ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG', message: /before/ });
    expect(native.calls).toHaveLength(0);
  });

  it('raw records and change tracking take record types, steps by default', async () => {
    for (const bad of [[], ['heartRate'], 'distance']) {
      await expect(
        StepTracker.getHealthConnectRecords(
          '2026-09-01T00:00:00Z',
          '2026-09-02T00:00:00Z',
          {
            recordTypes: bad as never,
          }
        )
      ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG', message: /recordTypes/ });
      await expect(
        StepTracker.getHealthConnectChangesToken({ recordTypes: bad as never })
      ).rejects.toMatchObject({ code: 'E_INVALID_CONFIG' });
    }
    expect(native.calls).toHaveLength(0);

    const distance = {
      recordType: 'distance',
      id: 'd1',
      startTime: 1,
      distanceMeters: 812.5,
    };
    native.when('getHealthConnectRecords', { records: [distance], truncated: false });
    const list = await StepTracker.getHealthConnectRecords(
      '2026-09-01T00:00:00Z',
      '2026-09-02T00:00:00Z',
      { recordTypes: ['steps', 'distance'] }
    );
    const first = list.records[0]!;
    // The union narrows on recordType.
    expect(first.recordType === 'distance' ? first.distanceMeters : first.count).toBe(
      812.5
    );

    native.when('getHealthConnectChangesToken', 'tok');
    await expect(StepTracker.getHealthConnectChangesToken()).resolves.toBe('tok');
    await StepTracker.getHealthConnectChangesToken({
      recordTypes: ['steps', 'distance'],
    });
    expect(native.calledWith('getHealthConnectRecords')).toEqual([
      [
        '2026-09-01T00:00:00.000Z',
        '2026-09-02T00:00:00.000Z',
        { recordTypes: ['steps', 'distance'] },
      ],
    ]);
    expect(native.calledWith('getHealthConnectChangesToken')).toEqual([
      [{ recordTypes: ['steps'] }],
      [{ recordTypes: ['steps', 'distance'] }],
    ]);
  });

  it('syncNow() unwraps its results, the queued upload flagged rather than erroring', async () => {
    const results = [
      {
        target: 'health_connect',
        syncedRecords: 2,
        failedRecords: 0,
        skippedRecords: 0,
        success: true,
        error: null,
        retryable: false,
      },
      {
        target: 'remote',
        syncedRecords: 0,
        failedRecords: 0,
        success: true,
        queued: true,
      },
    ];
    native.when('syncNow', { results });
    const events = await StepTracker.syncNow();
    expect(events).toEqual(results);
    expect(events.filter((e) => e.queued).map((e) => e.error)).toEqual([undefined]);
  });

  it('getSyncStatus() passes the stored outcome through', async () => {
    const status = {
      remote: {
        configured: true,
        pendingRecords: 3,
        lastAttemptAt: 10,
        lastSuccessAt: 0,
        consecutiveFailures: 2,
        lastFailure: {
          at: 10,
          reason: 'unauthorized',
          status: 401,
          message: 'Upload refused: HTTP 401',
          retryable: false,
        },
        authFailed: true,
      },
    };
    native.when('getSyncStatus', status);
    await expect(StepTracker.getSyncStatus()).resolves.toEqual(status);
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

  it('stops asking once steps are granted, whatever happened to distance', async () => {
    // The user allowed steps and unticked distance: granted is false, but
    // Health Connect is on. No sheet, and never settings.
    native.when('getHealthConnectStatus', {
      ...baseStatus,
      granted: false,
      stepsGranted: true,
      canReadSteps: true,
      shouldOpenSettings: false,
      missingPermissions: ['android.permission.health.READ_DISTANCE'],
    });
    const status = await StepTracker.enableHealthConnect();
    expect(status.stepsGranted).toBe(true);
    expect(native.calledWith('requestHealthConnectPermissions')).toHaveLength(0);
    expect(native.calledWith('openHealthConnectSettings')).toHaveLength(0);
  });

  it('still asks while steps are missing', async () => {
    native.when('getHealthConnectStatus', { ...baseStatus, stepsGranted: false });
    native.when('requestHealthConnectPermissions', { ...baseStatus, stepsGranted: true });
    await StepTracker.enableHealthConnect();
    expect(native.calledWith('requestHealthConnectPermissions')).toHaveLength(1);
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

  it('removeAllListeners() with no argument clears every event', () => {
    StepTracker.addListener('goalReached', () => {});
    StepTracker.addListener('dayChanged', () => {});
    StepTracker.addListener('historyBackfilled', () => {});
    StepTracker.addListener('motionWindow', () => {});
    StepTracker.addListener('suspiciousActivity', () => {});
    StepTracker.removeAllListeners();
    expect(__listenerCount('StepTrackerPro:goalReached')).toBe(0);
    expect(__listenerCount('StepTrackerPro:dayChanged')).toBe(0);
    expect(__listenerCount('StepTrackerPro:historyBackfilled')).toBe(0);
    expect(__listenerCount('StepTrackerPro:motionWindow')).toBe(0);
    expect(__listenerCount('StepTrackerPro:suspiciousActivity')).toBe(0);
  });

  it('removeListener() with no argument still works, but warns once', () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    StepTracker.addListener('stepsChanged', () => {});
    StepTracker.removeListener();
    StepTracker.removeListener();
    expect(__listenerCount('StepTrackerPro:stepsChanged')).toBe(0);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0]![0]).toMatch(/removeAllListeners/);
    warn.mockRestore();
  });

  it('removeAllListeners(event) removes only that event', () => {
    StepTracker.addListener('stepsChanged', () => {});
    StepTracker.addListener('dayChanged', () => {});
    StepTracker.removeAllListeners('stepsChanged');
    expect(__listenerCount('StepTrackerPro:stepsChanged')).toBe(0);
    expect(__listenerCount('StepTrackerPro:dayChanged')).toBe(1);
    StepTracker.removeAllListeners();
    expect(__listenerCount('StepTrackerPro:dayChanged')).toBe(0);
  });

  it('subscribes through the codegen-typed emitter on the new architecture', () => {
    const handlers: Array<(value: unknown) => void> = [];
    let removed = 0;
    setNativeModule({
      onStepsChanged: (handler: (value: unknown) => void) => {
        handlers.push(handler);
        return { remove: () => removed++ };
      },
    });
    const seen: unknown[] = [];
    const sub = StepTracker.addListener('stepsChanged', (payload) => seen.push(payload));
    // Nothing went through the legacy emitter.
    expect(__listenerCount('StepTrackerPro:stepsChanged')).toBe(0);
    handlers[0]!({ steps: 42 });
    expect(seen).toEqual([{ steps: 42 }]);
    StepTracker.removeAllListeners('stepsChanged');
    expect(removed).toBe(1);
    // Removing again is a no-op, not a second native remove.
    sub.remove();
    expect(removed).toBe(1);
  });

  it('delivers syncAuthFailed', () => {
    const seen: unknown[] = [];
    StepTracker.addListener('syncAuthFailed', (event) => seen.push(event));
    const payload = {
      target: 'remote',
      status: 401,
      reason: 'unauthorized',
      auth: 'headers',
    };
    __emit('StepTrackerPro:syncAuthFailed', payload);
    expect(seen).toEqual([payload]);
  });

  it('delivers suspiciousActivity with its flags', () => {
    const seen: unknown[] = [];
    StepTracker.addListener('suspiciousActivity', (event) => seen.push(event));
    const payload = {
      date: '2026-09-14',
      flags: [
        {
          type: 'charging',
          severity: 'strong',
          from: 0,
          to: 60000,
          steps: 90,
          evidence: {},
        },
      ],
      deviceSteps: 5000,
      suspectSteps: 90,
      mode: 'flag',
    };
    __emit('StepTrackerPro:suspiciousActivity', payload);
    expect(seen).toEqual([payload]);
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

  it('leaves details undefined when the native side attached no userInfo', async () => {
    native.when(
      'startTracking',
      Object.assign(new Error('nope'), { code: 'E_UNKNOWN', userInfo: null })
    );
    const error = await StepTracker.startTracking().catch((e) => e);
    expect(error.details).toBeUndefined();
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

describe('useStepTracker()', () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

  function Probe(props: { dailyGoal: number }) {
    useStepTracker({ dailyGoal: props.dailyGoal });
    return null;
  }

  it('pushes a config change through updateConfig, and only a real one', async () => {
    const snapshot = { date: '2026-09-14', steps: 1, state: 'idle' };
    native.when('initialize', snapshot);
    native.when('getTodaySteps', snapshot);
    native.when('getTrackingState', { state: 'idle' });
    native.when('updateConfig', {});

    let renderer: ReactTestRenderer | undefined;
    await TestRenderer.act(async () => {
      renderer = TestRenderer.create(React.createElement(Probe, { dailyGoal: 8000 }));
    });
    expect(native.calledWith('initialize')).toHaveLength(1);

    // A new render with the same values is not a change.
    await TestRenderer.act(async () => {
      renderer!.update(React.createElement(Probe, { dailyGoal: 8000 }));
    });
    expect(native.calledWith('updateConfig')).toHaveLength(0);

    await TestRenderer.act(async () => {
      renderer!.update(React.createElement(Probe, { dailyGoal: 9000 }));
    });
    // A new daily goal carries its weekly and monthly goals with it; before
    // 2.0.1 they stayed at the values derived from the first goal.
    expect(native.calledWith('updateConfig')).toEqual([
      [{ dailyGoal: 9000, weeklyGoal: 63000, monthlyGoal: 270000 }],
    ]);
    // Initialised once, however often config changed.
    expect(native.calledWith('initialize')).toHaveLength(1);
    await TestRenderer.act(async () => renderer!.unmount());
  });

  it('clears error once a later call succeeds', async () => {
    const snapshot = { date: '2026-09-14', steps: 1, state: 'idle' };
    native.when('initialize', snapshot);
    native.when(
      'startTracking',
      Object.assign(new Error('ACTIVITY_RECOGNITION must be granted'), {
        code: 'E_PERMISSION_DENIED',
      })
    );
    let result: UseStepTrackerResult | undefined;
    function Owner() {
      result = useStepTracker({});
      return null;
    }
    let renderer: ReactTestRenderer | undefined;
    await TestRenderer.act(async () => {
      renderer = TestRenderer.create(React.createElement(Owner));
    });

    await TestRenderer.act(async () => {
      await result!.start();
    });
    expect(result!.error).toMatchObject({ code: 'E_PERMISSION_DENIED' });

    // Granted, and started on the retry: the old failure is not still shown.
    native.when('startTracking', { ...snapshot, state: 'running' });
    await TestRenderer.act(async () => {
      await result!.start();
    });
    expect(result!.error).toBeNull();
    expect(result!.state).toBe('running');
    await TestRenderer.act(async () => renderer!.unmount());
  });
});

describe('useHealthConnect()', () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

  async function render(): Promise<UseHealthConnectResult> {
    let result: UseHealthConnectResult | undefined;
    function Probe() {
      result = useHealthConnect({ refreshOnForeground: false });
      return null;
    }
    let renderer: ReactTestRenderer | undefined;
    await TestRenderer.act(async () => {
      renderer = TestRenderer.create(React.createElement(Probe));
    });
    await TestRenderer.act(async () => renderer!.unmount());
    return result!;
  }

  const watch = { packageName: 'com.fitbit.FitbitMobile', steps: 8000 };

  beforeEach(() => {
    native.when('getInstalledCompanionApps', { apps: [] });
    native.when('getCurrentStepSource', { steps: 8000 });
    native.when('getStepSources', { sources: [watch], hasWearable: true });
  });

  it('loads sources when steps are granted though distance was refused', async () => {
    // The sheet lets the user untick distance alone: canRead is false, but
    // steps are enough to read a watch.
    native.when('getHealthConnectStatus', {
      ...baseStatus,
      canRead: false,
      canReadSteps: true,
      grantedReadTypes: ['steps'],
    });
    const result = await render();
    expect(native.calledWith('getStepSources')).toHaveLength(1);
    expect(result.sources).toEqual([watch]);
    expect(result.hasWearable).toBe(true);
  });

  it('skips the sources read without the steps grant', async () => {
    native.when('getHealthConnectStatus', {
      ...baseStatus,
      canRead: false,
      canReadSteps: false,
      grantedReadTypes: ['distance'],
    });
    const result = await render();
    expect(native.calledWith('getStepSources')).toHaveLength(0);
    expect(result.sources).toEqual([]);
  });

  it('keeps the last sources when a read times out', async () => {
    native.when('getHealthConnectStatus', { ...baseStatus, canReadSteps: true });
    let result: UseHealthConnectResult | undefined;
    function Probe() {
      result = useHealthConnect({ refreshOnForeground: false });
      return null;
    }
    let renderer: ReactTestRenderer | undefined;
    await TestRenderer.act(async () => {
      renderer = TestRenderer.create(React.createElement(Probe));
    });
    expect(result!.sources).toEqual([watch]);

    // The next read runs out of time: not "the watch is gone".
    native.when('getStepSources', {
      sources: [],
      hasWearable: false,
      healthConnect: 'timed_out',
    });
    await TestRenderer.act(async () => {
      await result!.refresh();
    });
    expect(native.calledWith('getStepSources')).toHaveLength(2);
    expect(result!.sources).toEqual([watch]);
    expect(result!.hasWearable).toBe(true);
    await TestRenderer.act(async () => renderer!.unmount());
  });

  it('falls back to canRead against a native side older than 2.1.1', async () => {
    native.when('getHealthConnectStatus', { ...baseStatus, canRead: true });
    await render();
    expect(native.calledWith('getStepSources')).toHaveLength(1);
  });
});

describe('useStepStats()', () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

  afterEach(() => jest.useRealTimers());

  async function mount(offset?: number) {
    function Probe() {
      useStepStats('week', { offset });
      return null;
    }
    let renderer: ReactTestRenderer | undefined;
    await TestRenderer.act(async () => {
      renderer = TestRenderer.create(React.createElement(Probe));
    });
    return renderer!;
  }

  beforeEach(() => native.when('getWeeklyStats', { totalSteps: 1, days: [] }));

  it('refreshes when the app comes back to the foreground', async () => {
    const renderer = await mount();
    expect(native.calledWith('getWeeklyStats')).toHaveLength(1);
    await TestRenderer.act(async () => __setAppState('active'));
    expect(native.calledWith('getWeeklyStats')).toHaveLength(2);
    await TestRenderer.act(async () => renderer.unmount());
  });

  it('reloads when a past day grows after the fact, in any window', async () => {
    // Last week: steps do not move it, but a late batch or a recovery can.
    const renderer = await mount(1);
    expect(native.calledWith('getWeeklyStats')).toHaveLength(1);
    await TestRenderer.act(async () =>
      __emit('StepTrackerPro:historyBackfilled', {
        date: '2026-09-13',
        addedSteps: 312,
        totalSteps: 9312,
        reason: 'late',
      })
    );
    expect(native.calledWith('getWeeklyStats')).toHaveLength(2);
    await TestRenderer.act(async () => renderer.unmount());
  });

  it('refreshes this week at most every 30 seconds while steps come in', async () => {
    jest.useFakeTimers();
    const renderer = await mount();
    await TestRenderer.act(async () => {
      for (let i = 0; i < 20; i++) __emit('StepTrackerPro:stepsChanged', { steps: i });
      await jest.advanceTimersByTimeAsync(0);
    });
    // The first burst refreshes once, at once.
    expect(native.calledWith('getWeeklyStats')).toHaveLength(2);
    await TestRenderer.act(async () => {
      __emit('StepTrackerPro:stepsChanged', { steps: 21 });
      await jest.advanceTimersByTimeAsync(STATS_LIVE_REFRESH_MS - 1);
    });
    expect(native.calledWith('getWeeklyStats')).toHaveLength(2);
    await TestRenderer.act(async () => {
      await jest.advanceTimersByTimeAsync(1);
    });
    expect(native.calledWith('getWeeklyStats')).toHaveLength(3);
    await TestRenderer.act(async () => renderer.unmount());
  });

  it('keeps refreshing live after the clock is set back', async () => {
    jest.useFakeTimers();
    const renderer = await mount();
    await TestRenderer.act(async () => {
      __emit('StepTrackerPro:stepsChanged', { steps: 1 });
      await jest.advanceTimersByTimeAsync(0);
    });
    expect(native.calledWith('getWeeklyStats')).toHaveLength(2);
    // Three hours back, by hand, right after that refresh.
    jest.setSystemTime(Date.now() - 3 * 60 * 60 * 1000);
    await TestRenderer.act(async () => {
      __emit('StepTrackerPro:stepsChanged', { steps: 2 });
      await jest.advanceTimersByTimeAsync(STATS_LIVE_REFRESH_MS);
    });
    // Within the usual 30 s, not three hours.
    expect(native.calledWith('getWeeklyStats')).toHaveLength(3);
    await TestRenderer.act(async () => renderer.unmount());
  });

  it('shows only the newest answer when loads overlap', async () => {
    // Last week's answer is slow; the switch to this month is answered
    // first. The late week must not land on top of the month.
    let answerWeek: (value: unknown) => void = () => {};
    native.when(
      'getWeeklyStats',
      new Promise((resolve) => {
        answerWeek = resolve;
      })
    );
    native.when('getMonthlyStats', { totalSteps: 2 });
    let result: ReturnType<typeof useStepStats> | undefined;
    function Probe(props: { period: 'week' | 'month' }) {
      result = useStepStats(props.period);
      return null;
    }
    let renderer: ReactTestRenderer | undefined;
    await TestRenderer.act(async () => {
      renderer = TestRenderer.create(React.createElement(Probe, { period: 'week' }));
    });
    await TestRenderer.act(async () => {
      renderer!.update(React.createElement(Probe, { period: 'month' }));
    });
    expect(result!.stats).toEqual({ totalSteps: 2 });
    await TestRenderer.act(async () => {
      answerWeek({ totalSteps: 1 });
    });
    expect(result!.stats).toEqual({ totalSteps: 2 });
    expect(result!.loading).toBe(false);
    await TestRenderer.act(async () => renderer!.unmount());
  });

  it('leaves a past week alone while steps come in', async () => {
    jest.useFakeTimers();
    const renderer = await mount(-1);
    await TestRenderer.act(async () => {
      __emit('StepTrackerPro:stepsChanged', { steps: 1 });
      await jest.advanceTimersByTimeAsync(STATS_LIVE_REFRESH_MS * 2);
    });
    expect(native.calledWith('getWeeklyStats')).toHaveLength(1);
    await TestRenderer.act(async () => renderer.unmount());
  });
});
