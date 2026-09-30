export type StepTrackerErrorCode =
  | 'E_UNSUPPORTED_PLATFORM'
  | 'E_NO_SENSOR'
  /** Emitted on the `error` event: the sensor exists but registration failed; the service is retrying. */
  | 'E_SENSOR_UNAVAILABLE'
  /**
   * @deprecated Never sent: every method works before `initialize()`, on
   *   the config stored last or the defaults. Kept so code that checks for
   *   it still compiles; it will be removed in 3.0.
   */
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
  /**
   * Play Integrity returned an error. The message carries Play's error
   * code, and `details` carries it as data with whether a retry can
   * succeed - see `IntegrityErrorDetails`.
   */
  | 'E_INTEGRITY_FAILED'
  /** The on-device database failed - a full disk, a corrupt file. */
  | 'E_DATABASE'
  | 'E_INVALID_CONFIG'
  | 'E_NO_ACTIVITY'
  | 'E_NOT_TRACKING'
  | 'E_UNKNOWN';

export class StepTrackerError extends Error {
  readonly code: StepTrackerErrorCode;
  /**
   * Structured detail the native side attached, when it did. For
   * `E_INTEGRITY_FAILED` it is an `IntegrityErrorDetails`.
   */
  readonly details?: Readonly<Record<string, unknown>>;

  constructor(
    code: StepTrackerErrorCode,
    message: string,
    details?: Readonly<Record<string, unknown>>
  ) {
    super(message);
    this.name = 'StepTrackerError';
    this.code = code;
    this.details = details;
  }
}

/** Normalises rejections coming off the native bridge. */
export function toStepTrackerError(error: unknown): StepTrackerError {
  if (error instanceof StepTrackerError) return error;
  const anyError = error as {
    code?: string;
    message?: string;
    userInfo?: unknown;
  } | null;
  const code = (anyError?.code ?? 'E_UNKNOWN') as StepTrackerErrorCode;
  // React Native copies a rejection's userInfo onto the error on both
  // architectures; it is null when the native side attached none.
  const userInfo = anyError?.userInfo;
  const details =
    userInfo != null && typeof userInfo === 'object' && !Array.isArray(userInfo)
      ? (userInfo as Record<string, unknown>)
      : undefined;
  return new StepTrackerError(code, anyError?.message ?? 'Unknown native error', details);
}
