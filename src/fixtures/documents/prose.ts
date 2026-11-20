/**
 * Prose demo fixture (synthetic image, mock text).
 *
 * Contains an intentional transcription error ("teh") in block 1 for the
 * user to correct. All content is original demo text.
 */

import type { NotebookDocument } from '../../domain/document';

export const proseDocument: NotebookDocument = {
  id: 'fixture-prose',
  schemaVersion: 1,
  title: 'Prose sample (demo)',
  kind: 'prose',
  image: { assetKey: 'prose', width: 900, height: 1200 },
  origin: 'mock',
  engineId: 'mock-ocr-engine/0.1.0',
  blocks: [
    {
      id: 'prose-b1',
      order: 0,
      kind: 'text',
      bounds: { x: 60, y: 115, width: 780, height: 270 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: true,
      rawText: 'Teh quick brown fox jumps over\nteh lazy dog near the river.',
      editedText: 'Teh quick brown fox jumps over\nteh lazy dog near the river.',
    },
    {
      id: 'prose-b2',
      order: 1,
      kind: 'text',
      bounds: { x: 60, y: 395, width: 780, height: 270 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawText: 'Handwriting notes from the\nfield trip on Tuesday morning.',
      editedText: 'Handwriting notes from the\nfield trip on Tuesday morning.',
    },
    {
      id: 'prose-b3',
      order: 2,
      kind: 'text',
      bounds: { x: 60, y: 675, width: 780, height: 270 },
      status: 'needs-review',
      provenance: 'demo-mock',
      needsReview: false,
      rawText: 'Remember to copy the lab\nresults into the shared notebook.',
      editedText: 'Remember to copy the lab\nresults into the shared notebook.',
    },
  ],
};
