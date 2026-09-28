# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.3.4] - 2026-09-28

### Fixed

In-memory intervals were measured on the wall clock. After the user set the
phone's clock back - hours, by hand; automatic time and time zone changes do
not do this - "now minus then" went negative and each interval held until
the clock caught up. All are measured on `SystemClock.elapsedRealtime()`
now:

- **Live step events stopped.** The `stepsChanged` throttle held every step,
  and its trailing event waited as long as the clock had moved, so the
  app's live count - `useStepTracker` included - froze for hours. The
  notification, already on the uptime clock, and `getTodaySteps()` were
  unaffected.
- **The Health Connect grant cache** looked fresh, so a permission granted
  or revoked meanwhile was not seen by reads; `getHealthConnectStatus()`
  reads fresh and was right.
- **Today's cached sources** did not expire, so a watch's steps looked stuck
  in the app. Signed snapshots read fresh.
- **The once-a-minute source refresh** stopped, so the notification stopped
  picking up the watch.
- **The once-a-minute integrity check** stopped; the end-of-day verdict and
  the `clock_changed` event were not affected.
- `getTrackingHealth().heartbeatAgeMs` reads -1 rather than a negative age
  when the stored heartbeat lies in the future of a clock set back.

Wall times stay where a time of day is meant - record and event timestamps,
the clock-change detector, `bootId`.

## [2.3.3] - 2026-09-28

Published by hand, without npm provenance.

### Fixed

- **The per-day refill's time is measured on the uptime clock.** Its
  deadline and budget came from the wall clock, so a clock set back during
  a range read could hand the refill more time than was left and time the
  range out; set forward, less than there was. They now come from
  `SystemClock.elapsedRealtime()`, the clock the range's own timeout runs
  on.

## [2.3.2] - 2026-09-28

Published by hand, without npm provenance.

### Fixed

- **The per-day refill could still push a range past its timeout.** Its 8
  seconds were counted from when it started, not against the range's 15:
  after a slow first read the two together could run over, and the whole
  range fell back to the phone's count. It now takes 8 seconds or what is
  left of the range's time less 2 seconds for the aggregate fill, whichever
  is less, and none at all when too little is left - the gap then comes
  from aggregates straight away.
- A stale doc comment on retention, left above the new one in 2.3.0.

## [2.3.1] - 2026-09-28

Published by hand, without npm provenance.

Fixes from a review of 2.3.0.

### Fixed

- **`useStepStats` could show a stale window.** Loads overlap - a new
  period, midnight, a live refresh - and a slow answer for the old window
  could land after the new one. Only the newest request's answer is shown
  now. Rolling windows still refresh live: they always end today, whatever
  `offset` says.
- **The per-day refill could time out a whole range.** With
  `healthConnectIgnoreManualEntries` on, the days a busy read missed were
  re-read one after another inside the range's 15 seconds. They are read
  four at a time now, within 8 seconds; a day that budget does not reach is
  answered from aggregates, losing only its manual split rather than the
  whole range falling back to the phone's count.
- **Raising the goal did not fire `goalReached` again.** The marker was the
  period alone, so after 5,000 was reached, reaching a new goal of 10,000 the
  same day fired nothing. The goal celebrated is stored with it: a higher
  goal fires again, a lower one does not.
- **Days kept for upload lost their minute evidence.** 2.3.0 kept a day not
  yet uploaded, with its summary and verdict, but still deleted its minutes
  and integrity events, so its `full` upload reported zero minutes. They are
  kept with the day now.

### Docs

- `hasAttestationKey()` means `attestDevice()` ran. On a device that refuses
  attestation it made an unattested key, and this is true for it;
  `getAttestationKeyInfo().attested` says whether the hardware vouched.

## [2.3.0] - 2026-09-28

Published by hand, without npm provenance.

Fixes from a review of 2.2.1. Two defaults change - see Changed.

### Fixed

- **A command after stop crashed the app.** A command reaching the service
  after tracking stopped - a Pause button left on screen, a config change
  racing a stop - made it stop without `startForeground()`, and Android
  killed the app with `ForegroundServiceDidNotStartInTimeException`. It now
  promotes first, as the stop command always did. Reproduced on 2.2.1 on an
  emulator; gone on 2.3.0.
- **A key made by a signed snapshot passed for an attested one.** Signing
  before any `attestDevice()` made an unattested key, and
  `hasAttestationKey()` then returned true: an app that checked it never
  attested, and uploads started carrying signatures from a key no server
  had, which signature auth also accepted. Only a key from `attestDevice()`
  counts now, for all three. `DeviceAttestation.createdBy` says which made
  the current key.
- **An empty `sources` in a snapshot could mean a failed read.** A timeout or
  an error gave `sources: []`, signed. The snapshot now reads sources fresh
  with 15 seconds, and carries `sourcesStatus` (`'read'`, `'not_consulted'`,
  `'timed_out'`, `'failed'`) inside the signature. Each record of a `full`
  upload carries it too.
- **`stopTracking()` could be undone.** It only sent the service a command,
  which Android may refuse while the app is in the background, so
  `shouldAutoStart` stayed on and tracking returned at the next launch or
  reboot; the snapshot it returned still said `running`. The module now
  records the stop itself, and a service that misses the command stops on
  its next heartbeat.
- **Retention deleted days that were never uploaded.** An endpoint down for
  longer than `historyRetentionDays` lost those days for good. Days a sync
  target in use has not accepted are now kept, up to a year.
- **Distance and calories were all-or-nothing.** A range read that hit the
  cap dropped even today's measured distance. They are read newest first
  like steps now, and only the days the read did not reach are derived.
- **With `healthConnectIgnoreManualEntries` on, days filled from aggregates
  kept their manual entries**, so a range could count what the day view
  left out. Those days are read again one at a time instead.
- **Cancellation was swallowed** by the single-day sources read, which then
  carried on in a cancelled coroutine.
- **Steps could be lost on phones that count with the step detector**: the
  non-wake-up detector dropped steps once its buffer filled with the screen
  off. The wake-up variant is used where the phone has one.
- **`useStepStats` only reloaded at midnight.** It now also reloads on
  foreground and, for a window with today in it, at most every 30 seconds
  while steps come in, without flipping `loading`.
- **A custom `notificationIcon` removed by resource shrinking fell back
  silently.** It is logged now, the keep rule is documented, and the Expo
  plugin writes it.

### Changed

- **The notification is private on the lock screen.** Steps are health
  data: on a phone set to hide sensitive content when locked, it shows
  "Counting steps" and no count. `notificationLockScreen: 'public'` restores
  the old behaviour. Existing notification channels are left as they are;
  the setting lives on each notification.
- **`hasAttestationKey()` is false for a key only a snapshot made**, so an
  install that never attested attests once after upgrading.

### Added

- `notificationLockScreen`, `DeviceAttestation.createdBy`,
  `VerificationSnapshot.sourcesStatus`, `STATS_LIVE_REFRESH_MS`.
- Expo plugin: `healthConnect: false` removes the Health Connect
  privacy-policy activity and alias from an app that never uses Health
  Connect; `notificationIcon` writes the keep rule.

### Docs

- The device integrity hints are called what they are: Magisk hides root
  from all of them, so they are hints for review, and Play Integrity and
  key attestation are what a server trusts.
- An older build over a newer one drops the database, unsynced days
  included - documented rather than changed, since the alternative is a
  crash on every launch.

## [2.2.1] - 2026-09-28

Published by hand, without npm provenance.

Fixes from a review of 2.2.0.

### Fixed

- **An incomplete steps read no longer drops the newest days.** A per-source
  read over a range - monthly stats, the sources list - read oldest first and
  stopped at its cap of 60,000 records, which a watch and Health Connect's
  own count writing a record a minute reach in about three weeks: the most
  recent days, today included, lost the watch and showed the phone's count.
  Steps are now read newest first, and the day the read stopped in and every
  day before it are answered from each app's daily totals through the
  aggregate API. On an emulator with 92,160 records over 32 days, 2.2.0
  returned exactly 60,000 steps for a 31-day window; 2.2.1 returns them all.
- **A range read that runs out of time says so.** Range stats and the
  sources list gave up after 4 seconds and fell back to this device without
  a word. They now allow 15, and `RangeStats.healthConnect` and
  `StepSourceList.healthConnect` report `'read'`, `'not_consulted'`,
  `'timed_out'` or `'failed'`; `useHealthConnect` keeps its last sources on a
  read that did not finish.
- **Read types and writes the app set itself must be declared again.** 2.2.0
  skipped any undeclared optional permission quietly, including a
  `healthConnectReadTypes` entry the app listed and writes it turned on
  itself - a mistake that should show at once. Only the defaults are skipped
  now; what the app sets itself rejects with `E_HEALTH_CONNECT_NOT_DECLARED`,
  as before 2.2.
- **Refusing to let the app write is not a refusal.** With reads on,
  `stepsGranted` needed `WRITE_STEPS` too, so a user who allowed reading and
  unticked writing was counted as refusing and asked again. Reading steps is
  enough now; `WRITE_STEPS` counts only for an app with reads off.

## [2.2.0] - 2026-09-28

Published by hand, without npm provenance.

Fixes from a review of 2.1.1, and the additions they needed.

### Fixed

- **Unticking distance is no longer a refusal.** A request after which the
  user allowed steps but not distance counted towards Health Connect's
  two-refusal limit, and `enableHealthConnect()` - waiting for everything
  config asks for - showed the sheet again on every call, then sent the
  user to Health Connect's settings on every call while steps worked. Only
  a refusal of steps counts now, `shouldOpenSettings` is about steps, and
  `enableHealthConnect()` is done once steps are allowed.
- **A partial distance read is not reported as measured.** A per-source read
  that failed part way or stopped at the page cap kept what it had read:
  labelled `health_connect` at the cap, `not_read` with a non-zero value
  after a failure. An incomplete read is now discarded - distance zeroed,
  `not_read`, derived - and the same goes for calories; an incomplete
  active-calories read reports `-1`. The cap is raised from 50 to 60 pages
  so 35 days of a watch writing a record a minute fits.
- **`authFailed` no longer depends on the clock.** It compared wall-clock
  times, so a clock moved between a refusal and new credentials could keep
  or clear it wrongly. It now compares a counter bumped on every credential
  change with the one each attempt read, and the order of attempts.
  Timestamps stay, for display. A refusal stored by 2.1 is not reported
  after the upgrade; the next scheduled upload files it again.

### Added

- **`getSignedSnapshot(date, options)`** returns only the signature block. A
  signed `getVerificationSnapshot()` carries the snapshot twice, as an
  object and as `signedPayload`.
- **`minutesStatus` and `motionWindowsStatus`** on a snapshot that
  `include`s them: `'enabled' | 'disabled'`, so an empty list is not
  ambiguous.
- **`HealthConnectStatus.stepsGranted`**: steps allowed for everything config
  turns on.

### Changed

- **The permission request fits the manifest.** Writes are on by default,
  and an app that declared no `WRITE_*` got `E_HEALTH_CONNECT_NOT_DECLARED`
  from its first request. What the package can do without - the write set,
  distance and calories - is now not asked for when undeclared, and
  `writeRequired` reads `false` without `WRITE_STEPS`. An undeclared
  `READ_STEPS` or explicit opt-in still rejects. `undeclaredPermissions`
  still lists all of them. Turning writes off by default is still planned
  for 3.0.

## [2.1.1] - 2026-09-27

Published by hand, without npm provenance.

### Fixed

- **A partial Health Connect grant no longer turns Health Connect off.** The
  sheet lets the user untick any single permission, and every earlier
  release read only when all three read permissions were granted and wrote
  only when all three write permissions were: a user who allowed steps but
  refused distance got no watch steps and no mirror, with no error. Steps
  alone now suffice for both. Distance and calories are read and written
  when granted and derived from stride when not, and ungranted types are no
  longer asked for on every read.

### Added

- `HealthConnectStatus.canReadSteps`, `grantedReadTypes`, `canWriteSteps`
  and `grantedWriteTypes`: what the user actually allowed. `useHealthConnect`
  loads sources on `canReadSteps`. `granted` and `canRead` keep their
  meaning.
- `distanceSource` on every `StepSource` (`'health_connect' | 'derived' |
  'none' | 'not_read'`) and on the resolved day (`'health_connect' |
  'derived'`), so a server checks distance against steps only where the
  distance was measured.

### Changed

- Cached Health Connect sources are dropped when the grants change, so a
  distance the user allows later shows up at once instead of up to ten
  minutes on for a past day.

## [2.1.0] - 2026-09-27

Published by hand, like 2.0.1, so it carries no npm provenance.

Additive: the evidence a server-side verifier scores, signed with the
totals; background upload failures that survive until the app looks; fewer
Health Connect permissions for apps that read steps alone. Nothing existing
changes shape unless asked.

### Added

- **`getVerificationSnapshot(date, { include })`** puts the evidence behind
  a day's totals inside the signed payload: `'minutes'` (the per-minute
  buckets), `'motionWindows'` and `'healthConnectRecords'` (the raw records,
  of `healthConnectRecordTypes`, default steps). One signature and one Play
  Integrity `requestHash` then cover what a server scores for cadence or
  motion, where before only the totals were signed and a modified app could
  send doctored minutes beside them. `include` is echoed; the records carry
  a `status` that says why they are missing when they are. Without
  `include` the snapshot is the 2.0 shape, and `schemaVersion` stays 2.
- **`getSyncStatus()`** returns what remote uploads last did, stored across
  process deaths: last attempt and success, consecutive failures, the last
  failure with its reason and HTTP status, pending days, and `authFailed` -
  the credentials were refused and nothing has changed since. Uploads run
  in WorkManager, usually with no JS to hear `syncAuthFailed`; the refusal
  used to be lost, and every later run failed the same way unnoticed.
- **`healthConnectReadTypes`** config, default
  `['steps', 'distance', 'totalCalories']`. An app that reads steps alone
  sets `['steps']` and declares and asks for `READ_STEPS` only; distance and
  calories on a day answered from Health Connect are then derived from the
  step count. The Expo plugin takes the same list as `healthConnect.readTypes`.
- **Distance records** in `getHealthConnectRecords(start, end, { recordTypes })`
  and in change tracking through
  `getHealthConnectChangesToken({ recordTypes })`, for per-interval distance
  checks. Records now carry `recordType`; distance ones carry
  `distanceMeters` in place of `count`. Steps alone remains the default, and
  each type needs its own read grant.
- **`prepareIntegrity(cloudProjectNumber)`** prepares Play Integrity's token
  provider ahead of the first `requestIntegrityToken()`, which otherwise pays
  for it.
- **`StepTrackerError.details`.** `E_INTEGRITY_FAILED` carries
  `{ playErrorCode, playError, retryable }`, and the docs list which of
  Play's codes are temporary.

### Changed

- `HealthConnectStatus.canRead` is measured against `healthConnectReadTypes`;
  with the default it is the same three permissions as before.
- A raw Health Connect read whose grant is revoked mid-call rejects with
  `E_HEALTH_CONNECT_DENIED` rather than `E_UNKNOWN`, and the message names
  the missing permission.

## [2.0.1] - 2026-09-27

Fixes from a review of 2.0.0. No API changes.

### Fixed

- **`updateConfig()` re-derives weekly and monthly goals.** `initialize()`
  derived them as the daily goal × 7 and × 30; `updateConfig()` did not, and
  the native side kept the old values. Since 2.0 `useStepTracker` sends every
  config change through `updateConfig()`, so a user who changed their daily
  goal kept weekly and monthly goals from the old one. A patch that sets them
  explicitly keeps its own.
- **`updateConfig()` validates like `initialize()`.** It skipped every check -
  height and weight ranges, the fraud thresholds, the `https` rule for
  `remoteSyncUrl`, `wearableAllowlist` - so bad values were clamped natively
  instead of rejected. An `http://` endpoint is still accepted when an
  earlier call set `remoteSyncAllowHttp`.
- **Both now reject values that were only clamped before:** negative goals,
  throttles and sync intervals, a stride over 3 m, a `NaN` daily goal, and
  unknown `sex`, `stepSource`, `wearableTrust`, `gapRecovery` and
  `remoteSyncPayload` values - all `E_INVALID_CONFIG`.
- **Unknown React Native version builds the new architecture.** When
  `node_modules/react-native` is not where the build looks and
  `newArchEnabled` is not set, 2.0.0 built the old-architecture code, which
  React Native 0.82+ cannot run. An explicit `newArchEnabled=false` still
  builds the old one.
- **The AGP lookup for `com.android.legacy-kapt`** tries every class loader
  that can see the app's AGP, not only the root buildscript's. An app with
  AGP in a `plugins {}` block built with 2.0.0 too in testing - React
  Native's Gradle plugin puts AGP on the root classpath anyway - so this
  covers setups without that plugin.
- **`schemaVersion` docs.** It was described as `2` from 1.5, with "absent"
  meaning the 1.4 shape. No 1.x release sent it: a snapshot without it is
  the 1.5 shape if it carries `integrity`, the 1.4 shape if not, and both
  parse as version 2 with fields missing.

### CI

- A fourth compatibility build: React Native 0.77.3 with the new architecture
  turned off, the path events take on the old architecture.
- The tag workflow stopped at `npm publish` for want of an `NPM_TOKEN`
  secret, so 2.0.1 was published by hand and carries no npm provenance,
  like 2.0.0.

## [2.0.0] - 2026-09-27

Current toolchains, a manifest that declares only what sensor counting
needs, and the pieces a server-side verifier was missing. Breaking changes
are listed first, with what to do about each in
[Migrating from 1.x](#migrating-from-1x).

### Breaking

- **Minimal library manifest.** The library now merges in only
  `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`,
  `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK`. The Health
  Connect permissions and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are declared
  by the app, and only when it uses them - no app inherits a permission it
  then has to justify to Play. A config that asks for an undeclared Health
  Connect permission rejects with the new `E_HEALTH_CONNECT_NOT_DECLARED`,
  naming the missing entries, instead of Health Connect silently leaving them
  off its sheet; `HealthConnectStatus.undeclaredPermissions` lists them up
  front. Without the battery permission `requestDisableBatteryOptimization()`
  opens the settings list, and `BackgroundRestrictionStatus.directPromptAvailable`
  says so.
- **Typed events on the new architecture.** Events reach JS through
  codegen-typed emitters (`onStepsChanged` and so on) instead of
  `RCTDeviceEventEmitter`. `StepTracker.addListener` and the hooks handle both
  architectures; code that subscribed to `StepTrackerPro:<event>` through its
  own `NativeEventEmitter` stops receiving events on the new architecture.
- **`useStepTracker` applies config changes.** It used to read config once,
  on mount, and silently drop every later change. It now pushes a change
  through `updateConfig()`, compared by value so a new object literal on each
  render is not a change. An app whose config legitimately changes will now
  see it take effect.
- **Refused uploads are not retried.** A 401 or 403 from `remoteSyncUrl`
  raises `syncAuthFailed` and ends the attempt; retrying the same credentials
  only earned more refusals. Other failures back off and retry as before.
- **The codegen spec changed** (new methods, the emitters, a second argument
  to `getVerificationSnapshot`), so the app's native code has to be rebuilt -
  as for any native module upgrade.

### Build and toolchains

- Builds on **AGP 9** with `android.builtInKotlin` either way. Built-in Kotlin
  refuses the Kotlin Android and kapt plugins, so the library applies AGP's
  `com.android.legacy-kapt` and adds the `gradle-kotlin` artifact that
  provides it - React Native apps do not have it on the classpath - at the
  host's AGP version. The architecture shims are registered as Kotlin
  sources too, which built-in Kotlin needs. `android.newDsl=true` builds as
  well.
- **React Native 0.82+ is new-architecture-only**, detected from the app's
  `react-native` package rather than trusting `newArchEnabled`, which newer
  templates may drop.
- `kotlinOptions {}` replaced with `compilerOptions`; Room's JVM-default
  requirement uses Kotlin 2.2's typed `jvmDefault` where it exists.
  `project.buildDir` replaced with `layout.buildDirectory`.
- The **Health Connect client follows compileSdk**: 1.1.0 stable from
  compileSdk 36, 1.1.0-beta01 below. `ext.healthConnectVersion` still pins.
- The library declares **minSdk 26** whatever the app says, so an app on
  React Native's template default of 24 fails the manifest merge naming this
  package.
- Inside an app the library's buildscript adds no AGP or Kotlin plugin of its
  own; only a standalone build of the package does.
- `getCurrentActivity()` replaced with `reactApplicationContext.currentActivity`
  (deprecated in React Native 0.80), and the three deprecation warnings
  Kotlin 2.2 raised in the package fixed. It compiles warning-free on RN 0.77
  and 0.87.
- **CI builds the packed library in real apps** made from React Native's
  template - 0.77.3 on AGP 8.7, 0.87.2 on AGP 9.2 with built-in Kotlin off
  and on - and checks the merged manifest carries no opt-in permission. The
  README has the compatibility table.

### Added

- **Play Integrity**: `requestIntegrityToken({ requestHash, cloudProjectNumber })`,
  a standard request bound to a signed snapshot's `payloadSha256`. Compile-only,
  like Activity Recognition: the app adds `com.google.android.play:integrity`,
  and without it the call rejects with `E_INTEGRITY_UNAVAILABLE`.
- `hasAttestationKey()` and `getAttestationKeyInfo()`, so an app attests once
  instead of replacing the key on every launch.
- `schemaVersion` and `libraryVersion` on the verification snapshot, first,
  so a server can pick a parser across releases. `schemaVersion` is 2.
- **Sealed remote-sync headers.** `remoteSyncHeaders` are encrypted with an
  AES-GCM key in the Android Keystore before they are stored; 1.x plaintext
  headers are sealed on first read.
- `syncAuthFailed` event and `remoteSyncAuth: 'signature'`, which sends no
  stored headers and authenticates uploads with the device key alone.
- `getHealthConnectRecords(start, end)`: every step record as stored - id,
  client record id, source app, recording method, device, start and end with
  zone offsets, last-modified time, count.
- `getHealthConnectChangesToken()` and `getHealthConnectChanges(token)`:
  inserted, updated and deleted step records since a cursor, with expired
  tokens reported as `tokenExpired` rather than thrown.
- `StepSource.hourlySteps` (24 local hours; every entry -1 on the aggregate
  path) and `StepSource.activeCalories` behind the opt-in
  `healthConnectReadActiveCalories`.
- `removeAllListeners(event?)`.
- **Jest mock** at `react-native-step-tracker-pro/jest`: every method a
  `jest.fn` resolving with an empty-day value, `__emit` to fire listeners,
  static hooks.
- **Expo config plugin**: declares the opt-in permissions and raises
  `android.minSdkVersion` to 26. Checked against `@expo/config-plugins` 57.
- A **publish workflow** that runs `npm publish --provenance` on a version
  tag.

### Fixed

- **Forged reboots.** `BootReceiver` logs a `reboot` integrity event only
  when `Settings.Global.BOOT_COUNT` has moved since the last one it logged,
  so an unprotected `QUICKBOOT_POWERON` from another app - or the duplicate
  some devices send - no longer writes fake reboots. The meaningless
  `priority="1000"` is gone.
- `SECURITY.md` and `CONTRIBUTING.md` are published; the changelog linked to
  both. The `author` field names the author.
- Changelog dates for 1.0.0, 1.3.0 and 1.4.0 now match the npm publish dates.

### Deprecated

- `removeListener()` with no argument. It removes every listener in the app,
  the hooks' included, and now warns once. Use `removeAllListeners()`. The
  no-argument form goes in 3.0; `removeListener(event)` stays.

### Not changed

- **Android only.** iOS stays unsupported, now stated plainly: autolinking
  skips it, `isSupported()` is false, and every method rejects with
  `E_UNSUPPORTED_PLATFORM`.
- The codegen types still come from `react-native/Libraries/Types/CodegenTypes`:
  React Native 0.77, the floor, does not export them from `react-native`.

### Migrating from 1.x

The full guide, with before-and-after examples per mode, is
[docs/MIGRATING.md](docs/MIGRATING.md). In short:

1. **Declare the Health Connect permissions you use** in
   `android/app/src/main/AndroidManifest.xml` - the snippets are in
   [docs/PERMISSIONS.md](docs/PERMISSIONS.md#what-the-library-declares-and-what-you-add).
   Delete any `tools:node="remove"` lines you added for them; they now remove
   nothing. Expo apps list the package under `plugins` instead.
2. **Declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** if you use the direct
   battery dialog; otherwise do nothing and the settings list opens.
3. **Subscribe through `StepTracker.addListener`** (or the hooks), not a
   `NativeEventEmitter` of your own.
4. **Replace `removeListener()` with no argument** by `removeAllListeners()`,
   or better, keep and `remove()` the subscriptions you create.
5. **Handle `syncAuthFailed`** if you set `remoteSyncHeaders`: refresh the
   token with `updateConfig`, then `syncNow()`.
6. **Pass config to `useStepTracker` from one component**, and expect changes
   to it to apply.
7. **Raise `minSdkVersion` to 26** if your app is still on React Native's
   default of 24.
8. Rebuild the native app.

## [1.5.0] - 2026-09-27

Integrity checks for apps that pay for steps: flag the ways a count is
faked, log what happened to the device, and let a server verify where the
numbers came from. Everything is opt-in. With `fraudDetection` left off,
nothing is recorded, every new figure is `0`, and every number is what it
was.

### Added

#### Per-minute step buckets

- With `fraudDetection.enabled`, every step this phone watches being taken
  is placed on the minute it was taken in: a new `step_minute` table,
  counts only, pruned at `historyRetentionDays`. `getStepMinutes(from, to)`
  reads it.
- A new engine hook reports each observed delta with the span it was taken
  in. Steps credited by gap recovery, and paused steps, are never observed.
- A lump after a silence — a sensor batch that overflowed with the screen
  off, or the first sample after a dead process — is parked as
  `untimedSteps` at the minute it arrived in and never judged for cadence.
  Spreading it across the gap would invent exactly the steady count the
  detector looks for.
- Each minute also counts the steps that arrived while charging, and while
  Activity Recognition said still or in a vehicle.

#### A fraud detector

- `FraudDetector` is pure Kotlin and judges a day from its minutes and
  motion windows. Strong flags: `cadence` (a minute over `maxCadenceSpm`,
  default 200), `steady_cadence` (`steadyCadenceMinutes`, default 30, in a
  row that never move by more than one step a minute: a swing gadget or
  motor), `continuous` (the excess past `maxContinuousMinutes`, default
  180), `charging`, `in_vehicle` from three minutes, `shake` (a motion
  window at 3.3 Hz or faster, hard and tonal) and `daily_volume` (the excess
  over `maxDailySteps`, default 50,000). Weak flags, evidence only:
  `activity_still`, `night`, `swing`, and `in_vehicle` under three minutes.
- `suspectSteps` is the strong flags' minutes, each counted once, plus the
  daily excess. It is on `StepSnapshot`, `DayRecord`, the verification
  snapshot and, with the checks on, the `'full'` remote payload.
- Today is re-judged about once a minute, from the sensor path while steps
  arrive and from the service heartbeat after they stop; a closing day gets
  a final verdict at rollover. Verdicts are stored in `integrity_day`.
- `getIntegrityReport(date)`: the flags with their evidence, the day's
  events, minute totals, the rules in force, charging and Activity
  Recognition state, and device hints.
- `suspiciousActivity` event, once per new flag, and an
  `onSuspiciousActivity` option on `useStepTracker`.
- Every threshold turns off at `0`. The defaults stay clear of honest
  walks, treadmill sessions and runs in the JVM tests; they are starting
  points to tune on real data.

#### Exclude mode

- `fraudDetection.mode: 'exclude'` takes `suspectSteps` out of this
  device's count before sources are resolved, and so out of everything the
  package shows or sends: `getTodaySteps()`, `stepsChanged`, the
  notification, goals, stats, the Health Connect mirror and the remote
  upload. `deviceSteps` stays raw, and `ResolvedStepSource.suspectStepsExcluded`
  says how much came out. Changing mode clears the continuity baseline.
  The default, `'flag'`, changes no number.

#### An integrity event log

- `integrity_event`, bounded to 2,000 rows and to retention:
  `clock_changed` (with the jump), `timezone_changed`, `reboot`,
  `reset_today`, `history_cleared`, `config_changed` (the keys that moved,
  logged even when the change turns the checks off), `sensor_changed`,
  `charging_started` / `charging_stopped`, `activity_changed`,
  `service_recovered` and `device_attested`. `getIntegrityEvents(from, to)`
  reads it. `clearHistory()` keeps it and logs the clear.

#### Keystore attestation and signing

- `attestDevice(challenge)` generates an EC P-256 key in the Android
  Keystore bound to your server's challenge and returns its certificate
  chain, so a server can check the device, its boot state and the app
  before trusting anything signed with it.
- `getVerificationSnapshot(date, { sign, nonce })` signs the snapshot. The
  exact JSON that was signed travels as `signature.signedPayload`, so a
  server verifies bytes it never has to rebuild. `payloadSha256` fits Play
  Integrity's `requestHash`.
- Once a key exists, every upload to `remoteSyncUrl` carries a
  `Step-Tracker-Signature` header over the exact body bytes.

#### Activity Recognition, optional

- `fraudDetection.activityRecognition` tags steps with Google's Activity
  Recognition transitions when the host app adds
  `play-services-location`. The package declares it compile-only, probes
  for it before touching it, and ships consumer ProGuard rules so an app
  without it builds and runs unchanged. No new permission; no location.

#### Health Connect late writes

- `StepSource.lateWrittenSteps`: steps from records last modified more
  than a day after they ended. Evidence for a server, never subtracted.

#### Storage

- **Room schema 5**, with `MIGRATION_4_5` creating `step_minute`,
  `integrity_day` and `integrity_event`. Nothing existing is touched, and
  the new tables start empty. Instrumented tests migrate version 2 and
  version 4 databases with real rows.

### Changed

- `getVerificationSnapshot()` takes an optional second argument and always
  carries `suspectSteps` and an `integrity` block. Both are `0` and empty,
  apart from the device hints, with the checks off.
- `initialize()` now tells a running service when it changed config, so
  receivers and schedules follow without waiting for a restart. It only
  does so on a real change, not on every launch.

### Tests

- 36 new JVM tests: the detector against synthetic days, minute
  attribution, config round-trip and clamping, the payload with integrity
  data and exclusion, and the emulator heuristic.
- 8 new instrumented tests, including one that runs the whole layer through
  the real core, database and Keystore and verifies a signed snapshot
  against the attested key.
- 12 new Jest tests for validation, the new methods and the event.

## [1.4.0] - 2026-09-18

The release for apps that pay for steps. Nothing here changes what a
consumer who touches no new config sees: the same resolved numbers, the same
events, the same remote payload. Everything is opt-in or additive.

### Added

#### Health Connect recording method per source

- `StepSource` now splits each origin's `steps` by Health Connect's
  `recordingMethod`, which the writing app stamps on every record:
  `manualSteps` (typed in by the user, `RECORDING_METHOD_MANUAL_ENTRY`),
  `unknownMethodSteps`, and the full `recordingMethods` split
  `{ active, automatic, manual, unknown }`. `steps` is still the full total.
  A hand-entered 20,000 was previously indistinguishable from a watch's
  count, which is the single easiest way to fake steps through a third-party
  app. Windows over 35 days are answered through the aggregate API, which
  carries no per-record metadata, so on those days the new fields are `-1` /
  `null` — the same limitation `stepsBeforeCoverage` has there. Every
  single-day read is exact.
- `healthConnectIgnoreManualEntries` config (default `false`). On, every
  external source competes on `steps - manualSteps` under every policy and
  even when pinned, so a typed-in number can never become the day's number;
  a source with nothing left once its manual entries are out has no count to
  offer and the phone answers. A manual entry's share of the pre-coverage
  steps is taken out too, so a hand-entered morning cannot survive as "steps
  from before install". Distance and calories for a reduced source are
  re-derived from the steps that remain. Turning the flag on or off clears
  the day's continuity baseline, so a baseline taken from a typed-in total
  does not survive until midnight.
- `ResolvedStepSource.manualStepsExcluded`: how much was taken out of the
  source that was evaluated. `externalSteps + manualStepsExcluded` is what
  Health Connect's own screen shows for it, so a UI can explain the
  difference. `0` whenever the flag is off.
- The records are still read — `READ_STEPS` covers them, there is no separate
  permission — and still listed per source; only the resolved number changes.
  [PLAY_STORE_COMPLIANCE.md](docs/PLAY_STORE_COMPLIANCE.md#on-manual-entries)
  says so for the health declaration.

#### Wearable trust under `auto` is opt-in strict

- `wearableTrust` config, `'metadata'` (default, unchanged behaviour) or
  `'catalog'`. Under `auto`, a candidate is trusted for its whole margin over
  the phone when it is a wearable — and by default "is a wearable" comes
  from the `Device.type` the writing app stamped on its records, which any
  app can set to `TYPE_WATCH`. That is fine for display and wrong for an app
  paying per step. Under `'catalog'` only a package the built-in catalog
  knows as a wearable's companion app, or one on the new `wearableAllowlist`
  config, earns that trust; an unlisted package that stamps a wearable type
  keeps `kind: 'watch'` for display and is bound by the coverage rule like a
  phone-side app. Pins, and the `wearable` / `health_connect` policies, are
  unaffected.
- `StepSource.trustedWearable` says which rule applied to each source.
  `isWearable` keeps describing the display classification.
- Changing `wearableTrust` or `wearableAllowlist` clears the day's
  continuity baseline, like a policy change.

#### Gap recovery is auditable and can leave closed days alone

- `recoveredSteps` on `DayRecord` and `StepSnapshot`: of this device's count
  for the day, how many steps gap recovery credited in one go — today's
  share of an overnight kill, a reboot's since-boot steps, an install's
  since-boot claim — rather than observing sample by sample. An
  apportionment is an estimate, and a server judging a day wants it
  separately. Stored as `daily_summary.recoveredSteps`, kept in the engine's
  atomic counter state so it cannot drift from the total, grown by exactly
  what a backfill adds to a past day, and `0` for a day counted live.
- **Room schema 3**, with a real `Migration(2, 3)` that adds the column
  with a default of `0` — the only honest value for a day whose split was
  never recorded. No destructive fallback: users have 35 days of history in
  that table. An instrumented `MigrationTestHelper` test builds version 2
  from the exported schema, migrates real rows and checks every one is
  still there.
- `historyBackfilled` event, `{ date, addedSteps, totalSteps, reason: 'gap'
  | 'reboot' }`, fired once per affected past day after the repository
  write commits. Until now `StepRepository.addToDay()` changed a previous
  day's stored total with no signal to JS; an app that had already paid for
  that day had no way to know its number moved. `dayChanged` is unchanged.
- `gapRecovery: 'today_capped'` and `gapRecoveryMaxSteps` (default 20,000):
  like `'today'`, but a recovery credits at most the cap to the active day
  and drops the rest, so a counter glitch after a week-long kill cannot
  mint 100,000 steps on one day. Closed days never change under it. The
  `GapRecovery` documentation now recommends `'today_capped'` or `'drop'`
  for apps where a settled day must never change after the fact.
- The policy application moved out of the engine into
  `StepGapSplitter.apply()`, pure and JVM-tested for all four values; the
  engine instrumented tests cover `today_capped`, the recovered share
  through a rollover, a reset and a re-seed, and the backfill reason.

#### One verification snapshot for server-side ingest

- `getVerificationSnapshot(date)`: everything a server needs to judge a day,
  with nothing resolved for it — this phone's own `deviceSteps` and
  `recoveredSteps`, the `sensor`, `coverageStartAt`, every Health Connect
  origin unresolved and `self` included (each with `manualSteps`,
  `recordingMethods` and `trustedWearable`), what the policy `resolved` for
  comparison, the device `capabilities`, the service's recovery `health`,
  and a `clock` block that puts the wall clock next to an
  `elapsedRealtime`-derived boot id so a server can spot clock edits. Apps
  that verify server-side were stitching this together from four calls.
  The JS wrapper rejects a malformed date with `E_INVALID_CONFIG` before
  touching native, like the range reads. [API.md](docs/API.md#getverificationsnapshotdate-string-options-promiseverificationsnapshot)
  has a worked example of posting it and a rule set for the other end.

#### Remote sync: idempotent, and optionally richer

- Every upload to `remoteSyncUrl` carries an `Idempotency-Key` header — a
  SHA-256 digest of the package name and each record's date and step count,
  sorted by date — so a batch WorkManager retried, or one `syncNow()` queued
  alongside the periodic job, is recognisable server-side as the same
  content. A day whose count has since grown produces a new key.
- `remoteSyncPayload` config, `'totals'` (default; the 1.3 shape byte for
  byte) or `'full'`, which adds per record this device's `deviceSteps` and
  `recoveredSteps`, the `stepSource` the policy resolved to, and the
  unresolved Health Connect `sources` for the day, read at upload time and
  `[]` when reads are not permitted.
- The body and the key moved into `RemotePayload`, pure and JVM-tested: same
  records → same key, one step changed → different key, and the default body
  has exactly the four fields it had in 1.3. A day whose Health Connect read
  failed uploads as this device's own with no origins, never with an empty
  `stepSource`; and the per-day reads for `'full'` are bounded as a whole,
  so a hanging provider cannot outlast the worker.

#### Motion signature windows (opt-in)

- `motionSampling: { enabled, windowSeconds?: 10, intervalMinutes?: 5 }`
  config, default disabled. While tracking is running and steps have accrued
  since the last window, the service samples the accelerometer for one window
  and stores **features only — never raw traces**: `dominantFrequencyHz`,
  `variance`, `zeroCrossingRate`, `peakRatio`, `stepsDuringWindow`,
  `startedAt`. Enough for a server to tell a 1.8 Hz walk from a 4 Hz shake;
  not enough to reconstruct anything. The last `motionWindowRetention`
  (default 288, a day at five-minute intervals) are kept in a new
  `motion_window` table (Room schema 4, additive migration).
- `getMotionWindows(startDate, endDate)` and the `motionWindow` event.
- Sampling stops while paused, is skipped while the app is in the background
  without the battery exemption, listens in on the pedometer's own samples on
  an accelerometer-only phone rather than registering twice, and on a
  non-wake-up accelerometer holds a timed partial wake lock for the window
  only when `accelerometerWakeLock` allows — the existing wake-lock policy.
- Seven JVM tests feed synthetic walking and shaking traces and assert the
  features separate them on frequency, variance, crossing rate and purity,
  at 20 Hz and 50 Hz alike, and that a still phone reports no frequency.

### Project hygiene

- The published tarball no longer carries `android/.kotlin/`. The Kotlin
  daemon writes crash logs under `android/.kotlin/errors/` during a local
  build, and `*.log` in `.npmignore` does not apply inside a directory that
  `package.json#files` lists, so a stray log shipped with the package.
  `!android/.kotlin` is now in `files`.
- `devDependencies.react-native`, `example/package.json` and the standalone
  Gradle pin in `android/settings.gradle` all say `0.77.0`, the floor
  `peerDependencies` has claimed since 1.1.0. Nothing about the floor moved:
  Room 2.7 needs Kotlin 2.0+, React Native 0.77 is the first release on
  Kotlin 2.0.21, and 0.76 is still on 1.9.24. The package was merely being
  developed and CI-tested against a version it does not claim to support.

## [1.3.0] - 2026-09-12

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

#### Runaway and stuck counts

- **Sensor jitter.** A reading a step or two below the previous one, with no
  restart behind it, was treated as a restart: the anchor moved down and the
  next sample added the dip back, so a day of HAL wobble crept the total
  upward. Backwards moves of up to three steps are now ignored.
- **Phone-side sources under `auto` are bound by coverage.** A wearable (or
  a pinned source) is trusted for its whole margin; a phone-side origin —
  Samsung Health, the platform's own count, an aggregator — may only supply
  the steps from before this device started counting today, and on a past
  day only fills a day this device has nothing for. An aggregator that sums
  two origins can no longer double the display, and an algorithm that counts
  5% high can no longer creep the total up sync after sync. New
  `StepSource.stepsBeforeCoverage` and `StepStateStore.coverageStartAt`.
- **Transient sensor failures are retried.** A registration that failed while
  the sensor service was not ready marked the tracker `unsupported`, which no
  recovery path would touch until the user pressed start again. The service
  now stays up and retries at 2, 5, 15 and 30 s, then every minute, emitting
  `E_SENSOR_UNAVAILABLE` meanwhile; only a device with no sensor at all is
  marked unsupported.
- **Health Connect reads are bounded** at four seconds, so a busy provider
  cannot hang `getTodaySteps()`; the phone's count answers and the read is
  retried next time. Granted permissions are cached for five seconds between
  explicit status checks.
- `resetToday()`, `clearHistory()` and `pruneHistory()` run on the serialised
  write lane, so a commit queued just before a reset cannot land after it and
  resurrect the old total.
- Every snapshot handed to JS carries `stepSource`. `initialize()`,
  `startTracking()`, `pauseTracking()`, `resumeTracking()` and
  `stopTracking()` returned one without it, so `snapshot.stepSource.…`
  threw until the first read.
- A continuity baseline taken earlier in the day still applies when Health
  Connect can no longer be read — a grant revoked from Health Connect's own
  settings, a provider mid-update — so `getTodaySteps()` and the notification
  keep agreeing. An explicit revoke through the package still clears it.
- The `eventThrottleMs` window no longer drops the last `stepsChanged` of a
  burst: a trailing emit fires when the window closes.
- Detector-only and accelerometer devices record where the day's coverage
  began on their first sample too, so an afternoon install on such a phone
  takes the morning from a phone-side source; and on those sensors a
  phone-side source is trusted for its whole margin, since steps taken while
  the process was dead are gone.
- Pausing releases the accelerometer and its wake lock; a changed
  `accelerometerThreshold` applies at the next step, not the next start.
- Persisted state carries a version; a continuity baseline written before
  the coverage rule is dropped on upgrade instead of surviving until
  midnight.

#### Security

- `remoteSyncUrl` must be `https://`: rejected at `initialize()` with
  `E_INVALID_CONFIG` and refused by the upload worker. `remoteSyncAllowHttp`
  opts a development server in.
- `HealthPrivacyPolicyActivity` — exported, reachable from the Health Connect
  UI — opens only `http(s)` URLs.
- [SECURITY.md](SECURITY.md): threat model, what is and is not encrypted,
  exported components and their guards, and what a rewards app must verify
  server-side.

#### Project hygiene

- Jest suite for the JS layer (39 tests) against a scripted native module:
  config validation, the `enableHealthConnect()` and
  `requestBackgroundPermissions()` ladders, events, error normalisation.
- ESLint (typescript-eslint, zero warnings), Prettier, `.editorconfig`.
- GitHub Actions: typecheck, lint, format, Jest, build and a pack-content
  check; Kotlin compile, JVM tests and Android lint (must be error-free);
  instrumented engine tests on an emulator on push. Dependabot for npm,
  Gradle and Actions.
- [CONTRIBUTING.md](CONTRIBUTING.md) with the invariants that are not
  negotiable.
- `package.json`: `engines`, `sideEffects: false`, `lint` / `test` /
  `format` scripts; tests and mocks excluded from the published package.

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

- 41 new JVM tests: `StepGapSplitterTest` (6), `StepContinuityTest` (8) —
  covering the 6,000 → 6,001 → 6,005 → 6,010 scenario, "next sync agrees, no
  double count", "phone on a desk raises the baseline" and "never sums" —
  `AccelerometerStepDetectorTest` (18), and platform-origin classification
  in `StepSourceResolverTest`.
- Instrumented engine tests grown from 11 to 24, now deterministic in wall
  clock; new cases for the overnight kill, the `drop` and `today` policies,
  fresh installs on an old boot, and sensor jitter.
- JVM tests: 56 in total, including the coverage rule for phone-side sources
  and the platform origin.

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

## [1.0.0] - 2026-09-08

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

[2.3.4]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.3.4
[2.3.3]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.3.3
[2.3.2]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.3.2
[2.3.1]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.3.1
[2.3.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.3.0
[2.2.1]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.2.1
[2.2.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.2.0
[2.1.1]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.1.1
[2.1.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.1.0
[2.0.1]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.0.1
[2.0.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v2.0.0
[1.5.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.5.0
[1.4.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.4.0
[1.3.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.3.0
[1.2.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.2.0
[1.1.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.1.0
[1.0.0]: https://github.com/SachinDeveloper-React/react-native-step-tracker-pro/releases/tag/v1.0.0
