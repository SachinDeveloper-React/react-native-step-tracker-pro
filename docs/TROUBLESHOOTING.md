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

Your app is on Kotlin 2.x and the library defaults to 1.9.24. Set `kotlinVersion`
and a matching `kspVersion` in the app's root `build.gradle` — see
[INSTALLATION.md](INSTALLATION.md#3-kotlin-2x-only-if-your-app-is-already-on-it).

## Build fails: `minSdkVersion 24 cannot be smaller than 26`

Set `minSdkVersion = 26` in the app's root `build.gradle`. Health Connect and
`java.time` both need it.

## `ForegroundServiceDidNotStartInTimeException`

Something in your app is delaying the service start past the 5-second window,
usually a heavy `Application.onCreate`. The service calls `startForeground`
first thing, so the delay is upstream.

## Health Connect permission sheet never opens

- `getHealthConnectStatus().available` is false → the provider is not installed
  (Android 13 and below) or needs an update (`requiresUpdate`).
- The user permanently denied. The sheet will not reappear; use
  `openHealthConnectSettings()`.
- Your app manifest is missing the rationale intent filter. Health Connect
  refuses to show the sheet without it.

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
