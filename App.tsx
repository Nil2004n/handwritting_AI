/**
 * NotebookOCR entry point (Phase 0).
 *
 * Minimal screen switching (list <-> review) without a navigation library:
 * the store already owns session state, so App only needs to know which
 * fixture is open. Safe-area aware, keyboard-safe via per-screen ScrollView.
 */

import React from 'react';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar, StyleSheet } from 'react-native';
import { StoreProvider, useStore } from './src/state/DocumentStore';
import { DocumentListScreen } from './src/screens/DocumentListScreen';
import { DocumentReviewScreen } from './src/screens/DocumentReviewScreen';

function Root() {
  const { state } = useStore();
  return (
    <SafeAreaView style={styles.safe} edges={['top', 'bottom', 'left', 'right']}>
      <StatusBar barStyle="dark-content" />
      {state.currentFixtureId ? (
        <DocumentReviewScreen fixtureId={state.currentFixtureId} />
      ) : (
        <DocumentListScreen />
      )}
    </SafeAreaView>
  );
}

function App() {
  return (
    <SafeAreaProvider>
      <StoreProvider>
        <Root />
      </StoreProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safe: {
    backgroundColor: '#faf7ef',
    flex: 1,
  },
});

export default App;
