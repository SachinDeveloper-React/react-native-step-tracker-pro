# Testing the package

Two layers. The engine logic — reboot, midnight, pause, counter resets — is
covered by instrumented tests that feed samples directly, so no walking is
needed. Everything that involves the OS keeping your process alive has to be
verified on a real device.

---

## 1. Link the package into a test app

It is not published yet, so point an app at the folder.

```sh
# in the package
npm pack                      # → react-native-step-tracker-pro-1.0.0.tgz

# in your test app
npm install ../react-native-step-tracker-pro/react-native-step-tracker-pro-1.0.0.tgz
```

`npm pack` is better than `file:` or `yarn link` here: Metro follows symlinks
badly, and `file:` installs a symlink on npm 7+. Re-run `npm pack` + install
after every change to the package.

Then set the SDK levels in the app's `android/build.gradle`:

```gradle
buildscript {
    ext {
        minSdkVersion = 26
        compileSdkVersion = 35
        targetSdkVersion = 35
    }
}
```

```sh
cd android && ./gradlew clean && cd ..
npx react-native run-android
```

---

## 2. Run the automated tests

```sh
npm test                 # Jest, JS layer, Jest mock, Expo plugin (170 tests, no device)
npm run test:android     # JVM: resolver, continuity, gap splitting, pedometer, motion signatures, remote payload, fraud detector, minute and hour attribution, sensor timestamps, owed late steps, integrity config (206 tests, no device)
npm run test:android:device   # instrumented engine, Room migration and integrity pipeline tests
```

The JVM suite pins the fraud detector with synthetic days: an ordinary walk,
a treadmill session and a run raise nothing; a swing gadget's metronomic
count, minutes faster than anyone walks, a charger, a car ride and a hand
shake are each flagged; lumps of untimed steps are never judged for
cadence; and the daily cap takes only the excess. It also walks five minutes
with the screen off and delivers them in one late batch, as a sleeping phone
does: judged on the minutes they were walked in, nothing is flagged.

The instrumented tests run fine on an emulator — they never touch the
sensor. Sixty-nine cases: forty drive the engine, three build the Room
database as an older version from the exported schema, migrate real rows to
the current version and check every one survived, nineteen run the
integrity layer, sync bookkeeping and background work end to end through the
real core, database and Keystore, four check that remote-sync headers are
sealed, read back and migrated from 1.x, and three pin when `goalReached`
fires. Among them:

| Test | What breaks if it fails |
|---|---|
| `accumulatesCounterDeltas` | basic counting |
| `sensorResetWithoutRebootDoesNotDoubleCount` | OEM HAL restarts inflate the total |
| `rebootOnSameDayClaimsStepsTakenSinceBoot` | steps between boot and service start are lost |
| `rebootOnPreviousDaySplitsPreServiceStepsAcrossMidnight` | steps since a pre-midnight boot are dropped or all land in one day |
| `overnightKillRecoversTheGapOnBothSidesOfMidnight` | steps counted while an OEM had the process dead overnight are lost, or the recovered share is not recorded |
| `aWeekLongKillCannotMintAWeekOfStepsOnOneDayUnderTodayCapped` | `today_capped` hands one day a week of counter |
| `migrate2To3KeepsEveryRowAndReadsRecoveredAsZero` | upgrading to 1.4 loses the user's history |
| `freshInstallOnAnOldBootDoesNotInventHistory` | a new install credits itself with days of steps it never saw |
| `wallClockJumpIsNotMistakenForAReboot` | an NTP correction doubles the day |
| `smallBackwardsJitterIsIgnoredNotReanchored` | a HAL that wobbles a step backwards creeps the total upward |
| `midnightRolloverFinalisesPreviousDay` | day totals never close, or leak forward |
| `theDayEndsAtMidnightWithoutWaitingForAStep` | a phone lying still across midnight keeps yesterday's total until the first step of the morning |
| `aResetAfterMidnightClosesYesterdayBeforeZeroingToday` | `resetToday()` after midnight wipes yesterday's unsaved steps along with today |
| `aLateBatchCountsOnTheDayItWasTakenAndClosesItAfter` | steps the phone held across midnight count on the new day |
| `aLateSampleAfterTheDayClosedIsOwedToTheDayItWasTaken` / `lateStepsForAClosedDayFollowTheGapPolicy` | a late sample after the day closed lands on the wrong day, or changes a closed day under a policy that promises it never will |
| `pausedStepsAreDiscardedAndResumeDoesNotBackfill` | pause does nothing, or resume dumps a backlog |
| `detectorFallbackIncrementsDirectly` | no-step-counter devices count nothing |
| `commitThresholdControlsDatabaseWrites` | a write per step, or no writes at all |
| `resetTodayClearsTotalButKeepsCountingAfterwards` | QA reset replays the old count |
| `metricsDeriveStrideFromHeightWhenNotOverridden` | distance/calories maths |
| `calendarWeekIsMondayAnchoredAndSevenDaysLong` | weekly stats window |
| `observedDeltasCarryTheSpanTheyWereTakenIn` | minute buckets land on the wrong minutes |
| `stepsCreditedByRecoveryAreNotObserved` | a recovered lump is judged as if it were walked in one minute |
| `untimedSamplesAreCountedAndObservedUntimed` | steps from a sample with an unusable timestamp are lost, or judged as if their arrival were when they were taken |
| `migrate4To5KeepsMotionWindowsAndAddsTheIntegrityTables` | upgrading to 1.5 loses motion windows or history |
| `aSwingGadgetIsFlaggedExcludedLoggedAndSigned` | the detector, exclude mode, the event log or snapshot signing is broken end to end |
| `withoutPlayServicesActivityRecognitionIsUnavailableNotACrash` | an app without Play Services crashes, or believes it has Activity Recognition |
| `aRebootIsLoggedOncePerBootCountNotPerBroadcast` | a forged or repeated boot broadcast writes fake reboots into the integrity log |
| `healthConnectPermissionsTheAppDoesNotDeclareAreReported` | a missing manifest entry fails silently instead of naming itself |
| `headersAreSealedNotStoredInTheClear` / `plaintextHeadersFromOneXAreMigratedOnFirstRead` | remote-sync credentials sit in SharedPreferences in the clear |
| `aDayThatGrewWhileItWasSyncingStaysQueued` | steps a backfill adds to a day while it uploads never reach the server or Health Connect |
| `aReadAfterMidnightIsOfTheNewDayWithoutAStep` | `getStepsForDate(today)` answers with yesterday's date and count until a step |
| `onlyTargetsInUseHaveAnythingPending` | `getPendingSyncCount()` never reaches 0 without a remote URL or Health Connect writes |
| `aStoppedTrackerGetsNoBackgroundWorkBackFromTheNextLaunch` | the next `initialize()` undoes the job cancelling `stopTracking()` did |
| `aTrackerThatNeverStartedKeepsItsBackgroundWork` | an app that only reads Health Connect gets no hourly upload |
| `aReadOfYesterdayWaitsForTheCloseToLand` | `getYesterdaySteps()` at the first open of the morning misses the last steps of yesterday |
| `aShakerRunUpToMidnightIsJudgedOnTheDayItCountsFor` | a shaker left running up to midnight on a sleeping phone passes its steps to the next day unflagged |
| `aReadOfYesterdayRightAfterALateBatchIncludesIt` | a snapshot of yesterday taken as the phone wakes misses the steps it delivered late |

### Compatibility builds

CI also packs the library and builds it inside apps made from React Native's
own template - 0.77.3 on AGP 8.7 with the new architecture on and off, and
0.87.2 on AGP 9.2 with built-in Kotlin off and on - and checks the merged
manifest carries no opt-in permission. To
reproduce one locally, follow the `compat` job in `.github/workflows/ci.yml`;
it needs only Node 22, JDK 17 and the Android SDK. The versions are in the
README's compatibility table.

Run one case:

```sh
./gradlew :react-native-step-tracker-pro:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.steptrackerpro.StepCounterEngineTest#rebootOnSameDayClaimsStepsTakenSinceBoot
```

---

## Mocking the package in your app's tests

The package ships a Jest mock. In a test file or a Jest setup file:

```ts
jest.mock('react-native-step-tracker-pro', () =>
  require('react-native-step-tracker-pro/jest')
);
```

Every method is a `jest.fn` resolving with an empty-day value - a snapshot
with `steps: 0`, empty history, Health Connect not installed, permissions
granted - so a test changes only what it cares about:

```ts
import StepTracker from 'react-native-step-tracker-pro';
import { __emit, mockSnapshot } from 'react-native-step-tracker-pro/jest';

(StepTracker.getTodaySteps as jest.Mock).mockResolvedValueOnce(
  mockSnapshot({ steps: 12000, goalReached: true })
);
__emit('stepsChanged', mockSnapshot({ steps: 12001 })); // fires addListener callbacks
```

Both imports resolve to the same mock module, so `__emit` reaches the
listeners the code under test added.
`useStepTracker`, `useStepStats` and `useHealthConnect` return settled,
static results, `isSupported()` returns `true`, and the constants and
`StepTrackerError` are the real ones.

---

## 3. Device check before anything else

Most emulators have no step hardware, so live counting can only be tested on a
phone.

```sh
adb shell dumpsys sensorservice | grep -i step
```

Or from JS:

```ts
console.log(await StepTracker.getDeviceCapabilities());
// { hasStepCounter: true, hasStepDetector: true, supported: true, ... }
```

If `supported` is false, everything below is untestable on that device.

---

## 4. Manual matrix

Set `PKG` to your app's applicationId first: `PKG=com.yourapp`

### App open

Start tracking, walk 20–30 steps. Sensor hubs often buffer the first few, so
walk properly rather than shaking the phone.

- [ ] `stepsChanged` fires in JS
- [ ] notification shows steps, km, kcal and the progress bar
- [ ] numbers in the notification and the UI agree

### Background and screen locked

```sh
adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_POWER      # screen off
# walk 50 steps
adb shell input keyevent KEYCODE_POWER
```

- [ ] lock screen notification shows an updated count
- [ ] reopening the app shows the same total, not a stale one

### App swiped away

Swipe the app out of recents.

- [ ] notification stays
- [ ] count still rises while walking
- [ ] `adb shell dumpsys activity services $PKG | grep StepTrackerService` shows it running

### Process killed

```sh
adb shell am force-stop $PKG
# walk 100 steps with the app dead
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
```

- [ ] the 100 steps appear after relaunch

This is the single most valuable test. `TYPE_STEP_COUNTER` kept counting in the
sensor hub while your process was gone; the first sample after restart should
reconcile them in. If they are missing, the anchor logic is wrong.

### Boot recovery

Real reboot:

```sh
adb reboot
# wait for the lock screen, do not open the app
```

- [ ] notification reappears on its own within a few seconds of unlock
- [ ] yesterday's / today's total is intact

Faster loop while iterating — fires the receiver without rebooting:

```sh
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED \
  -n $PKG/com.steptrackerpro.service.BootReceiver
```

This exercises the receiver's guard rails (initialised, `autoStartOnBoot`,
`shouldAutoStart`, permissions) but not the counter reset — only a real reboot
zeroes the hardware counter.

### Midnight rollover

No root needed. Settings → System → Date & time → turn off automatic, set the
date forward one day. Leave the phone still for a minute first, then take a
few steps.

- [ ] before any step, within a minute: the notification reads today's count
      and `dayChanged` fires with yesterday's total
- [ ] with the screen off across midnight, turning it on shows today's count
      at once
- [ ] `getStepsForDate(today)` and `getCurrentStepSource()` answer for today,
      not with yesterday's date and count
- [ ] today starts at 0, not at yesterday's number
- [ ] yesterday's row is in the database with its final count

Set the date back afterwards. On an emulator you can do it from the shell:

```sh
adb root && adb shell date $(date -v+1d +%m%d%H%M%Y.%S)   # macOS date syntax
adb shell am broadcast -a android.intent.action.TIME_SET
```

### Doze

```sh
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
# walk
adb shell dumpsys deviceidle unforce && adb shell dumpsys battery reset
```

- [ ] steps taken during forced idle are present afterwards

### OEM battery management

Test on a Xiaomi, Oppo, Vivo, Realme or Tecno device if you have one. They
kill foreground services that survive fine on a Pixel.

- [ ] with autostart off: the notification disappears within an hour of the screen going off (expected)
- [ ] walk 200 steps with it gone, open the app: the steps are there and `getTrackingHealth().recoveryCount` moved
- [ ] `getBackgroundRestrictionStatus().aggressiveOem` is true
- [ ] `openManufacturerAutoStartSettings()` lands on the OEM screen, not app info
- [ ] with autostart on and the battery exemption granted: counting survives an hour with the screen off, and if it is still killed the watchdog brings it back within 15 minutes (`lastRecoveryReason: 'watchdog'`)

### Accelerometer fallback

Needs a phone with no step sensor, or a debug build with the two hardware
paths stubbed out. `getDeviceCapabilities().bestSensor` must read
`'accelerometer'`.

- [ ] walk 100 steps with the phone in a pocket: count within ±5
- [ ] same with the phone in a bag: count within ±10
- [ ] pick the phone up, put it down, repeat five times: count unchanged
- [ ] ten minutes as a car passenger on a normal road: fewer than 20 steps
- [ ] screen off for ten minutes while walking: steps present when it comes back on (wake lock held — `adb shell dumpsys power | grep steptrackerpro`)
- [ ] `adb shell am force-stop`, walk, reopen: those steps are **not** there — expected, and documented

### Health Connect continuity (`auto`)

Needs a second source — Samsung Health, Google Fit, or a watch.

- [ ] with the other app ahead, `getTodaySteps().stepSource.merged` is true
- [ ] walk 20 steps: the number rises by ~20 immediately, without waiting for the other app to sync
- [ ] when the other app syncs, the number does not jump by the same 20 again
- [ ] leave the phone on a desk and walk with the watch: the number catches up on the next read

### Health Connect

Android 14+ has it built in. Android 13 and below need the app from Play.

```sh
adb shell am start -a android.health.connect.action.HEALTH_HOME_SETTINGS
```

- [ ] `getHealthConnectStatus()` reports `available: true`
- [ ] `requestHealthConnectPermissions()` opens the sheet (needs the rationale
      intent filter in your app manifest, or it silently does nothing)
- [ ] after `syncNow()`, today's steps appear in the Health Connect app
- [ ] syncing twice does **not** create two entries for the same day
- [ ] `readHealthConnectSteps()` returns data written by other apps too

### Offline

```sh
adb shell svc wifi disable && adb shell svc data disable
```

- [ ] counting, notification, stats and history all work unchanged
- [ ] `getPendingSyncCount()` rises if `remoteSyncUrl` is set, and stays 0
      with no `remoteSyncUrl` and Health Connect writes off
- [ ] after re-enabling the network, `syncCompleted` fires for `target: 'remote'`
      within the hour, and pending drops to 0
- [ ] `syncNow()` returns the remote entry with `queued: true` and no `error`,
      and its `syncCompleted` follows, even with nothing pending

### Stop

```sh
# WorkManager's own listing of enqueued work, by unique name
adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p $PKG
adb logcat -d -s WM-DiagnosticsWrkr
```

- [ ] while tracking: `stp_retention`, `stp_watchdog` and, as configured,
      `stp_health_connect_sync` and `stp_remote_sync` are enqueued
- [ ] after `stopTracking()` and a relaunch that calls `initialize()`: none of
      them are

### Goals

Set `dailyGoal: 20` temporarily.

- [ ] `goalProgressChanged` fires as the percentage climbs
- [ ] `goalReached` fires once, not on every step after the goal
- [ ] killing and relaunching the app does not re-fire it for the same day

### Integrity checks

Turn on `fraudDetection: { enabled: true }` and `motionSampling: { enabled: true,
intervalMinutes: 1 }`, then read `getIntegrityReport(today)` after each step.

```sh
# pretend a charger is plugged in, and undo it
adb shell dumpsys battery set ac 1
adb shell dumpsys battery reset
```

- [ ] walk 5 minutes normally: `suspectSteps` is 0, no strong flag
- [ ] walk 5 minutes with the screen off, then turn it on: `getStepMinutes()`
      spreads those steps over the minutes they were walked in, about 110
      each, with no `cadence` flag - not all of them in the minute the screen
      came on
- [ ] shake the phone with the screen off from 23:50 until after midnight,
      then turn it on: the steps before midnight are on yesterday - in
      `getHistory()`, with `historyBackfilled` (`reason: 'late'`) if the day
      had already closed - and yesterday's report carries the `cadence` flag
- [ ] shake the phone hard in your hand for 2 minutes: a `cadence` flag, and with a motion window open a `shake` flag
- [ ] walk 3 minutes with `dumpsys battery set ac 1`: a `charging` flag covering those steps, `charging_started` in `events`
- [ ] with a swing gadget, or the phone on a pendulum, for 40 minutes: a `steady_cadence` flag
- [ ] change the clock in Settings by an hour: a `clock_changed` event with `jumpMs` near ±3,600,000
- [ ] `resetToday()`: a `reset_today` event, today's minutes gone
- [ ] switch to `mode: 'exclude'`: the notification and `getTodaySteps()` drop by `suspectSteps`; `getHistory()` still shows the raw count
- [ ] `attestDevice('test-challenge')` returns a chain; `getVerificationSnapshot(today, { sign: true, nonce: 'n' })` carries a `signature` that verifies against its `publicKey`
- [ ] with `play-services-location` in the app and `activityRecognition: true`, 10 minutes as a car passenger: an `in_vehicle` flag

### Retention

Set `historyRetentionDays: 2`, then `pruneHistory()`.

- [ ] rows older than 2 days are gone
- [ ] `getMonthlyStats()` still returns a zero-filled range without crashing

---

## 5. Inspection

```sh
# counter state — the file to look at when a number seems wrong
adb shell run-as $PKG cat shared_prefs/StepTrackerProState.xml

# config
adb shell run-as $PKG cat shared_prefs/StepTrackerProConfig.xml

# service alive?
adb shell dumpsys activity services $PKG

# scheduled sync work
adb shell dumpsys jobscheduler | grep -A 8 $PKG

# logs
adb logcat -s StepTrackerService:V StepTrackerBoot:V
```

For the Room tables use Android Studio → **View → Tool Windows → App
Inspection → Database Inspector**. `step_minute`, `integrity_day` and
`integrity_event` hold the integrity checks' minutes, verdicts and log. It reads a live database, which is far less
painful than pulling the file.

Reading `StepTrackerProState.xml`:

| Key | Meaning |
|---|---|
| `steps_today` | current total |
| `anchor_steps` / `anchor_value` | the pin — `steps_today = anchor_steps + (raw − anchor_value)` |
| `last_raw_value` | last cumulative reading from the sensor |
| `boot_id` | approximate boot epoch; a jump here means a reboot was detected |
| `active_date` | the day the total belongs to |
| `should_auto_start` | whether the boot receiver will restart the service |

### Injecting a fake count for UI work

Only for screenshots — set `anchor_steps` too, or the next sensor sample
recomputes the total and wipes your edit.

```sh
adb shell am force-stop $PKG
adb shell run-as $PKG sh -c \
  "sed -i -e 's/\"steps_today\" value=\"[0-9]*\"/\"steps_today\" value=\"7500\"/' \
          -e 's/\"anchor_steps\" value=\"[0-9]*\"/\"anchor_steps\" value=\"7500\"/' \
   shared_prefs/StepTrackerProState.xml"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
```

The force-stop matters: SharedPreferences caches in memory, so an edit made
while the process is alive gets overwritten on the next write.

---

## 6. Order to test in

1. Instrumented tests on an emulator — catches logic regressions in a minute
2. Physical device: capabilities → permissions → live counting
3. Background, lock, swipe away
4. Force-stop recovery
5. Real reboot
6. Date rollover
7. Health Connect
8. OEM device, if you have one

Steps 1–4 catch almost everything. Steps 5–8 are where devices disagree with
each other, so they need real hardware rather than an emulator.
