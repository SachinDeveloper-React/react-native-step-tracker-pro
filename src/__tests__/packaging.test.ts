// eslint-disable-next-line @typescript-eslint/no-require-imports
const manifest = require('../../plugin/manifest') as {
  permissionsFor: (options?: object) => string[];
  applyPermissions: (
    manifest: { manifest: { 'uses-permission'?: Array<{ $: Record<string, string> }> } },
    options?: object
  ) => { manifest: { 'uses-permission': Array<{ $: Record<string, string> }> } };
  minSdkFor: (current?: string) => string | null;
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
