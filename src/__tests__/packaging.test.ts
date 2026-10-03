// eslint-disable-next-line @typescript-eslint/no-require-imports
const manifest = require('../../plugin/manifest') as {
  permissionsFor: (options?: object) => string[];
  applyPermissions: (
    manifest: { manifest: { 'uses-permission'?: Array<{ $: Record<string, string> }> } },
    options?: object
  ) => { manifest: { 'uses-permission': Array<{ $: Record<string, string> }> } };
  minSdkFor: (current?: string) => string | null;
  applyHealthActivities: (
    manifest: { manifest: Record<string, unknown> },
    options?: object
  ) => {
    manifest: {
      $: Record<string, string>;
      application: Array<Record<string, Array<{ $: Record<string, string> }>>>;
    };
  };
  keepXml: (icon?: unknown) => string | null;
};
import * as mock from '../jestMock';
import { StepTracker } from '../StepTracker';

describe('Expo config plugin', () => {
  it('declares nothing unless asked', () => {
    expect(manifest.permissionsFor()).toEqual([]);
  });

  it('declares the Health Connect set config asks for, and the battery prompt', () => {
    expect(
      manifest.permissionsFor({
        healthConnect: { write: false, historyRead: true, activeCalories: true },
        batteryOptimizationPrompt: true,
      })
    ).toEqual([
      'android.permission.health.READ_STEPS',
      'android.permission.health.READ_DISTANCE',
      'android.permission.health.READ_TOTAL_CALORIES_BURNED',
      'android.permission.health.READ_HEALTH_DATA_HISTORY',
      'android.permission.health.READ_ACTIVE_CALORIES_BURNED',
      'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
    ]);
    // Write-only never asks for the optional read grants.
    expect(
      manifest.permissionsFor({ healthConnect: { read: false, backgroundRead: true } })
    ).toEqual([
      'android.permission.health.WRITE_STEPS',
      'android.permission.health.WRITE_DISTANCE',
      'android.permission.health.WRITE_TOTAL_CALORIES_BURNED',
    ]);
  });

  it('declares only the read types asked for, steps always', () => {
    expect(
      manifest.permissionsFor({ healthConnect: { readTypes: ['steps'], write: false } })
    ).toEqual(['android.permission.health.READ_STEPS']);
    expect(
      manifest.permissionsFor({
        healthConnect: { readTypes: ['totalCalories'], write: false },
      })
    ).toEqual([
      'android.permission.health.READ_STEPS',
      'android.permission.health.READ_TOTAL_CALORIES_BURNED',
    ]);
    expect(() =>
      manifest.permissionsFor({ healthConnect: { readTypes: ['heartRate'] } })
    ).toThrow(/heartRate/);
  });

  it('declares one read permission per vital asked for, and none with reads off', () => {
    expect(
      manifest.permissionsFor({
        healthConnect: {
          readTypes: ['steps'],
          write: false,
          vitals: ['bloodPressure', 'heartRate'],
        },
      })
    ).toEqual([
      'android.permission.health.READ_STEPS',
      'android.permission.health.READ_HEART_RATE',
      'android.permission.health.READ_BLOOD_PRESSURE',
    ]);
    expect(
      manifest.permissionsFor({ healthConnect: { read: false, vitals: ['heartRate'] } })
    ).not.toContain('android.permission.health.READ_HEART_RATE');
    expect(() =>
      manifest.permissionsFor({ healthConnect: { vitals: ['steps'] } })
    ).toThrow(/steps/);
    expect(() =>
      manifest.permissionsFor({ healthConnect: { vitals: 'heartRate' } })
    ).toThrow(/array/);
  });

  it('adds only what the manifest does not already declare', () => {
    const result = manifest.applyPermissions(
      {
        manifest: {
          'uses-permission': [
            { $: { 'android:name': 'android.permission.health.READ_STEPS' } },
          ],
        },
      },
      { healthConnect: true }
    );
    const names = result.manifest['uses-permission'].map((p) => p.$['android:name']);
    expect(
      names.filter((n) => n === 'android.permission.health.READ_STEPS')
    ).toHaveLength(1);
    expect(names).toHaveLength(6);
  });

  it('removes the Health Connect activities only when told Health Connect is off', () => {
    const fresh = () => ({ manifest: { $: {}, application: [{ $: {} }] } });
    // Left out, or on: untouched - Android 14 needs them to request anything.
    expect(manifest.applyHealthActivities(fresh(), {})).toEqual(fresh());
    expect(manifest.applyHealthActivities(fresh(), { healthConnect: true })).toEqual(
      fresh()
    );

    const off = manifest.applyHealthActivities(fresh(), { healthConnect: false });
    expect(off.manifest.$['xmlns:tools']).toBe('http://schemas.android.com/tools');
    const app = off.manifest.application[0]!;
    expect(app.activity).toEqual([
      {
        $: {
          'android:name': 'com.steptrackerpro.health.HealthPrivacyPolicyActivity',
          'tools:node': 'remove',
        },
      },
    ]);
    expect(app['activity-alias']![0]!.$['android:name']).toBe(
      'com.steptrackerpro.health.ViewPermissionUsageActivity'
    );
  });

  it('writes a keep rule for a custom notification icon', () => {
    expect(manifest.keepXml(undefined)).toBeNull();
    expect(manifest.keepXml('ic_stat_steps')).toContain(
      'tools:keep="@drawable/ic_stat_steps"'
    );
    expect(() => manifest.keepXml('ic-stat')).toThrow(/drawable name/);
  });

  it('raises minSdkVersion to 26 and leaves a higher one alone', () => {
    expect(manifest.minSdkFor('24')).toBe('26');
    expect(manifest.minSdkFor(undefined)).toBe('26');
    expect(manifest.minSdkFor('28')).toBeNull();
  });
});

describe('Jest mock (react-native-step-tracker-pro/jest)', () => {
  it('has every method the real API has', () => {
    const real = Object.keys(StepTracker).sort();
    const mocked = Object.keys(mock.StepTracker).sort();
    expect(mocked).toEqual(real);
    Object.values(mock.StepTracker).forEach((value) =>
      expect(typeof value).toBe('function')
    );
  });

  it('resolves plausible empty-day values, overridable per test', async () => {
    await expect(mock.StepTracker.getTodaySteps()).resolves.toMatchObject({
      steps: 0,
      suspectSteps: 0,
    });
    (mock.StepTracker.getTodaySteps as unknown as jest.Mock).mockResolvedValueOnce(
      mock.mockSnapshot({ steps: 5000 })
    );
    await expect(mock.StepTracker.getTodaySteps()).resolves.toMatchObject({
      steps: 5000,
    });
    await expect(
      mock.StepTracker.getVerificationSnapshot('2026-09-14')
    ).resolves.toMatchObject({
      schemaVersion: 2,
      integrity: { enabled: false },
    });
  });

  it('fires listeners through __emit and removes them', () => {
    const seen: unknown[] = [];
    const sub = mock.StepTracker.addListener('stepsChanged', (payload) =>
      seen.push(payload)
    );
    mock.__emit('stepsChanged', { steps: 7 });
    sub.remove();
    mock.__emit('stepsChanged', { steps: 8 });
    expect(seen).toEqual([{ steps: 7 }]);
  });

  it('exports the hooks and constants the package does', () => {
    expect(mock.useStepTracker().ready).toBe(true);
    expect(mock.useStepStats().loading).toBe(false);
    expect(mock.useHealthConnect().status.available).toBe(false);
    expect(mock.isSupported()).toBe(true);
    expect(mock.DEFAULT_CONFIG.dailyGoal).toBe(10000);
    expect(new mock.StepTrackerError('E_UNKNOWN', 'x').code).toBe('E_UNKNOWN');
  });
});
