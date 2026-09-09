# react-native-step-tracker-pro

Android step tracking for React Native that keeps counting when the app is
closed, the screen is locked, the process is killed, or the phone reboots.
Hardware sensor → foreground service → Room → Health Connect.

Android only. iOS has CMPedometer and a completely different permission model;
wrapping both behind one API produces a bad version of each.

```ts
import StepTracker from "react-native-step-tracker-pro";

await StepTracker.initialize({ height: 175, weight: 75, dailyGoal: 10000 });
await StepTracker.requestPermissions();
await StepTracker.startTracking();

StepTracker.addListener("stepsChanged", (data) => console.log(data.steps));
```

## What it does

|                |                                                                   |
| -------------- | ----------------------------------------------------------------- |
| Sensor         | `TYPE_STEP_COUNTER`, falling back to `TYPE_STEP_DETECTOR`         |
| Background     | Kotlin foreground service, `health` type, `START_STICKY`          |
| Storage        | Room — `step_history` + `daily_summary`, 31–35 day retention      |
| Reboot         | `BootReceiver` restarts the service and re-anchors the counter    |
| Health Connect | availability, permissions, read, write, idempotent sync            |
| Watches        | reads a paired watch through Health Connect and never double-counts |
| Offline        | everything works with no network; remote sync is queued           |
| Bridge         | Turbo Module with an old-architecture shim, full TypeScript types |

## Install

```sh
npm install react-native-step-tracker-pro
cd android && ./gradlew clean
npx react-native run-android
```

Requires `minSdk 26`, `compileSdk 35`, RN ≥ 0.77. Full steps, including the
Gradle overrides for Kotlin 2.x and the manifest entries your app has to add:
[docs/INSTALLATION.md](docs/INSTALLATION.md).

## Configuration

```ts
await StepTracker.initialize({
  height: 175, // cm — drives stride length
  weight: 75, // kg — drives calories
  dailyGoal: 10000,
  historyRetentionDays: 31, // how much history to keep on device
  notificationTitle: "{steps} steps today",
  healthConnectEnabled: true,
  privacyPolicyUrl: "https://example.com/privacy", // required for health permissions
  stepSource: "auto", // phone sensor vs. a paired watch — see below
  remoteSyncUrl: "https://api.example.com/steps", // optional
});
```

Stride length defaults to `height × 0.415` (male) / `0.413` (female) /
`0.414` (unspecified); pass `strideLength` in metres to override. Calories use
`0.57 kcal × body mass (kg) × distance (km)`, adjustable via
`calorieCoefficient`. Both are estimates, and both match the order of magnitude
Google Fit and Samsung Health report — not their exact numbers, because neither
publishes its model.

## API

```ts
initialize(config)            updateConfig(config)      getConfig()
startTracking()               pauseTracking()           resumeTracking()
stopTracking()                getTrackingState()        isTracking()

getTodaySteps()               getYesterdaySteps()       getStepsForDate(date)
getWeeklyStats(options)       getMonthlyStats(options)  getYearlyStats(options)
getStatsForRange(from, to)    getHistory(from, to)

requestPermissions()          checkPermissions()        getDeviceCapabilities()
isBatteryOptimizationEnabled()                          requestDisableBatteryOptimization()

getHealthConnectStatus()      enableHealthConnect()     requestHealthConnectPermissions()
installHealthConnect()        openHealthConnectSettings()   revokeHealthConnectPermissions()
readHealthConnectSteps(a, b)  writeHealthConnectSteps(date)   syncWithHealthConnect()

getStepSources(from, to)      getCurrentStepSource()
setPreferredStepSource(pkg)   getInstalledCompanionApps()

syncNow()                     getPendingSyncCount()
resetToday()                  clearHistory()            pruneHistory(days)
```

Events: `stepsChanged`, `goalReached`, `goalProgressChanged`,
`trackingStateChanged`, `dayChanged`, `syncCompleted`, `stepSourceChanged`,
`healthConnectStatusChanged`, `error`.

```ts
const sub = StepTracker.addListener("goalReached", ({ type, goal }) => {});
sub.remove();
// or
StepTracker.removeListener("goalReached");
```

Signatures and payload shapes: [docs/API.md](docs/API.md).

## Hooks

```tsx
const { snapshot, state, start, pause, requestPermissions } = useStepTracker({
  dailyGoal: 10000,
  autoStart: true,
});

const { stats } = useStepStats("week");

const { status, sources, enable, selectSource } = useHealthConnect();
```

## Watches and Health Connect

If the user walks with a watch, the phone's sensor is not the whole story — a
phone on a desk counts nothing while a worn watch counts everything. Health
Connect is how the watch's number gets to you.

```ts
await StepTracker.initialize({
  privacyPolicyUrl: "https://example.com/privacy",
  stepSource: "auto",
});

// Installs the provider, or asks for permissions, or opens settings —
// whichever step the user is actually on.
const status = await StepTracker.enableHealthConnect();
```

After that, `getTodaySteps()` and the stats calls already report the resolved
number and carry a `stepSource` saying which app it came from.

**Sources are never summed.** The same walk is recorded by both the watch and
the phone, so adding them roughly doubles the count. Each policy picks one:

| `stepSource` | Behaviour |
| ------------ | --------- |
| `"auto"` *(default)* | whichever of the phone and the best external source counted more that day |
| `"device"` | phone sensor only — the pre-1.2 behaviour |
| `"wearable"` | a watch, band or ring wins whenever one has data |
| `"health_connect"` | the best external source wins, wearable or not |

With no Health Connect grant, `"auto"` is exactly the phone's own sensor —
resolution only engages once the user allows reads. Let the user choose a source
explicitly with `getStepSources()` and `setPreferredStepSource()`.

Permission handling, the install and settings fallbacks, and the full source
API: [docs/API.md](docs/API.md#health-connect).

## How the count survives things

`TYPE_STEP_COUNTER` reports steps since boot, so the day total is
`anchorSteps + (rawValue − anchorValue)`. The anchor is re-pinned on reboot, on
an OEM counter reset, and at midnight. Counter state lives in SharedPreferences
and is written on every sensor batch, so a process kill costs nothing — the
hardware kept counting while you were dead, and the next sample reconciles.

Details, including why steps between a reboot and the service restarting are
sometimes dropped on purpose: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Before you ship

Read [docs/PLAY_STORE_COMPLIANCE.md](docs/PLAY_STORE_COMPLIANCE.md). The three
things that get apps rejected here are the foreground service declaration, the
Health Connect permissions form, and asking for a battery optimisation
exemption without an eligible use case.

## Example app

```sh
cd example && npm install && npm run android
```

## Testing

```sh
cd android
./gradlew test                                          # JVM, no device needed
./gradlew :react-native-step-tracker-pro:connectedAndroidTest
```

Fourteen JVM tests cover step-source resolution — chiefly that a phone and a
watch are never added together. Eleven instrumented tests cover the reboot,
midnight, pause and counter-reset paths by feeding samples to the engine
directly, so they run on an emulator with no step hardware.

The device-level QA matrix — force-stop recovery, real reboot, Doze, Health
Connect, OEM battery managers — is in [docs/TESTING.md](docs/TESTING.md).

## Changelog

[CHANGELOG.md](CHANGELOG.md). Latest release **1.2.0** — Health Connect
permission lifecycle, provider install/update handling, the Play-required
privacy-policy screen, and step sources from a paired watch.

## Licence

MIT
