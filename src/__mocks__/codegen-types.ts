// Stand-in for react-native/Libraries/Types/CodegenTypes under Jest.
export type UnsafeObject = Record<string, unknown>;

/** The shape codegen gives a typed native event: subscribe, get a subscription back. */
export type EventEmitter<T> = (handler: (value: T) => void | Promise<void>) => {
  remove(): void;
};
