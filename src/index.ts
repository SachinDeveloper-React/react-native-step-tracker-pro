export { StepTracker, StepTracker as default, isSupported } from './StepTracker';
export { StepTrackerError } from './errors';
export type { StepTrackerErrorCode } from './errors';
export {
  DEFAULT_CONFIG,
  HEALTH_CONNECT_MAX_PROMPTS,
  HEALTH_CONNECT_PACKAGE,
  STRIDE_COEFFICIENT,
} from './constants';
export * from './hooks';
export type {
  CompanionApp,
  CurrentStepSource,
  DayChangedEvent,
  DayRecord,
  DeviceCapabilities,
  EventSubscription,
  GoalProgressEvent,
  GoalReachedEvent,
  HealthConnectAvailability,
  HealthConnectStatus,
  HealthConnectStatusEvent,
  PermissionStatus,
  RangeOptions,
  RangeStats,
  RequestHealthConnectOptions,
  ResolvedStepSource,
  SensorSource,
  Sex,
  StepSnapshot,
  StepSource,
  StepSourceChangedEvent,
  StepSourceKind,
  StepSourceList,
  StepSourcePolicy,
  StepTrackerConfig,
  StepTrackerEvent,
  StepTrackerEventMap,
  StepsChangedEvent,
  SyncEvent,
  TrackingState,
  TrackingStateEvent,
} from './types';
