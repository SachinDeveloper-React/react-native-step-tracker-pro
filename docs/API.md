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
`E_HEALTH_CONNECT_DENIED`, `E_HEALTH_CONNECT_NOT_DECLARED`, `E_INTEGRITY_UNAVAILABLE`,
`E_INTEGRITY_FAILED`, `E_DATABASE`, `E_INVALID_CONFIG`, `E_NO_ACTIVITY`,
`E_NOT_TRACKING`, `E_UNKNOWN`. `E_SENSOR_UNAVAILABLE` arrives on the `error`
event rather than as a rejection.

`E_DATABASE` means the on-device database failed - a full disk, a corrupt
file (2.3.7); before, those arrived as `E_UNKNOWN`. `E_NOT_INITIALIZED` is never sent:
every method works before `initialize()`, on the config stored last or the
defaults. It stays in the type for code that checks for it, and goes in 3.0.

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

Patches config at runtime; only the keys you pass change. Goals and the
notification update immediately. It is validated exactly as `initialize()`
is - out-of-range numbers and unknown values reject with `E_INVALID_CONFIG`
instead of being clamped natively - and a new `dailyGoal` re-derives
`weeklyGoal` (× 7) and `monthlyGoal` (× 30) unless the same call sets them.
An `http://` `remoteSyncUrl` is accepted when this call or an earlier one set
`remoteSyncAllowHttp`.

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

The decision is recorded at once (2.3): the returned snapshot says `stopped`,
and neither the next launch nor a reboot starts tracking again - even when the
app is in the background and Android refuses to deliver the stop to the
service. A service that misses it stops itself within a minute.

The sync, retention and watchdog jobs stay cancelled until `startTracking()`:
after a stop, `initialize()` and `updateConfig()` do not schedule them (2.3.6);
before, the next launch's `initialize()` scheduled the sync and retention jobs
again. A tracker that never started is not stopped - an app that only reads
Health Connect, or a user who never allowed activity recognition, keeps its
hourly upload and daily retention (2.3.7). `syncNow()` still uploads a stopped
tracker's pending days on request.

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
  timestamp: 1757145600000,
  recoveredSteps: 0,     // of this device's count, credited by gap recovery
  suspectSteps: 0,       // of this device's count, flagged by the integrity checks
  stepSource: { /* ResolvedStepSource, see Step sources */ }
}
```

Live from the counter, not the database — correct even if the last flush was
several steps ago. Under the `'auto'` policy with Health Connect reads
granted, `steps` is the resolved number (see
[Step sources](#step-sources-watches-and-other-apps)) and `stepSource` says
where it came from. Every snapshot the package hands out — from
`initialize()`, the lifecycle calls, the reads and `stepsChanged` — carries
`stepSource`; the lifecycle calls fill it from the source cache without a
Health Connect round trip.

### `getYesterdaySteps(): Promise<DayRecord>`
### `getStepsForDate(date: string): Promise<DayRecord>`

`date` is `yyyy-MM-dd` in the device timezone.

```ts
{ date: '2026-09-05', steps: 11204, distance: 7887.6, calories: 336.4, synced: true,
  syncedRemote: false, recoveredSteps: 200, suspectSteps: 0 }
```

`recoveredSteps` is how many of this device's steps for the day were
credited in one go by [gap recovery](ARCHITECTURE.md#gap-recovery) — the
share of an overnight kill apportioned to the day, a reboot's since-boot
steps, an install's since-boot claim — rather than observed sample by sample.
An apportionment is an estimate, so a server judging the day wants it
separately. It grows when gap recovery backfills a past day
(`historyBackfilled` with `reason: 'gap'` or `'reboot'`) - not for a
`'late'` backfill, whose steps were watched being taken - is `0` for a day
counted live and for every day stored before 1.4.0, and never includes
anything from Health Connect. Also on `StepSnapshot`.

`suspectSteps` is how many of this device's steps for the day the
[integrity checks](#integrity-checks) flagged — `0` unless
`fraudDetection.enabled`. Under `fraudDetection.mode: 'exclude'` a resolved
record has already had them taken out of `steps`; `getHistory()` rows are
stored counts and still include them.

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
  goalProgress: 0.774,
  healthConnect: 'read',    // | 'not_consulted' | 'timed_out' | 'failed' (2.2.1)
}
```

`averageSteps` divides by elapsed days so a half-finished month is not diluted
by days that have not happened yet.

`healthConnect` says whether other apps' steps went into the days. Under
`'timed_out'` or `'failed'` the days are this device's own count - not a range
without a watch - so call again rather than showing them as final. A range
reads up to 35 days of raw records, newest first; a month of a busy watch can
run past the read's cap, and the days it did not reach are answered with each
app's daily totals from Health Connect's aggregate API instead (2.2.1).

### `getStatsForRange(startDate, endDate): Promise<RangeStats>`
### `getHistory(startDate, endDate): Promise<DayRecord[]>`

Raw rows, zero-filled. Use this to draw charts; use `getStatsForRange` when you
want the aggregates computed for you.

### `getVerificationSnapshot(date: string, options?): Promise<VerificationSnapshot>`

Everything a server needs to judge one day, with **nothing resolved for it**.
An app that converts steps into anything of value should post this rather
than `steps`: the server never trusts a single resolved number, it wants the
phone's own count and every Health Connect origin separately, it wants to
know which records were typed in by hand, and it wants the recovered share
split out.

```ts
{
  schemaVersion: 2,           // the shape; read it first
  libraryVersion: '2.0.0',    // the package release that produced it
  date: '2026-09-14',
  deviceSteps: 7431,          // this phone's own sensor; never Health Connect
  recoveredSteps: 800,        // of deviceSteps, credited in one go by gap recovery
  sensor: 'step_counter',     // 'step_detector' / 'accelerometer' lose steps while dead
  coverageStartAt: 0,         // epoch ms this device covered the day from; 0 = whole day
  sources: [                  // every origin, unresolved, self included; [] without a grant
    { packageName: 'com.example.app', kind: 'self', steps: 7431, manualSteps: 0, ... },
    { packageName: 'com.fitbit.FitbitMobile', kind: 'watch', steps: 8240,
      manualSteps: 0, recordingMethods: { active: 0, automatic: 8240, manual: 0, unknown: 0 },
      isWearable: true, trustedWearable: true, ... },
    { packageName: 'com.example.other', kind: 'app', steps: 20000,
      manualSteps: 20000, isWearable: false, trustedWearable: false, ... },
  ],
  sourcesStatus: 'read',      // | 'not_consulted' | 'timed_out' | 'failed' (2.3)
  resolved: { steps: 8240, kind: 'watch', packageName: 'com.fitbit.FitbitMobile',
              usedExternal: true, merged: false, manualStepsExcluded: 0, ... },
  capabilities: { hasStepCounter: true, hasStepDetector: true,
                  manufacturer: 'samsung', model: 'SM-S928B', sdkInt: 35 },
  health: { recoveryCount: 4, lastRecoveryReason: 'watchdog',
            batteryOptimizationEnabled: true, aggressiveOem: false },
  clock: { wallClockMs: 1757845200000, bootId: 1757800000000,
           timezone: 'Asia/Kolkata', utcOffsetMinutes: 330 },
  suspectSteps: 0,            // of deviceSteps, flagged by the integrity checks
  integrity: { /* IntegrityReport, see Integrity checks */ },
}
```

`schemaVersion` changes only when a field is removed, renamed or changes
meaning - a new field does not bump it - so a server can parse by version
across releases. It is `2`, first sent by 2.0. A snapshot without it came
from 1.x: the 1.5 shape if it carries `integrity` (and `suspectSteps`), the
1.4 shape if not. Neither removed or renamed anything version 2 has, so both
parse as version 2 with those fields missing.
`libraryVersion` says which release of this package produced it.

The second argument, `{ sign?, nonce?, include?, healthConnectRecordTypes? }`,
signs the snapshot with the install's Keystore key and echoes a server-issued
nonce inside what was signed; the result then carries `nonce`, `signedAt` and
a `signature` block. See [Integrity checks](#attestdevicechallenge-string-promisedeviceattestation)
for the key, the attestation and how a server verifies it.

**The evidence, signed with the totals** (2.1). The totals above are built
from finer data - the per-minute buckets, the motion windows, the raw Health
Connect records - and a server that scores cadence or motion needs that data
under the same signature, or a modified app could send doctored minutes next
to genuine totals. `include` puts it inside the signed payload, so one
signature and one Play Integrity `requestHash` cover all of it:

```ts
const snapshot = await StepTracker.getVerificationSnapshot(date, {
  sign: true,
  nonce,
  include: ['minutes', 'motionWindows', 'healthConnectRecords'],
  healthConnectRecordTypes: ['steps', 'distance'], // default ['steps']
});
// snapshot.include              ['minutes', 'motionWindows', 'healthConnectRecords']
// snapshot.minutes              StepMinute[]   - as getStepMinutes(date, date)
// snapshot.motionWindows        MotionWindow[] - as getMotionWindows(date, date)
// snapshot.healthConnectRecords { status: 'read', recordTypes, records, truncated }
```

- `minutes` is recorded only while `fraudDetection.enabled`, and
  `motionWindows` only while `motionSampling.enabled`; otherwise each is `[]`.
- `healthConnectRecords` has the `getHealthConnectRecords()` shape plus a
  `status`: `'read'`, or why the records are missing - `'disabled'`,
  `'unavailable'`, `'not_granted'` (a read permission for `recordTypes`),
  `'timeout'` or `'failed'`. The snapshot is still returned and signed. Unlike
  `sources`, it does not follow the step-source policy: an explicit ask reads
  whenever the provider and the grant allow.
- `include` is echoed in a fixed order, so a server can tell "asked for and
  empty" from "not asked for". Without `include` the snapshot is exactly the
  2.0 shape, and `schemaVersion` stays `2` - these are new fields.

`minutesStatus` and `motionWindowsStatus` (2.2) say whether each list is being
recorded as the snapshot is taken - `'enabled'` or `'disabled'` - so an
empty list under `'enabled'` means nothing happened. A setting changed during
the day shows as a `config_changed` event in `integrity.events`.

A day of per-minute buckets is at most 1,440 entries, but a watch writing a
record a minute adds as many again per type, so ask for what the server
actually scores.

### `getSignedSnapshot(date, options?): Promise<SnapshotSignature>`

The signed snapshot and nothing else (2.2). A signed
`getVerificationSnapshot()` carries the snapshot twice - as the object, and
again as `signature.signedPayload`, the JSON text that was signed - and with
`include` that can be a lot. This returns only the signature block, the
snapshot in it once:

```ts
const signed = await StepTracker.getSignedSnapshot(date, { nonce, include: ['minutes'] });
// { keyId, algorithm, value, attested, signedPayload, payloadSha256 }
await api.post('/steps/verify', { signed });
const { token } = await StepTracker.requestIntegrityToken({
  requestHash: signed.payloadSha256,
  cloudProjectNumber,
});
```

It takes the same options as `getVerificationSnapshot()` and always signs.
The server verifies `value` over `signedPayload` and parses that - which is
what it should trust in either shape.

`resolved` is what the current policy chose, for comparison only. `sources`
follows the same rules as every other Health Connect read — no provider, no
grant or `stepSource: 'device'` leaves it empty, never an error — and
`sourcesStatus` says which: `'read'`, `'not_consulted'`, or `'timed_out'` /
`'failed'` when the read did not finish (2.3). A snapshot taken in the
background - no activity on screen and no foreground service, so with
tracking off - without `READ_HEALTH_DATA_IN_BACKGROUND` is `'not_consulted'`
(2.3.7): Health Connect refuses that read, so it is not made. With tracking
on, the tracking service is a foreground service, and the read is made (2.4). The snapshot reads
sources fresh, with 15 seconds to do it, and signs the status with them, so a
server never mistakes a read that failed for a day with no watch; and each
source carries `manualSteps`, `recordingMethods` and `trustedWearable`.
`coverageStartAt` is only known for today; a past day reads `0`. `clock`
puts the wall clock next to a boot id derived from `elapsedRealtime`: a clock
edit moves both and leaves the uptime behind `bootId` alone, so two snapshots
from the same boot with different `bootId`s mean the clock was changed
between them. Rejects with `E_INVALID_CONFIG` before touching native when
`date` is not `yyyy-MM-dd`.

**Posting it.** Upload the day you are about to pay for, and let the server
decide:

```ts
async function settle(date: string) {
  const snapshot = await StepTracker.getVerificationSnapshot(date);
  const response = await fetch('https://api.example.com/steps/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ userId, snapshot }),
  });
  return (await response.json()) as { creditedSteps: number; reason: string };
}
```

A server-side rule set that fits this shape:

- credit `deviceSteps - recoveredSteps` from a `'step_counter'` sensor
  outright; treat `recoveredSteps` and anything from an `'accelerometer'`
  as lower-confidence, and `bootId` moving without a reboot as a clock edit;
- for each source in `sources`, ignore `manualSteps` entirely, and give
  `steps - manualSteps` full weight only when `trustedWearable` is true;
  otherwise cap it at what the phone covered
  (`deviceSteps`, or the part before `coverageStartAt` on an install day);
- never re-bucket a day already paid: a later snapshot for the same `date`
  with a higher `deviceSteps` is a `historyBackfilled` recovery, or steps
  taken before midnight that the phone delivered after it, and whether to
  honour it is policy, not arithmetic — `gapRecovery: 'drop'` stops it
  happening at all, crediting nothing it cannot place on the current day
  (`'today_capped'` stops it too, but hands the current day those steps
  unjudged). Settling yesterday a few minutes after
  midnight on a phone that slept through it is when late steps are likeliest;
  a snapshot taken then already waits for them;
- use `health.recoveryCount` and `aggressiveOem` to explain a low day, not
  to inflate one;
- with the [integrity checks](#integrity-checks) on, take `suspectSteps` off
  the device count, read `integrity.flags` and `integrity.events` for why,
  and only trust a snapshot whose `signature` verifies against a key your
  server accepted from `attestDevice()`.

The built-in uploader sends the same per-source detail with
`remoteSyncPayload: 'full'` — see [Remote sync](#remote-sync).

### `getMotionWindows(startDate, endDate): Promise<MotionWindow[]>`

Motion signature windows that opened on the days between the two dates,
inclusive, oldest first. Empty unless `motionSampling.enabled`.

With `motionSampling: { enabled: true }`, every `intervalMinutes` (default 5)
while tracking is running and steps have accrued since the last window, the
service samples the accelerometer for `windowSeconds` (default 10) and keeps
**features only — never the samples**:

```ts
{
  startedAt: 1757845200000,   // epoch ms the window opened
  durationMs: 10040,
  sampleCount: 251,
  dominantFrequencyHz: 1.8,   // gait 1.2–2.5; a hand shake 3–6; still → 0
  variance: 2.1,              // (m/s²)² of the mean-removed magnitude; a shake is tens
  zeroCrossingRate: 3.6,      // ≈ 2 × the frequency for a clean oscillation
  peakRatio: 0.62,            // share of in-band energy at the peak; a shake is near 1
  stepsDuringWindow: 18,
}
```

Enough for a server to tell a walk from a phone being shaken — a walk at
1.8 Hz and a shake at 4 Hz are more than 2 Hz, an order of magnitude of
variance and twice the crossing rate apart — and not enough to reconstruct
anything about the movement. Each window also arrives on the `motionWindow`
event. The last `motionWindowRetention` (default 288, a day at five-minute
intervals) are kept; older ones are dropped as new ones are stored.

Sampling stops while paused, is skipped while the app is in the background
without the battery-optimisation exemption, and on a non-wake-up
accelerometer holds a partial wake lock for the window's length only when
`accelerometerWakeLock` allows — the same policy as the software pedometer.
On a phone counting over the accelerometer already, the window listens in on
the samples that are arriving; nothing extra is registered. A window that saw
too few samples to say anything is discarded rather than stored.

## Integrity checks

For apps that pay for steps. Off by default; nothing below records anything,
and every figure it reports is zero, until `fraudDetection.enabled`.

```ts
await StepTracker.initialize({
  fraudDetection: {
    enabled: true,
    mode: 'flag',              // 'flag' reports only; 'exclude' also takes suspect steps out
    maxCadenceSpm: 200,        // 0 turns any threshold off
    steadyCadenceMinutes: 30,
    maxContinuousMinutes: 180,
    maxDailySteps: 50000,
    flagWhileCharging: true,
    activityRecognition: false, // needs play-services-location in your app
  },
  motionSampling: { enabled: true }, // lets the detector tell a shake from a walk
});
```

With it on, every step this phone watches being taken is placed on the
minute it was taken in (`step_minute`), the service notes whether the phone
is plugged in and when the clock or zone changes, and a detector re-judges
the day about once a minute from those minutes and the day's motion windows.
It never changes a count: the verdict is a list of flags with the numbers
behind them, and `suspectSteps` — the strong flags' minutes, each counted
once, plus whatever the rest of the day exceeds `maxDailySteps` by.

| Flag | Severity | What it means |
|---|---|---|
| `cadence` | strong | a minute with more timed steps than `maxCadenceSpm` — faster than people walk or run; a hand shake |
| `steady_cadence` | strong | `steadyCadenceMinutes` in a row whose counts never move by more than one step from one minute to the next, with no pause: a swing gadget or motor. A treadmill drifts by more |
| `continuous` | strong | walking past `maxContinuousMinutes` with no pause; only the excess minutes |
| `charging` | strong | steps counted while plugged in — gadgets and shakers are usually left on a charger |
| `in_vehicle` | strong from 3 minutes, weak below | steps counted while Activity Recognition said in a vehicle |
| `activity_still` | weak | steps counted while Activity Recognition said still |
| `night` | weak | half an hour or more of walking starting between midnight and 5:00 |
| `shake` | strong | a motion window at 3.3 Hz or faster, with variance over 20 and a tonal peak |
| `swing` | weak | a motion window that is a near-pure tone at walking pace; a phone in a backpack can read the same, so it is evidence only |
| `daily_volume` | strong | the day's count past `maxDailySteps`; only the excess |

Only strong flags count towards `suspectSteps`. The defaults are starting
points chosen to stay clear of honest walking, running and treadmill sessions;
tune them on your own users' data before they cost anybody anything. A person
walking with a power bank plugged in is flagged `charging`, and a marathon
runs past `maxContinuousMinutes` — which is why the default `mode` is `'flag'`
and the verdict is meant for a server that weighs several signals together.

**What the timing rules protect.** With the screen off, the phone's sensor
hub holds a walk's steps and delivers them minutes later in one batch; each
keeps the minute it was taken in, up to 30 minutes late (2.3.6). Steps whose
minute is unknown — a batch that overflowed, the first sample after a dead
process, or samples whose own timestamps are unusable or older than that —
are stored as `untimedSteps` at the minute they arrived in and never judged
for cadence, steadiness or stamina: piling them into their arrival minute as
timed steps would flag an honest walk for a cadence nobody walks at, and
spreading them across the gap would invent exactly the metronomic count
`steady_cadence` looks for. Steps credited by gap recovery are not in any
minute at all; only the daily cap can reach them.

A batch like that can cross midnight. Its steps count towards the day they
were taken on, the one their minutes are on, and that day is judged with
them (2.3.7): a shaker left running up to midnight on a sleeping phone is flagged on
the day it counted for, not passed to the next day unjudged. When the day had
already closed - the screen came on, or a read ended it, before the batch was
read - the steps are added to it afterwards and announced with
`historyBackfilled` (`reason: 'late'`), under the default `gapRecovery:
'split'`. The policies that never change a closed day decide differently:
`'today'` and `'today_capped'` give them to the new day as recovered steps,
unjudged - under `'today_capped'` within `gapRecoveryMaxSteps`, the batch
counting as one recovery (2.4) - and `'drop'` discards them. `'split'` is
the one policy that judges them.

**`mode: 'exclude'`** also takes `suspectSteps` out of every number this
package shows or sends: `getTodaySteps()`, `stepsChanged`, the notification,
goals, stats, the Health Connect mirror and the remote upload. They come out
of this device's count *before* sources are resolved, so a watch can still
win. The raw count stays available as `deviceSteps`, every record carries
`suspectSteps`, and `ResolvedStepSource.suspectStepsExcluded` says how much
came out. Because a run is judged as it grows, the shown number can go down
when one is flagged after the fact; `stepsChanged` fires when it does.

### `getIntegrityReport(date: string): Promise<IntegrityReport>`

Everything the checks know about one day. Today is judged afresh on every
call; a past day reads the verdict stored when it closed.

```ts
{
  date: '2026-09-14',
  enabled: true,
  mode: 'flag',
  deviceSteps: 14210,       // this phone's own count, before any exclusion
  suspectSteps: 3960,
  flags: [
    { type: 'steady_cadence', severity: 'strong',
      from: 1757834400000, to: 1757836560000, steps: 3960,
      evidence: { minutes: 36, meanSpm: 110, minSpm: 110, maxSpm: 111 } },
    { type: 'charging', severity: 'strong', from: 1757834400000, to: 1757836560000,
      steps: 3960, evidence: { minutes: 36 } },
  ],
  events: [
    { at: 1757834390000, type: 'charging_started', detail: {} },
    { at: 1757840000000, type: 'clock_changed', detail: { jumpMs: -3600000 } },
  ],
  minutes: { count: 212, timedSteps: 13900, untimedSteps: 310, chargingSteps: 3960,
             stillSteps: 0, vehicleSteps: 0 },
  evaluatedAt: 1757845200000,
  rules: { maxCadenceSpm: 200, steadyCadenceMinutes: 30, maxContinuousMinutes: 180,
           maxDailySteps: 50000, flagWhileCharging: true },
  charging: false,          // right now; false for a past day
  activityRecognition: { requested: false, available: false, current: 'unknown' },
  device: { emulator: false, testKeysBuild: false, suBinary: false, adbEnabled: false,
            developerOptions: true, appDebuggable: false,
            stepCounter: { name: 'step_counter', vendor: 'Qualcomm', version: 1, wakeUp: false } },
}
```

`device` holds cheap hints — su paths, build properties, developer settings.
Each can be faked on a rooted phone, and Magisk hides root from all of them
as a matter of course, so treat them as hints for review, never as a verdict.
They are reported even with the checks off. What a server can trust is
[`attestDevice()`](#attestdevicechallenge-string-promisedeviceattestation)
and [Play Integrity](#requestintegritytokenoptions-promiseintegritytoken).

Events logged while the checks are on: `clock_changed` (`detail.jumpMs`),
`timezone_changed`, `reboot`, `reset_today`, `history_cleared`,
`config_changed` (`detail.keys` — the integrity-relevant settings that
moved, logged even when the change turns the checks off), `sensor_changed`
(`from`, `to`), `charging_started`, `charging_stopped`, `activity_changed`,
`service_recovered` (`reason`) and `device_attested` (`keyId`). The log is
bounded to 2,000 entries and to `historyRetentionDays`, and `clearHistory()`
keeps it — logging the clear — so wiping history cannot wipe the record that
it happened.

### `getIntegrityEvents(startDate, endDate): Promise<IntegrityEvent[]>`

The event log between two dates, inclusive, oldest first.

### `getStepMinutes(startDate, endDate): Promise<StepMinute[]>`

This phone's steps minute by minute, oldest first; only minutes with steps.

```ts
[{ minuteStart: 1757834400000, steps: 110, untimedSteps: 0,
   chargingSteps: 110, stillSteps: 0, vehicleSteps: 0 }]
```

Counts only, never samples. Recorded while the checks are on and kept for
`historyRetentionDays`. Post them with the snapshot if your server wants to
run its own rules over the day.

### `attestDevice(challenge: string): Promise<DeviceAttestation>`

Generates a fresh EC P-256 key in the Android Keystore, bound to a challenge
from your server, and returns its hardware attestation. Every signed snapshot
and every remote upload afterwards is signed with it. Calling it again
replaces the key.

```ts
const { challenge } = await api.post('/devices/challenge');
const attestation = await StepTracker.attestDevice(challenge); // 1–128 bytes of UTF-8
await api.post('/devices/attest', attestation);
// { keyId, algorithm: 'SHA256withECDSA', publicKey, certificateChain: [leaf, ..., root],
//   attested: true, securityLevel: 'tee' | 'strongbox' | 'software' | 'unknown', createdAt }
```

On the server, verify `certificateChain` up to Google's hardware attestation
root, check that the leaf's attestation extension carries your challenge,
your package name and signing certificate, a `verifiedBootState` of
`Verified` and a locked bootloader, then store `publicKey` against the
install. A rooted phone, an unlocked bootloader or an emulator fails there,
on your server, where it cannot be patched out. A device that refuses
attestation still gets a key, reported with `attested: false`; decide on the
server what that is worth.

**Signed snapshots.** Pass `{ sign: true, nonce }` to
[`getVerificationSnapshot`](#getverificationsnapshotdate-string-options-promiseverificationsnapshot):

```ts
const { nonce } = await api.post('/steps/nonce');
const snapshot = await StepTracker.getVerificationSnapshot(date, { sign: true, nonce });
await api.post('/steps/verify', { userId, snapshot });
```

`snapshot.signature` is `{ keyId, algorithm, value, attested, signedPayload,
payloadSha256 }`. Verify `value` (base64 DER ECDSA) over the UTF-8 bytes of
`signedPayload` with the key stored for `keyId`, check that the parsed
payload's `nonce` is the one you issued and has not been used, and then
trust **the parsed `signedPayload`, not the unsigned fields around it**. A
server never has to re-serialise anything the way the phone did. For Play
Integrity on top, pass `payloadSha256` to
[`requestIntegrityToken()`](#requestintegritytokenoptions-promiseintegritytoken).

Uploads to `remoteSyncUrl` carry the same kind of signature once a key exists
— see [Remote sync](#remote-sync).

### `hasAttestationKey(): Promise<boolean>`
### `getAttestationKeyInfo(): Promise<DeviceAttestation | null>`

Attest once, not on every launch. `attestDevice()` has to replace the key each
time - Android can only bind a challenge when a key is generated - so check
first, and only attest when the install has no key or your server has none
on file for it.

A signed snapshot taken before any `attestDevice()` makes a key of its own,
which no server has seen. From 2.3 that key does not count:
`hasAttestationKey()` is false for it, `getAttestationKeyInfo().createdBy` is
`'sign'` (against `'attestDevice'`), and it neither signs uploads nor satisfies
`remoteSyncAuth: 'signature'`. Before 2.3 it counted, and an app that checked
`hasAttestationKey()` never attested. After upgrading, such an install reports
`false` and attests once.

`hasAttestationKey()` means `attestDevice()` ran, not that the hardware vouched
for the key. A device that refuses attestation - some emulators, some old
phones - still gets a key from `attestDevice()`, unattested, and
`hasAttestationKey()` is true for it; `getAttestationKeyInfo().attested` (and
the chain your server received) say whether it was attested. Decide what an
unattested key is worth on the server, not by calling `attestDevice()` again:
the device will refuse again.

```ts
const info = await StepTracker.getAttestationKeyInfo(); // null when there is no key
if (!info || !(await api.hasKey(info.keyId))) {
  const { challenge } = await api.post('/devices/challenge');
  await api.post('/devices/attest', await StepTracker.attestDevice(challenge));
}
```

`getAttestationKeyInfo()` returns the same shape as `attestDevice()` without
touching the key; its chain still carries the challenge it was made with.

### `requestIntegrityToken(options): Promise<IntegrityToken>`

A Play Integrity token from a standard request, bound to `requestHash`. Key
attestation shows which device and which signed app hold the key; Play
Integrity adds what only Google can say - that this exact build is the one
Play distributed, how it was installed, and Google's own device and account
verdicts. Bind it to the signed snapshot:

```ts
const snapshot = await StepTracker.getVerificationSnapshot(date, { sign: true, nonce });
const { token } = await StepTracker.requestIntegrityToken({
  requestHash: snapshot.signature!.payloadSha256,
  cloudProjectNumber: 123456789012, // Play Console → App integrity
});
await api.post('/steps/verify', { snapshot, integrityToken: token });
```

The server decodes the token with Google's API and checks the verdicts and
that `requestHash` equals the hash of the `signedPayload` it verified. The
token provider is prepared once per project and reused; a stale one is
re-prepared and the request retried once.

Play Integrity is compile-only here, like Activity Recognition. Add it to your
app to use this:

```gradle
dependencies {
  implementation "com.google.android.play:integrity:1.6.0"
}
```

Without it the call rejects with `E_INTEGRITY_UNAVAILABLE`; when Play refuses,
with `E_INTEGRITY_FAILED`. `requestHash` is at most 500 characters.

### `prepareIntegrity(cloudProjectNumber: number): Promise<void>`

Prepares the token provider ahead of time (2.1). Preparing warms Play's side
up and can take seconds, and without this the first `requestIntegrityToken()`
pays for it. Call it at app start, or when the screen that will need a token
opens:

```ts
StepTracker.prepareIntegrity(123456789012).catch(() => {
  // Not fatal: requestIntegrityToken() prepares on its own if this failed.
});
```

It is idempotent - a provider already prepared is reused - and rejects exactly
like `requestIntegrityToken()`.

**Which failures to retry.** On `E_INTEGRITY_FAILED` from either call,
`error.details` is an `IntegrityErrorDetails`:
`{ playErrorCode, playError, retryable }`. Back off exponentially and try
again only when `retryable` is true:

| Play error | Code | Retry? | What fixes it |
|---|---|---|---|
| `NETWORK_ERROR` | -3 | yes | connectivity |
| `TOO_MANY_REQUESTS` | -8 | yes | backing off |
| `CANNOT_BIND_TO_SERVICE` | -9 | yes | backing off; an old Play Store can also cause it |
| `GOOGLE_SERVER_UNAVAILABLE` | -12 | yes | backing off |
| `CLIENT_TRANSIENT_ERROR` | -18 | yes | backing off |
| `INTEGRITY_TOKEN_PROVIDER_INVALID` | -19 | yes | already re-prepared and retried once here |
| `INTERNAL_ERROR` | -100 | yes | backing off |
| `API_NOT_AVAILABLE` | -1 | no | the user updating the Play Store |
| `PLAY_STORE_NOT_FOUND` | -2 | no | the official Play Store on the device |
| `PLAY_SERVICES_NOT_FOUND` | -6 | no | Play services on the device |
| `PLAY_STORE_VERSION_OUTDATED` | -14 | no | the user updating the Play Store |
| `PLAY_SERVICES_VERSION_OUTDATED` | -15 | no | the user updating Play services |
| `APP_NOT_INSTALLED` | -5 | no | the app coming from Play |
| `APP_UID_MISMATCH` | -7 | no | the app coming from Play |
| `CLOUD_PROJECT_NUMBER_IS_INVALID` | -16 | no | the right project number |
| `REQUEST_HASH_TOO_LONG` | -17 | no | a hash of at most 500 characters |

A code this release does not know reads `playError: 'UNKNOWN'` and
`retryable: false`, so an app never retries blindly.


### Activity Recognition

`activityRecognition: true` tags steps with Google's Activity Recognition
transitions — still, walking, running, on a bicycle, in a vehicle — so steps
counted in a car or with the phone still are flagged. It needs Play
Services, which this package declares compile-only so an app that does not
want it never ships it. Add it to your app to turn it on:

```gradle
// android/app/build.gradle
dependencies {
  implementation "com.google.android.gms:play-services-location:21.3.0"
}
```

No permission is needed beyond the `ACTIVITY_RECOGNITION` the package already
holds to count steps, and nothing about location is read. Without the
dependency, or on a phone without Play Services, it does nothing and
`IntegrityReport.activityRecognition.available` is `false`.

### What this cannot catch

Everything above runs on a phone the user controls. A rooted phone can feed
the sensor any numbers, hook this code, or edit its database; the checks
raise the effort for everyone else. Key attestation and signed payloads are
what move the final decision to your server, and a server rule set that
fits:

- credit nothing from an install whose attestation failed, or whose
  snapshots stop verifying;
- credit `deviceSteps - recoveredSteps - suspectSteps` outright; treat weak
  flags, `untimedSteps` and recovered steps as lower confidence;
- never reject on a single weak flag; send days with several flags, or a
  large `suspectSteps`, to review;
- look at the events: a `clock_changed` or `reset_today` next to a big day,
  or `config_changed` turning the checks off, deserves a look;
- keep your thresholds on the server, so they can change without a release.


---

## Writes

### `resetToday(): Promise<boolean>`

Zeroes today's counter and re-arms goal events. History is untouched. Meant for
QA builds. With the integrity checks on, today's minute buckets and verdict go
with the count, and a `reset_today` event is logged.

### `clearHistory(): Promise<boolean>`

Deletes every stored day, motion window, minute bucket and verdict. The
integrity event log is kept, and records the clear.

### `pruneHistory(retentionDays?): Promise<number>`

Deletes rows older than the window and returns how many were removed. Runs
automatically at midnight and once a day via WorkManager, using
`historyRetentionDays` from config.

A day a sync target in use has not accepted yet is kept past the window (2.3):
one not uploaded while `remoteSyncUrl` is set, one not mirrored while
Health Connect writes are on and allowed. An endpoint that is down for longer
than the window loses nothing. Nothing is kept past a year, however it
stands.

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
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which your app declares from 2.0, and
an eligible Play use case — read
[PLAY_STORE_COMPLIANCE.md](PLAY_STORE_COMPLIANCE.md) first. Without the
permission it opens the settings list instead, which reaches the same switch.

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
  directPromptAvailable: false,         // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS declared by the app
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
  canRead: false,              // every type in healthConnectReadTypes granted
  canWrite: false,             // all three write permissions granted
  canReadSteps: true,          // READ_STEPS: enough to use a watch's steps (2.1.1)
  grantedReadTypes: ['steps'], // of healthConnectReadTypes, what the user allowed
  canWriteSteps: true,         // WRITE_STEPS: enough to mirror this device
  grantedWriteTypes: ['steps'],
  stepsGranted: true,          // READ_STEPS - or WRITE_STEPS for a mirror-only app (2.2)
  backgroundReadGranted: false,
  historyReadGranted: false,
  grantedPermissions: [],
  missingPermissions: ['android.permission.health.READ_STEPS', ...],
  undeclaredPermissions: [],   // used by config, absent from your manifest
  denialCount: 0,              // requests after which steps were still refused
  shouldOpenSettings: false,   // steps refused past the sheet's limit
}
```

`availability` is the field to branch on. `not_supported` is the only value with
nothing to offer the user; the other two failure states are both fixed by
`installHealthConnect()`. A non-empty `undeclaredPermissions` means your
manifest is missing entries config would use. Distance, calories and the
write set are then simply not asked for (2.2); an undeclared `READ_STEPS`, or
an opt-in such as background reads, makes a request reject with
`E_HEALTH_CONNECT_NOT_DECLARED` until it is added
([PERMISSIONS.md](PERMISSIONS.md#when-an-entry-is-missing)).

**Partial grants.** The sheet lets the user untick any single permission.
Steps alone are enough: with `READ_STEPS` a watch's steps are used and its
distance and calories are derived when those were refused, and with
`WRITE_STEPS` this device's count is mirrored, distance and calories going
along only when allowed. Decide whether to show Health Connect data on
`canReadSteps`, not on `canRead`. `granted` still means everything config
asks for, so `enableHealthConnect()` asks again for what is missing, and a
UI can compare `grantedReadTypes` with `healthConnectReadTypes` to offer
"also allow distance". Before 2.1.1 a single unticked type turned Health
Connect off - no watch steps and no mirror - without an error.

### `enableHealthConnect(options?): Promise<HealthConnectStatus>`

The whole "turn Health Connect on" flow behind one call — install or update the
provider if it is missing, otherwise request permissions, otherwise fall back to
the settings screen once the sheet has stopped appearing. Wire it to a single
button and re-read the status when the app is next foregrounded.

It is done once steps are allowed (`stepsGranted`: `READ_STEPS`, or
`WRITE_STEPS` for an app with reads off), whatever the user did with writing,
distance and calories (2.2, writing from 2.2.1): it does not show the sheet again for them,
and never sends the user to settings over them. Ask for those with
`requestHealthConnectPermissions()`.

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

ISO-8601 instants with a zone, start before end, as for
[`getHealthConnectRecords()`](#gethealthconnectrecordsstartiso-endiso-options-promisehealthconnectrecordlist).
Aggregated per day across every source Health Connect knows about, so it
includes a paired watch, not just your app. When Health Connect cannot be
read it rejects, like `getHealthConnectRecords()`: `E_HEALTH_CONNECT_UNAVAILABLE`
without a provider, `E_HEALTH_CONNECT_DENIED` without `READ_STEPS` or when
Health Connect refuses the read - from the background, with no activity on
screen and no foreground service (tracking off), without
`READ_HEALTH_DATA_IN_BACKGROUND` (2.4). It used to resolve with no records,
which passed for a window nobody walked in.

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

### `getHealthConnectRecords(startIso, endIso, options?): Promise<HealthConnectRecordList>`

Every step record Health Connect holds between two instants, from every app,
as stored - for a server that wants the evidence rather than a total.

```ts
{
  records: [{
    recordType: 'steps',                       // from 2.1
    id: 'a1b2…',
    clientRecordId: 'stp-steps-2026-09-14',   // null when the writer set none
    clientRecordVersion: 1757845200000,
    packageName: 'com.fitbit.FitbitMobile',
    recordingMethod: 'automatic',              // | 'active' | 'manual' | 'unknown'
    device: { type: 'watch', manufacturer: 'Google', model: 'Pixel Watch 3' },
    startTime: 1757834400000, endTime: 1757834460000,
    startZoneOffsetSeconds: 19800, endZoneOffsetSeconds: 19800,
    lastModifiedTime: 1757835000000,
    count: 112,
  }],
  truncated: false,   // true past 10,000 records: narrow the window
}
```

ISO-8601 instants with a zone, start before end: `Z` or an offset such as
`+05:30`. Both go to native as UTC (2.3.6), the only form Android 8–13
parses; before, an offset failed there with `E_UNKNOWN`. A time with no zone names no
instant and rejects with `E_INVALID_CONFIG`. Unlike the resolved reads
it does not fall back quietly: it rejects with
`E_HEALTH_CONNECT_UNAVAILABLE` or `E_HEALTH_CONNECT_DENIED` - a read grant
missing, or Health Connect refusing a read from the background: no activity on
screen and no foreground service (tracking off), without
`READ_HEALTH_DATA_IN_BACKGROUND`.

**Distance records** (2.1). A server checking distance against steps per
interval needs both. `{ recordTypes: ['steps', 'distance'] }` returns them in
one list, oldest first; narrow each on `recordType`:

```ts
const { records } = await StepTracker.getHealthConnectRecords(from, to, {
  recordTypes: ['steps', 'distance'],
});
for (const record of records) {
  if (record.recordType === 'distance') use(record.distanceMeters);
  else use(record.count);
}
```

A distance record has every field a step record has except `count`, and
`distanceMeters` instead. Each type needs its own read permission granted -
`READ_DISTANCE` for distance - or the call rejects with
`E_HEALTH_CONNECT_DENIED` naming what is missing. Up to 10,000 records of
each type; `truncated` is true when any type had more.

### `getHealthConnectChangesToken(options?): Promise<string>`
### `getHealthConnectChanges(token: string): Promise<HealthConnectChanges>`

Change tracking for step records: what was inserted, updated or deleted
since a cursor, across every app, so a server can mirror Health Connect
without re-reading whole days.

```ts
let token = await storage.get('hcToken') ?? await StepTracker.getHealthConnectChangesToken();
for (;;) {
  const changes = await StepTracker.getHealthConnectChanges(token);
  if (changes.tokenExpired) {
    // Health Connect keeps changes for 30 days. Start again from a full read.
    token = await StepTracker.getHealthConnectChangesToken();
    await resyncWindow(await StepTracker.getHealthConnectRecords(from, to));
    break;
  }
  await upload(changes.upserted, changes.deletedIds);
  token = changes.nextToken!;
  if (!changes.hasMore) break;
}
await storage.set('hcToken', token);
```

`upserted` holds records in the `getHealthConnectRecords()` shape; `deletedIds`
the ids of deleted ones. Each call reads at most 20 pages; `hasMore` says to
call again straight away. Same rejections as `getHealthConnectRecords()`.

A token covers the record types it was taken for: steps by default, or
`getHealthConnectChangesToken({ recordTypes: ['steps', 'distance'] })` for
both (2.1). The changes then mix both types, as the records call does; tell
the type checker with `getHealthConnectChanges<AnyHealthConnectRecord>(token)`.

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

Each Health Connect read works out how far ahead the best external source is
*allowed* to be — its lead — stores it as today's baseline, and the number
shown becomes `deviceSteps + lead`; every step the phone counts moves it.

| Source | Allowed lead | Why |
|---|---|---|
| a wearable, or a pinned source | its whole margin over the phone, growing whenever it pulls further ahead | a watch on the wrist sees a walk the phone on the desk did not |
| a phone-side app (Samsung Health, Google Fit, the platform's own count, an aggregator) | only its steps from **before this device started covering the day** — an install at 15:00 takes the morning; nothing after | it reads the same phone, so for the hours both were counting it cannot have seen more; anything beyond that is inflation |
| a phone-side app, on a past day | the whole day, only if this device has nothing for it | |
| a phone-side app, on a phone counting with the detector or accelerometer | its whole margin | those sensors lose steps while the process is dead; a system app that kept counting has genuinely seen more |

The baseline never shrinks within a day and is dropped at midnight, so the
shown number is monotonic and goals key off it. The phone's raw count is what
gets written to Health Connect, never the merged number. An aggregator that
sums two origins cannot double the display, and a phone-side algorithm that
counts 5% high cannot creep it upward sync after sync.

#### What counts as a wearable: `wearableTrust`

`'auto'` is a display policy, not a fraud control. What earns a source the
whole-margin row in the table above is, by default, the `Device.type` the
writing app stamped on its records (`wearableTrust: 'metadata'`) — and any
app can stamp `TYPE_WATCH`. That is the right default for showing a user
their watch's number, and the wrong one for an app that pays per step.

`wearableTrust: 'catalog'` changes only the trust decision: a source is
trusted for its whole margin only if its package is one the built-in catalog
knows as a wearable's companion app (Fitbit, Garmin Connect, Galaxy
Wearable, Mi Fitness, Oura, …) or one you put on `wearableAllowlist`. An
unlisted package that stamps a wearable type keeps `kind: 'watch'` for
display, still shows up in `getStepSources()` with `isWearable: true`, and
is treated as a phone-side app by the coverage rule — it may fill the part of
the day before this device's coverage began, and nothing after. A pinned
source is trusted under either rule, as it always has been, and the
`'wearable'` and `'health_connect'` policies are unaffected: they promise
the other app's exact number by design. `StepSource.trustedWearable` says
which rule applied to each source. Changing `wearableTrust` or the allowlist
clears the day's continuity baseline.

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
      isWearable: true,     // by `kind`, for display
      trustedWearable: true, // whether 'auto' trusts its whole margin — see wearableTrust
      isPlatform: false,    // true for Health Connect's own on-device count
      manualSteps: 0,       // of `steps`, typed in by the user; -1 when not computed
      unknownMethodSteps: 0,
      recordingMethods: { active: 0, automatic: 8240, manual: 0, unknown: 0 },
      lateWrittenSteps: 0,  // of `steps`, from records modified over a day after they ended
      hourlySteps: [0, 0, 0, 0, 0, 0, 0, 312, 1180, /* … 24 entries */],
      activeCalories: -1,   // kcal, with healthConnectReadActiveCalories; -1 otherwise
      distanceSource: 'health_connect', // | 'derived' | 'none' | 'not_read' (2.1.1)
    },
  ],
  hasWearable: true,
  healthConnect: 'read',    // | 'not_consulted' | 'timed_out' | 'failed' (2.2.1)
}
```

An empty `sources` under `healthConnect: 'timed_out'` or `'failed'` is not
"no other apps": call again. `useHealthConnect` keeps its last list then. A
failed read is never cached (2.3.7), so the next call reads afresh. In the background -
no activity on screen and tracking off, the tracking service being a foreground
service, which Health Connect lets read - without `READ_HEALTH_DATA_IN_BACKGROUND`
the answer is `'not_consulted'`, as it is for range stats and the full upload.

`lateWrittenSteps` counts steps from records the writing app last modified
more than a day after they ended — a history pushed into Health Connect after
the fact, or a companion app that synced very late. It is evidence for a
server and is never subtracted: a watch that was out of range for two days
writes late too. `-1` on days answered through the aggregate API.

`hourlySteps` splits `steps` across the 24 local hours of the day, a record
spanning hours split by time, so a server can see *when* a source counted -
a watch that counted all day against a phone-side app that added 10,000 at
23:59, say. Over a range it is the hour-of-day total. Every entry is `-1`
when not computed. `activeCalories` is the source's active-calorie records,
read only with `healthConnectReadActiveCalories` on and granted; `-1`
otherwise.

`distanceSource` says where `distance` came from (2.1.1): `'health_connect'`
is the source's own distance records; `'derived'` is this app's own entry,
whose distance was written from stride; `'none'` means distance was read and
the source wrote none; `'not_read'` means `distance` is not in
`healthConnectReadTypes` or the user did not allow it. Only
`'health_connect'` is a measurement - check distance against steps on those
alone. The resolved day says the same on `ResolvedStepSource.distanceSource`:
`'health_connect'` when its distance is the winning source's own, `'derived'`
when it was estimated from the steps shown.

`manualSteps`, `unknownMethodSteps` and `recordingMethods` split `steps` by
Health Connect's `recordingMethod`, which the writing app stamps on every
record: counted by a sensor while the app was in use (`active`), counted in
the background (`automatic`), typed in by the user (`manual`), or unstated
(`unknown` — every record written before the field existed, and every app
that never sets it). `steps` is always the full total. A manual entry is the
one honest way to put 20,000 steps into Health Connect from a keyboard, and
without this split it is indistinguishable from a watch's count; an app that
verifies steps server-side wants the `manual` bucket separately, and
`healthConnectIgnoreManualEntries` keeps it out of the resolved number.

Windows longer than 35 days are answered through Health Connect's aggregate
API, which returns totals with no per-record metadata. On those days
`manualSteps` and `unknownMethodSteps` are `-1` and `recordingMethods` is
`null` — the same limitation `stepsBeforeCoverage` has there. Every
single-day read (`getTodaySteps`, `getStepsForDate`, the events, the
notification, `getVerificationSnapshot`) is exact.

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
  manualStepsExcluded: 0, // typed-in steps left out under healthConnectIgnoreManualEntries
  suspectStepsExcluded: 0, // flagged steps taken out of deviceSteps under fraudDetection 'exclude'
  distanceSource: 'derived', // | 'health_connect': the winning source's own distance (2.1.1)
  policy: 'auto',
  preferredPackage: null,
}
```

`deviceSteps` and `externalSteps` are always both populated, so a UI can offer
"your watch counted 8,240 — use that instead?" without a second call.

#### Manual entries

With `healthConnectIgnoreManualEntries: true`, every external source
competes on `steps - manualSteps` — under every policy, and even when
pinned — so a hand-entered 20,000 can never become the day's number. A
source with nothing left once its manual entries are out has no count to
offer and the phone answers. `manualStepsExcluded` on the resolution is what
was taken out of the source that was evaluated, and
`externalSteps + manualStepsExcluded` is the figure Health Connect's own
screen shows for it, so the UI can say "12,000 in Fitbit · 5,000 typed in by
hand were not counted" rather than leaving the difference unexplained. When
manual entries are excluded, distance and calories for that source are
re-derived from the steps that remain instead of carrying a typed-in distance
along. The flag is off by default: for a display app a user's own correction
is legitimate data, and nothing moves for a consumer who does not set it.

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

Under `fraudDetection.mode: 'exclude'`, the flagged steps come out of this
device's count before any source competes, everywhere above and in the
Health Connect mirror and remote upload; `suspectStepsExcluded` on the
resolution says how much. `getHistory()` rows still carry the stored count,
with `suspectSteps` alongside.

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
network. Until then its entry in the list says so rather than claiming an
outcome (2.3.6):

```ts
{ target: 'remote', syncedRecords: 0, failedRecords: 0, success: true, queued: true }
```

`success` there means the upload was queued. Its `syncCompleted` fires even
when there turns out to be nothing to send.

### `getPendingSyncCount(): Promise<number>`

Days a sync target in use has not accepted yet: not uploaded, when
`remoteSyncUrl` is set; not mirrored into Health Connect, when writes are on
and granted. Today does not count towards Health Connect, which writes it
again on every pass. With neither target in use it is 0 (2.3.6); before, it
counted the days an unused target had never taken, so it never reached 0.
Non-zero offline is normal.

### `getSyncStatus(): Promise<SyncStatus>`

What remote uploads last did, kept across process deaths (2.1). Uploads run
in a WorkManager job, usually with no JS alive to hear `syncAuthFailed` or
`syncCompleted`; this is where the app finds out when it next comes up.

```ts
{
  remote: {
    configured: true,           // remoteSyncUrl is set
    pendingRecords: 3,          // days the endpoint has not accepted yet
    lastAttemptAt: 1757845200000,
    lastSuccessAt: 1757800000000,
    consecutiveFailures: 2,
    lastFailure: {              // null when none; kept after a later success
      at: 1757845200000,
      reason: 'unauthorized',   // | 'forbidden' | 'no_key' | 'insecure_url'
                                // | 'http_error' | 'no_response'
      status: 401,              // null when the server did not answer
      message: 'Upload refused: HTTP 401',
      retryable: false,         // the worker retries http_error and no_response itself
    },
    authFailed: true,
  },
}
```

`authFailed` is the one to act on: the last attempt was refused over its
credentials, and nothing has changed since - no new `remoteSyncHeaders`,
`remoteSyncUrl` or `remoteSyncAuth`, no `attestDevice()`, no accepted upload.
Every scheduled upload would be refused the same way until the app acts:

```ts
const { remote } = await StepTracker.getSyncStatus();
if (remote.authFailed) {
  if (remote.lastFailure?.reason === 'no_key') {
    await registerKey(await StepTracker.attestDevice(await api.challenge()));
  } else {
    await StepTracker.updateConfig({
      remoteSyncHeaders: { Authorization: `Bearer ${await refreshToken()}` },
    });
  }
  await StepTracker.syncNow();
}
```

### Remote sync

With `remoteSyncUrl` set, a WorkManager job POSTs every day not yet uploaded
(`syncedRemote: false`) once an hour when a network is available, with
exponential backoff, and marks them uploaded on any 2xx - each day only if it
has not changed since the upload read it (2.3.6), so a day a
`historyBackfilled` recovery grew while the upload was in flight goes again
with its new total. `syncNow()` queues the same job immediately. The URL must
be `https://` unless `remoteSyncAllowHttp` is set; `remoteSyncHeaders` go on
every request.

**Authentication.** `remoteSyncHeaders` are sealed with an AES key in the
Android Keystore before they are stored, never written in the clear; headers
a 1.x release stored in the clear are sealed on first read. A 401 or 403 is
not retried with the same credentials: `syncAuthFailed` fires with the
status, and the app refreshes them with `updateConfig({ remoteSyncHeaders })`
and calls `syncNow()`. The refusal is also stored, so an app that was not
running reads it from [`getSyncStatus()`](#getsyncstatus-promisesyncstatus)
as `remote.authFailed`. `remoteSyncAuth: 'signature'` sends no headers at all
and authenticates with the `Step-Tracker-Signature` header alone - the key
from `attestDevice()`, checked against the public key your server stored -
so no secret lives on the device. Without a key it does not upload, and
`syncAuthFailed` fires with `reason: 'no_key'`.

**Every request carries an `Idempotency-Key` header**: a SHA-256 hex digest of
the app's package name and each record's `date` and `steps`, sorted by date.
WorkManager retries a failed batch verbatim, and `syncNow()` can queue the
same pending rows the periodic job is about to send, so a server may see one
batch twice; two requests with the same key carry the same numbers and the
second can be treated as a retry. A day whose count has grown since — a
`historyBackfilled` recovery — produces a new key, because it is new content.
Distance, calories and the recovered share are derived from or a split of
the same count and do not change the key. Set the header yourself in
`remoteSyncHeaders` to override it.

`remoteSyncPayload: 'totals'` (default) sends the shape every earlier release
sent, byte for byte:

```json
{
  "source": "react-native-step-tracker-pro",
  "sentAt": 1757845200000,
  "records": [
    { "date": "2026-09-13", "steps": 11204, "distance": 7887.6, "calories": 336.4 }
  ]
}
```

`remoteSyncPayload: 'full'` keeps those fields and adds, per record, what a
server that never trusts a single number needs:

```json
{
  "source": "react-native-step-tracker-pro",
  "sentAt": 1757845200000,
  "records": [
    {
      "date": "2026-09-13",
      "steps": 11204,
      "distance": 7887.6,
      "calories": 336.4,
      "deviceSteps": 11204,
      "recoveredSteps": 200,
      "stepSource": {
        "date": "2026-09-13", "steps": 12000, "kind": "watch",
        "packageName": "com.fitbit.FitbitMobile", "appName": "Fitbit",
        "deviceSteps": 11204, "externalSteps": 12000, "usedExternal": true,
        "merged": false, "baselineSteps": 0, "manualStepsExcluded": 0
      },
      "sources": [
        { "packageName": "com.fitbit.FitbitMobile", "appName": "Fitbit", "kind": "watch",
          "steps": 12000, "distance": 8900, "calories": 0, "lastRecordAt": 1757800000000,
          "isSelf": false, "isWearable": true, "trustedWearable": true, "isPlatform": false,
          "manualSteps": 0, "unknownMethodSteps": 0,
          "recordingMethods": { "active": 0, "automatic": 12000, "manual": 0, "unknown": 0 } },
        { "packageName": "com.example.app", "appName": "com.example.app", "kind": "self",
          "steps": 11204, "isSelf": true, "isWearable": false, "trustedWearable": false, "..." : "..." }
      ]
    }
  ]
}
```

`steps` and `deviceSteps` are the same number — the stored row is always this
device's own count, whatever the policy showed the user; `stepSource` is what
it showed. With `fraudDetection.enabled`, each record also carries
`suspectSteps` and `integrity` — that day's [integrity report](#getintegrityreportdate-string-promiseintegrityreport)
without the device hints — and under `fraudDetection.mode: 'exclude'`
`steps`, `distance` and `calories` are sent with the suspect steps taken out
(in either shape) while `deviceSteps` stays the raw count. The
`Idempotency-Key` then follows the steps as sent.

**Signed uploads.** Once `attestDevice()` has made a key, every upload also
carries a `Step-Tracker-Signature` header:
`keyId=<hex>;alg=SHA256withECDSA;sig=<base64>`, a signature over the exact
request body bytes. Verify it with the key you stored for `keyId` before
reading the body. An install that never called `attestDevice()` uploads
exactly as before. `sources` is read from Health Connect at upload time and is `[]`
when reads are not permitted, the provider is missing or `stepSource` is
`'device'`; `stepSource` is then this device's. Each pending day costs one
bounded, cached Health Connect read under `'full'`; pending is normally the
handful of days since the last successful upload.

---

## Events

```ts
const sub = StepTracker.addListener('stepsChanged', (data) => console.log(data.steps));
sub.remove();

StepTracker.removeAllListeners('goalReached'); // every listener for one event
StepTracker.removeAllListeners();              // every listener, the hooks' included
```

On the new architecture events arrive through the module's codegen-typed
emitters (`onStepsChanged` and so on); on the old one through
`NativeEventEmitter`. `addListener` picks the right one, and the
subscription behaves the same either way.

`removeListener(event)` still works. `removeListener()` with no argument is
deprecated: it removes every listener in the app, the hooks' included, and
warns once. Use `removeAllListeners()` when that is really what you mean.

| Event | Payload |
|---|---|
| `stepsChanged` | `StepSnapshot`. Throttled by `eventThrottleMs` (default 500 ms). |
| `goalReached` | `{ type: 'daily' \| 'weekly' \| 'monthly', goal, steps, date, timestamp }`. Fires at most once per period for a goal - again if the goal is raised and the new one reached that period, never for a lower one (2.3.1). |
| `goalProgressChanged` | `{ type, goal, steps, progress, date }`. Fires when the whole-percent bucket changes. |
| `trackingStateChanged` | `{ state, source, reason }`. `reason` is `'started'`, `'boot'`, `'restored_sticky'`, `'restored_watchdog'`, `'restored_foreground'`, `'paused'`, `'no_sensor'`, etc. |
| `stepSourceChanged` | `ResolvedStepSource`. Fires when the app answering for the user's steps flips — a watch coming into range mid-morning, or a pin being changed. |
| `healthConnectStatusChanged` | `HealthConnectStatus`. Fires after a permission request, a revoke, and on every foreground where the status moved — which is how you notice the user granting or revoking from outside the app. |
| `dayChanged` | `{ previousDate, currentDate, previousDaySteps }`. Refetch your stats here. |
| `suspiciousActivity` | `{ date, flags, deviceSteps, suspectSteps, mode }`. The [integrity checks](#integrity-checks) found something they had not reported for this day before; `flags` holds only the new ones. Only with `fraudDetection.enabled`. |
| `motionWindow` | `MotionWindow`. One motion signature window was stored — see [`getMotionWindows`](#getmotionwindowsstartdate-enddate-promisemotionwindow). Features only. |
| `historyBackfilled` | `{ date, addedSteps, totalSteps, reason: 'gap' \| 'reboot' \| 'late' }`. A **past** day's stored total grew after the fact: gap recovery placed steps on it, or - `'late'` (2.3.7) - steps taken on it arrived after it had closed, held by the phone with the screen off across midnight; those are not recovered steps, and the day's integrity verdict is taken again with them. Once per affected day and write, after the write commits. Never fires under `gapRecovery: 'today'`, `'today_capped'` or `'drop'`. If you have already settled `date` — paid for it, uploaded it — this is the only signal that its number moved. |
| `syncAuthFailed` | `{ target: 'remote', status, reason: 'unauthorized' \| 'forbidden' \| 'no_key', auth }`. The endpoint refused the credentials, or signature auth had no key. Not retried: refresh with `updateConfig({ remoteSyncHeaders })` or call `attestDevice()`, then `syncNow()`. |
| `syncCompleted` | `{ target: 'health_connect' \| 'remote', syncedRecords, failedRecords, skippedRecords, success, error?, retryable? }`. `skippedRecords` counts days left to a wearable that already owns them. |
| `error` | `{ code, message }`. Emitted from the service, where there is no promise to reject. `E_SENSOR_UNAVAILABLE` means the sensor exists but registration failed and the service is retrying; `E_NO_SENSOR` means there is nothing to register. |

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
| `notificationTitle` / `notificationText` | built-in | tokens: `{steps}` `{distance}` `{unit}` `{calories}` `{percent}` `{goal}`; `{distance}` is in `notificationDistanceUnit` and `{unit}` is its label (2.4). The built-in words are string resources (2.3.7) your app can translate or reword by defining them in its own `res/values*/strings.xml`: `stp_notification_title` (plurals, "%s steps"), `stp_notification_goal` ("%s goal"), `stp_notification_text` and `stp_notification_text_miles`, `stp_unit_km`, `stp_unit_mi`, `stp_paused_text`, `stp_locked_text`, `stp_channel_name`, `stp_action_pause`, `stp_action_resume`, `stp_action_open`. Counts and distance use the phone's number format |
| `notificationDistanceUnit` | `'km'` | `'km'` \| `'mi'` \| `'auto'` - the notification's distance unit (2.4). `'auto'` shows miles on a phone set up for the United States, the United Kingdom, Liberia or Myanmar, kilometres elsewhere. Only the notification: every distance this package returns is in metres |
| `notificationIcon` | bundled | drawable name in your app. Found by name, so with `shrinkResources` keep it - see [INSTALLATION.md](INSTALLATION.md#5-custom-notification-icon-recommended); a name that is not found logs a warning and uses the default |
| `notificationChannelName` | "Step tracking" | shown in Android settings |
| `notificationActions` | `true` | Pause/Resume/Open buttons |
| `notificationLockScreen` | `'private'` | `'private'` hides the count behind "Counting steps" on a locked screen whose owner hides sensitive content - steps are health data; `'public'` always shows it, as before 2.3 |
| `notificationThrottleMs` | 1000 | minimum ms between notification redraws |
| `eventThrottleMs` | 500 | minimum ms between `stepsChanged` events |
| `persistEveryNSteps` | 10 | database flush cadence |
| `healthConnectEnabled` | `true` | master switch for both reads and writes |
| `healthConnectSyncIntervalMinutes` | 30 | clamped to WorkManager's 15-minute floor; 0 disables |
| `healthConnectReadEnabled` | `true` | set `false` to mirror your own count without ever reading; `READ_*` is then never requested |
| `healthConnectWriteEnabled` | `true` | set `false` to read a watch's data without adding a second copy of your own; `WRITE_*` is then never requested |
| `healthConnectBackgroundRead` | `false` | also request `READ_HEALTH_DATA_IN_BACKGROUND`, for reads with no activity on screen and tracking off - the tracking service is a foreground service, which Health Connect lets read. Without it those reads are not made; see [PERMISSIONS.md](PERMISSIONS.md#background-reads-6) |
| `healthConnectHistoryRead` | `false` | also request `READ_HEALTH_DATA_HISTORY`; required to read past 30 days |
| `healthConnectReadActiveCalories` | `false` | also read active calories per source (`StepSource.activeCalories`); one more permission to declare |
| `healthConnectReadTypes` | `['steps', 'distance', 'totalCalories']` | which record types reads cover, each one read permission to declare; `'steps'` is required. `['steps']` asks for `READ_STEPS` alone, and a day answered from Health Connect then derives distance and calories from the step count. Left at the default, a type the manifest does not declare is simply not read; a type you list yourself must be declared, or requests reject with `E_HEALTH_CONNECT_NOT_DECLARED`. See [PERMISSIONS.md](PERMISSIONS.md) |
| `healthConnectIgnoreManualEntries` | `false` | subtract steps the user typed in (`RECORDING_METHOD_MANUAL_ENTRY`) from every Health Connect source before a winner is picked; `manualStepsExcluded` reports how much — see [Manual entries](#manual-entries) |
| `stepSource` | `'auto'` | `'auto'` \| `'device'` \| `'wearable'` \| `'health_connect'` — see [Step sources](#step-sources-watches-and-other-apps) |
| `preferredStepSourcePackage` | — | pins one Health Connect origin as the truth |
| `wearableTrust` | `'metadata'` | `'metadata'` \| `'catalog'` — what earns a source the `'auto'` policy's whole-margin trust: the `Device` stamp on its records, or membership of the catalog / `wearableAllowlist` — see [What counts as a wearable](#what-counts-as-a-wearable-wearabletrust) |
| `wearableAllowlist` | `[]` | packages trusted as wearables under `'catalog'` on top of the built-in catalog |
| `privacyPolicyUrl` | — | **required before shipping health permissions**; Health Connect links to it and Play review checks for it |
| `remoteSyncUrl` | — | optional endpoint for unsynced days; must be `https://` |
| `remoteSyncHeaders` | `{}` | e.g. auth headers; sealed with a Keystore key at rest, never returned by `getConfig()` |
| `remoteSyncAuth` | `'headers'` | `'headers'` \| `'signature'` — `'signature'` sends no headers and authenticates with the device key from `attestDevice()` alone |
| `remoteSyncAllowHttp` | `false` | permit a plain `http://` endpoint, for a development server |
| `remoteSyncPayload` | `'totals'` | `'totals'` \| `'full'` — what each uploaded record carries; see [Remote sync](#remote-sync) |
| `autoStartOnBoot` | `true` | |
| `gapRecovery` | `'split'` | what to do with steps counted while the service was dead across midnight: `'split'` by time, `'today'`, `'today_capped'`, or `'drop'` — see [ARCHITECTURE.md](ARCHITECTURE.md#gap-recovery). An app that pays per step keeps `'split'`, the one policy that judges every step the tracker saw on the day it was taken; where a paid day must never change, `'drop'`. `'today'` and `'today_capped'` hand the current day, unjudged, what belonged to a closed day |
| `gapRecoveryMaxSteps` | 20000 | under `'today_capped'`, the most one recovery may credit to the active day - a late batch from a closed day counting as one (2.4); the rest is dropped |
| `watchdogEnabled` | `true` | 15-minute WorkManager job that restarts a killed service (needs the battery exemption on Android 12+) |
| `accelerometerFallback` | `true` | count over the accelerometer on phones with neither step sensor — see [ARCHITECTURE.md](ARCHITECTURE.md#the-accelerometer-fallback) |
| `accelerometerWakeLock` | `true` | hold a partial wake lock while sampling a non-wake-up accelerometer, so counting survives the screen going off |
| `accelerometerThreshold` | `0.9` | m/s² of linear acceleration that counts as a step; raise for vehicle false positives, lower for missed gentle walks |
| `motionSampling` | `{ enabled: false }` | `{ enabled?, windowSeconds?: 10, intervalMinutes?: 5 }` — periodic accelerometer windows reduced to features on device; see [`getMotionWindows`](#getmotionwindowsstartdate-enddate-promisemotionwindow). Patched key by key: a key left out keeps its value |
| `motionWindowRetention` | 288 | how many motion windows to keep; a day at five-minute intervals |
| `fraudDetection` | `{ enabled: false }` | `{ enabled?, mode?: 'flag' \| 'exclude', maxCadenceSpm?: 200, steadyCadenceMinutes?: 30, maxContinuousMinutes?: 180, maxDailySteps?: 50000, flagWhileCharging?: true, activityRecognition?: false }` — per-minute buckets, the event log and the fraud detector; `0` turns a threshold off. Patched key by key: `updateConfig({ fraudDetection: { mode: 'exclude' } })` changes the mode alone. See [Integrity checks](#integrity-checks) |

---

## Hooks

### `useStepTracker(options)`

```tsx
const {
  snapshot, state, ready, error,
  start, pause, resume, stop, refresh, requestPermissions,
} = useStepTracker({ dailyGoal: 10000, autoStart: true, onGoalReached: (e) => {} });
```

Initialises once, pushes later config changes through `updateConfig()`
(compared by value, so a new object literal on each render is not a change),
subscribes to `stepsChanged` and `trackingStateChanged`, and re-reads the
snapshot when the app returns to the foreground. Use it in the one component
that owns config; other screens read with `getTodaySteps()`, `useStepStats()`
or a `stepsChanged` listener. Extra options:
`autoStart` (default false), `refreshOnForeground` (default true), and
`onSuspiciousActivity`, called with each `suspiciousActivity` event — the
snapshot is re-read then too, since under `mode: 'exclude'` the number can
drop without a sensor sample to carry it.

### `useStepStats(period, options?)`

```tsx
const { stats, loading, error, reload } = useStepStats('week');
```

`period` is `'week' | 'month' | 'year'`. Only the newest request's answer is
shown, so overlapping loads cannot put an old window on screen. Reloads on
`dayChanged`, on `historyBackfilled` (2.3.7) - a past day that grew after the fact,
whatever the window - and when the
app comes back to the foreground, and - for a window that includes today -
at most every 30 seconds (`STATS_LIVE_REFRESH_MS`) while steps come in, so
today's bar moves while walking (2.3). Those refreshes leave `loading` alone;
it covers the first load and `reload()`.

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
