# Troubleshooting

## Steps stay at zero

1. `getDeviceCapabilities()` — if `supported` is false the device has no step
   hardware and no accelerometer (or `accelerometerFallback` is off). Most
   emulators have neither step sensor; the emulator's virtual accelerometer
   does not produce gait, so the fallback counts nothing there either.
2. `checkPermissions()` — `ACTIVITY_RECOGNITION` must be granted.
3. `getTrackingState()` — `idle` or `stopped` means the service was never
   started or was stopped.
4. Some sensor hubs need 5–10 actual steps before the first event fires.
   Shaking the phone does nothing; walk. The accelerometer fallback also
   holds back the first four steps of every walk until it is sure it is one.

## Battery drain on a budget phone

`getDeviceCapabilities().bestSensor` is `'accelerometer'`: the phone has no
step sensor, so the service samples the accelerometer at 25 Hz and, unless
`hasWakeUpAccelerometer` is true, holds a wake lock to keep the CPU awake for
it. That is a few percent of battery a day and is the price of counting at
all on that hardware. Options, in order of how much they give up:

- `accelerometerWakeLock: false` — no wake lock; counts only while the screen
  is on.
- `accelerometerFallback: false` — no counting on that phone; Health Connect
  reads still work if another app supplies steps.

## Steps counted in a car or on a bus

Only the accelerometer fallback is affected; the hardware counter has its own
vehicle rejection. The detector ignores vibration above 3 Hz and rocking
below 0.8 Hz, but a rough road at walking cadence looks like walking. Raise
`accelerometerThreshold` (default 0.9 m/s²) toward 1.5 if users report it;
gentle walks with the phone in a bag start being missed above that.

## Counting stops when the screen goes off

Almost always OEM battery management, not Doze. Check first:

```ts
const health = await StepTracker.getTrackingHealth();
// looksDead: true, recoveryCount: 3 → the OEM is killing the service
```

Calling that from the foreground restarts the service on its own; the steps
taken while it was dead come back with the first sample. What stops it
happening again is the user:

```ts
await StepTracker.requestBackgroundPermissions();   // battery exemption, then OEM autostart
```

On Xiaomi, "Autostart" and "No restrictions" under battery saver both have to be
set, and the app locked in recents. On Samsung, remove the app from "Sleeping
apps". There is no API for any of it; the per-manufacturer instructions to
show are in [OEM_BATTERY.md](OEM_BATTERY.md).

## Steps counted while the app was dead did not show up

They should, on any device with `TYPE_STEP_COUNTER`: the first sample after a
restart carries everything since the last reading. If they are missing:

- `getDeviceCapabilities().hasStepCounter` false → the device is on the
  detector or the accelerometer, neither of which can recover steps taken
  while nothing was listening.
- The gap crossed midnight and `gapRecovery` is `'drop'` — that is the policy
  doing what it was told.
- The restart was a real reboot on a previous day: since-boot steps are split
  by time from the boot instant, so a phone rebooted at 23:00 and opened at
  09:00 credits 10% of them to yesterday. See
  [ARCHITECTURE.md](ARCHITECTURE.md#gap-recovery).

## The number is stuck at what Health Connect said

Under `'wearable'` and `'health_connect'` that is by design: they show the
other app's exact number and it moves when that app syncs. Use `'auto'` for a
number that keeps moving with the phone's own sensor between syncs
(`stepSource.merged: true`).

Under `'auto'`, a stuck number means the phone's own sensor is not counting —
check `getTrackingHealth()` and `checkPermissions()`.

## Notification does not appear

- Android 13+: `POST_NOTIFICATIONS` denied. Counting continues, the notification
  does not show.
- The channel is created at `IMPORTANCE_LOW`; if the user turned the channel off
  it stays off. Send them to app notification settings.
- **It shows, but the lock screen says "Counting steps" with no count.** That
  is the default from 2.3, `notificationLockScreen: 'private'`, on a phone set
  to hide sensitive content on the lock screen. Set `'public'` to show the
  count there regardless.
- **The icon is the default footprint in release builds only.** Resource
  shrinking removed your drawable; keep it (see
  [INSTALLATION.md](INSTALLATION.md#5-custom-notification-icon-recommended)).
  Logcat has a `StepTrackerPro` warning naming it.

## Steps jumped after a reboot

Expected on the boundary. Steps taken between boot and the service restarting
are counted if the boot happened on the same day, and dropped if it happened
before midnight. See [ARCHITECTURE.md](ARCHITECTURE.md#reboot).

## Steps reset when I reinstalled

SharedPreferences and the Room database are cleared on uninstall. If you need
history to survive reinstalls, sync to Health Connect or set `remoteSyncUrl`.

## History is gone after installing an older build

Installing a build with an older database version over a newer one - by hand
or from a QA channel; Play never downgrades a user - drops the database and
starts again, days not yet synced included. The alternative is a crash on
every launch until the newer build is back. Upload or mirror before testing a
downgrade on a device whose data matters.

## Build fails: Kotlin / Room version mismatch

React Native sets `rootProject.ext.kotlinVersion` and the library follows it, so
this should not happen on a stock app. If it does, set `kotlinVersion` and
`roomVersion` in the app's root `build.gradle` — see
[INSTALLATION.md](INSTALLATION.md#3-kotlin-and-room-versions).

## Build fails: `minSdkVersion 24 cannot be smaller than 26`

Set `minSdkVersion = 26` in the app's root `build.gradle`. Health Connect and
`java.time` both need it, and React Native's own template starts at 24. The
error names this package from 2.0, which declares 26 whatever the app says.
Expo: the config plugin raises it for you.

## Build fails on AGP 9: Kotlin or kapt plugin errors

`The 'org.jetbrains.kotlin.kapt' plugin is not compatible with built-in
Kotlin support` or `Plugin with id 'com.android.legacy-kapt' not found` come
from a 1.x release of this package on AGP 9 with `android.builtInKotlin` on.
2.0 detects built-in Kotlin and applies AGP's `com.android.legacy-kapt`,
pulling in the artifact that provides it. Upgrade; or, on 1.x, set
`android.builtInKotlin=false` as React Native 0.87's template does.

## `E_HEALTH_CONNECT_NOT_DECLARED`

From 2.0 the library manifest declares no Health Connect permission. Add the
entries your config asks for to your app's manifest - the message names them,
and so does `getHealthConnectStatus().undeclaredPermissions`. The snippets
are in [PERMISSIONS.md](PERMISSIONS.md#what-the-library-declares-and-what-you-add);
an app upgraded from 1.x, which declared them for you, is covered by
[MIGRATING.md](MIGRATING.md).

## The battery dialog opens a settings list instead

`requestDisableBatteryOptimization()` needs
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which the library no longer declares
from 2.0. Without it the call opens the settings list, which reaches the same
switch. Add the permission if you have an eligible Play use case
([PLAY_STORE_COMPLIANCE.md](PLAY_STORE_COMPLIANCE.md#3-battery-optimisation-exemption)).

## Uploads stop with `syncAuthFailed`

The endpoint answered 401 or 403, or `remoteSyncAuth: 'signature'` had no key
to sign with. The batch is not retried with credentials that were just
refused. Refresh the token with `updateConfig({ remoteSyncHeaders })` - or
call `attestDevice()` for signature auth - then `syncNow()`. After a backup is
restored onto a new phone the sealed headers cannot be opened there (the
Keystore key does not travel), so the first upload is refused and the same
event asks for fresh ones.

The event only reaches a running app, and uploads usually run with the app
closed. From 2.1 the refusal is stored: check `getSyncStatus()` when the app
comes up, and act when `remote.authFailed` is true - `lastFailure.reason`
says which of the three it was.

## `ForegroundServiceDidNotStartInTimeException`

Something in your app is delaying the service start past the 5-second window,
usually a heavy `Application.onCreate`. The service calls `startForeground`
first thing, so the delay is upstream.

Before 2.3 it also came from the package itself: a command that reached the
service after tracking was stopped - a Pause button still on screen, a config
change racing a stop - stopped the service without `startForeground`, and the
app crashed. Update.

## Health Connect permission sheet never opens

Check `getHealthConnectStatus()` first — it names the cause:

| Field | Meaning | Fix |
|---|---|---|
| `availability: 'not_installed'` | no provider (Android 13 and below) | `installHealthConnect()` |
| `availability: 'update_required'` | provider too old | `installHealthConnect()` |
| `availability: 'not_supported'` | device cannot run it | nothing to offer; hide the feature |
| `shouldOpenSettings: true` | refused twice, so the sheet no longer shows | `openHealthConnectSettings()` |

`enableHealthConnect()` picks the right one of these for you.

The two-refusal limit is the one that looks like a bug: the third
`requestHealthConnectPermissions()` resolves normally, with the same unchanged
status, and nothing appears on screen. That is the provider, not this package —
`denialCount` and `shouldOpenSettings` are there to tell it apart from an
instant denial.

A missing rationale intent filter also stops the sheet. The library declares it
and points it at your `privacyPolicyUrl`; if you replaced it with your own
activity, confirm both the `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`
filter and the `VIEW_PERMISSION_USAGE` alias resolve:

```sh
adb shell dumpsys package <applicationId> | grep -A3 PERMISSIONS_RATIONALE
```

## Steps keep climbing without walking

In order of likelihood:

1. **The hardware counter itself.** `TYPE_STEP_COUNTER` false-counts in a
   moving vehicle and when the phone is shaken; Google Fit and Samsung
   Health show the same on that device. Compare with the phone's own
   Health Connect count (`getStepSources()` → `isPlatform`), which comes
   from the same sensor: if both climb, it is the sensor.
2. **A HAL that jitters backwards.** A reading a step below the previous one
   used to be treated as a restart and the dip added back on the next
   sample. Fixed in 1.3 (`JITTER_TOLERANCE_STEPS`); if you see it on an
   older build, upgrade.
3. **A phone-side app inflating Health Connect.** An aggregator that writes
   the sum of two origins, or an app that counts 10% high. Under `'auto'`
   such a source may only supply steps from before this device started
   counting today, so it cannot pull the total up — check
   `stepSource.kind`: if it is `'watch'` or another wearable kind, the lead
   is trusted by design; if the user pinned the source, it is trusted by
   choice.
4. **The accelerometer fallback in a vehicle** — see below.

## Steps are stuck

1. `getTrackingHealth()` first. `looksDead: true` → the service was killed;
   calling that from the foreground restarts it, and
   [OEM_BATTERY.md](OEM_BATTERY.md) is how to stop it recurring.
2. `serviceAlive: true` but `lastSensorEventAt` old and the user has walked
   → the listener is not attached. The `error` event carries
   `E_SENSOR_UNAVAILABLE` while the service retries (2, 5, 15, 30 s, then
   every minute). If it never recovers, the HAL is refusing the listener;
   a reboot is the only fix, and `getDeviceCapabilities()` will say so on
   the next start.
3. Screen off and the notification not moving: on a non-wake-up counter the
   hub buffers with the CPU asleep and delivers the cumulative reading on
   wake — nothing is lost, the display catches up when the screen comes on.
4. `stepSource.usedExternal: true` with `merged: false` under `'wearable'`
   or `'health_connect'`: that is the other app's exact number and moves
   when it syncs. Use `'auto'` for a live count.
5. `state: 'paused'`.

## Steps are roughly double what the user walked

Something is summing two sources. This package never does — every `stepSource`
policy picks exactly one origin per day, and `'auto'`'s merge adds the phone's
*delta since the last read* to an external baseline, never two totals — so the
doubling is almost always outside it:

- Your own UI adding `getTodaySteps()` to a Health Connect read. Use one or the
  other; `getTodaySteps()` already includes the watch under `'auto'`.
- Adding up `getStepSources()` entries. Those are per-origin totals of the *same*
  walk, not slices of it. `getCurrentStepSource()` gives the number to show.

## The watch's steps never show up

- `getHealthConnectStatus().canRead` must be true. Write-only grants are enough
  to mirror your steps but not to see anyone else's.
- `stepSource: 'device'` reads nothing back by design. Use `'auto'` or
  `'wearable'`.
- The companion app has to have synced. Garmin, Fitbit and Zepp upload in
  batches, so a walk finished five minutes ago may not be in Health Connect yet.
  `getStepSources()` reports `lastRecordAt` per source.
- Under `'auto'` the watch only wins when it counted more. Check `deviceSteps`
  vs `externalSteps` on `getCurrentStepSource()`; if the watch is lower and you
  still want it, use `'wearable'` or pin it with `setPreferredStepSource()`.
- Without `healthConnectBackgroundRead: true`, nothing reads Health Connect
  in the background: background uploads and snapshots carry
  `sourcesStatus: 'not_consulted'`.

## A source shows as `kind: 'unknown'`

`kind` is read from the `Device` the writing app stamped on its records, with a
package-name catalog as a fallback. Apps that stamp nothing and are not in the
catalog land on `'unknown'` — they still work as sources and can still be pinned,
they just cannot be told apart from a phone-side app automatically, so `'wearable'`
will not select them. Pin them explicitly with `setPreferredStepSource()`.

Health Connect's own on-device count on Android 14 (SDK extension 20+) is
*not* `'unknown'`: its `android` / `com.android.healthconnect.phone.<hash>`
origin is recognised, classified `'phone'` and flagged `isPlatform: true`.

## Events fire in dev but not after a JS reload

`invalidate()` unsubscribes the module from the event bus on teardown. If you
hold a listener across a reload, re-register in a `useEffect` — the bundled
hooks already do.

If listeners vanish in a running app instead, look for a
`removeListener()` or `removeAllListeners()` call with no argument: it
removes every listener subscribed through the package, the hooks' included.
Keep the subscription `addListener` returns and call `remove()` on it.

## Numbers do not match Google Fit or Samsung Health

They will not, exactly. Those apps fuse the step counter with accelerometer and
GPS data and apply their own filtering; neither publishes its model. Steps
should land within a few percent. Distance and calories will differ more,
because both are estimates from height and weight here. Tune `strideLength` and
`calorieCoefficient` if you have better data.

## Inspecting state during QA

```sh
adb shell run-as <applicationId> cat shared_prefs/StepTrackerProState.xml
adb shell dumpsys activity services com.steptrackerpro.service.StepTrackerService
adb logcat -s StepTrackerService StepTrackerBoot
```

Simulating a reboot without rebooting: force-stop the app, then start it again.
The boot ID is unchanged, so the counter reconciles rather than re-anchoring —
which is exactly the process-death path you want to test.
