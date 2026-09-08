# API reference

Every method returns a promise and rejects with a `StepTrackerError` carrying a
stable `code`, so you can branch without matching on message strings.

```ts
import StepTracker, { StepTrackerError } from 'react-native-step-tracker-pro';

try {
  await StepTracker.startTracking();
} catch (error) {
  if ((error as StepTrackerError).code === 'E_PERMISSION_DENIED') { /* ... */ }
}
```

Codes: `E_UNSUPPORTED_PLATFORM`, `E_NO_SENSOR`, `E_NOT_INITIALIZED`,
`E_PERMISSION_DENIED`, `E_SERVICE_START_FAILED`, `E_HEALTH_CONNECT_UNAVAILABLE`,
`E_HEALTH_CONNECT_DENIED`, `E_DATABASE`, `E_INVALID_CONFIG`, `E_NO_ACTIVITY`,
`E_UNKNOWN`.

---

## Lifecycle

### `initialize(config?: StepTrackerConfig): Promise<StepSnapshot>`

Persists config natively and reconciles the counter against the current boot and
date. Idempotent — call it on every app launch, before anything else. Config is
stored in SharedPreferences, so the foreground service can rebuild it after a
process restart with no JS running.

### `updateConfig(config: StepTrackerConfig): Promise<StepTrackerConfig>`

Patches config at runtime. Goals and the notification update immediately.
Changing `height` or `sex` recomputes stride length unless you pass an explicit
`strideLength`.

### `startTracking(): Promise<StepSnapshot>`

Starts the foreground service. Rejects with `E_PERMISSION_DENIED` if
`ACTIVITY_RECOGNITION` is missing, `E_NO_SENSOR` if the device has neither step
sensor.

### `pauseTracking()` / `resumeTracking(): Promise<StepSnapshot>`

The service and notification stay alive; sensor deltas are folded into the
anchor instead of the total, so paused steps are discarded rather than buffered.

### `stopTracking(): Promise<StepSnapshot>`

Flushes to the database, unregisters the sensors, cancels sync work, kills the
service, and clears the auto-start-on-boot flag.

### `getTrackingState(): Promise<TrackingState>`

`'idle' | 'running' | 'paused' | 'stopped' | 'unsupported'`.

### `isTracking(): Promise<boolean>`

---

## Reading steps

### `getTodaySteps(): Promise<StepSnapshot>`

```ts
{
  date: '2026-09-06',
  steps: 7431,
  distance: 5231.4,      // metres
  calories: 223.1,       // kcal
  dailyGoal: 10000,
  goalProgress: 0.7431,  // 0..1
  goalReached: false,
  state: 'running',
  source: 'step_counter',
  timestamp: 1757145600000
}
```

Live from the counter, not the database — correct even if the last flush was
several steps ago.

### `getYesterdaySteps(): Promise<DayRecord>`
### `getStepsForDate(date: string): Promise<DayRecord>`

`date` is `yyyy-MM-dd` in the device timezone.

```ts
{ date: '2026-09-05', steps: 11204, distance: 7887.6, calories: 336.4, synced: true }
```

### `getWeeklyStats(options?)` / `getMonthlyStats(options?)` / `getYearlyStats(options?)`

```ts
type RangeOptions = {
  mode?: 'calendar' | 'rolling'; // default 'calendar'
  offset?: number;               // 0 = current, 1 = previous. Calendar mode only.
};
```

`calendar` snaps to the Monday-anchored ISO week, the calendar month, or the
calendar year. `rolling` uses the last 7 / 30 / 365 days ending today.

```ts
{
  startDate: '2026-08-31',
  endDate: '2026-09-06',
  totalSteps: 54210,
  totalDistance: 38160.4,
  totalCalories: 1630.2,
  averageSteps: 9035,       // over elapsed days, not the whole window
  activeDays: 6,
  bestDay: { date: '2026-09-03', steps: 14002, ... },
  days: [ /* zero-filled, ascending, today merged live */ ],
  goal: 70000,
  goalProgress: 0.774
}
```

`averageSteps` divides by elapsed days so a half-finished month is not diluted
by days that have not happened yet.

### `getStatsForRange(startDate, endDate): Promise<RangeStats>`
### `getHistory(startDate, endDate): Promise<DayRecord[]>`

Raw rows, zero-filled. Use this to draw charts; use `getStatsForRange` when you
want the aggregates computed for you.

---

## Writes

### `resetToday(): Promise<boolean>`

Zeroes today's counter and re-arms goal events. History is untouched. Meant for
QA builds.

### `clearHistory(): Promise<boolean>`
### `pruneHistory(retentionDays?): Promise<number>`

Deletes rows older than the window and returns how many were removed. Runs
automatically at midnight and once a day via WorkManager, using
`historyRetentionDays` from config.

---

## Permissions

### `requestPermissions(): Promise<PermissionStatus>`

Shows the runtime dialogs for `ACTIVITY_RECOGNITION` and, on Android 13+,
`POST_NOTIFICATIONS`. Needs a foreground activity; rejects with `E_NO_ACTIVITY`
otherwise.

```ts
{
  activityRecognition: true,
  postNotifications: true,
  foregroundServiceHealth: true, // always true below API 34
  allGranted: true
}
```

`allGranted` deliberately ignores `POST_NOTIFICATIONS` — denying it hides the
notification but does not stop counting.

### `checkPermissions(): Promise<PermissionStatus>`
### `getDeviceCapabilities(): Promise<DeviceCapabilities>`

```ts
{ hasStepCounter: true, hasStepDetector: true, supported: true,
  sdkInt: 35, manufacturer: 'samsung', model: 'SM-S928B' }
```

Check this before showing a step UI at all. A handful of low-end devices and
most emulators have no step hardware.

### `openAppSettings(): Promise<boolean>`

---

## Battery

### `isBatteryOptimizationEnabled(): Promise<boolean>`

True means the OS is still applying Doze restrictions to your app.

### `requestDisableBatteryOptimization(): Promise<boolean>`

Shows the system exemption dialog. Requires
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and an eligible Play use case — read
[PLAY_STORE_COMPLIANCE.md](PLAY_STORE_COMPLIANCE.md) first.

### `openBatteryOptimizationSettings(): Promise<boolean>`

Policy-safe alternative: opens the settings list and lets the user choose.

### `openManufacturerAutoStartSettings(): Promise<boolean>`

Best-effort deep link into Xiaomi / Oppo / Vivo / Huawei autostart screens,
falling back to app info. On those skins this matters more than Doze.

---

## Health Connect

### `getHealthConnectStatus(): Promise<HealthConnectStatus>`

```ts
{ available: true, requiresUpdate: false, granted: false, grantedPermissions: [] }
```

### `requestHealthConnectPermissions(): Promise<HealthConnectStatus>`

Launches the provider's permission sheet through a transparent activity. If the
user has permanently denied, the sheet does not appear — fall back to
`openHealthConnectSettings()`.

### `readHealthConnectSteps(startIso, endIso)`

ISO-8601 instants. Aggregated per day across every source Health Connect knows
about, so it includes a paired watch, not just your app.

```ts
{ totalSteps: 82310, records: [ { date, steps, distance, calories, synced } ] }
```

### `writeHealthConnectSteps(date: string): Promise<boolean>`
### `syncWithHealthConnect(): Promise<SyncEvent>`

Writes are idempotent: each record carries a stable `clientRecordId`
(`stp-steps-2026-09-06`), so re-syncing a day replaces the previous record
rather than stacking duplicates.

### `openHealthConnectSettings(): Promise<boolean>`

---

## Sync

### `syncNow(): Promise<SyncEvent[]>`

Runs Health Connect sync inline and queues the remote upload. The remote result
arrives later on the `syncCompleted` event, since WorkManager waits for a
network.

### `getPendingSyncCount(): Promise<number>`

Days written locally but not yet mirrored. Non-zero offline is normal.

---

## Events

```ts
const sub = StepTracker.addListener('stepsChanged', (data) => console.log(data.steps));
sub.remove();

StepTracker.removeListener('goalReached'); // all listeners for one event
StepTracker.removeListener();              // everything
```

| Event | Payload |
|---|---|
| `stepsChanged` | `StepSnapshot`. Throttled by `eventThrottleMs` (default 500 ms). |
| `goalReached` | `{ type: 'daily' \| 'weekly' \| 'monthly', goal, steps, date, timestamp }`. Fires at most once per period. |
| `goalProgressChanged` | `{ type, goal, steps, progress, date }`. Fires when the whole-percent bucket changes. |
| `trackingStateChanged` | `{ state, source, reason }`. `reason` is `'boot'`, `'paused'`, `'no_sensor'`, etc. |
| `dayChanged` | `{ previousDate, currentDate, previousDaySteps }`. Refetch your stats here. |
| `syncCompleted` | `{ target: 'health_connect' \| 'remote', syncedRecords, failedRecords, success, error? }` |
| `error` | `{ code, message }`. Emitted from the service, where there is no promise to reject. |

Events fire only while a React instance is alive. The service keeps counting and
writing to the database regardless — on resume, call `getTodaySteps()` rather
than replaying missed events. `useStepTracker` already does this.

---

## Config

| Key | Default | Notes |
|---|---|---|
| `height` | 170 | cm, drives stride length |
| `weight` | 70 | kg, drives calories |
| `strideLength` | derived | metres; overrides the height-based estimate |
| `sex` | `'unspecified'` | picks the stride coefficient only |
| `dailyGoal` | 10000 | |
| `weeklyGoal` | `dailyGoal × 7` | |
| `monthlyGoal` | `dailyGoal × 30` | |
| `calorieCoefficient` | 0.57 | kcal per kg per km |
| `historyRetentionDays` | 35 | set 31 for a one-month window |
| `notificationTitle` / `notificationText` | built-in | tokens: `{steps}` `{distance}` `{calories}` `{percent}` `{goal}` |
| `notificationIcon` | bundled | drawable name in your app |
| `notificationChannelName` | "Step tracking" | shown in Android settings |
| `notificationActions` | `true` | Pause/Resume/Open buttons |
| `notificationThrottleMs` | 1000 | minimum ms between notification redraws |
| `eventThrottleMs` | 500 | minimum ms between `stepsChanged` events |
| `persistEveryNSteps` | 10 | database flush cadence |
| `healthConnectEnabled` | `true` | |
| `healthConnectSyncIntervalMinutes` | 30 | clamped to WorkManager's 15-minute floor; 0 disables |
| `remoteSyncUrl` | — | optional HTTPS endpoint for unsynced days |
| `remoteSyncHeaders` | `{}` | e.g. auth headers |
| `autoStartOnBoot` | `true` | |

---

## Hooks

### `useStepTracker(options)`

```tsx
const {
  snapshot, state, ready, error,
  start, pause, resume, stop, refresh, requestPermissions,
} = useStepTracker({ dailyGoal: 10000, autoStart: true, onGoalReached: (e) => {} });
```

Initialises once, subscribes to `stepsChanged` and `trackingStateChanged`, and
re-reads the snapshot when the app returns to the foreground. Extra options:
`autoStart` (default false) and `refreshOnForeground` (default true).

### `useStepStats(period, options?)`

```tsx
const { stats, loading, error, reload } = useStepStats('week');
```

`period` is `'week' | 'month' | 'year'`. Reloads automatically on `dayChanged`.
