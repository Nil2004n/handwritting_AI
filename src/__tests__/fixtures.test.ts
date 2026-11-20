/** Fixture validity: ids, dimensions, bounds, order, table shape. */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FIXTURES } from '../fixtures';
import { validateDocument } from '../fixtures/validate';

function pngDimensions(assetKey: string): { width: number; height: number } {
  const buf = readFileSync(join(__dirname, '..', 'fixtures', 'images', `${assetKey}.png`));
  // PNG signature (8) + IHDR length (4) + 'IHDR' (4), then width/height BE32.
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

describe.each(FIXTURES.map(f => [f.id, f]))('fixture %s', (_id, fixture) => {
  test('passes structural validation', () => {
    expect(validateDocument(fixture)).toEqual([]);
  });

  test('metadata dimensions match the bundled PNG', () => {
    const actual = pngDimensions(fixture.image.assetKey);
    expect({ width: fixture.image.width, height: fixture.image.height }).toEqual(actual);
  });

  test('has no formula-model provenance claims', () => {
    for (const block of fixture.blocks) {
      expect(block.provenance).not.toBe('formula-model');
      if (block.kind === 'equation') {
        expect(block.latexProvenance).not.toBe('formula-model');
      }
    }
  });
});

test('registry covers prose, numericals, table, equation and mixed', () => {
  expect(FIXTURES.map(f => f.kind).sort()).toEqual(
    ['equation', 'mixed', 'numericals', 'prose', 'table'].sort(),
  );
});

test('table fixture preserves a blank cell', () => {
  const table = FIXTURES.find(f => f.id === 'fixture-table');
  expect(table).toBeDefined();
  const cells = table!.blocks.flatMap(b => (b.kind === 'table' ? b.rows.flatMap(r => r.cells) : []));
  expect(cells.length).toBe(12);
  const blanks = cells.filter(c => c.rawText === '' && c.editedText === '');
  expect(blanks.length).toBe(1);
});

test('mixed fixture orders text, table, equation in reading order', () => {
  const mixed = FIXTURES.find(f => f.id === 'fixture-mixed');
  expect(mixed!.blocks.map(b => b.kind)).toEqual(['text', 'table', 'equation']);
});

test('equation fixtures exercise frac, sup, sub and sqrt', () => {
  const latex = FIXTURES.flatMap(f =>
    f.blocks.flatMap(b => (b.kind === 'equation' ? [b.latex] : [])),
  ).join('\n');
  expect(latex).toMatch(/\\frac/);
  expect(latex).toMatch(/\^/);
  expect(latex).toMatch(/_/);
  expect(latex).toMatch(/\\sqrt/);
});
