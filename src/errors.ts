export type StepTrackerErrorCode =
  | 'E_UNSUPPORTED_PLATFORM'
  | 'E_NO_SENSOR'
  | 'E_NOT_INITIALIZED'
  | 'E_PERMISSION_DENIED'
  | 'E_SERVICE_START_FAILED'
  | 'E_HEALTH_CONNECT_UNAVAILABLE'
  | 'E_HEALTH_CONNECT_DENIED'
  | 'E_DATABASE'
  | 'E_INVALID_CONFIG'
  | 'E_NO_ACTIVITY'
  | 'E_NOT_TRACKING'
  | 'E_UNKNOWN';

export class StepTrackerError extends Error {
  readonly code: StepTrackerErrorCode;

  constructor(code: StepTrackerErrorCode, message: string) {
    super(message);
    this.name = 'StepTrackerError';
    this.code = code;
  }
}

/** Normalises rejections coming off the native bridge. */
export function toStepTrackerError(error: unknown): StepTrackerError {
  if (error instanceof StepTrackerError) return error;
  const anyError = error as { code?: string; message?: string } | null;
  const code = (anyError?.code ?? 'E_UNKNOWN') as StepTrackerErrorCode;
  return new StepTrackerError(code, anyError?.message ?? 'Unknown native error');
}
