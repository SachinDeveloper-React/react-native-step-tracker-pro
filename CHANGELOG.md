# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-09-09

Health Connect goes from "can read and write" to a complete integration —
provider availability, the full permission lifecycle, and the privacy-policy
screen Play requires — and step counts can now come from a paired watch.

### Added

#### Watch and multi-source step tracking

- **`stepSource` config** decides how the phone's sensor and the step data other
  apps publish to Health Connect are reconciled: `'auto'` (default, whichever
  counted more that day), `'device'`, `'wearable'`, `'health_connect'`. See
  [Step sources](docs/API.md#step-sources-watches-and-other-apps).
- **Sources are never summed.** A watch and a phone record the same walk
  independently, so adding them roughly doubles the count. Every policy selects
  exactly one origin per day.
- `getStepSources(startDate, endDate)` lists every app publishing steps, with
  per-source totals and whether it counted on the body. `hasWearable` says
  whether any non-self wearable contributed.
- `getCurrentStepSource()` reports which source is answering for today, plus
  `deviceSteps` and `externalSteps` so a UI can offer the switch without a
  second call.
- `setPreferredStepSource(packageName | null)` pins one origin as the source of
  truth. Stored outside config, so a user's choice survives an `initialize()`
  that does not mention it.
- `getInstalledCompanionApps()` reports wearable companion apps found on the
  phone — useful during onboarding, before Health Connect holds any data.
- Wearable classification reads the `Device` type the writing app stamped on its
  records, with a package-name catalog covering the ~25 companion apps that omit
  it (Wear OS, Samsung Health, Fitbit, Garmin, Huawei, Honor, Mi Fitness, Zepp,
  Amazfit, OPPO, OnePlus, Oura, WHOOP, Polar, Suunto, Withings, COROS and
  others). Unrecognised apps still work as sources, typed `unknown`.
- `stepSourceChanged` event, fired when the app answering for the user's steps
  flips — a watch coming into range mid-morning, or a pin being changed.

#### Health Connect

- `availability` now distinguishes `'available'`, `'update_required'`,
  `'not_installed'` and `'not_supported'`. The previous single `available: false`
  collapsed "one tap from working" with "will never work".
- `enableHealthConnect(options?)` runs the whole flow — install or update the
  provider, otherwise request, otherwise fall back to settings — behind one call.
- `installHealthConnect()` opens the Play Store listing with the onboarding
  referrer, so Health Connect runs setup straight after installing.
- `revokeHealthConnectPermissions()` drops every grant and resets the refusal
  counter.
- **Two-refusal detection.** Health Connect stops showing its permission sheet
  after two refusals, and a third request then resolves normally with nothing
  visible on screen — indistinguishable from an instant denial. `denialCount`
  and `shouldOpenSettings` tell the two apart so the user can be routed to
  Health Connect's own settings instead of a dialog that will never appear.
- Granular status: `canRead` and `canWrite` are reported separately, alongside
  `missingPermissions`, `backgroundReadGranted` and `historyReadGranted`.
- Optional permissions, requested only when opted into via
  `healthConnectBackgroundRead` / `healthConnectHistoryRead` or the
  `requestHealthConnectPermissions(options)` argument. Health Connect shows one
  sheet for the whole set, so asking for a permission the app does not need risks
  the ones it does.
- `healthConnectStatusChanged` event, fired after a request or revoke and on
  every foreground where the status moved — which is how an app notices the user
  granting or revoking from outside it.
- **`HealthPrivacyPolicyActivity` and both of its intent filters now ship with
  the library.** Health Connect requires every app requesting health permissions
  to answer "why do you want this?", and a missing rationale screen is the most
  common Play rejection for health apps. Set `privacyPolicyUrl` in
  `initialize()`; overriding the activity with your own is documented in
  [INSTALLATION.md](docs/INSTALLATION.md#health-connect-rationale-screen).
- `healthConnectWriteEnabled` config, for apps that want to display another
  source's data without adding a second copy of their own.
- `useHealthConnect()` hook wrapping the status, the source list and every
  action a settings screen needs.
- Fourteen JVM unit tests covering source resolution, chiefly that a phone and a
  watch are never added together. Run with `./gradlew test` — no device needed.

### Changed

- `getTodaySteps`, `getStepsForDate`, `getYesterdaySteps`, `getStatsForRange`,
  the weekly/monthly/yearly stats, the `stepsChanged` event and the foreground
  notification all report the resolved step count and carry a `stepSource`
  object naming where it came from. With no Health Connect read grant this is
  exactly the previous behaviour, so nothing changes for an app that does not
  use Health Connect.
- `syncWithHealthConnect()` skips days a wearable already owns rather than
  writing them, and reports them in the new `skippedRecords` field. Writing this
  phone's parallel count of the same walk would leave every other app reading
  Health Connect with both copies of it.
- Health Connect sync now requires only the *write* grants. Previously a user
  who allowed writing but refused reading got no mirror at all, even though every
  call the sync makes was permitted.
- Raw record reads are paged. Health Connect caps a response at 5000 records and
  returns a continuation token; ignoring it truncated busy days, and some watches
  write one record per minute.
- `getHistory()` deliberately still returns this device's stored rows verbatim,
  because its `synced` / `syncedRemote` flags describe local records.
- Goals stay keyed off this device's own count. A wearable's total arrives in
  jumps whenever its companion app syncs and can move backwards between them, so
  driving `goalReached` off it would fire twice for one day.
- `package.json` gained `repository`, `homepage` and `bugs`. Without
  `repository`, npm cannot resolve the README's relative links to `docs/`, so
  they were dead on npmjs.com.

### Fixed

- The rationale intent filter documented for host apps used the wrong action and
  category strings in the library's own manifest during development; the shipped
  entries use `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` and
  `android.intent.category.HEALTH_PERMISSIONS`.

### New error codes

`E_HEALTH_CONNECT_NOT_INSTALLED` and `E_HEALTH_CONNECT_UPDATE_REQUIRED`, both
answered by `installHealthConnect()`.

## [1.1.0] - 2026-09-08

### Changed

- **Room annotation processing moved from KSP to kapt.** A KSP plugin version has
  to match the Kotlin plugin version exactly, and a library cannot know which
  Kotlin its host app will bring — pinning one broke every app on a different
  Kotlin, and the KSP suffix cannot be derived from the Kotlin version. kapt
  ships inside the Kotlin Gradle plugin, so it matches the host automatically.
  There is no longer a `kspVersion` to set.
- Default Kotlin raised to 2.0.21, Room to 2.7.2. Room 2.6.1's bundled
  `kotlinx-metadata` rejects anything above Kotlin 2.0, and Room 2.7 itself needs
  Kotlin 2.0+.
- **Breaking:** `react-native` peer dependency raised from `>=0.74.0` to
  `>=0.77.0`, following from the Kotlin 2.0 floor above. On react-native
  0.74–0.76 the package still works if you pin `kotlinVersion = "1.9.24"` and
  `roomVersion = "2.6.1"`.

## [1.0.0] - 2026-09-06

Initial release.

- Hardware step counting via `TYPE_STEP_COUNTER`, falling back to
  `TYPE_STEP_DETECTOR`, with anchor-based reconciliation that survives reboots,
  OEM counter resets, process death and timezone changes in either direction.
- Foreground service (`health` type, `START_STICKY`) that keeps counting with the
  app closed, the screen locked or the process killed.
- Room storage — `step_history` and `daily_summary` — with configurable
  retention.
- Boot recovery via `BootReceiver`.
- Health Connect read, write and idempotent sync, with stable `clientRecordId`s
  so re-syncing a day replaces rather than duplicates.
- Daily, weekly and monthly goals with progress events.
- Optional remote sync to an HTTPS endpoint, queued through WorkManager.
- Battery-optimisation and manufacturer autostart helpers.
- Turbo Module with an old-architecture shim, and full TypeScript types.
- `useStepTracker` and `useStepStats` hooks.

[1.2.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.2.0
[1.1.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.1.0
[1.0.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.0.0
