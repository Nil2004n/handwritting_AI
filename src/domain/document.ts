/**
 * Core document contracts for NotebookOCR (Phase 0).
 *
 * These types are independent of React Native and Kotlin so they can be
 * shared across the JS UI and the future native OCR boundary.
 *
 * COORDINATE CONVENTION (applies to every SourceBounds in this file):
 * - Axis-aligned rectangle in source-image pixels: { x, y, width, height }.
 * - Origin (0, 0) is the TOP-LEFT corner of the image.
 * - Coordinates refer to the DISPLAYED-ORIENTATION image (i.e. after any
 *   EXIF rotation has been applied), before any UI scaling.
 * - The UI must map these through a "contain" fit (see
 *   src/utils/coordinates.ts), which letterboxes the image; overlay math
 *   must account for the letterbox offsets, never stretch the image.
 * - `polygon` is reserved for future native detector output (e.g. PaddleOCR
 *   DB polygons) and stays optional and separate from the axis-aligned box.
 */

export interface SourceBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Optional future detector polygon in the same pixel space as SourceBounds. */
export interface SourcePoint {
  x: number;
  y: number;
}

export type BlockKind = 'text' | 'table' | 'equation';

/**
 * Where a block's content came from. Phase 0 fixtures only ever use
 * 'demo-mock' (recognition output) and 'manual'/'demo' (equation seeds).
 * 'formula-model' is reserved for a future equation recognizer and must NOT
 * appear in Phase 0 fixtures.
 */
export type BlockProvenance =
  | 'demo-mock'
  | 'manual'
  | 'demo'
  | 'ocr-seeded'
  | 'formula-model';

export type ReviewStatus = 'needs-review' | 'edited' | 'confirmed';

interface BlockBase {
  /** Stable within the document; unique across all blocks of the document. */
  id: string;
  /** Zero-based reading order; must be dense (0..n-1) with no gaps. */
  order: number;
  kind: BlockKind;
  bounds: SourceBounds;
  /** Future native detector polygon; optional, same pixel space. */
  polygon?: SourcePoint[];
  status: ReviewStatus;
  provenance: BlockProvenance;
  /**
   * Optional recognizer confidence. Absence means "no score available" and
   * must stay absent: never manufacture a score, and never compare scores
   * across engines as though they were calibrated.
   */
  confidence?: number;
}

export interface TextBlock extends BlockBase {
  kind: 'text';
  /** Raw recognized text. Immutable: edits must never mutate this. */
  rawText: string;
  /** User-editable copy, seeded from rawText. Line breaks preserved. */
  editedText: string;
  /** Ambiguity flag for e.g. deliberate transcription issues. */
  needsReview: boolean;
}

export interface TableCell {
  /** Stable within the table; unique across all cells of the table. */
  id: string;
  /** Raw recognized cell text. Immutable: edits must never mutate this. */
  rawText: string;
  /** User-editable copy, seeded from rawText. Blank cells stay blank. */
  editedText: string;
  /** Optional per-cell source bounds, same pixel space. */
  bounds?: SourceBounds;
}

export interface TableRow {
  id: string;
  cells: TableCell[];
}

export interface TableBlock extends BlockBase {
  kind: 'table';
  rows: TableRow[];
  needsReview: boolean;
  /**
   * Phase 0 limitation: no merged cells. Every row must hold the same
   * number of cells; ragged tables are rejected by fixture validation.
   */
}

export type EquationProvenance = 'manual' | 'demo' | 'ocr-seeded' | 'formula-model';

export interface EquationBlock extends BlockBase {
  kind: 'equation';
  /**
   * Immutable raw seed text when the equation was seeded from recognition.
   * Null when authored manually. Edits must never mutate this.
   */
  rawSeed: string | null;
  /** User-editable LaTeX source. */
  latex: string;
  latexProvenance: EquationProvenance;
  needsReview: boolean;
}

export type Block = TextBlock | TableBlock | EquationBlock;

export type DocumentKind = 'prose' | 'numericals' | 'table' | 'equation' | 'mixed';

export interface SourceImageMeta {
  /**
   * Key resolved to a bundled asset by the app layer
   * (see src/app/imageAssets.ts). Fixtures never carry filesystem URIs or
   * remote URLs for bundled images.
   */
  assetKey: string;
  /** Actual pixel dimensions of the bundled image file. */
  width: number;
  height: number;
}

export interface NotebookDocument {
  /** Stable document/fixture ID. */
  id: string;
  /** Contract schema version; current: 1. */
  schemaVersion: 1;
  title: string;
  kind: DocumentKind;
  image: SourceImageMeta;
  /** Ordered blocks; `order` must match array position. */
  blocks: Block[];
  /** Phase 0: always 'mock'. Real on-device inference will use 'real'. */
  origin: 'mock' | 'real';
  /** Recognition engine identity, e.g. 'mock-ocr-engine/0.1.0'. */
  engineId: string;
  /** Optional ISO timestamps; generated outside rendering logic. */
  createdAt?: string;
}
