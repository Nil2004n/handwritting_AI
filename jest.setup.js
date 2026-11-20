/**
 * Jest setup: replace react-native-safe-area-context with its bundled mock
 * so SafeAreaProvider renders children synchronously in tests.
 */
/* eslint-env jest */
jest.mock('react-native-safe-area-context', () =>
  jest.requireActual('react-native-safe-area-context/jest/mock').default ?? {},
);
