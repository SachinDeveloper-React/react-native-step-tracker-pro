import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  AppState,
  Pressable,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import StepTracker, {
  isSupported,
  useHealthConnect,
  useStepStats,
  useStepTracker,
} from 'react-native-step-tracker-pro';
import type { TrackingHealth, VerificationSnapshot } from 'react-native-step-tracker-pro';

const palette = {
  surface: '#E9EDF2',
  card: '#FFFFFF',
  ink: '#16202B',
  muted: '#61717F',
  lane: '#0F4C81',
  laneDim: '#CBD7E3',
  goal: '#F2B705',
  line: '#D6DEE6',
};

const DAY_LABELS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];

/** Turns the status shape into one line a user can act on. */
function healthSummary(health: ReturnType<typeof useHealthConnect>): string {
  if (health.loading) return 'Checking…';
  const status = health.status;
  if (!status) return 'Unavailable.';
  switch (status.availability) {
    case 'not_supported':
      return 'Not available on this device.';
    case 'not_installed':
      return 'Not installed. Install it to sync with a watch or other apps.';
    case 'update_required':
      return 'Needs an update before it can be used.';
    default:
      break;
  }
  if (status.granted) {
    return health.hasWearable
      ? 'Connected. A wearable is publishing steps.'
      : 'Connected.';
  }
  if (status.shouldOpenSettings) {
    // Health Connect stops showing its sheet after two refusals, so asking
    // again does nothing visible — the settings screen is the only route left.
    return 'Access was declined twice, so the prompt no longer appears. Grant it in Health Connect settings.';
  }
  return status.canRead
    ? 'Partly connected — reading is allowed but writing is not.'
    : 'Not connected.';
}

export default function App() {
  const {
    snapshot,
    state,
    ready,
    error,
    start,
    pause,
    resume,
    stop,
    requestPermissions,
  } = useStepTracker({
    height: 175,
    weight: 75,
    dailyGoal: 10000,
    historyRetentionDays: 31,
    notificationTitle: '{steps} steps today',
    // Required before Play will accept health permissions; Health Connect
    // links to it from its own permission sheet.
    privacyPolicyUrl: 'https://example.com/privacy',
    // Take the phone's count or a paired watch's, whichever saw more of the
    // day. Never both added together.
    stepSource: 'auto',
    // The settings an app that pays for steps would use, here so the example
    // shows what they change. Off, a typed-in entry counts like any other;
    // on, it is read and listed but never becomes the day's number.
    healthConnectIgnoreManualEntries: true,
    // Only a catalogued companion app (or one on wearableAllowlist) is
    // trusted for its whole margin; anything else stamping TYPE_WATCH is
    // bound by the coverage rule like a phone-side app.
    wearableTrust: 'catalog',
    // A closed day never changes after the fact, and one day can never be
    // handed a week's worth of counter.
    gapRecovery: 'today_capped',
    onGoalReached: (event) =>
      Alert.alert('Goal reached', `${event.goal.toLocaleString()} steps done.`),
  });

  const { stats } = useStepStats('week');
  const health = useHealthConnect();
  const [pending, setPending] = useState(0);
  const [tracking, setTracking] = useState<TrackingHealth | null>(null);
  const [verification, setVerification] = useState<VerificationSnapshot | null>(null);

  // Re-read on every foreground: getTrackingHealth() is also what restarts a
  // service the OEM killed while the app was closed, and recoveryCount is
  // what decides whether to bother the user about it.
  const refreshHealth = useCallback(() => {
    if (!isSupported()) return;
    StepTracker.getTrackingHealth()
      .then(setTracking)
      .catch(() => {});
    StepTracker.getPendingSyncCount()
      .then(setPending)
      .catch(() => {});
  }, []);

  // What a server would be sent for today: the phone's own count, every
  // Health Connect origin unresolved, and what the policy chose. Re-read
  // with the snapshot so the manual and recovered figures track the count.
  const refreshVerification = useCallback(() => {
    if (!isSupported() || !snapshot?.date) return;
    StepTracker.getVerificationSnapshot(snapshot.date)
      .then(setVerification)
      .catch(() => {});
  }, [snapshot?.date]);

  useEffect(() => {
    refreshVerification();
  }, [refreshVerification, snapshot?.steps]);

  useEffect(() => {
    refreshHealth();
    const sub = AppState.addEventListener('change', (next) => {
      if (next === 'active') refreshHealth();
    });
    return () => sub.remove();
  }, [refreshHealth, snapshot?.date, state]);

  const onFixBackground = useCallback(async () => {
    // One screen per call: the battery exemption first, then the OEM's own
    // autostart screen. Say which one just opened so the user knows what to
    // tap on it.
    const shown = await StepTracker.requestBackgroundPermissions();
    if (shown === 'autostart') {
      Alert.alert(
        'Allow background activity',
        `Turn on autostart / background running for this app on the screen that opened. On ${tracking?.manufacturer ?? 'this phone'} this is what keeps counting alive.`
      );
    }
    refreshHealth();
  }, [refreshHealth, tracking?.manufacturer]);

  const onStart = useCallback(async () => {
    const granted = await requestPermissions();
    if (!granted) {
      Alert.alert(
        'Permission needed',
        'Physical activity access is required to read the step sensor.'
      );
      return;
    }
    await start();
  }, [requestPermissions, start]);

  if (!isSupported()) {
    return (
      <SafeAreaView style={styles.screen}>
        <View style={styles.center}>
          <Text style={styles.title}>Android only</Text>
          <Text style={styles.body}>
            This package reads Android step sensors. Run the example on an Android device.
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  if (!ready) {
    return (
      <SafeAreaView style={styles.screen}>
        <View style={styles.center}>
          <ActivityIndicator color={palette.lane} />
        </View>
      </SafeAreaView>
    );
  }

  const steps = snapshot?.steps ?? 0;
  const goal = snapshot?.dailyGoal ?? 10000;
  const progress = Math.min(1, snapshot?.goalProgress ?? 0);
  const km = ((snapshot?.distance ?? 0) / 1000).toFixed(2);
  const kcal = Math.round(snapshot?.calories ?? 0);
  const best = stats?.bestDay?.steps ?? 1;
  // Typed-in steps across every external origin for the day. Read and
  // listed, and under healthConnectIgnoreManualEntries never counted.
  const manualSteps = (verification?.sources ?? [])
    .filter((source) => !source.isSelf && source.manualSteps > 0)
    .reduce((sum, source) => sum + source.manualSteps, 0);
  const recoveredSteps = snapshot?.recoveredSteps ?? 0;

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="dark-content" backgroundColor={palette.surface} />
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.count}>{steps.toLocaleString()}</Text>
        <Text style={styles.countLabel}>
          {/* Attribution matters when the number was measured by hardware this
              app did not write. */}
          {snapshot?.stepSource?.usedExternal
            ? `steps today · from ${snapshot.stepSource.appName}`
            : 'steps today'}
        </Text>

        {/* The lane: filled portion is distance covered, ticks are 25% marks. */}
        <View style={styles.lane}>
          <View style={[styles.laneFill, { width: `${progress * 100}%` }]} />
          {[0.25, 0.5, 0.75].map((mark) => (
            <View key={mark} style={[styles.laneTick, { left: `${mark * 100}%` }]} />
          ))}
        </View>
        <Text style={styles.laneLabel}>
          {Math.round(progress * 100)}% of {goal.toLocaleString()}
        </Text>
        {/*
          The two figures a server weighs differently from the headline:
          steps this phone credited in one go after a dead period, and steps
          somebody typed into Health Connect by hand.
        */}
        {(recoveredSteps > 0 || manualSteps > 0) && (
          <Text style={styles.laneLabel}>
            {recoveredSteps > 0
              ? `${recoveredSteps.toLocaleString()} recovered after a gap`
              : ''}
            {recoveredSteps > 0 && manualSteps > 0 ? ' · ' : ''}
            {manualSteps > 0
              ? `${manualSteps.toLocaleString()} typed in by hand, not counted`
              : ''}
          </Text>
        )}

        <View style={styles.row}>
          <Metric value={km} unit="km" />
          <Metric value={String(kcal)} unit="kcal" />
          <Metric value={stats ? String(stats.activeDays) : '—'} unit="active days" />
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>This week</Text>
          <View style={styles.chart}>
            {(stats?.days ?? []).slice(0, 7).map((day, index) => (
              <View key={day.date} style={styles.bar}>
                <View
                  style={[
                    styles.barFill,
                    {
                      height: `${Math.max(4, (day.steps / Math.max(best, 1)) * 100)}%`,
                      backgroundColor: day.steps >= goal ? palette.goal : palette.lane,
                    },
                  ]}
                />
                <Text style={styles.barLabel}>{DAY_LABELS[index]}</Text>
              </View>
            ))}
          </View>
          <Text style={styles.cardMeta}>
            {(stats?.totalSteps ?? 0).toLocaleString()} total ·{' '}
            {(stats?.averageSteps ?? 0).toLocaleString()} daily average
          </Text>
        </View>

        <View style={styles.controls}>
          {state !== 'running' && state !== 'paused' && (
            <Button label="Start tracking" onPress={onStart} primary />
          )}
          {state === 'running' && <Button label="Pause" onPress={pause} />}
          {state === 'paused' && <Button label="Resume" onPress={resume} primary />}
          {(state === 'running' || state === 'paused') && (
            <Button label="Stop" onPress={stop} />
          )}
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Health Connect</Text>
          <Text style={styles.body}>{healthSummary(health)}</Text>

          <View style={styles.controls}>
            {/*
              Which button to show is entirely decided by the status shape:
              nothing to offer, install, ask, or go to settings because the
              provider has stopped showing its sheet. `enable()` walks the same
              ladder in one call if you would rather not branch.
            */}
            {health.status?.availability === 'not_supported' ? null : health.status
                ?.installable ? (
              <Button label="Install Health Connect" onPress={health.install} primary />
            ) : health.status?.shouldOpenSettings ? (
              <Button label="Open settings" onPress={health.openSettings} primary />
            ) : !health.status?.granted ? (
              <Button label="Connect" onPress={health.enable} primary />
            ) : (
              <Button label="Disconnect" onPress={health.revoke} />
            )}
            <Button
              label="Sync now"
              onPress={async () => {
                await StepTracker.syncNow();
                setPending(await StepTracker.getPendingSyncCount());
              }}
            />
          </View>

          <Text style={styles.body}>
            {pending} {pending === 1 ? 'day' : 'days'} waiting to sync.
          </Text>
        </View>

        {/*
          Every app publishing steps, so the user can choose. These totals are
          per-source counts of the same walking, not slices of it — picking one
          is the whole point, adding them would roughly double the number.
        */}
        {health.sources.length > 0 && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Step source</Text>
            <Text style={styles.body}>
              Phone counted {health.current?.deviceSteps.toLocaleString() ?? 0}
              {health.current?.externalSteps
                ? ` · best other source ${health.current.externalSteps.toLocaleString()}`
                : ''}
            </Text>
            <View style={styles.controls}>
              <Button
                label="This phone"
                primary={!health.current?.usedExternal}
                onPress={() => health.selectSource(null)}
              />
              {health.sources
                .filter((source) => !source.isSelf)
                .map((source) => (
                  <Button
                    key={source.packageName}
                    label={`${source.appName}${source.isWearable ? ' ⌚' : ''} · ${source.steps.toLocaleString()}`}
                    primary={health.current?.packageName === source.packageName}
                    onPress={() => health.selectSource(source.packageName)}
                  />
                ))}
            </View>
          </View>
        )}

        {/*
          Only shown on phones that need it: an aggressive OEM skin, Doze still
          applying, or evidence (recoveryCount) that the service has actually
          been killed. Stock Android users never see this card.
        */}
        {tracking &&
          tracking.shouldBeRunning &&
          (tracking.aggressiveOem ||
            tracking.batteryOptimizationEnabled ||
            tracking.recoveryCount > 0) && (
            <Pressable style={styles.warning} onPress={onFixBackground}>
              <Text style={styles.warningText}>
                {tracking.recoveryCount > 0
                  ? `${tracking.manufacturer} has stopped step counting ${tracking.recoveryCount} ${tracking.recoveryCount === 1 ? 'time' : 'times'}. Your steps were recovered, but the live count and notification were off in between. Tap to keep it running.`
                  : 'This phone may stop apps in the background to save power. Tap to allow step counting to keep running.'}
              </Text>
            </Pressable>
          )}

        {/*
          What getVerificationSnapshot() would hand a server for today:
          nothing resolved, every origin separately, so the number on screen
          is never the one that gets paid.
        */}
        {verification && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Verification snapshot</Text>
            <Text style={styles.body}>
              Phone {verification.deviceSteps.toLocaleString()} via {verification.sensor}
              {verification.recoveredSteps > 0
                ? ` (${verification.recoveredSteps.toLocaleString()} recovered)`
                : ''}
              {' · '}resolved {verification.resolved.steps.toLocaleString()} from{' '}
              {verification.resolved.appName}
              {verification.resolved.manualStepsExcluded > 0
                ? ` (${verification.resolved.manualStepsExcluded.toLocaleString()} manual excluded)`
                : ''}
            </Text>
            {verification.sources
              .filter((source) => !source.isSelf)
              .map((source) => (
                <Text key={source.packageName} style={styles.cardMeta}>
                  {source.appName}: {source.steps.toLocaleString()}
                  {source.manualSteps > 0
                    ? ` · ${source.manualSteps.toLocaleString()} manual`
                    : ''}
                  {source.isWearable
                    ? source.trustedWearable
                      ? ' · trusted wearable'
                      : ' · stamped as a wearable, not trusted'
                    : ''}
                </Text>
              ))}
            <Text style={styles.cardMeta}>
              Recovered {verification.health.recoveryCount}× ·{' '}
              {verification.clock.timezone}
            </Text>
          </View>
        )}

        {error && <Text style={styles.error}>{error.message}</Text>}

        <Text style={styles.footer}>
          Sensor: {snapshot?.source ?? 'none'} · State: {state}
          {tracking
            ? ` · Service: ${tracking.serviceAlive ? 'alive' : 'not running'}`
            : ''}
          {snapshot?.stepSource?.merged
            ? ` · ${snapshot.stepSource.appName} +${snapshot.stepSource.baselineSteps.toLocaleString()} baseline`
            : ''}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function Metric({ value, unit }: { value: string; unit: string }) {
  return (
    <View style={styles.metric}>
      <Text style={styles.metricValue}>{value}</Text>
      <Text style={styles.metricUnit}>{unit}</Text>
    </View>
  );
}

function Button({
  label,
  onPress,
  primary,
}: {
  label: string;
  onPress: () => void;
  primary?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        primary && styles.buttonPrimary,
        pressed && styles.buttonPressed,
      ]}
    >
      <Text style={[styles.buttonLabel, primary && styles.buttonLabelPrimary]}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: palette.surface },
  content: { padding: 24, paddingBottom: 48 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32 },
  title: { fontSize: 22, fontWeight: '600', color: palette.ink, marginBottom: 8 },
  body: { fontSize: 15, lineHeight: 22, color: palette.muted },
  count: { fontSize: 68, fontWeight: '300', color: palette.ink, letterSpacing: -2 },
  countLabel: { fontSize: 15, color: palette.muted, marginTop: -4 },
  lane: {
    height: 14,
    borderRadius: 7,
    backgroundColor: palette.laneDim,
    marginTop: 24,
    overflow: 'hidden',
  },
  laneFill: { height: '100%', backgroundColor: palette.lane, borderRadius: 7 },
  laneTick: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 1,
    backgroundColor: palette.surface,
  },
  laneLabel: { fontSize: 13, color: palette.muted, marginTop: 8 },
  row: { flexDirection: 'row', marginTop: 28, gap: 12 },
  metric: {
    flex: 1,
    backgroundColor: palette.card,
    borderRadius: 12,
    paddingVertical: 16,
    paddingHorizontal: 14,
  },
  metricValue: { fontSize: 22, fontWeight: '500', color: palette.ink },
  metricUnit: { fontSize: 12, color: palette.muted, marginTop: 2 },
  card: {
    backgroundColor: palette.card,
    borderRadius: 12,
    padding: 18,
    marginTop: 20,
  },
  cardTitle: { fontSize: 16, fontWeight: '600', color: palette.ink, marginBottom: 14 },
  cardMeta: { fontSize: 13, color: palette.muted, marginTop: 12 },
  chart: { flexDirection: 'row', alignItems: 'flex-end', height: 120, gap: 10 },
  bar: { flex: 1, height: '100%', justifyContent: 'flex-end', alignItems: 'center' },
  barFill: { width: '100%', borderRadius: 4 },
  barLabel: { fontSize: 11, color: palette.muted, marginTop: 6 },
  controls: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 20 },
  button: {
    paddingVertical: 12,
    paddingHorizontal: 18,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: palette.line,
    backgroundColor: palette.card,
  },
  buttonPrimary: { backgroundColor: palette.lane, borderColor: palette.lane },
  buttonPressed: { opacity: 0.7 },
  buttonLabel: { fontSize: 15, color: palette.ink, fontWeight: '500' },
  buttonLabelPrimary: { color: '#FFFFFF' },
  warning: {
    marginTop: 20,
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#FDF3D6',
    borderWidth: 1,
    borderColor: palette.goal,
  },
  warningText: { fontSize: 14, lineHeight: 20, color: '#5C4708' },
  error: { marginTop: 16, fontSize: 14, color: '#B3261E' },
  footer: { marginTop: 28, fontSize: 12, color: palette.muted },
});
