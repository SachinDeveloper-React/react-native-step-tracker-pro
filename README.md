# react-native-step-tracker-pro

Android step tracking for React Native that keeps counting when the app is
closed, the screen is locked, the process is killed, or the phone reboots —
and keeps the number moving when a watch or another app is also counting.
Hardware sensor → foreground service → Room → Health Connect.

**Android only.** There is no iOS implementation and none is planned: iOS has
CMPedometer, HealthKit and App Attest, and a completely different permission
model; wrapping both behind one API produces a bad version of each. On iOS the
package is inert rather than broken - autolinking skips it (no pod is added),
`isSupported()` returns `false`, every method rejects with
`E_UNSUPPORTED_PLATFORM`, and `addListener` returns a no-op subscription - so
a cross-platform app can import it unconditionally and branch on
`isSupported()`.

```ts
import StepTracker from "react-native-step-tracker-pro";

await StepTracker.initialize({ height: 175, weight: 75, dailyGoal: 10000 });
await StepTracker.requestPermissions();
await StepTracker.startTracking();

StepTracker.addListener("stepsChanged", (data) => console.log(data.steps));
```

## Three ways to use it

| Mode | What counts | Guide |
|---|---|---|
| **Native sensor only** | the phone's hardware step counter, in a foreground service; no Health Connect, no health permissions | [USAGE_MODES.md § A](docs/USAGE_MODES.md#mode-a-native-sensor-only) |
| **Health Connect only** | whatever Samsung Health, Google Fit or a watch wrote; nothing runs in the background | [USAGE_MODES.md § B](docs/USAGE_MODES.md#mode-b-health-connect-only) |
| **Both** *(default)* | the phone counts, and a source that saw more of the day supplies the number — merged live, never summed | [USAGE_MODES.md § C](docs/USAGE_MODES.md#mode-c-both-recommended) |

Each mode has its own manifest entries, permission flow and Play Console
forms; the guide has all three side by side. From 2.0 the library declares
only what sensor counting needs - Health Connect and the battery-exemption
prompt are yours to add, so nothing you do not use needs justifying to Play.
Permissions in one place: [docs/PERMISSIONS.md](docs/PERMISSIONS.md).

## What it does

|                |                                                                   |
| -------------- | ----------------------------------------------------------------- |
| Sensor         | `TYPE_STEP_COUNTER` → `TYPE_STEP_DETECTOR` → a software pedometer over the accelerometer on phones with neither |
| Background     | Kotlin foreground service, `health` type, `START_STICKY`          |
| OEM killers    | foreground restart, 15-minute watchdog, boot receiver; every step counted while dead is recovered from the hardware counter, including across midnight |
| Storage        | Room — `step_history` + `daily_summary`, 31–35 day retention      |
| Reboot         | `BootReceiver` restarts the service and re-anchors the counter    |
| Health Connect | availability, permissions scoped to config, read, write, idempotent sync |
| Watches        | reads a paired watch through Health Connect and never double-counts |
| Continuity     | Health Connect says 6,000 → the phone keeps it moving: 6,001, 6,005, 6,010 |
| Fraud checks   | opt-in: flags shaken phones, swing gadgets, charging, vehicles and implausible totals; event log; Keystore attestation and signed snapshots |
| Offline        | everything works with no network; remote sync is queued           |
| Bridge         | Turbo Module with an old-architecture shim, full TypeScript types |

## Install

```sh
npm install react-native-step-tracker-pro
cd android && ./gradlew clean
npx react-native run-android
```

Requires `minSdk 26` (React Native's template starts at 24 - raise it),
`compileSdk 35` or higher, RN ≥ 0.77, AGP 8 or 9. From 2.0 the Health Connect
permissions are yours to declare - see [the modes](#three-ways-to-use-it) and
[who declares what](docs/PERMISSIONS.md#who-declares-what). Upgrading from
1.x: [docs/MIGRATING.md](docs/MIGRATING.md).
Expo: add `"react-native-step-tracker-pro"` to `plugins` and prebuild; the
config plugin declares what you opt into. Full steps:
[docs/INSTALLATION.md](docs/INSTALLATION.md).

## Compatibility

What CI builds on every push, by installing the packed library into an app
made from React Native's own template and compiling it there:

| React Native | Architecture | Android Gradle Plugin | Gradle | Kotlin | compileSdk | Kotlin mode | Health Connect client |
|---|---|---|---|---|---|---|---|
| 0.77.3 | new | 8.7.2 | 8.10.2 | 2.0.21 | 35 | Kotlin Android plugin | 1.1.0-beta01 |
| 0.77.3 | old | 8.7.2 | 8.10.2 | 2.0.21 | 35 | Kotlin Android plugin | 1.1.0-beta01 |
| 0.87.2 | new (the only one) | 9.2.1 | 9.4.1 | 2.2.0 | 37 | Kotlin Android plugin (`builtInKotlin=false`, the template default) | 1.1.0 |
| 0.87.2 | new (the only one) | 9.2.1 | 9.4.1 | 2.2.0 | 37 | AGP built-in Kotlin | 1.1.0 |

The new-architecture builds run codegen, typed events included; the old one
covers the legacy event path. The package's own build and
JVM tests run standalone on AGP 8.6 and Kotlin 2.0.21, on the old
architecture; the instrumented tests on an API 37 emulator.

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
  healthConnectReadEnabled: true, // false → mirror-only, READ_* never requested
  healthConnectWriteEnabled: true, // false → display-only, WRITE_* never requested
  gapRecovery: "split", // where steps counted while dead across midnight go
  remoteSyncUrl: "https://api.example.com/steps", // optional
});
```

An app that converts steps into anything of value adds
`healthConnectIgnoreManualEntries: true`, `wearableTrust: "catalog"`,
`gapRecovery: "today_capped"` and `remoteSyncPayload: "full"`, and posts
`getVerificationSnapshot(date)` to its server rather than the number on
screen — see [Watches and Health Connect](#watches-and-health-connect) and
[docs/API.md](docs/API.md#getverificationsnapshotdate-string-promiseverificationsnapshot).

It also turns on the integrity checks, which flag the usual ways a count is
faked — a phone shaken by hand, swung by a gadget, left on a charger,
carried in a car, or a total nobody walks — and attests the device so the
server can verify signed snapshots:

```ts
await StepTracker.initialize({
  fraudDetection: { enabled: true, mode: "flag" }, // "exclude" also takes suspect steps out
  motionSampling: { enabled: true },
});
await StepTracker.attestDevice(challengeFromYourServer);
const snapshot = await StepTracker.getVerificationSnapshot(date, { sign: true, nonce });
// snapshot.suspectSteps, snapshot.integrity.flags, snapshot.signature
```

The default `"flag"` mode changes no number; the verdict is evidence for your
server. See [docs/API.md](docs/API.md#integrity-checks).

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
getTrackingHealth()

getTodaySteps()               getYesterdaySteps()       getStepsForDate(date)
getWeeklyStats(options)       getMonthlyStats(options)  getYearlyStats(options)
getStatsForRange(from, to)    getHistory(from, to)      getVerificationSnapshot(date, opts)
getMotionWindows(from, to)

getIntegrityReport(date)      getIntegrityEvents(from, to)  getStepMinutes(from, to)
attestDevice(challenge)       hasAttestationKey()       getAttestationKeyInfo()
requestIntegrityToken(opts)

requestPermissions()          checkPermissions()        getDeviceCapabilities()
isBatteryOptimizationEnabled()                          requestDisableBatteryOptimization()
openBatteryOptimizationSettings()                       openManufacturerAutoStartSettings()
getBackgroundRestrictionStatus()                        requestBackgroundPermissions()

getHealthConnectStatus()      enableHealthConnect()     requestHealthConnectPermissions()
installHealthConnect()        openHealthConnectSettings()   revokeHealthConnectPermissions()
readHealthConnectSteps(a, b)  writeHealthConnectSteps(date)   syncWithHealthConnect()
getHealthConnectRecords(a, b) getHealthConnectChangesToken()  getHealthConnectChanges(token)

getStepSources(from, to)      getCurrentStepSource()
setPreferredStepSource(pkg)   getInstalledCompanionApps()

syncNow()                     getPendingSyncCount()
resetToday()                  clearHistory()            pruneHistory(days)
```

Events: `stepsChanged`, `goalReached`, `goalProgressChanged`,
`trackingStateChanged`, `dayChanged`, `historyBackfilled`, `motionWindow`,
`suspiciousActivity`, `syncCompleted`, `syncAuthFailed`, `stepSourceChanged`,
`healthConnectStatusChanged`, `error`. On the new architecture they arrive
through codegen-typed emitters, on the old one through `NativeEventEmitter`;
`addListener` hides the difference.

```ts
const sub = StepTracker.addListener("goalReached", ({ type, goal }) => {});
sub.remove();
// or, for every listener of one event
StepTracker.removeAllListeners("goalReached");
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
| `"auto"` *(default)* | whichever of the phone and the best external source counted more, **merged live**: the other app's lead becomes a baseline and the phone's sensor keeps counting on top |
| `"device"` | phone sensor only — the pre-1.2 behaviour |
| `"wearable"` | a watch, band or ring wins whenever one has data; its exact number |
| `"health_connect"` | the best external source wins, wearable or not; its exact number |

With no Health Connect grant, `"auto"` is exactly the phone's own sensor —
resolution only engages once the user allows reads. Let the user choose a source
explicitly with `getStepSources()` and `setPreferredStepSource()`.

**Why `auto` merges.** A watch publishes in batches. Take its number and the
display freezes at 6,000 for the whole walk, then jumps. Instead `auto`
remembers how far ahead it was (its *lead*), shows `phoneSteps + lead`, and
only raises the lead when the other app has genuinely seen more than the
phone — so the user sees 6,001, 6,005, 6,010 as they walk and nothing is ever
counted twice. A wearable is trusted for its whole margin; a phone-side app
(Samsung Health, an aggregator) may only supply the part of the day before
this device started counting, so it can fill in the morning after an
afternoon install but can never inflate a day the phone covered.
`stepSource.merged` and `stepSource.baselineSteps` report it.

**`auto` is a display policy, not a fraud control.** What makes a source "a
wearable" for that trust is, by default, the device type the writing app
stamped on its records — and any app can stamp `TYPE_WATCH`. That is right
for showing a user their watch's number and wrong for an app that pays per
step. `wearableTrust: 'catalog'` changes only the trust decision: a source
gets its whole margin only if its package is one the built-in catalog knows
as a wearable's companion app or one you list in `wearableAllowlist`; an
unlisted package that stamps a watch keeps `kind: 'watch'` for display but is
bound by the coverage rule like any phone-side app. Pair it with
`healthConnectIgnoreManualEntries: true` so a typed-in entry never becomes
the day's number, and verify server-side with `getVerificationSnapshot()`.

Permission handling, the install and settings fallbacks, and the full source
API: [docs/API.md](docs/API.md#health-connect).

## How the count survives things

`TYPE_STEP_COUNTER` reports steps since boot, so the day total is
`anchorSteps + (rawValue − anchorValue)`. The anchor is re-pinned on reboot, on
an OEM counter reset, and at midnight. Counter state lives in SharedPreferences
and is written on every sensor batch, so a process kill costs nothing — the
hardware kept counting while you were dead, and the next sample reconciles.
When the dead period crossed midnight the steps are split between the days by
time (`gapRecovery`), rather than dropped.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Low-end and OEM phones

Xiaomi, Oppo, Vivo, Realme, Tecno, Samsung and others kill foreground services
and ignore `START_STICKY`. The package brings itself back — on the next app
open, from a 15-minute watchdog, and on boot — and recovers every step the
hardware counted in between. Stopping the killing is up to the user, and there
is no API for it:

```ts
const health = await StepTracker.getTrackingHealth();   // looksDead, recoveryCount…
if (health.aggressiveOem || health.batteryOptimizationEnabled) {
  await StepTracker.requestBackgroundPermissions();     // battery exemption, then OEM autostart
}
```

Per-manufacturer instructions to show the user: [docs/OEM_BATTERY.md](docs/OEM_BATTERY.md).

Phones with no step sensor at all — some entry-level Tecno, itel, Lava and
Redmi A-series — count over the accelerometer instead of refusing to start
(`accelerometerFallback`). It costs a few percent of battery a day and loses
steps while the process is dead; `getDeviceCapabilities().bestSensor` tells
you when a user is on it.

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

In your app's Jest tests, mock the whole package in one line:

```ts
jest.mock("react-native-step-tracker-pro", () =>
  require("react-native-step-tracker-pro/jest")
);
```

Every method resolves with an empty-day value and is a `jest.fn`, so a test
overrides one with `mockResolvedValueOnce`; `__emit(event, payload)` fires a
listener; the hooks return settled results. See
[docs/TESTING.md](docs/TESTING.md#mocking-the-package-in-your-apps-tests).

This package's own suites:

```sh
npm test                    # Jest: the JS layer against a scripted native module
npm run test:android        # JVM, no device needed
npm run test:android:device # instrumented engine tests on an emulator
```

Eighty-three Jest tests cover config validation, the flows in the JS layer,
the typed and legacy event paths, the hook's config handling, the shipped
Jest mock and the Expo plugin. A hundred and thirty-seven JVM tests cover step-source resolution,
the `auto` merge and its coverage rule, manual-entry exclusion and wearable
trust, gap splitting under all four policies, the accelerometer pedometer
against synthetic gait, motion signatures against a synthetic walk and
shake, the fraud detector against synthetic days, minute attribution, and
the remote payload — chiefly that a phone and a watch are never added
together, that a phone-side app cannot inflate a covered day, that a
typed-in number never becomes the day's number, that a car is not a walk,
and that a swing gadget is flagged while a treadmill is not. Forty-five
instrumented tests cover the reboot, midnight, overnight-kill,
capped-recovery, sensor-jitter, pause and counter-reset paths by feeding
samples to the engine directly, the Room migrations against real rows, and
the integrity layer end to end through the real core, database and
Keystore, the sealed remote-sync headers and the reboot log, so they run on
an emulator with no step hardware. CI runs all of it on every push, plus the
compatibility builds above.

The device-level QA matrix — force-stop recovery, real reboot, Doze, Health
Connect, OEM battery managers — is in [docs/TESTING.md](docs/TESTING.md).

## Changelog

[CHANGELOG.md](CHANGELOG.md). Latest release **2.0.1** — fixes `updateConfig()`
(validation, and weekly and monthly goals following a new daily goal), builds
the new architecture when the React Native version cannot be found, and adds
an old-architecture compatibility build. **2.0.0** brought current toolchains
(AGP 9 with or without built-in Kotlin, Kotlin 2.2, compileSdk 37, React
Native 0.82+ as new-architecture-only), a minimal manifest that declares only
what sensor counting needs, typed codegen events, Play Integrity, raw Health
Connect records and change tracking, sealed remote-sync headers with a
`syncAuthFailed` event, a Jest mock and an Expo config plugin. Breaking
changes and how to move: [docs/MIGRATING.md](docs/MIGRATING.md).

## Licence

MIT
