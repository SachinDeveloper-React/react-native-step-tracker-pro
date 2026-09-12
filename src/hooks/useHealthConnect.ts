import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import StepTracker, { isSupported } from '../StepTracker';
import type {
  CompanionApp,
  CurrentStepSource,
  HealthConnectStatus,
  RequestHealthConnectOptions,
  StepSource,
} from '../types';

export interface UseHealthConnectOptions extends RequestHealthConnectOptions {
  /**
   * Re-read status when the app is foregrounded. Default true, and worth
   * keeping: the user grants permissions and installs the provider in another
   * app, so coming back is the only moment this can be noticed.
   */
  refreshOnForeground?: boolean;
  /** How far back to look for other step sources. Default 7 days. */
  sourceWindowDays?: number;
}

export interface UseHealthConnectResult {
  status: HealthConnectStatus | null;
  /** Every app publishing steps, this device included. */
  sources: StepSource[];
  /** Which source is answering for today. */
  current: CurrentStepSource | null;
  /** Wearable companion apps installed on the phone. */
  companionApps: CompanionApp[];
  /** A watch, band or ring other than this phone has data. */
  hasWearable: boolean;
  loading: boolean;
  error: Error | null;
  /**
   * Install → request → settings, whichever step applies. Safe to call from a
   * single "Connect Health Connect" button.
   */
  enable: () => Promise<HealthConnectStatus | null>;
  /** Just the permission sheet, no install or settings fallback. */
  request: () => Promise<HealthConnectStatus | null>;
  openSettings: () => Promise<void>;
  install: () => Promise<void>;
  revoke: () => Promise<void>;
  /** Pins one source as the truth, or clears the pin with `null`. */
  selectSource: (packageName: string | null) => Promise<void>;
  refresh: () => Promise<void>;
}

/**
 * Local-date key. `toISOString()` is UTC, which at 02:00 IST is still
 * yesterday - the native side keys everything on the device's local date, so
 * a UTC key here silently dropped today from the window for anyone east of
 * Greenwich in the early morning.
 */
function localDateKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function daysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return localDateKey(date);
}

function today(): string {
  return localDateKey(new Date());
}

/**
 * Everything a "Health Connect" settings screen needs: current status, the
 * apps competing to supply steps, and the actions that move the user forward.
 *
 * The status shape is what decides which button to show — `installable` means
 * offer install, `shouldOpenSettings` means the permission sheet has stopped
 * appearing and only the settings screen is left, and `granted` means done.
 * `enable()` walks that ladder for you.
 */
export function useHealthConnect(
  options: UseHealthConnectOptions = {}
): UseHealthConnectResult {
  const {
    refreshOnForeground = true,
    sourceWindowDays = 7,
    backgroundRead,
    historyRead,
  } = options;

  const [status, setStatus] = useState<HealthConnectStatus | null>(null);
  const [sources, setSources] = useState<StepSource[]>([]);
  const [current, setCurrent] = useState<CurrentStepSource | null>(null);
  const [companionApps, setCompanionApps] = useState<CompanionApp[]>([]);
  const [hasWearable, setHasWearable] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const requestOptions = useRef<RequestHealthConnectOptions>({});
  requestOptions.current = { backgroundRead, historyRead };
  const windowDays = useRef(sourceWindowDays);
  windowDays.current = sourceWindowDays;

  const refresh = useCallback(async () => {
    if (!isSupported()) {
      setLoading(false);
      return;
    }
    try {
      const next = await StepTracker.getHealthConnectStatus();
      setStatus(next);
      setCompanionApps(await StepTracker.getInstalledCompanionApps());
      // Reading sources without the grant returns an empty list, so skip the
      // round trip and keep whatever the last granted read produced.
      if (next.canRead) {
        const list = await StepTracker.getStepSources(
          daysAgo(windowDays.current),
          today()
        );
        setSources(list.sources);
        setHasWearable(list.hasWearable);
      }
      setCurrent(await StepTracker.getCurrentStepSource());
      setError(null);
    } catch (e) {
      setError(e as Error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const sub = StepTracker.addListener('healthConnectStatusChanged', () => {
      void refresh();
    });
    const sourceSub = StepTracker.addListener('stepSourceChanged', () => {
      void refresh();
    });
    return () => {
      sub.remove();
      sourceSub.remove();
    };
  }, [refresh]);

  useEffect(() => {
    if (!refreshOnForeground) return;
    const sub = AppState.addEventListener('change', (next) => {
      if (next === 'active') void refresh();
    });
    return () => sub.remove();
  }, [refresh, refreshOnForeground]);

  const guard = useCallback(async <T>(fn: () => Promise<T>): Promise<T | null> => {
    try {
      return await fn();
    } catch (e) {
      setError(e as Error);
      return null;
    }
  }, []);

  const enable = useCallback(async () => {
    const next = await guard(() =>
      StepTracker.enableHealthConnect(requestOptions.current)
    );
    if (next) setStatus(next);
    await refresh();
    return next;
  }, [guard, refresh]);

  const request = useCallback(async () => {
    const next = await guard(() =>
      StepTracker.requestHealthConnectPermissions(requestOptions.current)
    );
    if (next) setStatus(next);
    await refresh();
    return next;
  }, [guard, refresh]);

  const openSettings = useCallback(async () => {
    await guard(() => StepTracker.openHealthConnectSettings());
  }, [guard]);

  const install = useCallback(async () => {
    await guard(() => StepTracker.installHealthConnect());
  }, [guard]);

  const revoke = useCallback(async () => {
    await guard(() => StepTracker.revokeHealthConnectPermissions());
    await refresh();
  }, [guard, refresh]);

  const selectSource = useCallback(
    async (packageName: string | null) => {
      await guard(() => StepTracker.setPreferredStepSource(packageName));
      await refresh();
    },
    [guard, refresh]
  );

  return {
    status,
    sources,
    current,
    companionApps,
    hasWearable,
    loading,
    error,
    enable,
    request,
    openSettings,
    install,
    revoke,
    selectSource,
    refresh,
  };
}
