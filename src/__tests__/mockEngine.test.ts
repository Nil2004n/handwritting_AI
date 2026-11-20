/** Mock engine: determinism, error contract, copy independence. */
import { FIXTURES } from '../fixtures';
import { MockOcrEngine } from '../services/ocr/mockOcrEngine';

const engine = new MockOcrEngine();

test('returns deterministic output for the same fixture', async () => {
  const first = await engine.recognize({ type: 'fixture', fixtureId: 'fixture-prose' });
  const second = await engine.recognize({ type: 'fixture', fixtureId: 'fixture-prose' });
  expect(second).toEqual(first);
  expect(first.documentId).toBe('fixture-prose');
  expect(first.engineId).toBe(engine.engineId);
});

test('returns independent copies that cannot mutate fixtures or later results', async () => {
  const before = JSON.stringify(FIXTURES.find(f => f.id === 'fixture-table')!.blocks);
  const result = await engine.recognize({ type: 'fixture', fixtureId: 'fixture-table' });
  const table = result.blocks[0];
  if (table.kind === 'table') {
    table.rows[0].cells[0].editedText = 'MUTATED';
    table.rows[1].cells[1].rawText = 'MUTATED';
  }
  const again = await engine.recognize({ type: 'fixture', fixtureId: 'fixture-table' });
  expect(JSON.stringify(again.blocks)).toBe(before);
  expect(JSON.stringify(FIXTURES.find(f => f.id === 'fixture-table')!.blocks)).toBe(before);
});

test('rejects device images with UNSUPPORTED_SOURCE', async () => {
  await expect(
    engine.recognize({ type: 'device-image', imageUri: 'file:///photo.jpg' }),
  ).rejects.toMatchObject({ code: 'UNSUPPORTED_SOURCE' });
});

test('rejects unknown fixtures and malformed requests with INVALID_REQUEST', async () => {
  await expect(engine.recognize({ type: 'fixture', fixtureId: 'nope' })).rejects.toMatchObject({
    code: 'INVALID_REQUEST',
  });
  // @ts-expect-error intentional malformed request for contract testing
  await expect(engine.recognize({ type: 'camera' })).rejects.toMatchObject({
    code: 'INVALID_REQUEST',
  });
});
