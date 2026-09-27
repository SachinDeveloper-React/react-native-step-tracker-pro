import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import StepTracker, { isSupported } from '../StepTracker';
import type {
  GoalReachedEvent,
  StepSnapshot,
  StepTrackerConfig,
  SuspiciousActivityEvent,
  TrackingState,
} from '../types';

export interface UseStepTrackerOptions extends StepTrackerConfig {
  /** Initialise and start tracking as soon as permissions allow. Default false. */
  autoStart?: boolean;
  /** Refetch the snapshot when the app returns to the foreground. Default true. */
  refreshOnForeground?: boolean;
  onGoalReached?: (event: GoalReachedEvent) => void;
  /**
   * The integrity checks found something new for a day. Only fires with
   * `fraudDetection.enabled`. The snapshot is re-read as well, because under
   * `mode: 'exclude'` the number can drop without a sensor sample to carry it.
   */
  onSuspiciousActivity?: (event: SuspiciousActivityEvent) => void;
}

export interface UseStepTrackerResult {
  snapshot: StepSnapshot | null;
  state: TrackingState;
  ready: boolean;
  error: Error | null;
  start: () => Promise<void>;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  stop: () => Promise<void>;
  refresh: () => Promise<void>;
  requestPermissions: () => Promise<boolean>;
}

/**
 * Wires the tracker into a component: initialises once, pushes later config
 * changes through `updateConfig()`, subscribes to `stepsChanged`, and
 * re-reads the snapshot when the app is foregrounded.
 *
 * Use it in one component - the one that owns config. Two mounted copies
 * with different config would each push theirs; read-only screens use
 * `StepTracker.getTodaySteps()`, `useStepStats()` or a `stepsChanged`
 * listener instead.
 */
export function useStepTracker(
  options: UseStepTrackerOptions = {}
): UseStepTrackerResult {
  const {
    autoStart = false,
    refreshOnForeground = true,
    onGoalReached,
    onSuspiciousActivity,
    ...config
  } = options;

  const [snapshot, setSnapshot] = useState<StepSnapshot | null>(null);
  const [state, setState] = useState<TrackingState>('idle');
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const configRef = useRef(config);
  configRef.current = config;
  // Config compared by value, so an object literal rebuilt on every render
  // is not a change. Functions and the hook's own options are not in it.
  const configKey = JSON.stringify(config);
  // What the native side was last given; null until initialize() succeeds.
  const appliedKey = useRef<string | null>(null);
  const goalRef = useRef(onGoalReached);
  goalRef.current = onGoalReached;
  const suspiciousRef = useRef(onSuspiciousActivity);
  suspiciousRef.current = onSuspiciousActivity;

  const refresh = useCallback(async () => {
    if (!isSupported()) return;
    try {
      setSnapshot(await StepTracker.getTodaySteps());
      setState(await StepTracker.getTrackingState());
    } catch (e) {
      setError(e as Error);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    if (!isSupported()) {
      setState('unsupported');
      setReady(true);
      return;
    }

    (async () => {
      try {
        const initial = await StepTracker.initialize(configRef.current);
        if (cancelled) return;
        appliedKey.current = JSON.stringify(configRef.current);
        setSnapshot(initial);
        setState(initial.state);
        if (autoStart) {
          const perms = await StepTracker.checkPermissions();
          if (perms.allGranted) {
            const started = await StepTracker.startTracking();
            if (!cancelled) {
              setSnapshot(started);
              setState(started.state);
            }
          }
        }
      } catch (e) {
        if (!cancelled) setError(e as Error);
      } finally {
        if (!cancelled) setReady(true);
      }
    })();

    const stepsSub = StepTracker.addListener('stepsChanged', (payload) => {
      setSnapshot(payload);
    });
    const stateSub = StepTracker.addListener('trackingStateChanged', (payload) => {
      setState(payload.state);
    });
    const goalSub = StepTracker.addListener('goalReached', (payload) => {
      goalRef.current?.(payload);
    });
    const daySub = StepTracker.addListener('dayChanged', () => {
      void refresh();
    });
    // The app answering for the user's steps flipped - a watch came into
    // range, a pin changed. The number and its attribution may have moved
    // without a sensor sample to carry them, so re-read.
    const sourceSub = StepTracker.addListener('stepSourceChanged', () => {
      void refresh();
    });
    const suspiciousSub = StepTracker.addListener('suspiciousActivity', (payload) => {
      suspiciousRef.current?.(payload);
      void refresh();
    });

    return () => {
      cancelled = true;
      stepsSub.remove();
      stateSub.remove();
      goalSub.remove();
      daySub.remove();
      sourceSub.remove();
      suspiciousSub.remove();
    };
  }, [autoStart, refresh]);

  // Config that changes after mount goes through updateConfig(). Before 2.0
  // it was read once, on mount, and every later change was silently lost.
  // The hook is meant to have one owner: the component that holds config.
  useEffect(() => {
    if (!ready || !isSupported()) return;
    if (appliedKey.current === null || appliedKey.current === configKey) return;
    appliedKey.current = configKey;
    StepTracker.updateConfig(configRef.current)
      .then(() => refresh())
      .catch((e) => setError(e as Error));
  }, [configKey, ready, refresh]);

  useEffect(() => {
    if (!refreshOnForeground) return;
    const sub = AppState.addEventListener('change', (next) => {
      if (next === 'active') void refresh();
    });
    return () => sub.remove();
  }, [refresh, refreshOnForeground]);

  // Each action is memoised separately. Building them inline in the returned
  // object handed callers a new function identity on every render, which loops
  // any `useEffect` that lists one in its dependency array.
  const run = useCallback(async (fn: () => Promise<StepSnapshot>) => {
    try {
      const result = await fn();
      setSnapshot(result);
      setState(result.state);
    } catch (e) {
      setError(e as Error);
    }
  }, []);

  const start = useCallback(() => run(() => StepTracker.startTracking()), [run]);
  const pause = useCallback(() => run(() => StepTracker.pauseTracking()), [run]);
  const resume = useCallback(() => run(() => StepTracker.resumeTracking()), [run]);
  const stop = useCallback(() => run(() => StepTracker.stopTracking()), [run]);

  const requestPermissions = useCallback(async () => {
    try {
      const result = await StepTracker.requestPermissions();
      return result.allGranted;
    } catch (e) {
      setError(e as Error);
      return false;
    }
  }, []);

  return {
    snapshot,
    state,
    ready,
    error,
    refresh,
    start,
    pause,
    resume,
    stop,
    requestPermissions,
  };
}
