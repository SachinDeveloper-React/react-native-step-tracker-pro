# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] - 2026-09-11

The release for phones that kill services and users who also wear a watch:
steps counted while the process was dead are recovered instead of dropped, a
Health Connect number keeps moving with the phone's own sensor instead of
freezing between syncs, and the package brings itself back on OEM skins that
ignore `START_STICKY`.

### Added

#### Live continuity between Health Connect and the phone's sensor

- Under `stepSource: 'auto'`, a Health Connect source that is ahead of the
  phone no longer freezes the displayed number at its last upload. Its *lead*
  over the phone becomes the day's baseline and the phone's own sensor keeps
  counting on top: 6,000 from Health Connect becomes 6,001, 6,005, 6,010 as
  the user walks. The next read raises the baseline only when the other app
  has genuinely seen more than the phone, so nothing is counted twice, and
  the baseline never shrinks within a day, so the shown number is monotonic.
  See [USAGE_MODES.md § C](docs/USAGE_MODES.md#mode-c-both-recommended).
- `ResolvedStepSource.merged` and `baselineSteps` report when a number is a
  baseline plus live delta, and by how much.
- The baseline is persisted, so it survives the source cache expiring and
  the process dying; it is cleared at midnight, on `resetToday()`, on a
  policy or pin change and on a revoke.
- The sensor path asks for a fresh Health Connect read of today's sources
  when the cache has expired (throttled to once a minute), so a watch that
  syncs mid-walk reaches the notification and `stepsChanged` without the app
  being opened — given `READ_HEALTH_DATA_IN_BACKGROUND`.
- `'wearable'` and `'health_connect'` are unchanged: they promise the other
  app's exact number.

#### Gap recovery

- Steps the hardware counted while the service was dead are now **claimed**
  after any blank-anchor event — the midnight rollover, `resetToday()`, a
  wall-clock correction, an OEM kill spanning any of them — rather than
  dropped. Inside one day they go to today; across midnight they are spread
  between the days in proportion to time. The rollover now preserves the
  time of the last reading so the split has something to go on.
- `gapRecovery` config: `'split'` (default), `'today'`, `'drop'`. `'drop'`
  restores the pre-1.3 behaviour for apps where an over-credit is worse than
  a loss.
- A proven reboot on a previous day spreads the since-boot steps the same
  way instead of dropping them. A fresh install that has never seen a
  reading still claims since-boot steps only when the boot was today, and
  never invents history.
- Backfilled shares for past days go through a new
  `StepRepository.addToDay()` and are re-queued for sync.

#### OEM and low-end phone handling

- **Foreground restart.** When the app comes to the foreground — or calls
  `initialize()` or `getTrackingHealth()` — and the persisted state says
  tracking is on but no service is alive, the service is started. A
  foregrounded app may always start its own foreground service, so this
  works on every OEM with no exemption.
- **`WatchdogWorker`**, every 15 minutes while tracking, restarts a killed
  service. On Android 12+ this needs the battery-optimisation exemption,
  which is why the OEM guidance asks for it. `watchdogEnabled` config.
- **Heartbeat.** The service stamps a heartbeat every 60 s from its sensor
  thread, so `heartbeatAgeMs` says how long ago it was last known alive even
  when the user is still.
- `getTrackingHealth()`: `serviceAlive`, `looksDead`, `heartbeatAgeMs`,
  `recoveryCount`, `lastRecoveryReason` (`sticky` / `boot` / `watchdog` /
  `foreground` / `initialize`), `aggressiveOem`, `batteryOptimizationEnabled`.
- `getBackgroundRestrictionStatus()`: `aggressiveOem`,
  `batteryOptimizationEnabled`, `autoStartSettingsAvailable`,
  `backgroundStartNeedsExemption`.
- `requestBackgroundPermissions()`: the battery exemption then the OEM
  autostart screen, one per call, returning which was shown.
- `openManufacturerAutoStartSettings()` now covers Xiaomi/Redmi/POCO
  (autostart and power keeper), OPPO/realme/OnePlus, vivo/iQOO, Huawei/Honor,
  Tecno/Infinix/itel, Samsung, ASUS, Meizu, Nokia/HMD, Letv and Lenovo, with
  several component names per skin, and tries the direct start even when
  resolution fails.
- `trackingStateChanged` reasons `restored_sticky`, `restored_boot`,
  `restored_watchdog`, `restored_foreground`, `restored_initialize`.
- [docs/OEM_BATTERY.md](docs/OEM_BATTERY.md) with per-manufacturer
  instructions to show the user.

#### Accelerometer fallback for phones with no step sensor

- `AccelerometerStepDetector`, a software pedometer used only when the device
  exposes neither `TYPE_STEP_COUNTER` nor `TYPE_STEP_DETECTOR`: magnitude,
  gravity removal, jitter smoothing, hysteresis peak detection, cadence
  gating (250 ms – 1.2 s) and a four-step warm-up, all time-based so the
  delivery rate does not matter. Sampled at 25 Hz with one-second batching,
  preferring the wake-up variant of the sensor and holding a partial wake
  lock otherwise. Config: `accelerometerFallback` (default true),
  `accelerometerWakeLock` (default true), `accelerometerThreshold` (0.9 m/s²).
- `SensorSource` gains `'accelerometer'`. `getDeviceCapabilities()` gains
  `hasAccelerometer`, `hasWakeUpAccelerometer` and `bestSensor`, and
  `supported` now honours the fallback flag.
- Eighteen JVM tests against synthetic gait in two shapes — sinusoids and
  impulse-shaped heel strikes with a rebound: walks at 1.2–2 Hz, a shuffle at
  0.9 Hz, a run at 2.8 Hz, 20 / 25 / 50 Hz sampling, a still phone with heavy
  sensor noise, a car's 3–12 Hz vibration and a bus rocking at 0.5–0.7 Hz.

#### Health Connect's own on-device steps

- Health Connect on Android 14 with SDK extension 20+ records the phone's
  steps itself under the `android` origin, or under a per-device, per-app
  `com.android.healthconnect.phone.<hash>` synthetic package after the June
  2026 provider update. Both are now recognised: `kind: 'phone'`, named
  "This phone (Android)", flagged `isPlatform: true` on `StepSource`, and
  never treated as a wearable regardless of the `Device` stamp. Previously
  they surfaced as `unknown`.

#### OEM screen discovery

- When none of the hard-coded autostart components resolves,
  `openManufacturerAutoStartSettings()` scans the OEM manager packages
  present on the device for an exported activity named like an autostart or
  battery screen, so firmware nobody has catalogued still lands on the right
  area instead of app info. `getBackgroundRestrictionStatus().autoStartTarget`
  reports the `package/class` it will open.

#### Health Connect permissions scoped to config

- `healthConnectReadEnabled` config (default true). With it off the `READ_*`
  permissions are never requested and every policy behaves like `'device'`.
- With `healthConnectWriteEnabled: false` the `WRITE_*` permissions are no
  longer requested either. Previously a display-only app still put six
  permissions on the sheet and had to justify all six to Play.
- `HealthConnectStatus.granted` now means "everything this config needs";
  `readRequired` / `writeRequired` say what that is. `canRead` / `canWrite`
  are unchanged.
- The sync worker is only scheduled when writes are enabled.

#### Docs

- [USAGE_MODES.md](docs/USAGE_MODES.md): native sensor only, Health Connect
  only, and both — config, manifest trims, permissions and Play forms for
  each, side by side.
- [PERMISSIONS.md](docs/PERMISSIONS.md): every permission, the order to ask,
  the denial matrix.
- [PLAY_STORE_COMPLIANCE.md](docs/PLAY_STORE_COMPLIANCE.md) reorganised per
  mode, with accurate guidance on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### Changed

- Goals now follow the displayed number rather than the phone's raw count.
  Under `'auto'` the displayed number is monotonic for the day and
  `GoalTracker` fires once per period regardless, so the double-fire the old
  rule guarded against cannot happen.
- Long Health Connect windows (over 35 days — yearly stats) are read through
  the aggregate API per origin instead of raw records. A raw read is capped
  at 50,000 records, which a watch writing one record a minute fills in
  about five weeks; past that the old path silently truncated the year.
- Every write to the day tables now runs on one single-threaded dispatcher in
  submission order. A rollover and a backfill for the same day arriving in
  one sample could previously race.
- `initialize()` sends only the keys the caller passed. Spreading the JS
  defaults sent `height: 170` on every `initialize({ dailyGoal })` and reset
  a returning user's stride.
- Stride is derived on the native side from stored height and sex. JS
  passes an explicit `strideLength` through, sends `0` when height or sex
  changes so derivation resumes, and otherwise leaves the key alone.
  `updateConfig({ sex })` no longer needs a `getConfig()` round trip.
  `estimateStride(height, sex)` is exported for UIs that want the number.
- The notification's throttle now schedules a trailing redraw, so the shade
  never sits on the count from one step ago after the user stops walking.
- `initialize()` no longer re-sends `ACTION_START` to a service that is
  demonstrably alive.

### Fixed

- `useHealthConnect` computed its source window with `toISOString()`, which
  is UTC: at 02:00 IST the "today" key was yesterday's and today's sources
  were excluded from the list.
- `openManufacturerAutoStartSettings()` always fell back to the app-info page
  on Android 11+, because the OEM manager packages were not declared in
  `<queries>` and so never resolved.
- Steps between the last sample before midnight and the first after it were
  dropped on every rollover.
- Steps between the last sample and a wall-clock correction larger than 60 s
  were dropped, even though the counter had not restarted.

### Tests

- 33 new JVM tests: `StepGapSplitterTest` (6), `StepContinuityTest` (8) —
  covering the 6,000 → 6,001 → 6,005 → 6,010 scenario, "next sync agrees, no
  double count", "phone on a desk raises the baseline" and "never sums" —
  `AccelerometerStepDetectorTest` (18), and platform-origin classification
  in `StepSourceResolverTest`.
- Instrumented engine tests grown from 11 to 22, now deterministic in wall
  clock; new cases for the overnight kill, the `drop` and `today` policies,
  and fresh installs on an old boot.

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

[1.3.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.3.0
[1.2.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.2.0
[1.1.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.1.0
[1.0.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.0.0
