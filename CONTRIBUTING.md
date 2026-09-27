# Contributing

## Setup

```sh
npm install
cd android && ./gradlew assembleDebug   # needs ANDROID_HOME and JDK 17
```

The library builds on its own — `android/settings.gradle` pins the React Native
version so no host app is needed.

## Checks that run in CI

```sh
npm run typecheck      # tsc
npm run lint           # eslint, zero warnings
npm run format         # prettier --check
npm test               # jest: the JS layer against a scripted native module
npm run build          # bob → lib/
npm run test:android   # JVM: engine gap splitting, source resolution, continuity, pedometer
npm run lint:android   # Android lint, must be free of errors
npm run test:android:device   # instrumented engine tests; needs an emulator or device
```

CI also packs the library and builds it inside apps made from React Native's
template (the `compat` job): 0.77.3 with the new architecture on and off,
and 0.87.2 with AGP 9's built-in Kotlin off and on. A build-script change
should be checked the same way before it is merged - the steps are in that
job.

All of them must pass before a pull request is merged. `npm run format:write`
fixes formatting.

## Where things live

| Layer | Path | Tested by |
|---|---|---|
| Public API and validation | `src/StepTracker.ts`, `src/types.ts` | `src/__tests__` (Jest) |
| Counter engine, gap recovery | `android/.../core/StepCounterEngine.kt`, `StepGapSplitter.kt` | `androidTest` (instrumented), `test` (JVM) |
| Health Connect, source resolution, continuity | `android/.../health/` | `test` (JVM) |
| Accelerometer pedometer | `android/.../core/AccelerometerStepDetector.kt` | `test` (JVM, synthetic gait) |
| Service, watchdog, boot | `android/.../service/`, `sync/WatchdogWorker.kt` | device matrix in [docs/TESTING.md](docs/TESTING.md) |

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains the design; read the
counting maths there before touching the engine.

## Rules that are not negotiable

- **Origins are never summed.** A phone and a watch record the same walk;
  every code path picks one, or adds a phone-side source's *pre-coverage*
  share only. A change that can show `phone + watch` is a bug, not a feature.
- **The displayed number is monotonic within a day.** Nothing may make
  `getTodaySteps()` go down except `resetToday()`, midnight, and
  `fraudDetection.mode: 'exclude'` taking out steps flagged after the fact.
- **Steps counted while the process was dead are recovered, never invented.**
  Gap recovery claims what the hardware counter proves; a fresh install claims
  nothing it cannot prove.
- **Every write to the day tables goes through the write lane.**
- **No permission is requested that the configured mode does not need.**
- **The library manifest declares only what sensor counting needs.**
  Anything opt-in - Health Connect, the battery-exemption dialog - is declared
  by the app, documented in `docs/PERMISSIONS.md`, and added by the Expo
  plugin in `plugin/manifest.js`.
- **Play Services stays compile-only.** Anything that needs it is probed for
  at runtime and listed in `consumer-rules.pro` as `-dontwarn`.

## Adding an OEM

`BatteryOptimizationHelper.autoStartCandidates()` — add the component under the
manufacturer, and the package to `MANAGER_PACKAGES` and to the `<queries>` block
in `android/src/main/AndroidManifest.xml`. Then add the user-facing path to
[docs/OEM_BATTERY.md](docs/OEM_BATTERY.md). Include the firmware version you
verified it on in the pull request.

## Adding a wearable companion app

`StepSourceCatalog.KNOWN` and the `<queries>` block. A `Device` stamp on the
records always overrides the catalog, so the catalog only matters for apps that
write none.

## Releasing

1. Bump `version` in `package.json` (`npm version X.Y.Z --no-git-tag-version`
   updates the lockfile too) and give the release its entry in `CHANGELOG.md`.
2. `npm run build && npm pack --dry-run` and check the file list.
3. Commit, tag `vX.Y.Z` and push the tag. The `Publish` workflow checks the
   tag against `package.json`, runs the checks, and publishes with
   `npm publish --provenance` - which only works from CI, so do not publish
   from a laptop. It needs an `NPM_TOKEN` repository secret.
4. Set the release's date in `CHANGELOG.md` to the day npm shows it was
   published.
