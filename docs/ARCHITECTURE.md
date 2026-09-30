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
 └───────▲──────┘ └─────▲──────┘ └─────────────┘ └───────────────┘
         │ onObserved   │
         │       ┌──────┴────────────┐   ┌───────────────────┐
         │       │ IntegrityMonitor  │◄──│ IntegritySignals  │ charging, clock,
         │       │ timeline, detector│   │ (in the service)  │ Activity Recognition
         │       └───────────────────┘   └───────────────────┘
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
from `lastEventAt`, the time of the last reading, and the time of the sample
that closes the gap - its own timestamp when it has a usable one, so a batch
delivered after midnight ends the gap when its sample was taken, not when it
arrived:

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
other three leave closed days alone. That suits an app that has already
paid for a day, but `'today'` and `'today_capped'` pay for it by handing the
active day, unjudged, steps that belong to a closed one - an overnight gap's
earlier share, a late batch - so an app that pays per step is better served
by `'split'`, which judges every step the tracker saw on its own day, or by
`'drop'`, which credits nothing it cannot place on the active day.
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

The first sample *taken* after midnight runs it, and so does
`StepTrackerCore.rollDayIfDue()` — from the service's once-a-minute heartbeat,
from the date-change, clock, zone and screen-on broadcasts the service
listens for while it runs, and before every read of a day — which then sends
`stepsChanged` for the new day. Before 2.3.6, waiting for a sample alone
left a phone lying still across midnight on yesterday: the notification kept
yesterday's total, `getStepsForDate(today)` and `getCurrentStepSource()`
answered with yesterday's date and count, and `dayChanged`, the closing
day's final save and its verdict all waited for the first step of the
morning. The heartbeat alone was not enough either: it does not tick while
the CPU sleeps, so the screen coming on could show yesterday for up to a
minute.

Closing a day - its final save, its final verdict, `dayChanged` - runs on the
write lane, and a read of a past day waits for the latest close to land
first: `getYesterdaySteps()` or a signed snapshot of yesterday at the first
open of the morning would otherwise read the stored row before the last few
uncommitted steps reached it. Retention and the Health Connect pass follow
the close without holding reads up. The close marks its own coroutine
context, so the reads it makes itself never wait for it.

Zero report latency does not make samples punctual. It makes the hub hand over
each change as it happens *while the CPU is awake*. With the screen off and the
CPU asleep, the default step counter — a non-wake-up sensor — keeps its
samples in the hub's FIFO and delivers them in one batch when something next
wakes the CPU, often minutes later. Each keeps its own timestamp, and the
service reads up to 30 minutes of that lateness back into the time the step was
taken (`SensorEventTime`), so the minute buckets place the walk where it
happened. The day each sample counts towards is the day it was taken on, too:

- A sample taken before midnight does not close the day it belongs to. Its
  steps count on that day, and the first sample taken after midnight closes
  it - with the late steps in it and their minutes judged by its final
  verdict.
- If the heartbeat, a broadcast or a read closed the day first, a sample taken
  on it is kept out of the new day and follows `gapRecovery`, like every
  step that belongs to a closed day. Under `'split'` it is owed to its own
  day: the debt is written with the counter state that left it out of the new
  day, in one atomic edit, and the core adds the batch to the day a moment
  later - one write, one `historyBackfilled` with `reason: 'late'`, one new
  verdict, not one per sample. The credit waits for the day's own close and
  holds a lock while it writes and judges, so two verdicts for one day are
  never written at once; a read of the day adds whatever is still waiting
  first, and a write that fails leaves its steps owed for the next credit.
  Those steps were watched being taken, so they do not grow the day's
  recovered share. `'today'` and `'today_capped'`,
  which never change a closed day, give them to the new day in one go as
  recovered steps - under `'today_capped'` within `gapRecoveryMaxSteps`, the
  batch counting as one recovery - and `'drop'` discards them.

The day used to be chosen by arrival. Once 2.3.6 kept the minutes' real
times, a late batch's steps counted on the new day while their minutes sat on
the old one, and neither day's verdict judged them: a shaker run up to
midnight on a sleeping phone passed half an hour of steps to the next day
unflagged. Untimed samples still count on the day they arrive.

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

### Motion signature windows

Opt-in (`motionSampling.enabled`). A tick on the sensor thread fires every
`intervalMinutes`; when tracking is running, not paused, steps have accrued
since the last window, and the app is either in the foreground (an activity
on screen — the foreground service alone does not count) or holds the
battery exemption, the service opens a `MotionWindowSampler` for
`windowSeconds`. On a phone already counting over the accelerometer the
sampler listens in on the samples that are arriving; otherwise the
accelerometer is registered for the window and released with it, and on a
non-wake-up sensor a partial wake lock with a timeout is held for the same
span under the same `accelerometerWakeLock` policy as the pedometer. The
sample that fills the window closes it; a safety post closes one the sensor
stopped delivering into.

`MotionWindowSampler` keeps only magnitudes and timestamps, at most a
minute's worth, and reduces them to `MotionFeatures`: a dominant frequency
by direct Fourier evaluation at the samples' own timestamps between 0.5 and
6 Hz (so delivery rate and batching do not matter), the variance of the
mean-removed magnitude, the zero-crossing rate, the share of in-band energy
at the peak, and the steps the engine counted meanwhile. Below a stillness
floor every spectral figure is 0, so a phone on a table does not report a
frequency picked out of noise. The samples are discarded once the features
are taken; nothing else ever holds them. Features go through the write lane
into `motion_window`, trimmed to `motionWindowRetention` in the same
transaction, and out on `motionWindow`. JVM tests feed synthetic walking at
1.8 Hz and shaking at 4 Hz and assert the two separate on frequency,
variance, crossing rate and purity, at 20 and 50 Hz alike.

### Integrity checks

Opt-in (`fraudDetection.enabled`). Four parts, each small, and none of them
ever changes a count.

**Minute buckets.** The engine reports every delta it *watched* — never one
gap recovery credited in one go, never a paused one — through
`onObserved(from, to, steps, timed)`, where `from` and `to` are the previous
and current samples' event times. An event time is when the step was taken,
not when its sample arrived: a batch the hub held while the CPU slept arrives
minutes late, and `SensorEventTime` reads each sample's own timestamp back — on
the elapsed-realtime clock, or on the wall clock for the HALs that stamp that
— up to 30 minutes old. A timestamp that fits neither is replaced with the
arrival time and the sample is reported `timed = false`. Stamping those with
the arrival as if it were known, which is what happened to anything over a
minute old before 2.3.6, put a whole screen-off walk into the minute its batch
arrived in: hundreds of honest steps in one minute, and a strong `cadence`
flag on them. `MinuteAttribution` places each delta: an untimed one is parked
as `untimedSteps` at its arrival minute, however few its steps; a delta after
up to a minute of silence is split across the minutes it overlaps, in
proportion, with cumulative rounding so the shares always sum back; a few
steps after a longer silence are a walk starting and belong to the arrival
minute; more than 20 after a longer silence is a lump — a hub FIFO that
overflowed with the screen off, or the first sample after a dead process —
and is parked as `untimedSteps` at the arrival minute. A lump is never spread
across its gap: that would fabricate a perfectly steady cadence for the
detector to find. Each share is tagged with whether the phone was charging
and what Activity Recognition last said. `StepTimeline` holds the increments
in memory, bounded, until the write lane drains them into `step_minute` on
the next commit, where they are added to what each minute already holds.

**The detector.** `FraudDetector.evaluate(minutes, windows, rules, zone)` is
pure and JVM-tested with synthetic days. It flags minutes over the cadence
cap; unbroken runs of active minutes (40+ timed steps, nothing untimed) whose
count never moves by more than one step minute to minute for
`steadyCadenceMinutes`; the excess of runs past `maxContinuousMinutes`;
charging and vehicle tags grouped into runs; long walks starting in the small
hours; and motion windows that read as a hand shake (3.3 Hz or faster,
variance over 20, `peakRatio` at least 0.25 — the purity floor keeps a
runner's harmonics out) or a swing (a near-pure tone between 0.8 and 2.6 Hz).
`peakRatio` tops out near 0.5 for a pure tone at the sampler's 0.05 Hz bins
over a 10-second window, and a smooth synthetic walk reads 0.46, so `swing`
is weak — evidence, not a verdict. The union of the strong flags' minutes,
each counted once, is the day's `flaggedSteps`; `suspectSteps` adds whatever
the rest of the device count exceeds `maxDailySteps` by, computed at read
time so a backfill that grows a day is judged against its new total.

**Evaluation.** `IntegrityMonitor` re-judges today at most once a minute —
from the sensor path while steps arrive and from the service heartbeat after
they stop — on the write lane, after flushing, so a verdict always sees every
minute flushed before it. The verdict is stored in `integrity_day`, replaced
whole, and any flag not in the previous verdict is announced on
`suspiciousActivity`, so an event fires once per flag across process
restarts. A closing day gets a final verdict at rollover. Today's flagged
total is also held in memory for the paths that cannot suspend — the sensor
callback and the notification.

**Exclude mode.** `mode: 'exclude'` takes `suspectSteps` out of this
device's `DayTotals` *before* the resolver sees them, in `resolveDay`,
`resolveFromCache` and `resolvedStats`, and in what goes to Health Connect
and the remote endpoint. Doing it at the resolver's input rather than its
output keeps every policy, pin and continuity baseline working on one
consistent device count; a watch can still win the day. A change of mode
clears the continuity baseline, like a policy change. The engine's own count,
`step_history` and `deviceSteps` stay raw.

**Signals and the log.** While the service runs with the checks on,
`IntegritySignals` holds dynamic, unexported receivers for power
connected/disconnected (the sticky battery broadcast seeds the state), clock
and zone changes (the jump is measured as the boot id's move, since a clock
edit moves the wall clock and not uptime), and — when the host app ships
`play-services-location` — Activity Recognition transitions through an
explicit mutable `PendingIntent` to an unexported receiver. Play Services is
compile-only: `ActivityRecognitionBridge` probes for the class before any
reference to it runs, and consumer ProGuard rules keep R8's missing-class
check quiet for apps without it. Reboots are logged by `BootReceiver`,
resets, clears and config changes by the core, sensor changes and recoveries
by the service. The log lives in `integrity_event`, bounded to 2,000 rows and
to retention, and survives `clearHistory()`.

**Attestation and signing.** `DeviceAttestation` keeps one EC P-256 key in
the Android Keystore under a fixed alias. `attestDevice(challenge)` replaces
it with one generated with `setAttestationChallenge`, falling back to an
unattested key on hardware that refuses, and returns the certificate chain
for a server to verify. A signed snapshot is serialised once to JSON, signed
with `SHA256withECDSA`, and returned with that exact text as
`signedPayload`, so the server verifies bytes it did not have to rebuild.
Asked for with `include`, the day's minute buckets, motion windows and raw
Health Connect records go into that same text before it is signed, so the
evidence a server scores carries the signature the totals do, and a Play
Integrity token bound to `payloadSha256` covers it too.
Remote uploads are signed over the exact body bytes once a key exists.

## Storage

Six Room tables:

- `step_history` — `id`, `date` (unique), `steps`, `distance`, `calories`,
  `synced`, `createdAt`, `updatedAt`. Hot write path.
- `daily_summary` — `date` (PK), `totalSteps`, `totalDistance`,
  `totalCalories`, `recoveredSteps`, `updatedAt`. Rolled-up mirror of the
  same values, written in the same transaction as `step_history`, plus the
  recovered share (schema version 3; earlier rows migrate with `0`). Reads
  join it back onto the history row.
- `motion_window` — `id`, `date`, `startedAt` (indexed), `durationMs`,
  `sampleCount`, `dominantFrequencyHz`, `variance`, `zeroCrossingRate`,
  `peakRatio`, `stepsDuringWindow` (schema version 4). Bounded to
  `motionWindowRetention` rows by the insert transaction.
- `step_minute` — `minuteStart` (PK), `date` (indexed), `steps`,
  `untimedSteps`, `chargingSteps`, `stillSteps`, `vehicleSteps` (schema
  version 5). Only minutes with steps; increments are added to a row, never
  replace it, in a read-then-write transaction because API 26's SQLite
  predates `ON CONFLICT DO UPDATE`. Pruned at `historyRetentionDays`.
- `integrity_day` — `date` (PK), `flaggedSteps`, `flagsJson`, `evaluatedAt`
  (schema version 5). The detector's last verdict per day, replaced whole.
- `integrity_event` — `id`, `at` (indexed), `date`, `type`, `detailJson`
  (schema version 5). Bounded to 2,000 rows and to retention; kept by
  `clearHistory()`.

`MIGRATION_4_5` creates the three integrity tables and touches nothing else;
they start empty rather than being back-filled from history nobody timed.

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
history, never the live count. Steps owed to a closed day by late samples
live there too, in the same edit as the counter state that left them out of
the active day, until the core has added them; the core takes them with a
synchronous commit before its write, so a process death loses at most that
write and never adds them twice.

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
be alive; the same beat ends the day at midnight when no step does, as do
the date-change, clock, zone and screen-on broadcasts. Every non-user start
is recorded (`recoveryCount`, `lastRecoveryReason`) so an app can decide when
to show the OEM guidance.

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
`suspiciousActivity` fires once per flag, keyed on the flag's type and start,
compared against the day's stored verdict.

The module hands each event to a generated `emitOn…` method. On the new
architecture codegen generates them from the spec's `EventEmitter`
properties, and they reach JS through the TurboModule's typed emitter; JS
subscribes with `NativeStepTrackerPro.onStepsChanged(handler)` and so on. On
the old architecture there is no typed emitter, so the old-architecture spec
shim implements the same thirteen methods over `RCTDeviceEventEmitter` under
the `StepTrackerPro:` prefix, and JS falls back to `NativeEventEmitter`. The
module's code is identical on both; `StepTracker.addListener` picks the path
by whether the typed emitter exists, and keeps its own registry of
subscriptions so `removeAllListeners()` works on either.

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

The jobs follow config, and `stopTracking()` cancels them. The service
schedules them when tracking starts, and a config change reschedules them -
except after that stop: `StepStateStore.stoppedByUser` records it, and until
`startTracking()` clears it, `initialize()` and `updateConfig()` leave the
jobs cancelled. Before 2.3.6 they scheduled them on every launch, which
brought back what a stop had cancelled; 2.3.6 then scheduled them only while
tracking was on, which left a tracker that never started - an app that only
reads Health Connect, a user who never allowed activity recognition -
without its hourly upload.

Today's row is written to Health Connect on every pass but left `synced = 0`,
because it is still moving. Closed days are marked synced once written — each
only while it still holds the count the pass read. A backfill can land on a
day while a pass is in flight — the first sample of the morning on a phone
whose OEM killed the service overnight, backfilling yesterday — and it
re-queues the day; marking it done over that would keep the new steps out for
good. Remote uploads mark their days the same way.

A day a wearable owns is skipped and marked done rather than written. Skipping
is the point — Health Connect keeps origins separate, so writing this phone's
parallel count of the same walk leaves every other reader with two copies of it.
Marking it done rather than leaving it queued matters too: nothing about that day
will ever make it writable, so a pending row would be retried for as long as it
stays in retention.

Remote uploads classify the response: 2xx marks the rows uploaded — those
still holding the count the upload read; 401 and 403 raise `syncAuthFailed`
and end the attempt without a retry, because the same credentials would be
refused again; anything else, or no response, is
retried with backoff. Every outcome is also written to `StepStateStore`, filed
under the moment the attempt read its credentials, because the worker
usually runs with no JS to hear the event. `getSyncStatus()` reads it back;
`authFailed` holds until the URL, headers, auth mode or key change after that
moment, or an upload is accepted. `remoteSyncHeaders` live sealed in SharedPreferences -
AES-GCM with a Keystore key (`SecretVault`) - and are opened when config is
read. Under `remoteSyncAuth: 'signature'` none are sent and the request
authenticates with the `Step-Tracker-Signature` header alone.

## Build

`android/build.gradle` adapts to the app it is built into rather than
assuming one toolchain:

- **React Native version.** Read from the nearest
  `node_modules/react-native/package.json` above the Gradle root, which finds
  it in a plain app, a hoisted monorepo and this repository alike. From 0.82
  the new architecture is the only one, so the new-architecture sources and
  codegen are used whatever `newArchEnabled` says; below 0.82 the property
  decides. With neither known - no `react-native` package where it is looked
  for, and no property - the new architecture is assumed, since it has been
  the default since 0.76 and guessing old on 0.82+ builds code that release
  cannot run.
- **Kotlin mode.** On AGP 9 with `android.builtInKotlin` not set to `false`,
  AGP compiles Kotlin itself and refuses the Kotlin Android and Kotlin kapt
  plugins. The script then applies `com.android.legacy-kapt` instead, and -
  because that plugin lives in `com.android.tools.build:gradle-kotlin`, which
  React Native apps do not put on the classpath - adds that artifact to its
  own buildscript classpath at the host's AGP version, found by reflection
  (the buildscript block is compiled before that classpath exists) through
  whichever class loader sees it: the script's own, the root buildscript's,
  or the thread's. On AGP 8, or with built-in Kotlin off, it is
  the Kotlin Android plugin and its kapt, as in 1.x. The architecture shim
  directories are registered as Kotlin sources as well as Java ones, because
  built-in Kotlin compiles only `kotlin` source directories and the defaults.
- **Compiler options** go through `kotlin { compilerOptions { } }`, which
  works from Kotlin 2.0 under both modes. Room needs real JVM default methods
  in the DAO interfaces: Kotlin 2.2's typed `jvmDefault` option where it
  exists, the `-Xjvm-default=all` flag before it.
- **Health Connect client** follows `compileSdkVersion`: 1.1.0 from 36, the
  1.1.0-beta01 that builds against 35 below.
- **minSdk** is declared as at least 26 whatever the app says, so an app
  below it fails the manifest merge naming this package.
- **`BuildConfig.LIBRARY_VERSION`** is the npm version from `package.json`,
  compiled in for the verification snapshot.
- **Standalone builds.** Only when the module is the root of the build - CI
  and development - does its buildscript add AGP and the Kotlin plugin; inside
  an app it adds nothing of its own, so it never puts a second AGP next to
  the host's.

The combinations this is checked against are in the README's compatibility
table, built on every push by the `compat` CI job.

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
retried next time. A read that fails is treated the same way: it is reported
as `'failed'` and not cached. A raw read that fails falls back to the
aggregate API, and when that fails too the whole read fails - it used to
come back empty, which was cached for up to ten minutes and signed into
snapshots as `'read'`, a day with no other apps. Health Connect also refuses
reads from an app in the background unless it holds
`READ_HEALTH_DATA_IN_BACKGROUND`. It asks AppOps whether the app is in the
foreground, and AppOps' foreground reaches as far as a foreground service: an
activity on screen, or the tracking service running. So while tracking is on
every read is allowed with the app closed; with tracking off and without the
grant, a read in the background is not tried at all and reports
`'not_consulted'` - the sync worker, a background snapshot or `full` upload.
The raw reads leave the rule to Health Connect and pass its refusal on as
`E_HEALTH_CONNECT_DENIED`, so they are never refused a read it would allow.
2.3.7 took the tracking service for the background, and skipped reads Health
Connect would have answered. Granted permissions
are cached for five seconds between the explicit status checks, so the
sensor path's refresh does not cost an IPC per minute.

Goals follow the displayed number. Under `auto` it is monotonic for the day,
and `GoalTracker` fires each goal at most once per period regardless.

## Threading

- Sensor callbacks arrive on the sensor thread. Engine mutation is
  `@Synchronized`.
- Database and Health Connect work runs on `Dispatchers.IO` from the core's
  `SupervisorJob` scope, so one failed write cannot cancel the pipeline.
- Bridge conversion happens on whichever thread resolves the promise; the module
  never touches the engine off-lock.
