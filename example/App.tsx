import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
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
  useStepStats,
  useStepTracker,
} from 'react-native-step-tracker-pro';

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

export default function App() {
  const { snapshot, state, ready, error, start, pause, resume, stop, requestPermissions } =
    useStepTracker({
      height: 175,
      weight: 75,
      dailyGoal: 10000,
      historyRetentionDays: 31,
      notificationTitle: '{steps} steps today',
      onGoalReached: (event) =>
        Alert.alert('Goal reached', `${event.goal.toLocaleString()} steps done.`),
    });

  const { stats } = useStepStats('week');
  const [battery, setBattery] = useState<boolean | null>(null);
  const [pending, setPending] = useState(0);

  useEffect(() => {
    if (!isSupported()) return;
    StepTracker.isBatteryOptimizationEnabled().then(setBattery).catch(() => {});
    StepTracker.getPendingSyncCount().then(setPending).catch(() => {});
  }, [snapshot?.date]);

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

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="dark-content" backgroundColor={palette.surface} />
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.count}>{steps.toLocaleString()}</Text>
        <Text style={styles.countLabel}>steps today</Text>

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
                      backgroundColor:
                        day.steps >= goal ? palette.goal : palette.lane,
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
          <Text style={styles.body}>
            {pending} {pending === 1 ? 'day' : 'days'} waiting to sync.
          </Text>
          <View style={styles.controls}>
            <Button
              label="Grant access"
              onPress={async () => {
                const status = await StepTracker.requestHealthConnectPermissions();
                if (!status.granted) StepTracker.openHealthConnectSettings();
              }}
            />
            <Button
              label="Sync now"
              onPress={async () => {
                await StepTracker.syncNow();
                setPending(await StepTracker.getPendingSyncCount());
              }}
            />
          </View>
        </View>

        {battery === true && (
          <Pressable
            style={styles.warning}
            onPress={() => StepTracker.openBatteryOptimizationSettings()}
          >
            <Text style={styles.warningText}>
              Battery optimisation is on for this app. Counting can stop when the
              screen is off. Tap to change it.
            </Text>
          </Pressable>
        )}

        {error && <Text style={styles.error}>{error.message}</Text>}

        <Text style={styles.footer}>
          Sensor: {snapshot?.source ?? 'none'} · State: {state}
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
