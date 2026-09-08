export { StepTracker, StepTracker as default, isSupported } from './StepTracker';
export { StepTrackerError } from './errors';
export type { StepTrackerErrorCode } from './errors';
export { DEFAULT_CONFIG, STRIDE_COEFFICIENT } from './constants';
export * from './hooks';
export type {
  DayChangedEvent,
  DayRecord,
  DeviceCapabilities,
  EventSubscription,
  GoalProgressEvent,
  GoalReachedEvent,
  HealthConnectStatus,
  PermissionStatus,
  RangeOptions,
  RangeStats,
  SensorSource,
  Sex,
  StepSnapshot,
  StepTrackerConfig,
  StepTrackerEvent,
  StepTrackerEventMap,
  StepsChangedEvent,
  SyncEvent,
  TrackingState,
  TrackingStateEvent,
} from './types';
