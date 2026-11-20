/**
 * Mixed demo fixture (synthetic image): text + table + equation in explicit
 * reading order, showing region selection across block kinds.
 */

import type { NotebookDocument } from '../../domain/document';

const raw: string[][] = [
  ['Name', 'Score', 'Grade'],
  ['Asha', '8.5', 'B+'],
  ['Ravi', '', 'A'],
];

export const mixedDocument: NotebookDocument = {
  id: 'fixture-mixed',
  schemaVersion: 1,
  title: 'Mixed page sample (demo)',
  kind: 'mixed',
  image: { assetKey: 'mixed', width: 900, height: 1300 },
  origin: 'mock',
  engineId: 'mock-ocr-engine/0.1.0',
  blocks: [
    {
      id: 'mixed-t1',
      order: 0,
      kind: 'text',
      bounds: { x: 60, y: 80, width: 780, height: 240 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawText: 'Quiz results for group B.\nScores are out of 10 points.',
      editedText: 'Quiz results for group B.\nScores are out of 10 points.',
    },
    {
      id: 'mixed-b1',
      order: 1,
      kind: 'table',
      bounds: { x: 130, y: 400, width: 640, height: 400 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rows: raw.map((cells, r) => ({
        id: `mixed-b1-r${r}`,
        cells: cells.map((text, c) => ({
          id: `mixed-b1-c${r}-${c}`,
          rawText: text,
          editedText: text,
          bounds: {
            x: 150 + c * 200 + 8,
            y: 420 + r * 120 + 8,
            width: 184,
            height: 104,
          },
        })),
      })),
    },
    {
      id: 'mixed-e1',
      order: 2,
      kind: 'equation',
      bounds: { x: 60, y: 880, width: 780, height: 240 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawSeed: '\\bar{x} = \\frac{8.5 + 9.0}{2}',
      latex: '\\bar{x} = \\frac{8.5 + 9.0}{2}',
      latexProvenance: 'demo',
    },
  ],
};
