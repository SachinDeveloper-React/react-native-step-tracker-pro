# Troubleshooting

## Steps stay at zero

1. `getDeviceCapabilities()` — if `supported` is false the device has no step
   hardware. Most emulators do not.
2. `checkPermissions()` — `ACTIVITY_RECOGNITION` must be granted.
3. `getTrackingState()` — `idle` or `stopped` means the service was never
   started or was stopped.
4. Some sensor hubs need 5–10 actual steps before the first event fires.
   Shaking the phone does nothing; walk.

## Counting stops when the screen goes off

Almost always OEM battery management, not Doze.

```ts
if (await StepTracker.isBatteryOptimizationEnabled()) {
  await StepTracker.openBatteryOptimizationSettings();
}
await StepTracker.openManufacturerAutoStartSettings(); // Xiaomi, Oppo, Vivo, Huawei
```

On Xiaomi, "Autostart" and "No restrictions" under battery saver both have to be
set. On Samsung, remove the app from "Sleeping apps". There is no API for either;
you have to walk the user there.

## Notification does not appear

- Android 13+: `POST_NOTIFICATIONS` denied. Counting continues, the notification
  does not show.
- The channel is created at `IMPORTANCE_LOW`; if the user turned the channel off
  it stays off. Send them to app notification settings.

## Steps jumped after a reboot

Expected on the boundary. Steps taken between boot and the service restarting
are counted if the boot happened on the same day, and dropped if it happened
before midnight. See [ARCHITECTURE.md](ARCHITECTURE.md#reboot).

## Steps reset when I reinstalled

SharedPreferences and the Room database are cleared on uninstall. If you need
history to survive reinstalls, sync to Health Connect or set `remoteSyncUrl`.

## Build fails: KSP version mismatch

React Native sets `rootProject.ext.kotlinVersion` and the library follows it, so
this should not happen on a stock app. If it does, set `kotlinVersion` in the
app's root `build.gradle` — see
[INSTALLATION.md](INSTALLATION.md#3-kotlin-2x-only-if-your-app-is-already-on-it).

## Build fails: `minSdkVersion 24 cannot be smaller than 26`

Set `minSdkVersion = 26` in the app's root `build.gradle`. Health Connect and
`java.time` both need it.

## `ForegroundServiceDidNotStartInTimeException`

Something in your app is delaying the service start past the 5-second window,
usually a heavy `Application.onCreate`. The service calls `startForeground`
first thing, so the delay is upstream.

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

## Steps are roughly double what the user walked

Something is summing two sources. This package never does — every `stepSource`
policy picks exactly one origin per day — so the doubling is almost always
outside it:

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
- Background syncs return empty without `healthConnectBackgroundRead: true`.

## A source shows as `kind: 'unknown'`

`kind` is read from the `Device` the writing app stamped on its records, with a
package-name catalog as a fallback. Apps that stamp nothing and are not in the
catalog land on `'unknown'` — they still work as sources and can still be pinned,
they just cannot be told apart from a phone-side app automatically, so `'wearable'`
will not select them. Pin them explicitly with `setPreferredStepSource()`.

The same applies to on-device steps recorded by the platform itself on Android
14+, which are attributed to a device-specific synthetic package name.

## Events fire in dev but not after a JS reload

`invalidate()` unsubscribes the module from the event bus on teardown. If you
hold a listener across a reload, re-register in a `useEffect` — the bundled
hooks already do.

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
