export type StepTrackerErrorCode =
  | 'E_UNSUPPORTED_PLATFORM'
  | 'E_NO_SENSOR'
  /** Emitted on the `error` event: the sensor exists but registration failed; the service is retrying. */
  | 'E_SENSOR_UNAVAILABLE'
  | 'E_NOT_INITIALIZED'
  | 'E_PERMISSION_DENIED'
  | 'E_SERVICE_START_FAILED'
  | 'E_HEALTH_CONNECT_UNAVAILABLE'
  /** No provider installed. Answer with `installHealthConnect()`. */
  | 'E_HEALTH_CONNECT_NOT_INSTALLED'
  /** Provider too old. Also answered by `installHealthConnect()`. */
  | 'E_HEALTH_CONNECT_UPDATE_REQUIRED'
  | 'E_HEALTH_CONNECT_DENIED'
  /**
   * A Health Connect permission config asks for is not declared in the app's
   * manifest. From 2.0 the app declares them; see docs/PERMISSIONS.md.
   */
  | 'E_HEALTH_CONNECT_NOT_DECLARED'
  /** `requestIntegrityToken()` needs `com.google.android.play:integrity` in the app. */
  | 'E_INTEGRITY_UNAVAILABLE'
  /** Play Integrity returned an error; the message carries Play's error code. */
  | 'E_INTEGRITY_FAILED'
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
