/**
 * The slice of react-native the JS layer touches, scripted for Jest. Tests
 * install a fake native module with `setNativeModule()` and drive it.
 */
type Listener = (payload: unknown) => void;

export const Platform = { OS: 'android' as string };

const listeners = new Map<string, Set<Listener>>();

export class NativeEventEmitter {
  addListener(event: string, listener: Listener) {
    const set = listeners.get(event) ?? new Set<Listener>();
    set.add(listener);
    listeners.set(event, set);
    return { remove: () => set.delete(listener) };
  }
  removeAllListeners(event: string) {
    listeners.delete(event);
  }
}

/** Test hook: fire a native event into every registered listener. */
export function __emit(event: string, payload: unknown) {
  listeners.get(event)?.forEach((l) => l(payload));
}

export function __listenerCount(event: string): number {
  return listeners.get(event)?.size ?? 0;
}

export const NativeModules: Record<string, unknown> = {};

export const TurboModuleRegistry = {
  get<T>(name: string): T | null {
    return (NativeModules[name] as T) ?? null;
  },
};

export const AppState = {
  addEventListener: () => ({ remove: () => {} }),
  currentState: 'active',
};

export type TurboModule = object;

/** Test hook: install (or clear) the fake native module. */
export function setNativeModule(mod: Record<string, unknown> | null) {
  if (mod) NativeModules.StepTrackerPro = mod;
  else delete NativeModules.StepTrackerPro;
  listeners.clear();
}
