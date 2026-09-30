# Usage modes

The package can count steps three ways. Pick one deliberately — it decides
which permissions you request, which Play Console forms you fill in, and what
`getTodaySteps()` means.

| Mode | Counts with | Works with app closed | Needs Health Connect | Play forms |
|---|---|---|---|---|
| [A. Native sensor only](#mode-a-native-sensor-only) | phone's hardware step counter, foreground service | yes | no | foreground service |
| [B. Health Connect only](#mode-b-health-connect-only) | whatever other apps / a watch wrote to Health Connect | n/a — nothing runs | yes | health data |
| [C. Both (recommended)](#mode-c-both-recommended) | phone sensor **and** Health Connect, merged live | yes | optional at runtime | both |

Mode C is what Google Fit and Samsung Health do: count on the phone, take a
watch's number when it saw more, and keep the number moving between watch
syncs. It degrades to Mode A on a phone with no Health Connect; on a phone
with no step sensor it counts over the accelerometer, and with that fallback
disabled it behaves as Mode B.

---

## Mode A: Native sensor only

The phone's `TYPE_STEP_COUNTER` (or `TYPE_STEP_DETECTOR` fallback) read by a
foreground service. No Health Connect at all — no health permissions, no
privacy-policy activity, no health declaration form.

### Config

```ts
await StepTracker.initialize({
  height: 175,
  weight: 75,
  dailyGoal: 10000,
  stepSource: 'device',          // never read Health Connect
  healthConnectEnabled: false,   // never write it either, no sync worker
});
await StepTracker.requestPermissions();   // ACTIVITY_RECOGNITION (+ POST_NOTIFICATIONS on 13+)
await StepTracker.startTracking();
```

### Manifest — nothing to add

From 2.0 the library manifest merges in only what this mode needs - the
sensor, foreground-service, notification, boot and wake-lock permissions - so
there are no health permissions to strip and nothing for Play to question.

Two optional touches in `android/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Only with an eligible Play use case: lets requestDisableBatteryOptimization()
         show the direct dialog instead of the settings list. -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application>
        <!-- The rationale screen Health Connect links to. Harmless without
             Health Connect; remove it if you want no exported activity from here. -->
        <activity android:name="com.steptrackerpro.health.HealthPrivacyPolicyActivity" tools:node="remove" />
        <activity-alias android:name="com.steptrackerpro.health.ViewPermissionUsageActivity" tools:node="remove" />
    </application>
</manifest>
```

The battery prompt is recommended on low-end phones - see
[OEM_BATTERY.md](OEM_BATTERY.md). Without it the same call opens the
settings list.

### Permissions to request

| Permission | When | How |
|---|---|---|
| `ACTIVITY_RECOGNITION` | before `startTracking()` | `requestPermissions()` |
| `POST_NOTIFICATIONS` (Android 13+) | same call; optional | `requestPermissions()` |
| Battery exemption / OEM autostart | after tracking is on, on phones that need it | `requestBackgroundPermissions()` |

Full flow in [PERMISSIONS.md](PERMISSIONS.md).

### What you get

- `getTodaySteps()` / `stepsChanged` — the phone's count, live.
- `stepSource.kind` is always `'self'`.
- Steps counted while the process was dead are recovered from the hardware
  counter on restart (see [Gap recovery](ARCHITECTURE.md#gap-recovery)).
- On a phone with no step sensor — some entry-level Tecno, itel, Lava and
  Redmi A-series builds — the service falls back to a software pedometer over
  the accelerometer (`accelerometerFallback`, default true).
  `getDeviceCapabilities().bestSensor` says which sensor is in use. The
  fallback costs battery (the CPU stays awake to sample) and loses steps
  taken while the process is dead, because there is no hardware counter to
  reconcile from; see [ARCHITECTURE.md](ARCHITECTURE.md#the-accelerometer-fallback).
  With the fallback disabled, `supported` is false and `startTracking()`
  rejects with `E_NO_SENSOR`.

### Play Console

- Foreground service declaration (`FOREGROUND_SERVICE_HEALTH`), with video.
- Data safety: fitness info, collected, not shared (unless `remoteSyncUrl`).
- No health apps declaration.

---

## Mode B: Health Connect only

No sensor, no foreground service, no notification. The app reads the steps
other apps — Samsung Health, Google Fit, a watch's companion app — have
written to Health Connect, and shows them. Use it when another app already
owns step counting for your users and you only present the number, or when
you want zero background footprint.

### Config

```ts
await StepTracker.initialize({
  stepSource: 'health_connect',        // the best external origin wins
  healthConnectEnabled: true,
  healthConnectReadEnabled: true,
  healthConnectWriteEnabled: false,    // never add our own records
  privacyPolicyUrl: 'https://example.com/privacy',   // required
});
// Never call startTracking().
const status = await StepTracker.enableHealthConnect();
if (status.canRead) {
  const today = await StepTracker.getTodaySteps();   // resolved from Health Connect
}
```

With `healthConnectWriteEnabled: false` the permission sheet asks only for the
three `READ_*` grants, and `status.granted` means "reads granted".

### Manifest — add the read set, remove the sensor

Add the Health Connect read permissions - the library no longer declares any
(see [PERMISSIONS.md](PERMISSIONS.md#what-the-library-declares-and-what-you-add)) -
and strip the sensor side this mode does not use:

```xml
<!-- Health Connect reads -->
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_DISTANCE" />
<uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED" />

<!-- No sensor, no service, no boot restart -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" tools:node="remove" />
<uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" tools:node="remove" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" tools:node="remove" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" tools:node="remove" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" tools:node="remove" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" tools:node="remove" />

<application>
    <service android:name="com.steptrackerpro.service.StepTrackerService" tools:node="remove" />
    <receiver android:name="com.steptrackerpro.service.BootReceiver" tools:node="remove" />
    <receiver android:name="com.steptrackerpro.service.NotificationActionReceiver" tools:node="remove" />
</application>
```

Add `READ_HEALTH_DATA_IN_BACKGROUND` only if you set
`healthConnectBackgroundRead: true` (a widget or a background sync that reads
while the app is closed), and `READ_HEALTH_DATA_HISTORY` only with
`healthConnectHistoryRead: true` (anything older than 30 days). Set
`healthConnectWriteEnabled: false` too, so the package never asks for the
write set you did not declare.

### Permissions to request

| Permission | When | How |
|---|---|---|
| Health Connect `READ_STEPS`, `READ_DISTANCE`, `READ_TOTAL_CALORIES_BURNED` | when the user opts into the feature | `enableHealthConnect()` |
| `READ_HEALTH_DATA_IN_BACKGROUND` (optional) | only with `healthConnectBackgroundRead` | same sheet |
| `READ_HEALTH_DATA_HISTORY` (optional) | only with `healthConnectHistoryRead` | same sheet |

### What you get

- `getTodaySteps()`, `getStepsForDate()`, the stats calls — resolved from
  Health Connect. `stepSource` names the app the number came from.
- `getStepSources()` — every app publishing steps, so the user can pin one
  with `setPreferredStepSource()`.
- **No live `stepsChanged` events.** Nothing is running. Re-read on
  foreground (`useStepTracker` and `useHealthConnect` already do) and on
  `healthConnectStatusChanged`.
- **Numbers arrive when the other app syncs.** A watch's companion app
  uploads in batches; a walk finished five minutes ago may not be there yet.
- With no external data for a day, the day reads 0 with `kind: 'self'`.

### Play Console

- Health apps declaration, listing only the `READ_*` permissions.
- Privacy policy naming steps, distance and calories as read data types.
- No foreground service declaration.

---

## Mode C: Both (recommended)

The phone counts on its own, exactly as in Mode A, **and** Health Connect is
read so a watch or another app that saw more of the day can supply the number.
This is the default configuration.

### Config

```ts
await StepTracker.initialize({
  height: 175,
  weight: 75,
  dailyGoal: 10000,
  stepSource: 'auto',                  // default: merge, see below
  healthConnectEnabled: true,          // default
  healthConnectReadEnabled: true,      // default
  healthConnectWriteEnabled: true,     // default; false to read without mirroring
  // The tracking service already lets Health Connect be read with the app
  // closed; add healthConnectBackgroundRead: true only for reads while it is
  // down - on phones whose maker kills it often.
  privacyPolicyUrl: 'https://example.com/privacy',   // required
});

await StepTracker.requestPermissions();
await StepTracker.startTracking();

// Later, from a settings screen or an onboarding step — never on first
// launch, and never blocking: counting works without it.
await StepTracker.enableHealthConnect();
```

Nothing else changes at runtime. Health Connect not installed, not supported,
or refused → the numbers are the phone's own, and everything else works.

### How the two are reconciled — the `auto` policy

The one rule: **origins are never summed.** A user walking with a watch and a
phone has the same steps recorded twice; adding them shows double.

Under `'auto'`, each Health Connect read works out how far ahead the best
external source is allowed to be, and that *lead* becomes today's baseline:
the number shown is `phoneSteps + lead`, the phone's sensor keeps ticking, and
the user sees **6,001, 6,005, 6,010…** during a walk instead of 6,000 frozen
until the other app next syncs. What the lead may be depends on what the
source is:

- **A wearable** (or any source the user pinned) is trusted outright: a watch
  on the wrist sees a walk the phone on the desk did not. Its lead is its
  whole margin over the phone, and it grows whenever the watch pulls further
  ahead.
- **A phone-side origin** — Samsung Health, Google Fit, Health Connect's own
  on-device count, an aggregator — reads the *same phone*. For the hours this
  device was counting it cannot legitimately have seen more, so all it may add
  is the part of the day **before this device's coverage began**: an install
  at 15:00 takes the morning's 6,000 from Samsung Health, and nothing after.
  An aggregator that sums the platform's count and this package's own cannot
  double the display, and a phone-side algorithm that counts 5% high cannot
  creep the total up sync after sync. On a past day a phone-side source is
  used only when this device has nothing for it at all.

When both counted the same walk the external total lands on the number
already being shown and nothing is counted twice.

Concretely, a fresh install at 15:00 on a phone whose Samsung Health already
holds 6,000 steps for today, and a watch paired later that afternoon:

| Event | phone count | Health Connect | shown |
|---|---|---|---|
| install, first read | 0 | Samsung 6,000 (all before 15:00) | 6,000 |
| user walks 10 steps | 10 | 6,000 (not synced yet) | 6,010 |
| Samsung Health syncs 6,012 (counts a touch high) | 10 | 6,012 | 6,010 — the 2 extra never enter |
| phone left on desk, watch walks 1,000 | 10 | watch 1,010 for the day | 7,010 — watch lead 1,000 + Samsung 6,000 |
| user walks 40 with both | 50 | (not synced) | 7,050 |

The shown number is monotonic for the day, so `goalReached` fires off it.
`stepSource.merged` is true and `stepSource.baselineSteps` says how far ahead
the external source was; `deviceSteps` and `externalSteps` are always both
reported so a UI can show "5,500 on this phone · 6,000 from Samsung Health".

`'wearable'` and `'health_connect'` do not merge: they promise the other app's
exact number and keep it, jumps included, which is what a user who treats the
watch as the source of truth wants. `'device'` never reads.

### Steps typed in by hand

Every Health Connect record says how it was produced — counted by a sensor,
or typed in by the user (`RECORDING_METHOD_MANUAL_ENTRY`). `getStepSources()`
reports the split per source as `manualSteps` and `recordingMethods`, and by
default the resolver treats a manual entry like any other steps: for a
display app a user correcting their own day is legitimate. An app that
converts steps into anything of value should set

```ts
healthConnectIgnoreManualEntries: true,
```

after which every source competes on `steps - manualSteps` under every
policy, pin included, and `stepSource.manualStepsExcluded` on each snapshot
says how much was left out so the screen can explain the difference from
Health Connect's own number. The records are still read — the permission is
the same — and still listed; only the resolved number changes. Details in
[API.md](API.md#manual-entries).

### Manifest — add the read and write sets

The library declares no Health Connect permission from 2.0. Add the default
set, plus background reads if you turn them on:

```xml
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_DISTANCE" />
<uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED" />
<uses-permission android:name="android.permission.health.WRITE_STEPS" />
<uses-permission android:name="android.permission.health.WRITE_DISTANCE" />
<uses-permission android:name="android.permission.health.WRITE_TOTAL_CALORIES_BURNED" />
<!-- with healthConnectBackgroundRead: true -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />
```

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is yours to add if you want the direct
dialog (see [PLAY_STORE_COMPLIANCE.md](PLAY_STORE_COMPLIANCE.md#3-battery-optimisation-exemption)).
The full list, with what each is for, is in
[PERMISSIONS.md](PERMISSIONS.md#what-the-library-declares-and-what-you-add).

### Permissions to request

| Permission | When | How |
|---|---|---|
| `ACTIVITY_RECOGNITION` | before `startTracking()` | `requestPermissions()` |
| `POST_NOTIFICATIONS` (13+) | same call; optional | `requestPermissions()` |
| Health Connect read + write set | when the user opts in, from settings or onboarding step 2 | `enableHealthConnect()` |
| `READ_HEALTH_DATA_IN_BACKGROUND` | with the set above, when `healthConnectBackgroundRead` | same sheet |
| Battery exemption / OEM autostart | after tracking is on, on phones that need it | `requestBackgroundPermissions()` |

### What you get

- Everything from Mode A, plus: `getTodaySteps()`, the stats calls, the
  `stepsChanged` event and the notification all report the merged number.
- `stepSourceChanged` fires when the app answering for the user flips.
- On a phone with no step sensor the service counts over the accelerometer
  (see Mode A); with `accelerometerFallback: false`, `startTracking()`
  rejects with `E_NO_SENSOR` but every read still works from Health Connect —
  the app behaves as Mode B on that device.
- On Android 14 with SDK extension 20+, Health Connect records this phone's
  own steps by itself once any app holds `READ_STEPS`, under the `android`
  origin (a `com.android.healthconnect.phone.<hash>` synthetic package after
  the June 2026 provider update). It comes from the same hardware counter
  this package reads, so it appears in `getStepSources()` as
  `kind: 'phone'`, `isPlatform: true`, named "This phone (Android)", and is
  never treated as a wearable. Under `'auto'` it is a harmless near-duplicate
  of the phone's own count — and on a fresh install mid-day it is exactly
  what supplies the steps taken before the app existed.
- The sync worker mirrors the phone's days into Health Connect every 30
  minutes with stable `clientRecordId`s. Days a wearable already owns are
  skipped, so other apps never see two copies of one walk.

### Play Console

- Foreground service declaration.
- Health apps declaration for every `android.permission.health.*` left in the
  merged manifest.
- Privacy policy naming the read and written data types.

---

## Switching modes after release

`stepSource`, `healthConnectReadEnabled` and `healthConnectWriteEnabled` can
change in any `initialize()` or `updateConfig()` call. Changing the policy
clears the day's continuity baseline; the next read takes a fresh one.
Manifest changes need a new release, and adding a health permission to the
manifest means a new health declaration. A config that asks for a permission
the manifest does not declare fails loudly: the request rejects with
`E_HEALTH_CONNECT_NOT_DECLARED`.
