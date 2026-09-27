# Security

## Reporting

Email the address in `package.json` (`author`) with the details, or open a
GitHub security advisory. Please do not file public issues for
vulnerabilities. Expect an acknowledgement within a week.

## What this package handles

Step, distance and calorie totals per day, on the device. Health data under
Google Play's policy, though low-sensitivity in practice. No account data, no
location, no network access unless `remoteSyncUrl` is configured.

## Threat model and what is done about it

### Data at rest

- Counter state and config live in the app's private `SharedPreferences`;
  history in a private Room database. Both are sandboxed per app by Android
  and are **not additionally encrypted** — a rooted device or a backup
  extract can read them. If that matters for your product, exclude them from
  backups (`android:allowBackup="false"` or backup rules) and treat the
  numbers as the user's own data, which they are.
- `remoteSyncHeaders` are stored in the same place, in the clear. Put
  short-lived tokens in them, never long-lived secrets, and rotate them from
  your app. They are never returned by `getConfig()`.
- With the integrity checks on, per-minute step counts, the detector's
  verdicts and the integrity event log sit in the same database. Counts and
  flags only - no samples, no location. They are what a rooted user would
  edit, which is why a verdict that costs money belongs on a server, checked
  against a signed snapshot.
- The signing key made by `attestDevice()` lives in the Android Keystore,
  in secure hardware where the device has it, and never leaves it. Nothing
  about it is in SharedPreferences except whether it was attested.

### Data in transit

- `remoteSyncUrl` must be `https://`. The JS layer rejects anything else at
  `initialize()` and the worker refuses to upload to it, unless
  `remoteSyncAllowHttp` is set for a development server.
- Health Connect traffic is on-device IPC.

### Exported components

| Component | Exported | Why | Guard |
|---|---|---|---|
| `BootReceiver` | yes | `BOOT_COMPLETED` is only delivered to exported receivers | acts only when the user had tracking on and permissions are held; an unprotected `QUICKBOOT_POWERON` from another app can at most restart tracking the user already asked for |
| `HealthPrivacyPolicyActivity` (+ alias) | yes | Health Connect and Play require it | opens only `http(s)` URLs from the app's own config; never an arbitrary scheme |
| `StepTrackerService` | no | | |
| `HealthPermissionActivity` | no | | |
| `NotificationActionReceiver` | no | | PendingIntents are `FLAG_IMMUTABLE` |
| `ActivityTransitionReceiver` | no | Play Services delivers Activity Recognition results to it | reached only through an explicit `PendingIntent` this package creates; mutable because Play Services fills in the result, which an explicit, unexported target keeps from anyone else |

The integrity checks' charging and clock receivers are registered at runtime
with `RECEIVER_NOT_EXPORTED`, while the service runs. `BootReceiver` also logs
a `reboot` integrity event; since `QUICKBOOT_POWERON` is unprotected, another
app can add one to the log - the log is evidence, not proof.

### Input from the bridge

Dates are validated as `yyyy-MM-dd` and ordered before reaching native.
Numeric config is clamped natively (`StepTrackerConfig.sanitised()`) as well
as validated in JS, because config is also rebuilt from persisted JSON.

### Step data integrity (rewards, leaderboards)

This package counts what the sensors report. It does not, and cannot,
prove the steps were walked. With `fraudDetection.enabled` it flags the
common ways the count is faked - a phone shaken by hand, swung by a gadget,
left on a charger, carried in a car, totals no person walks - and with
`attestDevice()` and signed snapshots it lets a server check that the
numbers came unmodified from a genuine device. See
[docs/API.md](docs/API.md#integrity-checks). What remains:

- A rooted device can feed a mock sensor, hook this code or edit its
  database; attestation catches an unlocked bootloader and an emulator, and
  a signature that stops verifying catches edits after signing, but nothing
  on the device can stop a determined attacker before that.
- A gadget tuned to vary its pace, or a person shaking a phone in bursts,
  can stay under every threshold. The checks raise the effort; they are not
  a proof of walking.
- `resetToday()` and `clearHistory()` are part of the public API.
- Steps recovered after a dead period across midnight are **apportioned by
  time** (`gapRecovery: 'split'`), which is an estimate. Use `'drop'` where an
  over-credit costs money.
- Under `'auto'`, a source the user pins is trusted outright.
- Steps typed into Health Connect by hand are read like any other record.
  Set `healthConnectIgnoreManualEntries` to keep them out of the resolved
  number, and check `StepSource.manualSteps` server-side; the recording
  method is the writing app's own statement, not proof.

If steps have monetary value, verify server-side: turn on the integrity
checks, attest the device and post signed verification snapshots, rate-limit
implausible daily totals, compare against `stepSource` and
`getTrackingHealth()` (`recoveryCount`, `bestSensor`), and treat
`'accelerometer'`, merged counts, `untimedSteps` and weak flags as
lower-confidence.

### Permissions

Only what the configured mode needs is requested; see
[docs/PERMISSIONS.md](docs/PERMISSIONS.md). `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
and the Health Connect permissions are declared by the library manifest and
are meant to be removed with `tools:node="remove"` by apps that do not use them.

### Dependencies

Runtime: AndroidX core, lifecycle, activity, Room, WorkManager, the Health
Connect client, and kotlinx-coroutines — all Google or JetBrains. No
third-party network or analytics code. `play-services-location` is
compile-only: it ships only if your app adds it, for Activity Recognition. `npm audit` and Dependabot run on the
JS toolchain, which is development-only; nothing from `node_modules` ships in
the package.
