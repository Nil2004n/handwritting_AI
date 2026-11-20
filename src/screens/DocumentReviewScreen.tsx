/**
 * Document review screen (Phase 0).
 *
 * Flow: "Extract demo" runs the mock engine asynchronously (loading state,
 * duplicate taps ignored) -> ordered blocks render -> selecting a block
 * highlights its source region -> raw output stays read-only while the
 * editable copy can change. Edits persist per document for the session.
 * Re-extraction requires an explicit two-step reset first, so edits are
 * never silently overwritten.
 */

import React, { useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { Block } from '../domain/document';
import { DemoBanner } from '../components/DemoBanner';
import { SourceImageView } from '../components/SourceImageView';
import { EquationBlockEditor, TableBlockEditor, TextBlockEditor } from '../components/BlockEditors';
import { getFixture } from '../fixtures';
import { useStore } from '../state/DocumentStore';

function blockLabel(block: Block, index: number): string {
  const kind = block.kind === 'text' ? 'Text' : block.kind === 'table' ? 'Table' : 'Equation';
  const state = block.status === 'edited' ? 'edited' : block.status === 'confirmed' ? 'confirmed' : 'needs review';
  return `${index + 1} · ${kind} — ${state}`;
}

export function DocumentReviewScreen({ fixtureId }: { fixtureId: string }) {
  const {
    state,
    backToList,
    selectBlock,
    extractDemo,
    resetDocument,
    updateText,
    updateCell,
    updateLatex,
  } = useStore();
  const [confirmingReset, setConfirmingReset] = useState(false);

  const fixture = getFixture(fixtureId);
  const session = state.sessions[fixtureId];

  if (!fixture || !session) {
    return (
      <View style={styles.page}>
        <Text style={styles.title}>Unknown document</Text>
        <Pressable testID="back-button" style={styles.button} onPress={backToList}>
          <Text style={styles.buttonText}>Back to samples</Text>
        </Pressable>
      </View>
    );
  }

  const blocks = session.blocks;
  const selected = blocks?.find(b => b.id === session.selectedBlockId) ?? null;
  const running = session.extraction === 'running';

  return (
    <ScrollView style={styles.page} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      <DemoBanner />
      <Pressable testID="back-button" onPress={backToList} accessibilityRole="button" accessibilityLabel="Back to samples">
        <Text style={styles.back}>‹ Back to samples</Text>
      </Pressable>
      <Text style={styles.title}>{fixture.title}</Text>
      <Text style={styles.meta} testID="origin-line">
        Origin: {fixture.origin} (demo mock) · Engine: {fixture.engineId}
      </Text>

      {blocks === null ? (
        <View style={styles.extractBox}>
          <Text style={styles.subtitle}>
            No extracted content yet. “Extract demo” calls the mock OCR engine through the shared
            OCR interface and returns the deterministic fixture result.
          </Text>
          {running ? (
            <View style={styles.loadingRow}>
              <ActivityIndicator testID="extract-loading" size="small" />
              <Text style={styles.subtitle}>Extracting demo…</Text>
            </View>
          ) : (
            <Pressable
              testID="extract-button"
              style={styles.button}
              onPress={() => extractDemo(fixtureId)}
              accessibilityRole="button"
              accessibilityLabel="Extract demo">
              <Text style={styles.buttonText}>Extract demo</Text>
            </Pressable>
          )}
          {session.extraction === 'error' ? (
            <View style={styles.errorBox} testID="extract-error">
              <Text style={styles.errorText}>{session.error ?? 'Extraction failed.'}</Text>
              <Pressable style={styles.button} onPress={() => extractDemo(fixtureId)}>
                <Text style={styles.buttonText}>Retry</Text>
              </Pressable>
            </View>
          ) : null}
        </View>
      ) : (
        <View style={styles.review}>
          <SourceImageView
            assetKey={fixture.image.assetKey}
            imageWidth={fixture.image.width}
            imageHeight={fixture.image.height}
            regions={blocks.map(b => ({
              id: b.id,
              label: b.kind,
              bounds: b.bounds,
            }))}
            selectedId={session.selectedBlockId}
            onSelect={id => selectBlock(fixtureId, id)}
          />
          <Text style={styles.sectionTitle}>Blocks in reading order</Text>
          {blocks.map((block, index) => {
            const isSelected = block.id === session.selectedBlockId;
            return (
              <Pressable
                key={block.id}
                testID={`select-${block.id}`}
                onPress={() => selectBlock(fixtureId, block.id)}
                style={[styles.blockRow, isSelected && styles.blockRowSelected]}
                accessibilityRole="button"
                accessibilityLabel={`Select block ${index + 1}`}>
                <Text style={[styles.blockRowText, isSelected && styles.blockRowTextSelected]}>
                  {blockLabel(block, index)}
                </Text>
              </Pressable>
            );
          })}
          {selected ? (
            <View style={styles.editorBox} testID={`editor-${selected.id}`}>
              <Text style={styles.sectionTitle}>Selected block</Text>
              {selected.kind === 'text' ? (
                <TextBlockEditor block={selected} onChange={text => updateText(fixtureId, selected.id, text)} />
              ) : selected.kind === 'table' ? (
                <TableBlockEditor
                  block={selected}
                  onChangeCell={(cellId, text) => updateCell(fixtureId, selected.id, cellId, text)}
                />
              ) : (
                <EquationBlockEditor
                  block={selected}
                  onChange={latex => updateLatex(fixtureId, selected.id, latex)}
                />
              )}
            </View>
          ) : null}
          <View style={styles.resetBox}>
            {confirmingReset ? (
              <>
                <Text style={styles.warning}>
                  Reset discards {session.hasEdits ? 'your edits' : 'the demo result'} and returns to
                  the pre-extraction state. This cannot be undone.
                </Text>
                <Pressable
                  testID="confirm-reset-button"
                  style={[styles.button, styles.dangerButton]}
                  onPress={() => {
                    setConfirmingReset(false);
                    resetDocument(fixtureId);
                  }}>
                  <Text style={styles.buttonText}>Confirm reset</Text>
                </Pressable>
                <Pressable style={styles.buttonSecondary} onPress={() => setConfirmingReset(false)}>
                  <Text style={styles.buttonSecondaryText}>Keep my data</Text>
                </Pressable>
              </>
            ) : (
              <Pressable
                testID="reset-button"
                style={styles.buttonSecondary}
                onPress={() => setConfirmingReset(true)}>
                <Text style={styles.buttonSecondaryText}>
                  {session.hasEdits ? 'Reset demo data (discards edits)…' : 'Reset demo data…'}
                </Text>
              </Pressable>
            )}
            <Text style={styles.meta}>
              Prose/table equations shown here are demo mock data — nothing was recognized from the image.
            </Text>
          </View>
        </View>
      )}
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
    paddingBottom: 40,
  },
  back: {
    color: '#1a6fd4',
    fontSize: 16,
    fontWeight: '600',
  },
  title: {
    color: '#22201b',
    fontSize: 22,
    fontWeight: '700',
  },
  meta: {
    color: '#6b6455',
    fontSize: 13,
  },
  subtitle: {
    color: '#5b5347',
    fontSize: 14,
  },
  sectionTitle: {
    color: '#22201b',
    fontSize: 16,
    fontWeight: '700',
    marginTop: 4,
  },
  extractBox: {
    gap: 12,
  },
  loadingRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
  },
  button: {
    alignItems: 'center',
    backgroundColor: '#1a6fd4',
    borderRadius: 8,
    justifyContent: 'center',
    minHeight: 48,
    padding: 12,
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '600',
  },
  dangerButton: {
    backgroundColor: '#b3261e',
  },
  buttonSecondary: {
    alignItems: 'center',
    borderColor: '#1a6fd4',
    borderRadius: 8,
    borderWidth: 1,
    justifyContent: 'center',
    minHeight: 48,
    padding: 12,
  },
  buttonSecondaryText: {
    color: '#1a6fd4',
    fontSize: 15,
    fontWeight: '600',
  },
  errorBox: {
    backgroundColor: '#fdecea',
    borderColor: '#b3261e',
    borderRadius: 8,
    borderWidth: 1,
    gap: 8,
    padding: 12,
  },
  errorText: {
    color: '#7a1f1a',
    fontSize: 14,
  },
  review: {
    gap: 10,
  },
  blockRow: {
    backgroundColor: '#ffffff',
    borderColor: '#c9c2b2',
    borderRadius: 8,
    borderWidth: 1,
    minHeight: 48,
    justifyContent: 'center',
    padding: 12,
  },
  blockRowSelected: {
    borderColor: '#1a6fd4',
    borderWidth: 2,
  },
  blockRowText: {
    color: '#3d3830',
    fontSize: 15,
  },
  blockRowTextSelected: {
    color: '#1a6fd4',
    fontWeight: '700',
  },
  editorBox: {
    backgroundColor: '#ffffff',
    borderColor: '#c9c2b2',
    borderRadius: 10,
    borderWidth: 1,
    gap: 8,
    padding: 14,
  },
  resetBox: {
    gap: 8,
    marginTop: 4,
  },
  warning: {
    color: '#7a1f1a',
    fontSize: 14,
    fontWeight: '600',
  },
});
