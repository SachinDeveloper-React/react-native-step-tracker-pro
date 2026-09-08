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

## 2. Run the engine tests

```sh
cd android
./gradlew :react-native-step-tracker-pro:connectedAndroidTest
```

Runs fine on an emulator — the tests never touch the sensor. Eleven cases
covering:

| Test | What breaks if it fails |
|---|---|
| `accumulatesCounterDeltas` | basic counting |
| `sensorResetWithoutRebootDoesNotDoubleCount` | OEM HAL restarts inflate the total |
| `rebootOnSameDayClaimsStepsTakenSinceBoot` | steps between boot and service start are lost |
| `rebootOnPreviousDayDropsPreServiceSteps` | yesterday's steps land in today |
| `midnightRolloverFinalisesPreviousDay` | day totals never close, or leak forward |
| `pausedStepsAreDiscardedAndResumeDoesNotBackfill` | pause does nothing, or resume dumps a backlog |
| `detectorFallbackIncrementsDirectly` | no-step-counter devices count nothing |
| `commitThresholdControlsDatabaseWrites` | a write per step, or no writes at all |
| `resetTodayClearsTotalButKeepsCountingAfterwards` | QA reset replays the old count |
| `metricsDeriveStrideFromHeightWhenNotOverridden` | distance/calories maths |
| `calendarWeekIsMondayAnchoredAndSevenDaysLong` | weekly stats window |

Run one case:

```sh
./gradlew :react-native-step-tracker-pro:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.steptrackerpro.StepCounterEngineTest#rebootOnSameDayClaimsStepsTakenSinceBoot
```

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
date forward one day. Then take a few steps.

- [ ] `dayChanged` fires with yesterday's total
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

Test on a Xiaomi, Oppo, Vivo or Realme device if you have one. They kill
foreground services that survive fine on a Pixel.

- [ ] with autostart off: counting stops (expected)
- [ ] `openManufacturerAutoStartSettings()` lands on the right screen
- [ ] with autostart on: counting survives an hour with the screen off

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
- [ ] `getPendingSyncCount()` rises if `remoteSyncUrl` is set
- [ ] after re-enabling the network, `syncCompleted` fires for `target: 'remote'`
      within the hour, and pending drops to 0

### Goals

Set `dailyGoal: 20` temporarily.

- [ ] `goalProgressChanged` fires as the percentage climbs
- [ ] `goalReached` fires once, not on every step after the goal
- [ ] killing and relaunching the app does not re-fire it for the same day

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
Inspection → Database Inspector**. It reads a live database, which is far less
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
