/**
 * OCR engine contracts for NotebookOCR (Phase 0).
 *
 * Independent of React Native and Kotlin. The single-method interface keeps
 * the JS<->native boundary small: the future Kotlin implementation will
 * accept the same request shape (with `type: 'device-image'`) over the
 * documented bridge (see docs/NATIVE_API.md).
 */

import type { Block } from './document';

export type OcrRequest =
  | {
      /** Resolve a bundled demo fixture. The only source Phase 0 supports. */
      type: 'fixture';
      fixtureId: string;
      /** Optional caller-selected source region, same pixel space as blocks. */
      region?: { x: number; y: number; width: number; height: number };
    }
  | {
      /**
       * Future Android path: an image URI handed to the Kotlin module.
       * Phase 0 mock rejects this with UNSUPPORTED_SOURCE.
       */
      type: 'device-image';
      imageUri: string;
      region?: { x: number; y: number; width: number; height: number };
    };

export interface OcrResult {
  /** Echoes the requested fixture ID. */
  documentId: string;
  /** Engine identity, e.g. 'mock-ocr-engine/0.1.0'. */
  engineId: string;
  /**
   * Deep, independent copy of the fixture blocks. Callers may mutate the
   * result freely; fixtures and later results are never affected.
   */
  blocks: Block[];
}

export type OcrErrorCode =
  | 'UNSUPPORTED_SOURCE'
  | 'INVALID_REQUEST'
  | 'RECOGNITION_FAILED';

export interface OcrError extends Error {
  code: OcrErrorCode;
}

export function createOcrError(code: OcrErrorCode, message: string): OcrError {
  const err = new Error(message) as OcrError;
  err.name = 'OcrError';
  err.code = code;
  return err;
}

export interface OcrEngine {
  readonly engineId: string;
  recognize(request: OcrRequest): Promise<OcrResult>;
}
