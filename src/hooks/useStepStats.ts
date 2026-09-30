import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import StepTracker, { isSupported } from '../StepTracker';
import type { RangeOptions, RangeStats } from '../types';

export type StatsPeriod = 'week' | 'month' | 'year';

export interface UseStepStatsResult {
  stats: RangeStats | null;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
}

/**
 * While steps come in, a window that includes today is refreshed at most
 * this often - enough for today's bar to move while walking, without a
 * native read per step.
 */
export const STATS_LIVE_REFRESH_MS = 30_000;

/**
 * Fetches a windowed summary and keeps it current: again when the day rolls
 * over, when a past day grows after the fact, when the app comes back to the
 * foreground, and - for a window that includes today - every
 * {@link STATS_LIVE_REFRESH_MS} while steps come in.
 * Those refreshes are quiet: `loading` only covers the first load and
 * explicit `reload()` calls, so a chart does not flicker as it updates.
 */
export function useStepStats(
  period: StatsPeriod,
  options: RangeOptions = {}
): UseStepStatsResult {
  const [stats, setStats] = useState<RangeStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const { mode, offset } = options;

  // Loads overlap - a new period, midnight, a live refresh - and answers can
  // arrive out of order. Only the newest request's answer is shown, so a
  // slow one for last week can never land on top of this month.
  const latest = useRef(0);

  const load = useCallback(
    async (quiet: boolean) => {
      if (!isSupported()) {
        setLoading(false);
        return;
      }
      const request = ++latest.current;
      if (!quiet) setLoading(true);
      try {
        const opts: RangeOptions = { mode, offset };
        const next =
          period === 'week'
            ? await StepTracker.getWeeklyStats(opts)
            : period === 'month'
              ? await StepTracker.getMonthlyStats(opts)
              : await StepTracker.getYearlyStats(opts);
        if (request !== latest.current) return;
        setStats(next);
        setError(null);
      } catch (e) {
        if (request !== latest.current) return;
        setError(e as Error);
      } finally {
        if (request === latest.current) setLoading(false);
      }
    },
    [period, mode, offset]
  );

  const reload = useCallback(() => load(false), [load]);

  // Only a window that has today in it moves with each step.
  const live = mode === 'rolling' || (offset ?? 0) === 0;

  useEffect(() => {
    void load(false);
    let timer: ReturnType<typeof setTimeout> | null = null;
    let lastLive = 0;
    const onSteps = () => {
      if (!live || timer) return;
      // Trailing: the refresh after a burst of steps includes all of them.
      // Clamped both ways: a clock the user sets back after a refresh
      // would otherwise make this hours, and hold every live refresh with it.
      const wait = Math.min(
        STATS_LIVE_REFRESH_MS,
        Math.max(0, lastLive + STATS_LIVE_REFRESH_MS - Date.now())
      );
      timer = setTimeout(() => {
        timer = null;
        lastLive = Date.now();
        void load(true);
      }, wait);
    };
    const subs = [
      StepTracker.addListener('dayChanged', () => void load(false)),
      StepTracker.addListener('stepsChanged', onSteps),
      // A past day grew after the fact - recovered, or taken before midnight
      // and delivered after it. Any window may hold it, so reload quietly.
      StepTracker.addListener('historyBackfilled', () => void load(true)),
    ];
    const app = AppState.addEventListener('change', (next) => {
      if (next === 'active') void load(true);
    });
    return () => {
      subs.forEach((sub) => sub.remove());
      app.remove();
      if (timer) clearTimeout(timer);
      // Whatever is still in flight answers for a window no longer shown.
      latest.current++;
    };
  }, [load, live]);

  return { stats, loading, error, reload };
}
