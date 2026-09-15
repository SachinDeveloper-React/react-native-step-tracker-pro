# Architecture

```
                       ┌──────────────────────────┐
   JS  ────────────────│  StepTracker.ts (typed)  │
                       └────────────┬─────────────┘
                                    │ Turbo Module
                       ┌────────────▼─────────────┐
                       │  StepTrackerProModule    │  thin: validate, delegate,
                       └────────────┬─────────────┘  convert
                                    │
     ┌──────────────────────────────▼───────────────────────────────┐
     │                      StepTrackerCore                         │
     │        (process-wide singleton: config, engine, goals)       │
     └───┬──────────────┬──────────────┬───────────────┬────────────┘
         │              │              │               │
 ┌───────▼──────┐ ┌─────▼──────┐ ┌─────▼───────┐ ┌─────▼─────────┐
 │ StepCounter  │ │StepRepo/   │ │HealthConnect│ │  StepEventBus │
 │   Engine     │ │  Room      │ │   Manager   │ │               │
 └───────▲──────┘ └────────────┘ └─────────────┘ └───────────────┘
         │
 ┌───────┴────────────────┐        ┌──────────────┐
 │  StepTrackerService    │◄───────│ BootReceiver │
 │  (foreground, health)  │        └──────────────┘
 └────────────────────────┘
```

The module is the only piece that dies with JS. Everything below it keeps
running, which is why the module holds no state of its own.

## Why a singleton

The service and the React module live in the same process. Sharing one
`StepTrackerCore` means the counter cannot fork: a `getTodaySteps()` call from
JS reads the exact same engine the sensor callback just wrote to. Anything
routed through IPC or broadcasts would drift.

## The counting maths

`TYPE_STEP_COUNTER` reports steps since the device booted. The day total is:

```
stepsToday = anchorSteps + (rawValue − anchorValue)
```

`anchorValue` is the raw reading that corresponded to `anchorSteps`. The anchor
is re-pinned whenever the meaning of `rawValue` changes.

### Reboot

The counter restarts at zero. Two signals have to agree before the steps since
boot are claimed:

1. The approximate boot timestamp — `System.currentTimeMillis() −
   SystemClock.elapsedRealtime()` — moved by more than 60 seconds.
2. The reading itself went backwards, below `lastRawValue`.

The boot timestamp on its own is not enough, because it is derived from the wall
clock: a manual clock change, or an NTP correction on a phone whose RTC was
wrong at boot, moves it by hours without the counter restarting. Acting on that
alone would add the day's steps to themselves.

Once a real reboot is established, the steps taken between boot and the first
sample are real and unclaimed. If the boot was today they all go to today. If
it was on a previous day they are spread from the boot instant to now by the
gap-recovery rule below.

A device that has **never** produced a reading — a fresh install — is the
exception: with no evidence the app existed when the since-boot steps were
taken, it claims them only when the boot was today, and never invents history
for earlier days.

### Gap recovery

The anchor is also blanked without a reboot: by the midnight rollover, by
`resetToday()`, and by a boot-timestamp move that turns out to be a wall-clock
correction. In all of those the counter did *not* restart, so
`rawValue − lastRawValue` is exactly the number of steps taken since the last
reading — including every step the hardware counted while an OEM task killer
had the process dead overnight. They are claimed, not dropped.

Where they land depends on whether the gap crossed midnight, which is known
from `lastEventAt`, the time of the last reading:

| Gap | `gapRecovery` | Result |
|---|---|---|
| inside one day | any | all to today |
| across midnight | `'split'` (default) | spread across the days in proportion to time: killed at 23:00, revived at 09:00 → 10% to yesterday, 90% to today |
| across midnight | `'today'` | all to today |
| across midnight | `'today_capped'` | all to today, up to `gapRecoveryMaxSteps` (20,000); the rest is dropped, not moved |
| across midnight | `'drop'` | discarded — for an app where an over-credit costs money |

The policy is applied by `StepGapSplitter.apply()`, which is pure and
tested on the JVM for all four; the engine only supplies the dates and the
cap. `'today_capped'` exists for the week-long kill: the first sample back
carries every step since the last reading, and a counter glitch on top of
that could hand one day a hundred thousand steps. The cap applies to what a
recovery credits, not to the day — counting continues past it — and it also
bounds the one case the splitter cannot place, a last reading that is in the
future because the clock was set back.

Shares for days other than the active one go out through
`StepCounterEngine.onBackfill` with a reason (`gap`, or `reboot` when the
counter provably restarted), and `StepTrackerCore` adds them to the stored
rows (re-queuing them for sync) and then emits `historyBackfilled` once per
day, after the write has committed. Only `'split'` ever produces one: the
other three leave closed days alone, which is why `'today_capped'` and
`'drop'` are the recommendation for an app that has already paid for a day.
The rollover deliberately preserves `lastEventAt`: stamping "now" there
erased the evidence of when the gap started and put every overnight step
into the new day.

### The recovered share

Every step a recovery credits — today's share of a gap, a reboot's since-boot
steps, an install's since-boot claim — was apportioned, not observed. The
engine keeps a running `recoveredToday` in `StepStateStore`, written in the
same atomic edit as the counter state so the two cannot drift, zeroed with
the total at rollover and reset, and restored with it when the date moves
backwards. It is committed to `daily_summary.recoveredSteps` with every
write; `StepRepository.addToDay()` grows it by exactly what it added to a
past day, and `upsertKeepingRecovered` refuses to let a stale snapshot of the
live day lower it under a backfill. `DayRecord.recoveredSteps` and
`StepSnapshot.recoveredSteps` report it, and the verification snapshot and
the `'full'` remote payload carry it, so a server can weigh a recovered share
differently from steps it knows were watched. Days stored before the column
existed read `0`: the only honest value for a day whose split was never
recorded.

Before 1.3 these steps were dropped, which is what "I walked to work and the
app shows zero" looked like on phones that kill services overnight.

### Sensor jitter

Some HALs report a reading a step or two *below* the previous one with no
restart behind it — float rounding, or a hub re-ordering a batch. Treating
that as a restart re-pinned the anchor at the lower reading, and the next
sample added the dip back; a day of such wobbles crept the total upward. A
backwards move of at most `JITTER_TOLERANCE_STEPS` (3) is now ignored
outright and the higher reading kept. A real restart drops to near zero and
is still caught by the rule below; uptime going backwards is still a reboot
whatever the reading did.

### Counter reset without a reboot

Some OEM sensor HALs restart the counter when the last listener unregisters.
Detected as `rawValue` dropping below `lastRawValue`, the previous reading; the
anchor is re-pinned at the current reading with `anchorSteps = stepsToday`, so
the running total is preserved and nothing is double counted.

Comparing against `anchorValue` instead would miss it entirely for the whole of
any day the device booted on, because the anchor is `0` for that day and no
valid reading is below zero.

### Timezone moving backwards

`rollDateIfNeeded()` fires on any change of the local date key, in either
direction. Travelling west across the date line moves it backwards: the day
being left is persisted but is not finished, and the day being adopted may
already have steps stored against it, so `StepTrackerCore` re-seeds the live
counter from the database instead of letting it report zero mid-day.

### Midnight

`rollDateIfNeeded()` finalises the previous day into Room, emits `dayChanged`,
prunes past the retention window, pushes the closed day into Health Connect,
clears the day's continuity baseline, and blanks the anchor so the next sample
goes through gap recovery — which is how the handful of steps between the last
sample before midnight and the first after it end up on the right sides.

Sensor batching is set to zero latency, so a sample delivered after midnight for
steps taken before it is not a practical concern. If you raise
`MAX_REPORT_LATENCY_US` to save battery, that trade-off comes back.

### Process death

Counter state is written to SharedPreferences on every sensor batch
(`StepStateStore.writeCounterState`, one atomic edit). If the OS kills the
service, `START_STICKY` restarts it, `reconcile()` reloads the anchor, and the
first sample recovers everything the hardware counted while the process was
gone. There is no window in which steps are lost — that is the whole reason for
using `TYPE_STEP_COUNTER` instead of `TYPE_STEP_DETECTOR`.

A restart with a null intent is the OS bringing the service back, not a user
action, so it restores rather than starts: `setPaused()` ignores a no-op
transition, and a pause the user set before the process died survives it. Arming
the resume re-anchor on every start would discard exactly the steps this
paragraph promises to recover.

### The detector fallback

`TYPE_STEP_DETECTOR` fires once per step and carries no cumulative value, so it
increments the total directly. On devices with no step counter, steps taken
while the process is dead are genuinely lost. `getDeviceCapabilities()` tells
you which sensor you are on.

### The accelerometer fallback

A phone with neither step sensor still has an accelerometer, and at the
bottom of the market that is not rare. `AccelerometerStepDetector` is a
software pedometer for those devices, used only when both hardware paths fail
and `accelerometerFallback` is on:

1. magnitude of the three axes, so orientation does not matter;
2. a slow low-pass (τ 0.5 s) tracks gravity, subtracting it leaves the gait;
3. a fast low-pass (τ 40 ms) knocks off sensor jitter without blunting a
   heel strike;
4. hysteresis peak detection — rising through `accelerometerThreshold`
   (0.9 m/s²) arms, falling below 30% of it fires — so every step fires once
   whatever the sample rate;
5. cadence gating — closer than 250 ms to the last step is bounce, further
   than 1.2 s ends the walk;
6. a four-step warm-up, so picking the phone up is not a walk, released in
   full once the walk is confirmed.

All of it is time-based, so 20 Hz on a budget phone, 50 Hz on a flagship and a
batched second of samples behave the same. Eighteen JVM tests feed it
synthetic gait in two shapes — smooth sinusoids and impulse-shaped heel
strikes with a rebound, the latter closer to a pocketed phone — as walks,
shuffles, runs, a still phone with heavy sensor noise, a car's 3–12 Hz
vibration and a bus rocking. It counts one per stride on the gait and nothing
on the rest. The 40 ms smoother was chosen on that matrix: 60 ms dropped
gentle strikes, 30 ms let a car's 5 Hz vibration through.

The service samples at 25 Hz with a one-second report latency (hubs with a
FIFO batch; hubs without ignore it), prefers the wake-up variant of the
sensor, and on the far more common non-wake-up one holds a partial wake lock
for as long as the listener is registered (`accelerometerWakeLock`). That
wake lock is the cost: a few percent of battery a day, which is why this is
a fallback and never a preference. Steps taken while the process is dead are
lost, as with the detector. Detected steps go through
`StepCounterEngine.onDetectorSample()` like any other per-step source.

## Storage

Two Room tables:

- `step_history` — `id`, `date` (unique), `steps`, `distance`, `calories`,
  `synced`, `createdAt`, `updatedAt`. Hot write path.
- `daily_summary` — `date` (PK), `totalSteps`, `totalDistance`,
  `totalCalories`, `recoveredSteps`, `updatedAt`. Rolled-up mirror of the
  same values, written in the same transaction as `step_history`, plus the
  recovered share (schema version 3; earlier rows migrate with `0`). Reads
  join it back onto the history row.

`saveDay()` refuses to lower an existing day's count. A re-anchored counter can
briefly report fewer steps than were already committed; ignoring that write is
cheaper than reasoning about it later. Deliberate writes that must be allowed to
lower a day — `resetToday()` — go through `overwriteDay()` instead, otherwise
the old total would survive the reset and history would disagree with the live
counter permanently. Backfilled gap steps go through `addToDay()`.

Every write to the day tables runs on one single-threaded dispatcher
(`writeLane`), in submission order. The engine can hand out a rollover and a
backfill for the same day within one sample, and two IO coroutines racing on
that row would let whichever ran second decide the total.

Health Connect and the remote endpoint track their progress in **separate**
columns (`synced` and `syncedRemote`). Sharing one flag let whichever sink ran
first hide the row from the other, so a day uploaded remotely would never reach
Health Connect.

Counter state deliberately lives in SharedPreferences, not Room: it is written
constantly and must be readable synchronously from `onStartCommand` before any
coroutine gets a chance to run. It also means a corrupt database costs you
history, never the live count.

Flush cadence is `persistEveryNSteps` (default 10) plus a forced flush on pause,
stop, task removal and destroy.

## Foreground service

Declared `android:foregroundServiceType="health"`, which on Android 14+ requires
`FOREGROUND_SERVICE_HEALTH` and is passed to `startForeground`. Below 34 the
two-argument overload is used, so no unknown type reaches an older platform.

`startForeground` is called first thing in `onStartCommand`, before anything
that can throw, or the OS raises `ForegroundServiceDidNotStartInTimeException`.

`stopWithTask="false"` keeps the service alive when the user swipes the app
away — that is the whole point. `onTaskRemoved` just flushes.

### Liveness and recovery

OEM skins kill the service and drop the `START_STICKY` restart. Nothing
about the count is lost — the hardware counter keeps going and gap recovery
reconciles it — but the notification, the events and the midnight rollover
stop until something brings the service back. Three things do:

| Path | Trigger | Constraint |
|---|---|---|
| foreground restart | `onHostResume`, `initialize()`, `getTrackingHealth()` | none: a foregrounded app may always start its service |
| `WatchdogWorker` | every 15 min | Android 12+ refuses a background foreground-service start without an exemption; the battery-optimisation exemption is one |
| `BootReceiver` | reboot / update | `autoStartOnBoot` |

A sensor that exists but refuses a listener — the sensor service is not ready
for a few seconds after boot on several OEMs, and some HALs reject a new
listener while tearing down an old one — is retried: at 2, 5, 15 and 30
seconds, then once a minute from the heartbeat for as long as the tracker is
meant to be running, with `E_SENSOR_UNAVAILABLE` on the `error` event
meanwhile. Only a device with no sensor at all is marked `unsupported`.
Marking a transient failure unsupported is what used to leave the tracker
idle until the user pressed start again.

"Dead" is decided from `shouldAutoStart` (started, never stopped — a paused
tracker counts, its notification is meant to be up) and the absence of a
`StepTrackerService` instance in this process. The service, the workers and
the React module share the process, so that flag is definitive: a SIGKILL
takes it with the process and only `onDestroy` clears it otherwise. The
service also stamps a heartbeat every 60 s from its sensor thread even when
the user is still, so `heartbeatAgeMs` says how long ago it was last known to
be alive. Every non-user start is recorded (`recoveryCount`,
`lastRecoveryReason`) so an app can decide when to show the OEM guidance.

Notification updates are throttled to `notificationThrottleMs` and skipped when
the step count has not changed, so a walk does not redraw the shade sixty times
a minute.

## Events

`StepEventBus` is a plain in-process pub/sub. The module subscribes on
construction and unsubscribes on `invalidate()`. Emission is a no-op when no
React instance is active — the service does not care whether anyone is
listening, it keeps writing to storage either way.

`stepsChanged` is throttled by `eventThrottleMs`. `goalProgressChanged` fires
only when the whole-percent bucket moves. `goalReached` is keyed on the period's
start date, so it fires once per day/week/month and rearms by itself.

## Sync

Three WorkManager jobs:

| Worker | Constraint | Period |
|---|---|---|
| `HealthConnectSyncWorker` | none; only when writes are enabled | `healthConnectSyncIntervalMinutes`, floor 15 |
| `RemoteSyncWorker` | network connected | 1 hour, exponential backoff |
| `RetentionWorker` | none | daily |
| `WatchdogWorker` | none; only while tracking | 15 minutes |

Health Connect sync has no network constraint because Health Connect is entirely
on-device. Offline sync is still sync. Only the optional `remoteSyncUrl` upload
waits for connectivity, and nothing else waits on it.

Today's row is written to Health Connect on every pass but left `synced = 0`,
because it is still moving. Closed days are marked synced once written.

A day a wearable owns is skipped and marked done rather than written. Skipping
is the point — Health Connect keeps origins separate, so writing this phone's
parallel count of the same walk leaves every other reader with two copies of it.
Marking it done rather than leaving it queued matters too: nothing about that day
will ever make it writable, so a pending row would be retried for as long as it
stays in retention.

## Step source resolution

The phone's sensor is one of several things counting the user's steps. A watch
counts the same walk independently and publishes it to Health Connect under its
own data origin, so the two overlap almost completely.

**Origins are never summed.** `StepSourceResolver` picks exactly one per day —
which one depends on the `stepSource` policy — and every read path goes through
it. Adding a phone's 7,800 to a watch's 8,000 would report 15,800 for a day the
user walked 8,000.

```
engine snapshot ─┐
                 ├─► StepSourceResolver ─► resolved DayTotals ─► JS / notification
Health Connect ──┘        (picks one)
origins by day
```

Classification comes from the `Device` the writing app stamped on its records,
which is the authoritative signal; `StepSourceCatalog` maps package names to
names and kinds for the companion apps that stamp nothing. An unrecognised
package is still a usable source, just typed `unknown`.

One origin is special-cased ahead of the `Device` stamp: Health Connect's own
on-device step count (Android 14, SDK extension 20+), attributed to `android`
before the June 2026 provider update and to a per-device, per-app synthetic
package `com.android.healthconnect.phone.<hash>` after it. It is this phone's
hardware counter under another name, so it is always `phone` and never a
wearable, whatever a record says. The hash cannot be hard-coded; the prefix is
what is matched.

Two read paths exist because they have different budgets:

| Path | Used by | Cost |
|---|---|---|
| `resolveDay` / `resolvedStats` | promise-returning reads | a real Health Connect query, cached 30 s for today and 10 min for past days |
| `resolveFromCache` | sensor callback, notification | cache only, no IPC, falls back to device-only |

The sensor callback fires several times a second during a walk and cannot
suspend, so it cannot query. Serving it the last known origin split is what
keeps the live `stepsChanged` stream, the notification and `getTodaySteps()`
reporting the same number instead of disagreeing with each other.

### Continuity under `auto`

The raw resolver's answer is right at the instant it is computed and wrong a
minute later: a watch publishes in batches, so once it is ahead the displayed
number would freeze at its last upload while the phone's own sensor kept
ticking underneath. `StepContinuity` fixes that by storing the external
*lead* rather than the external total:

```
read:   external 6,000, device 5,500  →  baseline = 500
then:   shown = device + 500          →  6,001, 6,005, 6,010 …
read:   external 7,000, device 6,500  →  lead 500 ≤ baseline, unchanged
read:   external 7,000, device 2,000  →  lead 5,000 > baseline, adopt it
```

The baseline only grows within a day, is keyed to the date, lives in
`StepStateStore` so it outlives the source cache and the process, and is
cleared at midnight, on `resetToday()`, on a policy or pin change and on a
revoke. The sensor path applies it from memory with no IPC; the promise path
observes it on every fresh read; and the sensor path asks for a fresh read
(throttled to once a minute, and only when the cache has expired) so a watch
syncing mid-walk reaches the notification without the app being opened —
given `READ_HEALTH_DATA_IN_BACKGROUND`.

What the lead may be depends on the source. A wearable, or a source the user
pinned, is trusted for its whole margin: a watch sees a walk the phone on the
desk did not. A phone-side origin reads the same phone, so for the hours this
device was covering the day it cannot legitimately have seen more; all it may
add is its steps from before coverage began. `StepStateStore.coverageStartAt`
records that instant — set only by a first-ever reading, i.e. an install, and
otherwise the start of the day, since with a hardware counter gap recovery
reaches back through any dead time to midnight — and
`HealthConnectManager.readDailyStepsBySource` splits each origin's records
around it into `StepSource.stepsBeforeCoverage`. On a past day, where
coverage is unknown, a phone-side origin is used only when this device has
nothing stored for it. On a device counting with the detector or the
accelerometer the rule is relaxed and a phone-side origin is trusted for its
whole margin: steps taken while the process was dead are gone on those
sensors, and Samsung Health, which runs as a system app and is not killed,
has genuinely seen more. This is what stops an aggregator that sums two origins
from doubling the display, and a phone-side algorithm that counts 5% high
from creeping the total up sync after sync.

What makes a source "a wearable" for that trust is the `wearableTrust`
rule, decided in one place (`StepSourceTrust`) so the resolver's choice and
the `trustedWearable` flag on each source cannot disagree. `metadata`, the
default, goes by the `Device` stamp; `catalog` requires the package to be in
`StepSourceCatalog.KNOWN` with a wearable kind or on the app's
`wearableAllowlist`, and treats everything else as phone-side for the
coverage rule while leaving its display `kind` alone. With
`healthConnectIgnoreManualEntries` on, each source competes on its counted
steps only: `StepSource.excludingManual()` takes the manual-entry bucket out
of the total and out of the pre-coverage share, and the resolution reports
what it removed as `manualStepsExcluded`.

Only `auto` merges. `wearable` and `health_connect` promise the other app's
exact number and keep it. The phone's raw count is what is written to Health
Connect, never the merged one, so other readers never see a blend.

Every Health Connect read is bounded by `HC_READ_TIMEOUT_MS` (4 s). A
provider that is migrating or being updated can block far longer; past the
bound the phone's own count is the answer, nothing is cached, and the read is
retried next time. Granted permissions are cached for five seconds between
the explicit status checks, so the sensor path's refresh does not cost an
IPC per minute.

Goals follow the displayed number. Under `auto` it is monotonic for the day,
and `GoalTracker` fires each goal at most once per period regardless.

## Threading

- Sensor callbacks arrive on the sensor thread. Engine mutation is
  `@Synchronized`.
- Database and Health Connect work runs on `Dispatchers.IO` from the core's
  `SupervisorJob` scope, so one failed write cannot cancel the pipeline.
- Bridge conversion happens on whichever thread resolves the promise; the module
  never touches the engine off-lock.
