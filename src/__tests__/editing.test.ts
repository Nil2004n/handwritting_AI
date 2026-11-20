/** Editing semantics through the session reducer. */
import type { Block } from '../domain/document';
import { FIXTURES, getFixture } from '../fixtures';
import { deepClone } from '../fixtures/validate';
import { initialState, reducer, sessionWithBlocks } from '../state/DocumentStore';

function seedState(fixtureId: string) {
  const fixture = getFixture(fixtureId)!;
  const blocks = deepClone(fixture.blocks);
  let state = reducer(initialState, { type: 'OPEN_FIXTURE', fixtureId });
  state = {
    ...state,
    sessions: { ...state.sessions, [fixtureId]: sessionWithBlocks(fixtureId, blocks) },
  };
  return state;
}

function textBlock(blocks: Block[], id: string) {
  const b = blocks.find(x => x.id === id);
  if (!b || b.kind !== 'text') throw new Error(`text block ${id} missing`);
  return b;
}

test('text edits change editedText but never rawText', () => {
  const state = seedState('fixture-prose');
  const next = reducer(state, {
    type: 'UPDATE_TEXT',
    fixtureId: 'fixture-prose',
    blockId: 'prose-b1',
    text: 'The quick brown fox...',
  });
  const edited = textBlock(next.sessions['fixture-prose'].blocks!, 'prose-b1');
  expect(edited.editedText).toBe('The quick brown fox...');
  expect(edited.rawText).toBe('Teh quick brown fox jumps over\nteh lazy dog near the river.');
  expect(edited.status).toBe('edited');
  // Sibling block untouched.
  expect(textBlock(next.sessions['fixture-prose'].blocks!, 'prose-b2').editedText).toBe(
    textBlock(state.sessions['fixture-prose'].blocks!, 'prose-b2').editedText,
  );
});

test('table edits preserve unrelated cells and raw text', () => {
  const state = seedState('fixture-table');
  const next = reducer(state, {
    type: 'UPDATE_CELL',
    fixtureId: 'fixture-table',
    blockId: 'table-b1',
    cellId: 'table-b1-c1-0',
    text: 'Pears',
  });
  const table = next.sessions['fixture-table'].blocks![0];
  if (table.kind !== 'table') throw new Error('expected table');
  expect(table.rows[1].cells[0].editedText).toBe('Pears');
  expect(table.rows[1].cells[0].rawText).toBe('Apples');
  expect(table.rows[1].cells[1].editedText).toBe('4');
  expect(table.rows[2].cells[1].editedText).toBe('');
  // Fixture registry untouched.
  const fixtureCell = FIXTURES.find(f => f.id === 'fixture-table')!.blocks[0];
  if (fixtureCell.kind !== 'table') throw new Error('expected table');
  expect(fixtureCell.rows[1].cells[0].editedText).toBe('Apples');
});

test('equation edits preserve the raw seed', () => {
  const state = seedState('fixture-equation');
  const next = reducer(state, {
    type: 'UPDATE_LATEX',
    fixtureId: 'fixture-equation',
    blockId: 'eq-e1',
    latex: '\\frac{x}{2}',
  });
  const eq = next.sessions['fixture-equation'].blocks![0];
  if (eq.kind !== 'equation') throw new Error('expected equation');
  expect(eq.latex).toBe('\\frac{x}{2}');
  expect(eq.rawSeed).toBe('\\frac{x^2 + 3x + 2}{x + 1} = x + 2');
});

test('switching documents keeps each fixture state independent', () => {
  let state = seedState('fixture-prose');
  state = reducer(state, {
    type: 'UPDATE_TEXT',
    fixtureId: 'fixture-prose',
    blockId: 'prose-b1',
    text: 'edited prose',
  });
  // Open a second document and edit it.
  state = reducer(state, { type: 'OPEN_FIXTURE', fixtureId: 'fixture-table' });
  state = {
    ...state,
    sessions: {
      ...state.sessions,
      'fixture-table': sessionWithBlocks('fixture-table', deepClone(getFixture('fixture-table')!.blocks)),
    },
  };
  state = reducer(state, {
    type: 'UPDATE_CELL',
    fixtureId: 'fixture-table',
    blockId: 'table-b1',
    cellId: 'table-b1-c1-0',
    text: 'Pears',
  });
  // Return: prose edits intact, table edits intact, no cross-talk.
  expect(textBlock(state.sessions['fixture-prose'].blocks!, 'prose-b1').editedText).toBe('edited prose');
  const table = state.sessions['fixture-table'].blocks![0];
  if (table.kind !== 'table') throw new Error('expected table');
  expect(table.rows[1].cells[0].editedText).toBe('Pears');
  expect(state.sessions['fixture-prose'].selectedBlockId).toBe('prose-b1');
});

test('EXTRACT_START refuses to restart a running extraction', () => {
  let state = reducer(initialState, { type: 'OPEN_FIXTURE', fixtureId: 'fixture-prose' });
  state = reducer(state, { type: 'EXTRACT_START', fixtureId: 'fixture-prose' });
  const again = reducer(state, { type: 'EXTRACT_START', fixtureId: 'fixture-prose' });
  expect(again).toBe(state);
});

test('RESET_DOCUMENT returns to the pre-extraction state', () => {
  let state = seedState('fixture-prose');
  state = reducer(state, { type: 'RESET_DOCUMENT', fixtureId: 'fixture-prose' });
  const session = state.sessions['fixture-prose'];
  expect(session.blocks).toBeNull();
  expect(session.extraction).toBe('idle');
  expect(session.hasEdits).toBe(false);
});
