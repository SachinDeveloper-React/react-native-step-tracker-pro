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
`E_HEALTH_CONNECT_NOT_INSTALLED`, `E_HEALTH_CONNECT_UPDATE_REQUIRED`,
`E_HEALTH_CONNECT_DENIED`, `E_DATABASE`, `E_INVALID_CONFIG`, `E_NO_ACTIVITY`,
`E_NOT_TRACKING`, `E_UNKNOWN`.

Which calls you need depends on your [usage mode](USAGE_MODES.md).

---

## Lifecycle

### `initialize(config?: StepTrackerConfig): Promise<StepSnapshot>`

Persists config natively and reconciles the counter against the current boot and
date. Idempotent — call it on every app launch, before anything else. Config is
stored in SharedPreferences, so the foreground service can rebuild it after a
process restart with no JS running.

Only the keys you pass cross the bridge; everything else keeps the value the
native side already holds, so `initialize({ dailyGoal })` on a returning user
does not reset their height.

### `updateConfig(config: StepTrackerConfig): Promise<StepTrackerConfig>`

Patches config at runtime. Goals and the notification update immediately.
Changing `height` or `sex` switches stride back to being derived from them
unless you pass an explicit `strideLength`. `getConfig().strideLength` reads
`0` while derived; `estimateStride(height, sex)` gives the same number the
native side uses.

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

### `getTrackingHealth(): Promise<TrackingHealth>`

Whether tracking is actually working, as opposed to merely marked running.

```ts
{
  serviceAlive: false,          // a service instance exists in this process
  shouldBeRunning: true,        // the user started tracking and never stopped it
  looksDead: true,              // should be running (paused counts) but no
                                // service exists → an OEM killed it
  lastHeartbeatAt: 1757580000000,
  heartbeatAgeMs: 5400000,
  lastSensorEventAt: 1757579000000,
  lastRecoveryAt: 1757570000000,
  lastRecoveryReason: 'watchdog',   // 'sticky' | 'boot' | 'watchdog' | 'foreground' | 'initialize'
  recoveryCount: 4,             // since the user last pressed start
  batteryOptimizationEnabled: true,
  aggressiveOem: true,
  manufacturer: 'Xiaomi',
}
```

Calling it from the foreground also restarts a dead service — the foreground
is the one place a background-start restriction cannot apply. `recoveryCount`
climbing is the cue to show the OEM battery guidance; see
[OEM_BATTERY.md](OEM_BATTERY.md).

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
several steps ago. Under the `'auto'` policy with Health Connect reads
granted, `steps` is the resolved number (see
[Step sources](#step-sources-watches-and-other-apps)) and `stepSource` says
where it came from.

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
{
  hasStepCounter: true,          // the hardware counter: counts with the process dead
  hasStepDetector: true,
  hasAccelerometer: true,
  hasWakeUpAccelerometer: false, // without one the software pedometer needs a wake lock
  supported: true,               // honours accelerometerFallback
  bestSensor: 'step_counter',    // 'step_counter' | 'step_detector' | 'accelerometer' | 'none'
  sdkInt: 35, manufacturer: 'samsung', model: 'SM-S928B',
}
```

Check this before showing a step UI at all. Most emulators have no step
hardware. On a phone whose `bestSensor` is `'accelerometer'`, warn the user
that counting costs more battery and stops while the app is force-stopped.

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

Best-effort deep link into the OEM's autostart / background-launch screen —
Xiaomi, Redmi, POCO, OPPO, realme, OnePlus, vivo, iQOO, Huawei, Honor, Tecno,
Infinix, itel, Samsung, ASUS, Meizu, Nokia and others — falling back to app
info. On those skins this matters more than Doze.

### `getBackgroundRestrictionStatus(): Promise<BackgroundRestrictionStatus>`

```ts
{
  manufacturer: 'Xiaomi',
  brand: 'Redmi',
  aggressiveOem: true,                  // known to kill foreground services
  batteryOptimizationEnabled: true,     // Doze still applies
  autoStartSettingsAvailable: true,     // the deep link will land on an OEM screen
  autoStartTarget: 'com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity',
  backgroundStartNeedsExemption: true,  // Android 12+: the watchdog needs the exemption
}
```

`autoStartTarget` is what `openManufacturerAutoStartSettings()` will open.
Known components are tried first; when none resolves on a firmware nobody has
catalogued, the OEM's manager package is scanned for an exported activity
named like an autostart or battery screen, so the call still lands somewhere
useful. Log the value in support tickets.

### `requestBackgroundPermissions(options?): Promise<'none' | 'battery' | 'autostart'>`

The whole "keep tracking alive on this phone" flow behind one call. Opens the
battery exemption dialog if Doze still applies (or the settings list with
`{ directPrompt: false }`), otherwise the OEM autostart screen if one exists,
and returns which so the UI can explain it. One screen per call — call again on
the next foreground until nothing is left. Guidance text per manufacturer:
[OEM_BATTERY.md](OEM_BATTERY.md).

---

## Health Connect

### `getHealthConnectStatus(): Promise<HealthConnectStatus>`

```ts
{
  available: true,
  availability: 'available',   // | 'update_required' | 'not_installed' | 'not_supported'
  requiresUpdate: false,
  installable: false,          // installHealthConnect() would lead somewhere
  granted: false,              // everything *this config* needs
  readRequired: true,          // healthConnectReadEnabled
  writeRequired: true,         // healthConnectWriteEnabled
  canRead: false,              // enough to display a watch's steps
  canWrite: false,             // enough to mirror this device's steps
  backgroundReadGranted: false,
  historyReadGranted: false,
  grantedPermissions: [],
  missingPermissions: ['android.permission.health.READ_STEPS', ...],
  denialCount: 0,
  shouldOpenSettings: false,
}
```

`availability` is the field to branch on. `not_supported` is the only value with
nothing to offer the user; the other two failure states are both fixed by
`installHealthConnect()`.

### `enableHealthConnect(options?): Promise<HealthConnectStatus>`

The whole "turn Health Connect on" flow behind one call — install or update the
provider if it is missing, otherwise request permissions, otherwise fall back to
the settings screen once the sheet has stopped appearing. Wire it to a single
button and re-read the status when the app is next foregrounded.

```ts
await StepTracker.enableHealthConnect({ backgroundRead: true });
```

### `requestHealthConnectPermissions(options?): Promise<HealthConnectStatus>`

Launches the provider's permission sheet through a transparent activity, and
resolves with the status afterwards. A denial is a normal outcome, not a
rejection — check `granted` on the result.

```ts
type RequestHealthConnectOptions = {
  backgroundRead?: boolean;  // READ_HEALTH_DATA_IN_BACKGROUND
  historyRead?: boolean;     // READ_HEALTH_DATA_HISTORY, needed past 30 days
};
```

Both default to the matching config flag. The read and write sets follow
`healthConnectReadEnabled` / `healthConnectWriteEnabled`, so a read-only app
never puts `WRITE_*` on the sheet. Health Connect shows one sheet for the
whole set, so asking for an optional permission the app does not need risks the
ones it does.

Rejects with `E_HEALTH_CONNECT_NOT_INSTALLED`, `E_HEALTH_CONNECT_UPDATE_REQUIRED`
or `E_HEALTH_CONNECT_UNAVAILABLE` when there is no usable provider, and
`E_NO_ACTIVITY` when called with the app in the background.

**Health Connect stops showing the sheet after two refusals.** A third call
resolves with the same unchanged status and nothing visible happens, which is
indistinguishable from an instant denial. `shouldOpenSettings` marks that state —
route the user to `openHealthConnectSettings()` from there.

### `installHealthConnect(): Promise<boolean>`

Opens the Play Store listing for the Health Connect provider, with the
onboarding referrer set so setup runs straight after the install. The right
answer to `availability: 'not_installed'` or `'update_required'`.

On Android 14+ Health Connect is part of the OS, so `installable` is never true
there — an unavailable provider means `not_supported`.

### `revokeHealthConnectPermissions(): Promise<boolean>`

Drops every grant this app holds and resets the refusal counter, so the next
request starts from a clean sheet rather than being sent to settings.

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

Days a wearable already owns are skipped rather than written, and counted in
`skippedRecords`. Writing this phone's parallel count of the same walk would
leave every other app reading Health Connect with both copies of it.

### `openHealthConnectSettings(): Promise<boolean>`

---

## Step sources (watches and other apps)

A user walking with a watch has the same steps recorded twice — once by the
watch, once by this phone. **The two are never added together.** Every policy
below picks exactly one source per day; summing them would report roughly double.

### `stepSource` config

| Value | Behaviour |
|---|---|
| `'auto'` *(default)* | Whichever of this phone and the best external source counted more, **merged live**: when the other app is ahead its lead becomes the day's baseline and the phone's own sensor keeps counting on top, so 6,000 from Health Connect becomes 6,001, 6,005, 6,010 as the user walks instead of freezing until the next sync. |
| `'device'` | Phone sensor only. Health Connect is still written to, never read back. This is the pre-1.2 behaviour. |
| `'wearable'` | A watch, band or ring wins whenever one has data, even if it counted fewer steps. The exact external number, jumps included. For users who treat the wearable as the truth. |
| `'health_connect'` | The best external origin wins, wearable or not. The exact external number. For an app that only presents data another app owns. |

Under `'auto'` with no Health Connect grant, nothing changes: the numbers are
exactly this device's sensor. Resolution only ever engages once the user has
allowed reads.

#### How `'auto'` merges

Each Health Connect read compares the best external source with the phone.
If the other app is ahead by N, N is stored as today's *baseline* and the
number shown becomes `deviceSteps + N`; every step the phone counts moves it.
The next read raises the baseline only if the other app has pulled further
ahead again — the phone on a desk while a watch walked — and leaves it alone
when both counted the same walk, so nothing is counted twice. The baseline
never shrinks within a day and is dropped at midnight, so the shown number is
monotonic and goals key off it. The phone's raw count is what gets written to
Health Connect, never the merged number.

```ts
const { steps, stepSource } = await StepTracker.getTodaySteps();
// steps: 6010
// stepSource: { kind: 'app', appName: 'Samsung Health', merged: true,
//               baselineSteps: 6000, deviceSteps: 10, externalSteps: 6000,
//               usedExternal: true, ... }
```

Changing `stepSource`, `setPreferredStepSource()`, `resetToday()` and a
Health Connect revoke all clear the baseline; the next read takes a fresh one.
Worked examples in [USAGE_MODES.md](USAGE_MODES.md#mode-c-both-recommended).

### `getStepSources(startDate, endDate): Promise<StepSourceList>`

Every app that published steps over the range, with what each contributed.

```ts
{
  sources: [
    {
      packageName: 'com.fitbit.FitbitMobile',
      appName: 'Fitbit',
      kind: 'watch',        // self | watch | fitness_band | ring | chest_strap | phone | app | unknown
      steps: 8240,
      distance: 6100,       // metres, 0 when the source published none
      calories: 310,        // kcal, 0 when the source published none
      lastRecordAt: 1757400000000,
      isSelf: false,
      isWearable: true,
      isPlatform: false,    // true for Health Connect's own on-device count
    },
  ],
  hasWearable: true,
}
```

On Android 14 with SDK extension 20+, Health Connect records the phone's own
steps itself once any app holds `READ_STEPS`. That origin shows up here as
`kind: 'phone'`, `isPlatform: true`, named "This phone (Android)" — its
package is `android`, or `com.android.healthconnect.phone.<hash>` after the
June 2026 provider update, and the hash differs per device and per app.

These totals **do not add up to the range's step count** and are not meant to.
Use the list to show the user their options, then pin one.

`kind` comes from the `Device` the writing app stamped on its records, which is
the reliable signal; a package-name catalog names and classifies the companion
apps that omit it. An unrecognised app still appears, just as `'unknown'`.

Returns an empty list when Health Connect is unavailable or reads are not
granted.

### `getCurrentStepSource(): Promise<CurrentStepSource>`

Which source is answering for today, and what the alternatives counted.

```ts
{
  date: '2026-09-09',
  steps: 8240,          // the number being reported
  kind: 'watch',
  packageName: 'com.fitbit.FitbitMobile',   // null when this phone won
  appName: 'Fitbit',
  deviceSteps: 5100,    // what this phone counted, win or lose
  externalSteps: 8240,  // what the best external source counted, win or lose
  usedExternal: true,
  merged: false,        // true when steps = external baseline + phone delta ('auto')
  baselineSteps: 0,     // the external lead when the baseline was taken
  policy: 'auto',
  preferredPackage: null,
}
```

`deviceSteps` and `externalSteps` are always both populated, so a UI can offer
"your watch counted 8,240 — use that instead?" without a second call.

### `setPreferredStepSource(packageName: string | null)`

Pins one origin as the source of truth, overriding what the policy would pick.
`null` clears it. Stored outside config, so a user's choice survives an
`initialize()` that does not mention it.

A pin naming a source with no data for the day falls back to the policy rather
than reporting zero.

### `getInstalledCompanionApps(): Promise<CompanionApp[]>`

Wearable companion apps found on the phone — Galaxy Wearable, Fitbit, Garmin
Connect and so on. Useful during onboarding, before Health Connect has any data
to look at.

### Where resolution applies

`getTodaySteps`, `getStepsForDate`, `getYesterdaySteps`, `getStatsForRange`, the
weekly/monthly/yearly stats, the `stepsChanged` event and the foreground
notification all report the resolved number, and carry a `stepSource` object
saying where it came from.

`getHistory()` is the exception: it returns this device's stored rows verbatim,
because its `synced` / `syncedRemote` flags describe local records.

`state` and `source` on a snapshot keep describing **this device's** sensor even
when the count came from a watch — whether the foreground service is running is
a separate question from where the number came from. `source` is one of
`'step_counter'`, `'step_detector'`, `'accelerometer'` (the software pedometer
on phones with no step sensor) or `'none'`.

Goals follow the number on screen. Under `'auto'` that number is monotonic for
the day, and every goal fires at most once per period regardless, so a
wearable total arriving in jumps cannot fire a goal twice.

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
| `trackingStateChanged` | `{ state, source, reason }`. `reason` is `'started'`, `'boot'`, `'restored_sticky'`, `'restored_watchdog'`, `'restored_foreground'`, `'paused'`, `'no_sensor'`, etc. |
| `stepSourceChanged` | `ResolvedStepSource`. Fires when the app answering for the user's steps flips — a watch coming into range mid-morning, or a pin being changed. |
| `healthConnectStatusChanged` | `HealthConnectStatus`. Fires after a permission request, a revoke, and on every foreground where the status moved — which is how you notice the user granting or revoking from outside the app. |
| `dayChanged` | `{ previousDate, currentDate, previousDaySteps }`. Refetch your stats here. |
| `syncCompleted` | `{ target: 'health_connect' \| 'remote', syncedRecords, failedRecords, skippedRecords, success, error?, retryable? }`. `skippedRecords` counts days left to a wearable that already owns them. |
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
| `healthConnectEnabled` | `true` | master switch for both reads and writes |
| `healthConnectSyncIntervalMinutes` | 30 | clamped to WorkManager's 15-minute floor; 0 disables |
| `healthConnectReadEnabled` | `true` | set `false` to mirror your own count without ever reading; `READ_*` is then never requested |
| `healthConnectWriteEnabled` | `true` | set `false` to read a watch's data without adding a second copy of your own; `WRITE_*` is then never requested |
| `healthConnectBackgroundRead` | `false` | also request `READ_HEALTH_DATA_IN_BACKGROUND`; without it background reads return empty |
| `healthConnectHistoryRead` | `false` | also request `READ_HEALTH_DATA_HISTORY`; required to read past 30 days |
| `stepSource` | `'auto'` | `'auto'` \| `'device'` \| `'wearable'` \| `'health_connect'` — see [Step sources](#step-sources-watches-and-other-apps) |
| `preferredStepSourcePackage` | — | pins one Health Connect origin as the truth |
| `privacyPolicyUrl` | — | **required before shipping health permissions**; Health Connect links to it and Play review checks for it |
| `remoteSyncUrl` | — | optional HTTPS endpoint for unsynced days |
| `remoteSyncHeaders` | `{}` | e.g. auth headers |
| `autoStartOnBoot` | `true` | |
| `gapRecovery` | `'split'` | what to do with steps counted while the service was dead across midnight: `'split'` by time, `'today'`, or `'drop'` — see [ARCHITECTURE.md](ARCHITECTURE.md#gap-recovery) |
| `watchdogEnabled` | `true` | 15-minute WorkManager job that restarts a killed service (needs the battery exemption on Android 12+) |
| `accelerometerFallback` | `true` | count over the accelerometer on phones with neither step sensor — see [ARCHITECTURE.md](ARCHITECTURE.md#the-accelerometer-fallback) |
| `accelerometerWakeLock` | `true` | hold a partial wake lock while sampling a non-wake-up accelerometer, so counting survives the screen going off |
| `accelerometerThreshold` | `0.9` | m/s² of linear acceleration that counts as a step; raise for vehicle false positives, lower for missed gentle walks |

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

### `useHealthConnect(options?)`

Everything a "Health Connect" settings screen needs.

```tsx
const {
  status, sources, current, companionApps, hasWearable, loading, error,
  enable, request, openSettings, install, revoke, selectSource, refresh,
} = useHealthConnect({ backgroundRead: true });
```

The status shape decides which button to show, and `enable()` walks the whole
ladder on its own:

```tsx
if (status?.availability === 'not_supported') return <Text>Not available on this device.</Text>;
if (status?.installable)        return <Button title="Install Health Connect" onPress={install} />;
if (status?.shouldOpenSettings) return <Button title="Open Health Connect settings" onPress={openSettings} />;
if (!status?.granted)           return <Button title="Connect Health Connect" onPress={enable} />;
```

Once connected, `sources` lists the apps competing to supply steps and
`selectSource(packageName)` pins one:

```tsx
{sources.filter((s) => !s.isSelf).map((s) => (
  <Button
    key={s.packageName}
    title={`${s.appName} — ${s.steps} steps${s.isWearable ? ' (worn)' : ''}`}
    onPress={() => selectSource(s.packageName)}
  />
))}
<Button title="Use this phone" onPress={() => selectSource(null)} />
```

Re-reads on `healthConnectStatusChanged`, on `stepSourceChanged`, and on every
foreground — the user grants permissions and installs the provider in another
app, so coming back is the only moment it can be noticed. Options:
`backgroundRead`, `historyRead`, `refreshOnForeground` (default true) and
`sourceWindowDays` (default 7).
