/** Shared demo-mode banner. Rendered on every screen in Phase 0. */
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

export function DemoBanner() {
  return (
    <View style={styles.banner} testID="demo-banner" accessibilityRole="alert">
      <Text style={styles.text}>Demo mode — OCR is not connected. All results are mock data.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: {
    backgroundColor: '#fff3cf',
    borderColor: '#d9a900',
    borderRadius: 8,
    borderWidth: 1,
    padding: 10,
  },
  text: {
    color: '#5c4a00',
    fontSize: 14,
    fontWeight: '600',
  },
});
