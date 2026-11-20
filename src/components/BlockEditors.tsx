/**
 * Block editors (Phase 0): text, table, equation.
 *
 * Every editor shows the raw recognition output separately (read-only) and
 * an editable control bound to the session copy. Raw values are never
 * written to — edits flow through the store's update callbacks.
 */

import React from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import type { EquationBlock, TableBlock, TextBlock } from '../domain/document';
import { EquationPreview } from './EquationPreview';

function RawBox({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.rawBox}>
      <Text style={styles.rawLabel}>{label}</Text>
      <Text style={styles.rawText} selectable>
        {value === '' ? '(blank)' : value}
      </Text>
    </View>
  );
}

export function TextBlockEditor({
  block,
  onChange,
}: {
  block: TextBlock;
  onChange: (text: string) => void;
}) {
  return (
    <View style={styles.editor}>
      <RawBox label="Raw recognition output (read-only, demo mock)" value={block.rawText} />
      <Text style={styles.label}>Editable text</Text>
      <TextInput
        testID={`edit-${block.id}`}
        style={styles.multiline}
        multiline
        value={block.editedText}
        onChangeText={onChange}
        textAlignVertical="top"
        accessibilityLabel={`Edit text block ${block.id}`}
      />
      {block.needsReview ? <Text style={styles.flag}>Flagged for review — check the source image.</Text> : null}
    </View>
  );
}

export function TableBlockEditor({
  block,
  onChangeCell,
}: {
  block: TableBlock;
  onChangeCell: (cellId: string, text: string) => void;
}) {
  const columnCount = block.rows[0]?.cells.length ?? 0;
  return (
    <View style={styles.editor}>
      <Text style={styles.label}>
        Editable grid — {block.rows.length} rows × {columnCount} columns (no merged cells in Phase 0)
      </Text>
      {block.rows.map((row, r) => (
        <View key={row.id} style={styles.tableRow}>
          {row.cells.map((cell, c) => (
            <TextInput
              key={cell.id}
              testID={`cell-${cell.id}`}
              style={styles.cell}
              value={cell.editedText}
              onChangeText={text => onChangeCell(cell.id, text)}
              placeholder={cell.rawText === '' ? '(blank)' : undefined}
              accessibilityLabel={`Edit row ${r + 1} column ${c + 1}`}
            />
          ))}
        </View>
      ))}
      <RawBox
        label="Raw recognition output (read-only, demo mock)"
        value={block.rows.map(r => r.cells.map(c => (c.rawText === '' ? '(blank)' : c.rawText)).join(' | ')).join('\n')}
      />
    </View>
  );
}

export function EquationBlockEditor({
  block,
  onChange,
}: {
  block: EquationBlock;
  onChange: (latex: string) => void;
}) {
  return (
    <View style={styles.editor}>
      <RawBox
        label={
          block.rawSeed === null
            ? 'No recognition seed (authored manually — never auto-recognized)'
            : 'Raw seed (read-only, demo mock — never auto-recognized)'
        }
        value={block.rawSeed ?? '(authored manually)'}
      />
      <Text style={styles.label}>Editable LaTeX</Text>
      <TextInput
        testID={`latex-${block.id}`}
        style={[styles.multiline, styles.mono]}
        multiline
        autoCapitalize="none"
        autoCorrect={false}
        value={block.latex}
        onChangeText={onChange}
        textAlignVertical="top"
        accessibilityLabel={`Edit LaTeX for equation block ${block.id}`}
      />
      <Text style={styles.label}>Local preview (offline, bundled renderer)</Text>
      <EquationPreview latex={block.latex} />
      {block.needsReview ? <Text style={styles.flag}>Needs review — verify against the source region.</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  editor: {
    gap: 8,
  },
  label: {
    color: '#3d3830',
    fontSize: 14,
    fontWeight: '600',
  },
  multiline: {
    backgroundColor: '#ffffff',
    borderColor: '#c9c2b2',
    borderRadius: 8,
    borderWidth: 1,
    fontSize: 16,
    minHeight: 96,
    padding: 10,
  },
  mono: {
    fontFamily: 'monospace',
  },
  rawBox: {
    backgroundColor: '#f1ede1',
    borderColor: '#c9c2b2',
    borderRadius: 8,
    borderWidth: 1,
    padding: 10,
  },
  rawLabel: {
    color: '#6b6455',
    fontSize: 12,
    fontWeight: '600',
    marginBottom: 4,
  },
  rawText: {
    color: '#3d3830',
    fontSize: 15,
  },
  tableRow: {
    flexDirection: 'row',
    gap: 6,
  },
  cell: {
    backgroundColor: '#ffffff',
    borderColor: '#c9c2b2',
    borderRadius: 6,
    borderWidth: 1,
    flex: 1,
    fontSize: 15,
    minHeight: 48,
    padding: 8,
  },
  flag: {
    color: '#8a3b00',
    fontSize: 13,
    fontWeight: '600',
  },
});
