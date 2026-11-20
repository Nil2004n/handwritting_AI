/**
 * Fixture registry: the five Phase 0 demo documents.
 *
 * `createdAt` is stamped once at module load (outside rendering logic).
 * The mock engine serves deep copies of these; the UI must never mutate
 * the registry entries directly.
 */

import type { NotebookDocument } from '../domain/document';
import { proseDocument } from './documents/prose';
import { numericalsDocument } from './documents/numericals';
import { tableDocument } from './documents/table';
import { equationDocument } from './documents/equation';
import { mixedDocument } from './documents/mixed';

function stamp(doc: NotebookDocument): NotebookDocument {
  return { ...doc, createdAt: new Date().toISOString() };
}

export const FIXTURES: NotebookDocument[] = [
  stamp(proseDocument),
  stamp(numericalsDocument),
  stamp(tableDocument),
  stamp(equationDocument),
  stamp(mixedDocument),
];

export function getFixture(id: string): NotebookDocument | undefined {
  return FIXTURES.find(f => f.id === id);
}
