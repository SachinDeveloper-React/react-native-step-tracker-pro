// @ts-check
const tseslint = require('typescript-eslint');
const prettier = require('eslint-config-prettier');

module.exports = tseslint.config(
  { ignores: ['lib/**', 'node_modules/**', 'android/**', 'example/android/**', '*.js'] },
  ...tseslint.configs.recommended,
  prettier,
  {
    files: ['src/**/*.ts', 'src/**/*.tsx', 'example/**/*.tsx'],
    rules: {
      // The bridge speaks in UnsafeObject; the typed surface lives in StepTracker.ts.
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/consistent-type-imports': 'error',
      'no-console': 'error',
    },
  },
  {
    files: ['src/__tests__/**', 'src/__mocks__/**'],
    rules: { '@typescript-eslint/no-non-null-assertion': 'off' },
  },
  {
    files: ['example/**'],
    rules: { 'no-console': 'off' },
  }
);
