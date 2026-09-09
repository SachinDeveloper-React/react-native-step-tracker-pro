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

Once a real reboot is established:

- **Boot happened today.** The steps taken between boot and the first sample are
  real and unclaimed, so `anchorValue = 0` and they are picked up.
- **Boot happened on a previous day.** Those steps straddle midnight and cannot
  be attributed, so `anchorValue = rawValue` and they are dropped. Losing a few
  steps beats moving yesterday's steps into today.

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
prunes past the retention window, pushes the closed day into Health Connect, and
re-pins with `anchorSteps = 0`.

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

## Storage

Two Room tables:

- `step_history` — `id`, `date` (unique), `steps`, `distance`, `calories`,
  `synced`, `createdAt`, `updatedAt`. Hot write path.
- `daily_summary` — `date` (PK), `totalSteps`, `totalDistance`,
  `totalCalories`, `updatedAt`. Rolled-up mirror of the same values, written in
  the same transaction as `step_history`.

`saveDay()` refuses to lower an existing day's count. A re-anchored counter can
briefly report fewer steps than were already committed; ignoring that write is
cheaper than reasoning about it later. Deliberate writes that must be allowed to
lower a day — `resetToday()` — go through `overwriteDay()` instead, otherwise
the old total would survive the reset and history would disagree with the live
counter permanently.

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
| `HealthConnectSyncWorker` | none | `healthConnectSyncIntervalMinutes`, floor 15 |
| `RemoteSyncWorker` | network connected | 1 hour, exponential backoff |
| `RetentionWorker` | none | daily |

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

Two read paths exist because they have different budgets:

| Path | Used by | Cost |
|---|---|---|
| `resolveDay` / `resolvedStats` | promise-returning reads | a real Health Connect query, cached 30 s for today and 10 min for past days |
| `resolveFromCache` | sensor callback, notification | cache only, no IPC, falls back to device-only |

The sensor callback fires several times a second during a walk and cannot
suspend, so it cannot query. Serving it the last known origin split is what
keeps the live `stepsChanged` stream, the notification and `getTodaySteps()`
reporting the same number instead of disagreeing with each other.

Goals deliberately stay on this device's own count. A wearable's total arrives
in jumps whenever its companion app syncs and can move backwards between them,
so firing `goalReached` off it would trigger twice for one day.

## Threading

- Sensor callbacks arrive on the sensor thread. Engine mutation is
  `@Synchronized`.
- Database and Health Connect work runs on `Dispatchers.IO` from the core's
  `SupervisorJob` scope, so one failed write cannot cancel the pipeline.
- Bridge conversion happens on whichever thread resolves the promise; the module
  never touches the engine off-lock.
