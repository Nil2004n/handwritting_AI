/**
 * Equation demo fixture (synthetic image, mock seeds).
 *
 * Covers fraction, superscript, subscript and square root. Seeds are
 * labeled 'demo' provenance: they were typed by hand for this fixture, NOT
 * produced by any equation recognizer.
 */

import type { NotebookDocument } from '../../domain/document';

export const equationDocument: NotebookDocument = {
  id: 'fixture-equation',
  schemaVersion: 1,
  title: 'Equation sample (demo)',
  kind: 'equation',
  image: { assetKey: 'equation', width: 900, height: 700 },
  origin: 'mock',
  engineId: 'mock-ocr-engine/0.1.0',
  blocks: [
    {
      id: 'eq-e1',
      order: 0,
      kind: 'equation',
      bounds: { x: 70, y: 90, width: 760, height: 210 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: true,
      rawSeed: '\\frac{x^2 + 3x + 2}{x + 1} = x + 2',
      latex: '\\frac{x^2 + 3x + 2}{x + 1} = x + 2',
      latexProvenance: 'demo',
    },
    {
      id: 'eq-e2',
      order: 1,
      kind: 'equation',
      bounds: { x: 70, y: 360, width: 760, height: 210 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawSeed: 'A = \\sqrt{b_1^2 + b_2^2}',
      latex: 'A = \\sqrt{b_1^2 + b_2^2}',
      latexProvenance: 'demo',
    },
  ],
};
