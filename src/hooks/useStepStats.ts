import { useCallback, useEffect, useState } from 'react';
import StepTracker, { isSupported } from '../StepTracker';
import type { RangeOptions, RangeStats } from '../types';

export type StatsPeriod = 'week' | 'month' | 'year';

export interface UseStepStatsResult {
  stats: RangeStats | null;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
}

/** Fetches a windowed summary and refreshes it when the day rolls over. */
export function useStepStats(
  period: StatsPeriod,
  options: RangeOptions = {}
): UseStepStatsResult {
  const [stats, setStats] = useState<RangeStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const { mode, offset } = options;

  const reload = useCallback(async () => {
    if (!isSupported()) {
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const opts: RangeOptions = { mode, offset };
      const next =
        period === 'week'
          ? await StepTracker.getWeeklyStats(opts)
          : period === 'month'
            ? await StepTracker.getMonthlyStats(opts)
            : await StepTracker.getYearlyStats(opts);
      setStats(next);
      setError(null);
    } catch (e) {
      setError(e as Error);
    } finally {
      setLoading(false);
    }
  }, [period, mode, offset]);

  useEffect(() => {
    void reload();
    const sub = StepTracker.addListener('dayChanged', () => void reload());
    return () => sub.remove();
  }, [reload]);

  return { stats, loading, error, reload };
}
