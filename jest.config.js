/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.test.ts'],
  moduleNameMapper: {
    // The JS layer is tested against a scripted native module; nothing here
    // needs the real react-native runtime.
    '^react-native$': '<rootDir>/src/__mocks__/react-native.ts',
    '^react-native/Libraries/Types/CodegenTypes$':
      '<rootDir>/src/__mocks__/codegen-types.ts',
  },
  transform: {
    '^.+\\.tsx?$': ['ts-jest', { tsconfig: 'tsconfig.jest.json', diagnostics: true }],
  },
  collectCoverageFrom: ['src/**/*.ts', '!src/**/__tests__/**', '!src/**/__mocks__/**'],
};
