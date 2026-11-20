/**
 * Deterministic mock OCR engine (Phase 0).
 *
 * - Resolves `{ type: 'fixture', fixtureId }` requests against the bundled
 *   fixture registry after a short artificial delay (loading state).
 * - Rejects `{ type: 'device-image' }` with UNSUPPORTED_SOURCE: there is no
 *   real recognizer yet (see docs/NATIVE_API.md).
 * - Returns deep, independent copies so editing a result can never mutate
 *   the fixtures or any other result.
 * - Deterministic: the same fixture ID always yields identical output.
 */

import type { Block } from '../../domain/document';
import type { OcrEngine, OcrRequest, OcrResult } from '../../domain/ocr';
import { createOcrError } from '../../domain/ocr';
import { getFixture } from '../../fixtures';
import { deepClone } from '../../fixtures/validate';

export const MOCK_ENGINE_ID = 'mock-ocr-engine/0.1.0';

/** Artificial latency so the UI loading state is observable. */
export const MOCK_LATENCY_MS = 600;

export class MockOcrEngine implements OcrEngine {
  readonly engineId = MOCK_ENGINE_ID;

  async recognize(request: OcrRequest): Promise<OcrResult> {
    if (!request || typeof request !== 'object') {
      throw createOcrError('INVALID_REQUEST', 'Request must be an object.');
    }
    if (request.type === 'device-image') {
      throw createOcrError(
        'UNSUPPORTED_SOURCE',
        'Device images need the future native OCR module, which is not built yet. ' +
          'Phase 0 only extracts bundled demo fixtures.',
      );
    }
    if (request.type !== 'fixture') {
      throw createOcrError(
        'INVALID_REQUEST',
        `Unknown request type: ${(request as { type?: unknown }).type}.`,
      );
    }
    if (!request.fixtureId || typeof request.fixtureId !== 'string') {
      throw createOcrError('INVALID_REQUEST', 'fixtureId must be a non-empty string.');
    }
    const fixture = getFixture(request.fixtureId);
    if (!fixture) {
      throw createOcrError(
        'INVALID_REQUEST',
        `Unknown fixture id: ${request.fixtureId}.`,
      );
    }
    await new Promise(resolve => setTimeout(resolve, MOCK_LATENCY_MS));
    const blocks: Block[] = deepClone(fixture.blocks);
    return { documentId: fixture.id, engineId: this.engineId, blocks };
  }
}

export const mockOcrEngine = new MockOcrEngine();
