/**
 * Demo document selection (Phase 0).
 *
 * Lists the bundled synthetic fixtures. Camera/gallery import is explicitly
 * out of scope: the buttons are shown disabled so the boundary is visible,
 * never appearing functional.
 */

import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { FIXTURES } from '../fixtures';
import { DemoBanner } from '../components/DemoBanner';
import { useStore } from '../state/DocumentStore';

const KIND_LABEL: Record<string, string> = {
  prose: 'Prose',
  numericals: 'Numericals',
  table: 'Table',
  equation: 'Equation',
  mixed: 'Mixed page',
};

export function DocumentListScreen() {
  const { openFixture } = useStore();
  return (
    <ScrollView style={styles.page} contentContainerStyle={styles.content}>
      <DemoBanner />
      <Text style={styles.title}>Demo documents</Text>
      <Text style={styles.subtitle}>
        Synthetic samples bundled with the app. No camera, no uploads, no OCR — open a sample to try
        the review flow with mock data.
      </Text>
      {FIXTURES.map(fixture => (
        <Pressable
          key={fixture.id}
          testID={`open-${fixture.id}`}
          style={styles.card}
          onPress={() => openFixture(fixture.id)}
          accessibilityRole="button"
          accessibilityLabel={`Open ${fixture.title}`}>
          <Text style={styles.cardKind}>{KIND_LABEL[fixture.kind] ?? fixture.kind} · synthetic</Text>
          <Text style={styles.cardTitle}>{fixture.title}</Text>
          <Text style={styles.cardMeta}>
            {fixture.blocks.length} blocks · {fixture.image.width}×{fixture.image.height}px · mock
          </Text>
        </Pressable>
      ))}
      <View style={styles.importBox}>
        <Text style={styles.importTitle}>Import (not available in Phase 0)</Text>
        <Pressable style={[styles.button, styles.buttonDisabled]} disabled accessibilityLabel="Take photo, unavailable">
          <Text style={styles.buttonText}>Take photo — unavailable</Text>
        </Pressable>
        <Pressable style={[styles.button, styles.buttonDisabled]} disabled accessibilityLabel="Choose from gallery, unavailable">
          <Text style={styles.buttonText}>Choose from gallery — unavailable</Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  page: {
    backgroundColor: '#faf7ef',
    flex: 1,
  },
  content: {
    gap: 12,
    padding: 16,
    paddingBottom: 32,
  },
  title: {
    fontSize: 24,
    fontWeight: '700',
    color: '#22201b',
  },
  subtitle: {
    color: '#5b5347',
    fontSize: 14,
  },
  card: {
    backgroundColor: '#ffffff',
    borderColor: '#c9c2b2',
    borderRadius: 10,
    borderWidth: 1,
    padding: 14,
  },
  cardKind: {
    color: '#1a6fd4',
    fontSize: 12,
    fontWeight: '700',
  },
  cardTitle: {
    color: '#22201b',
    fontSize: 17,
    fontWeight: '600',
    marginTop: 2,
  },
  cardMeta: {
    color: '#6b6455',
    fontSize: 13,
    marginTop: 4,
  },
  importBox: {
    borderColor: '#c9c2b2',
    borderRadius: 10,
    borderStyle: 'dashed',
    borderWidth: 1,
    gap: 8,
    padding: 14,
  },
  importTitle: {
    color: '#5b5347',
    fontSize: 14,
    fontWeight: '600',
  },
  button: {
    alignItems: 'center',
    backgroundColor: '#1a6fd4',
    borderRadius: 8,
    minHeight: 48,
    justifyContent: 'center',
    padding: 12,
  },
  buttonDisabled: {
    backgroundColor: '#a8a294',
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600',
  },
});
