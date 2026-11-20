/**
 * Numericals demo fixture (synthetic image, mock text/equation).
 *
 * Ordered calculation steps with decimals, a negative sign, units and an
 * exponent. Step 3 is flagged ambiguous ("7" vs "1", circled on the page).
 * Nothing is solved or evaluated: the app only transcribes.
 */

import type { NotebookDocument } from '../../domain/document';

export const numericalsDocument: NotebookDocument = {
  id: 'fixture-numericals',
  schemaVersion: 1,
  title: 'Numericals sample (demo)',
  kind: 'numericals',
  image: { assetKey: 'numericals', width: 900, height: 1200 },
  origin: 'mock',
  engineId: 'mock-ocr-engine/0.1.0',
  blocks: [
    {
      id: 'num-t1',
      order: 0,
      kind: 'text',
      bounds: { x: 60, y: 100, width: 780, height: 180 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawText: 'Problem 4: a cart of mass 2.50 kg\naccelerates at -3.2 m/s^2.',
      editedText: 'Problem 4: a cart of mass 2.50 kg\naccelerates at -3.2 m/s^2.',
    },
    {
      id: 'num-t2',
      order: 1,
      kind: 'text',
      bounds: { x: 60, y: 280, width: 780, height: 350 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawText: 'Step 1: F = m * a\nStep 2: F = 2.50 * (-3.2) = -8.0 N',
      editedText: 'Step 1: F = m * a\nStep 2: F = 2.50 * (-3.2) = -8.0 N',
    },
    {
      id: 'num-t3',
      order: 2,
      kind: 'text',
      bounds: { x: 60, y: 640, width: 780, height: 340 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: true,
      rawText: 'Step 3: W = F * d = -8.0 * 1.75 (?) = -14.0 J\nStep 4: check units: N * m = J',
      editedText: 'Step 3: W = F * d = -8.0 * 1.75 (?) = -14.0 J\nStep 4: check units: N * m = J',
    },
    {
      id: 'num-e1',
      order: 3,
      kind: 'equation',
      bounds: { x: 60, y: 1010, width: 780, height: 130 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawSeed: 'E = \\frac{1}{2} m v^2',
      latex: 'E = \\frac{1}{2} m v^2',
      latexProvenance: 'demo',
    },
  ],
};
