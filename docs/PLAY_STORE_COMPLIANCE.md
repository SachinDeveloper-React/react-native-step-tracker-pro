# Play Store compliance

Read this before your first upload. A step tracker touches three of Play's
sensitive areas at once — foreground services, health data and battery
exemptions — and each has its own declaration form. Which forms you fill in
depends on your [usage mode](USAGE_MODES.md):

| | Mode A: native only | Mode B: Health Connect only | Mode C: both |
|---|---|---|---|
| Foreground service declaration | **yes** | no | **yes** |
| Health apps declaration | no | **yes** (reads) | **yes** (reads + writes) |
| Privacy policy naming health data types | no | **yes** | **yes** |
| Rationale activity in manifest | no (remove) | **yes** | **yes** |
| Battery exemption permission | optional, justified | remove | optional, justified |
| Data safety: fitness info | yes | yes | yes |

The library manifest declares everything Mode C needs. For A or B, remove
what you do not use with `tools:node="remove"` — every declared permission is
one Play asks you to justify, and an unjustified one is a rejection.
[USAGE_MODES.md](USAGE_MODES.md) has the exact manifest blocks.

## 1. Foreground service type

Play requires a declared type and a justification for every foreground service.
This package uses `health`.

**Play Console → App content → Foreground service permissions.**

- **Permission:** `FOREGROUND_SERVICE_HEALTH`
- **Purpose:** continuous step counting for a fitness feature
- **Why a foreground service is required:** the app must keep reading the step
  sensor while the screen is off and the app is not in the foreground; deferred
  work such as WorkManager cannot sample a sensor continuously
- **Video:** record a screen capture showing the notification appearing when
  tracking starts, and the count updating while walking with the app closed.
  Reviewers reject text-only submissions here.

The notification must be visible and honest. Do not hide it, do not make it
dismissible, and do not use a title that misrepresents what is running.

**Android 14+ prerequisite.** A `health` service needs `ACTIVITY_RECOGNITION`
granted at runtime or `startForeground` throws. The package checks before
starting; make sure your onboarding asks for it first (see
[PERMISSIONS.md](PERMISSIONS.md)).

**Android 15 / targetSdk 35.** `health` is not on the list of types that may
no longer be launched from a `BOOT_COMPLETED` receiver, so boot recovery
keeps working. It is also not a "while-in-use" type, so the watchdog and boot
paths may start it without a visible activity when an exemption applies.

## 2. Health Connect

**Play Console → App content → Health apps.** Every `android.permission.health.*`
in the merged manifest has to be justified, so remove the ones you do not use.

Requirements:

- A privacy policy that specifically names the Health Connect data types you
  read and write (steps, distance, total calories burned), states that data
  is stored on-device, and explains any transmission off-device. Link it from
  both the Play listing and in-app.
- The `ACTION_SHOW_PERMISSIONS_RATIONALE` intent filter and the
  `VIEW_PERMISSION_USAGE` activity alias. Missing these is the most common
  rejection. **The library declares both** and points them at a screen that
  opens your `privacyPolicyUrl` — set that in `initialize()`, or override the
  activity with your own (see
  [INSTALLATION.md](INSTALLATION.md#health-connect-rationale-screen)).
- The two optional permissions (`READ_HEALTH_DATA_IN_BACKGROUND`,
  `READ_HEALTH_DATA_HISTORY`) are declared in the merged manifest even when the
  config flags are off, so either justify them on the form or `tools:node="remove"`
  them. Background reads in particular get extra scrutiny; the justification
  that fits this package is *"keeps the step count shown in the ongoing
  notification in sync with a paired watch while the app is not open"*.
- No advertising, no selling health data, and no sharing it with third parties
  for anything unrelated to the feature the user asked for.
- Data deletion must be possible from inside your app. `clearHistory()` covers
  your local store; Health Connect data is deleted from the Health Connect app.

### Read-only vs read-write

The permission sheet follows config, so what you declare should too:

| Config | Declare | Form says |
|---|---|---|
| `healthConnectReadEnabled: true`, `healthConnectWriteEnabled: false` | `READ_*` only | "reads steps to display data from other apps and wearables" |
| `healthConnectWriteEnabled: true`, `healthConnectReadEnabled: false` | `WRITE_*` only | "writes the app's own step count so other apps can use it" |
| both (default) | both | both |

### On duplicate step data

On Android 14 with SDK extension 20 or higher, Health Connect records the
phone's own steps by itself, from the same `TYPE_STEP_COUNTER` this package
reads, as soon as any app holds `READ_STEPS` — attributed to the `android`
origin, or to a `com.android.healthconnect.phone.<hash>` synthetic package
after the June 2026 provider update
([Google's steps guide](https://developer.android.com/health-and-fitness/health-connect/features/steps)).
If you also write steps, users see the same walk from two origins in the
Health Connect UI. The package mitigates it — records carry a stable
`clientRecordId` so re-syncing never stacks duplicates, and a day a wearable
already owns is skipped rather than written — but your origin still sits
alongside the platform's. Three options:

- **Read-only** — `healthConnectWriteEnabled: false`. Reads keep working, so a
  watch's data still reaches your UI, but nothing of yours is added to the
  user's Health Connect record. The cleanest answer if another app already
  owns step counting for this user.
- **Write with attribution** — the default.
- **Off entirely** — `healthConnectEnabled: false`, strip the health
  permissions from your manifest. No declaration form at all (Mode A).

Pick one deliberately and say which in your privacy policy. The platform's
own origin is recognised by the package (`isPlatform: true`, `kind: 'phone'`,
"This phone (Android)"); other origins you do not recognise — OEM health apps
write under their own package names — are just another source.

### On manual entries

Health Connect lets a user type steps in by hand, in its own UI or in any
app that offers it, and stamps such records `RECORDING_METHOD_MANUAL_ENTRY`.
There is no separate permission for them: `READ_STEPS` returns typed-in
records alongside counted ones, so this package always *reads* them and
always lists them — `getStepSources()` reports `manualSteps` and a
`recordingMethods` split per source. What it does with them is config:
by default a manual entry is ordinary data, and with
`healthConnectIgnoreManualEntries: true` it is left out of the resolved
number, which is the right setting for any app that converts steps into
currency, rewards or a leaderboard position. Say which in your privacy
policy if you rely on the distinction, and note that the stamp is the
writing app's own statement — every mainstream app labels its manual
entries honestly, but a purpose-built app can lie, which is why a rewards
app still verifies server-side (see [SECURITY.md](../SECURITY.md)).

### On reporting a watch's steps as your own

When a wearable owns the day, the number your UI shows was measured by hardware
you did not write. That is fine and is what users expect, but say so: the
`stepSource` object on every snapshot names the app the count came from, and
surfacing that ("8,240 steps · from Fitbit") is both better UX and a cleaner
story for review than presenting another app's measurement unattributed.
Under `'auto'`, `stepSource.merged` tells you the number is that app's
baseline plus the phone's own counting on top.

## 3. Battery optimisation exemption

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` lets `requestDisableBatteryOptimization()`
show a one-tap system dialog. Google's policy: *apps may not request direct
exemption from Doze and App Standby unless the negative effect on the app's
core function is significant.* Continuous background step counting is a core
function that Doze and OEM battery managers do break, so a step tracker
qualifies — **provided the app's main purpose is step tracking**. A game or a
shopping app with a step widget does not.

If you keep it:

- Ask in context, never on first launch: after tracking is on, and ideally
  after `getTrackingHealth().recoveryCount` shows the service being killed.
- Put the reason on screen before the dialog: "Your phone stops apps in the
  background to save power. Allow this so your steps keep counting."
- Be ready to describe that flow if Play asks. Reviews of this permission
  are usually automated and pass for fitness apps; when they do not, the
  usual fix is a clearer in-app explanation, not removal.

If you would rather not:

```xml
<uses-permission
    android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    tools:node="remove" />
```

and call `requestBackgroundPermissions({ directPrompt: false })`, which opens
the system list instead. That path needs no justification. Note that on
Android 12+ the exemption — however it was granted — is also what allows the
watchdog to restart a killed service from the background; without it,
recovery waits for the user to open the app.

The OEM autostart screens (`openManufacturerAutoStartSettings()`) need no
permission and no declaration.

## 4. ACTIVITY_RECOGNITION

A runtime permission from Android 10. Request it in context — after the user has
tapped something that clearly starts tracking — not on first launch. Play's data
safety form must list it under "Health and fitness → Fitness info".

## 5. Data safety form

Declare, at minimum:

| Data type | Collected | Shared | Purpose |
|---|---|---|---|
| Fitness info (steps, distance, calories) | Yes | Only if you set `remoteSyncUrl` | App functionality |

If `remoteSyncUrl` is set, you are transmitting health data off-device: declare
it as shared, state the recipient, confirm encryption in transit (use HTTPS —
the worker will happily post to `http://`, and you should not), and offer a
deletion path.

## 6. Notification content

Android 13+ makes notifications a runtime permission. Denying it hides the
notification but does not stop the service, and `allGranted` in
`checkPermissions()` reflects that deliberately. Do not block your onboarding on
`POST_NOTIFICATIONS`.

## 7. Package visibility (`<queries>`)

The library manifest declares `<queries>` for the Health Connect provider, the
Play Store, ~25 wearable companion apps and ~20 OEM battery managers. These are
not permissions and need no declaration; they exist so the package can tell
whether those apps are installed on Android 11+. Play's *package visibility*
policy only restricts `QUERY_ALL_PACKAGES`, which the package does not use.

## 8. Pre-launch checklist

- [ ] Picked a [usage mode](USAGE_MODES.md) and removed the manifest entries it does not need
- [ ] Foreground service declaration submitted, with a video (Modes A, C)
- [ ] Health apps declaration submitted, only for permissions left in the merged manifest (Modes B, C)
- [ ] Privacy policy names each Health Connect data type read and/or written, and is set as `privacyPolicyUrl`
- [ ] `ACTION_SHOW_PERMISSIONS_RATIONALE` filter and `VIEW_PERMISSION_USAGE` alias present (Modes B, C) or removed (Mode A)
- [ ] `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` removed, or asked for in context with an explanation on screen
- [ ] Unused `android.permission.health.*` entries removed with `tools:node="remove"`
- [ ] Data safety form matches whether `remoteSyncUrl` is configured
- [ ] `ACTIVITY_RECOGNITION` requested in context, with an explanation on screen
- [ ] In-app data deletion available
- [ ] Tested on a Xiaomi/Redmi or Oppo/Realme device: force-stop, walk, reopen — steps present, `recoveryCount` moved
