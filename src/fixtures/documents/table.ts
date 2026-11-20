/**
 * Table demo fixture (synthetic image, mock cells).
 *
 * 4 rows x 3 columns. Cell (row 3, column 2) is intentionally blank and
 * must stay blank unless the user types into it. No merged cells
 * (documented Phase 0 limitation).
 */

import type { NotebookDocument } from '../../domain/document';

const GX = 150;
const GY = 170;
const CW = 300;
const RH = 140;

function cellBounds(col: number, row: number) {
  return { x: GX + col * CW + 8, y: GY + row * RH + 8, width: CW - 16, height: RH - 16 };
}

const raw: string[][] = [
  ['Item', 'Qty', 'Price'],
  ['Apples', '4', '1.20'],
  ['Bread', '', '2.05'],
  ['Milk', '2', '3.40'],
];

export const tableDocument: NotebookDocument = {
  id: 'fixture-table',
  schemaVersion: 1,
  title: 'Table sample (demo)',
  kind: 'table',
  image: { assetKey: 'table', width: 1200, height: 900 },
  origin: 'mock',
  engineId: 'mock-ocr-engine/0.1.0',
  blocks: [
    {
      id: 'table-b1',
      order: 0,
      kind: 'table',
      bounds: { x: 130, y: 150, width: 940, height: 600 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rows: raw.map((cells, r) => ({
        id: `table-b1-r${r}`,
        cells: cells.map((text, c) => ({
          id: `table-b1-c${r}-${c}`,
          rawText: text,
          editedText: text,
          bounds: cellBounds(c, r),
        })),
      })),
    },
  ],
};
