/**
 * Shared fixture helpers (Phase 0).
 *
 * Validates the structural invariants every fixture must satisfy. Used by
 * unit tests; the UI treats fixtures as already-validated demo data.
 */

import type { NotebookDocument } from '../domain/document';
import { isBoundsInsideImage } from '../utils/coordinates';

export function validateDocument(doc: NotebookDocument): string[] {
  const errors: string[] = [];
  if (!doc.id) errors.push('document id is empty');
  if (doc.schemaVersion !== 1) errors.push(`unsupported schemaVersion ${doc.schemaVersion}`);
  if (!(doc.image.width > 0) || !(doc.image.height > 0)) {
    errors.push(`invalid image dimensions ${doc.image.width}x${doc.image.height}`);
  }

  const blockIds = new Set<string>();
  doc.blocks.forEach((block, index) => {
    if (blockIds.has(block.id)) errors.push(`duplicate block id ${block.id}`);
    blockIds.add(block.id);
    if (block.order !== index) {
      errors.push(`block ${block.id} has order ${block.order} but sits at index ${index}`);
    }
    if (!isBoundsInsideImage(block.bounds, doc.image.width, doc.image.height)) {
      errors.push(`block ${block.id} bounds ${JSON.stringify(block.bounds)} outside image`);
    }
    if (block.provenance === 'formula-model') {
      errors.push(`block ${block.id} claims formula-model provenance in a mock fixture`);
    }
    if (block.kind === 'table') {
      const widths = new Set(block.rows.map(r => r.cells.length));
      if (widths.size !== 1) {
        errors.push(`table ${block.id} has inconsistent row widths`);
      }
      const cellIds = new Set<string>();
      for (const row of block.rows) {
        for (const cell of row.cells) {
          if (cellIds.has(cell.id)) errors.push(`duplicate cell id ${cell.id}`);
          cellIds.add(cell.id);
          if (cell.bounds && !isBoundsInsideImage(cell.bounds, doc.image.width, doc.image.height)) {
            errors.push(`cell ${cell.id} bounds outside image`);
          }
        }
      }
    }
    if (block.kind === 'equation' && block.latexProvenance === 'formula-model') {
      errors.push(`equation ${block.id} claims formula-model provenance in a mock fixture`);
    }
  });

  const orders = doc.blocks.map(b => b.order).sort((a, b2) => a - b2);
  orders.forEach((order, i) => {
    if (order !== i) errors.push(`reading order is not dense (expected ${i}, saw ${order})`);
  });
  return errors;
}

/** Deep clone helper so edits and engine results never alias fixture data. */
export function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
