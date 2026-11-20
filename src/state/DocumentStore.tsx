/**
 * Session-scoped document state (Phase 0).
 *
 * - One session entry per opened fixture; entries are independent.
 * - Edits live here, keyed by document, and survive navigation between the
 *   sample list and the review screen for the lifetime of the app session.
 * - No persistence, no database: state is intentionally in-memory only.
 * - The reducer is UI-free and unit-tested (editing never mutates raw
 *   recognition output; documents never share references).
 */

import React, { createContext, useContext, useMemo, useReducer } from 'react';
import type { Block } from '../domain/document';
import type { OcrError } from '../domain/ocr';
import { mockOcrEngine } from '../services/ocr/mockOcrEngine';
import { getFixture } from '../fixtures';
import { deepClone } from '../fixtures/validate';

export type ExtractionState = 'idle' | 'running' | 'done' | 'error';

export interface DocSession {
  fixtureId: string;
  /** Null until the first successful "Extract demo". */
  blocks: Block[] | null;
  selectedBlockId: string | null;
  extraction: ExtractionState;
  error: string | null;
  /** True once the user edits anything after the latest extraction. */
  hasEdits: boolean;
}

export interface StoreState {
  currentFixtureId: string | null;
  sessions: Record<string, DocSession>;
}

type Action =
  | { type: 'OPEN_FIXTURE'; fixtureId: string }
  | { type: 'BACK_TO_LIST' }
  | { type: 'SELECT_BLOCK'; fixtureId: string; blockId: string }
  | { type: 'EXTRACT_START'; fixtureId: string }
  | { type: 'EXTRACT_SUCCESS'; fixtureId: string; blocks: Block[] }
  | { type: 'EXTRACT_ERROR'; fixtureId: string; message: string }
  | { type: 'RESET_DOCUMENT'; fixtureId: string }
  | { type: 'UPDATE_TEXT'; fixtureId: string; blockId: string; text: string }
  | { type: 'UPDATE_CELL'; fixtureId: string; blockId: string; cellId: string; text: string }
  | { type: 'UPDATE_LATEX'; fixtureId: string; blockId: string; latex: string };

function freshSession(fixtureId: string): DocSession {
  return {
    fixtureId,
    blocks: null,
    selectedBlockId: null,
    extraction: 'idle',
    error: null,
    hasEdits: false,
  };
}

function withSession(
  state: StoreState,
  fixtureId: string,
  update: (session: DocSession) => DocSession,
): StoreState {
  const existing = state.sessions[fixtureId] ?? freshSession(fixtureId);
  return {
    ...state,
    sessions: { ...state.sessions, [fixtureId]: update(existing) },
  };
}

function mapBlock(blocks: Block[], blockId: string, fn: (b: Block) => Block): Block[] {
  return blocks.map(b => (b.id === blockId ? fn(b) : b));
}

export function reducer(state: StoreState, action: Action): StoreState {
  switch (action.type) {
    case 'OPEN_FIXTURE':
      return {
        ...state,
        currentFixtureId: action.fixtureId,
        sessions: {
          ...state.sessions,
          [action.fixtureId]: state.sessions[action.fixtureId] ?? freshSession(action.fixtureId),
        },
      };
    case 'BACK_TO_LIST':
      return { ...state, currentFixtureId: null };
    case 'SELECT_BLOCK':
      return withSession(state, action.fixtureId, s => ({ ...s, selectedBlockId: action.blockId }));
    case 'EXTRACT_START':
      // Guard duplicated in the UI; the reducer also refuses to restart a run.
      if ((state.sessions[action.fixtureId]?.extraction ?? 'idle') === 'running') {
        return state;
      }
      return withSession(state, action.fixtureId, s => ({
        ...s,
        extraction: 'running',
        error: null,
      }));
    case 'EXTRACT_SUCCESS':
      return withSession(state, action.fixtureId, s => ({
        ...s,
        extraction: 'done',
        error: null,
        blocks: action.blocks,
        hasEdits: false,
        selectedBlockId: action.blocks[0]?.id ?? null,
      }));
    case 'EXTRACT_ERROR':
      return withSession(state, action.fixtureId, s => ({
        ...s,
        extraction: 'error',
        error: action.message,
      }));
    case 'RESET_DOCUMENT':
      return withSession(state, action.fixtureId, s => ({
        ...s,
        blocks: null,
        selectedBlockId: null,
        extraction: 'idle',
        error: null,
        hasEdits: false,
      }));
    case 'UPDATE_TEXT':
      return withSession(state, action.fixtureId, s => ({
        ...s,
        hasEdits: true,
        blocks: (s.blocks ?? []).map(b =>
          b.id === action.blockId && b.kind === 'text'
            ? { ...b, editedText: action.text, status: 'edited' as const }
            : b,
        ),
      }));
    case 'UPDATE_CELL':
      return withSession(state, action.fixtureId, s => ({
        ...s,
        hasEdits: true,
        blocks: mapBlock(s.blocks ?? [], action.blockId, b => {
          if (b.kind !== 'table') return b;
          return {
            ...b,
            status: 'edited' as const,
            rows: b.rows.map(r => ({
              ...r,
              cells: r.cells.map(c =>
                c.id === action.cellId ? { ...c, editedText: action.text } : c,
              ),
            })),
          };
        }),
      }));
    case 'UPDATE_LATEX':
      return withSession(state, action.fixtureId, s => ({
        ...s,
        hasEdits: true,
        blocks: mapBlock(s.blocks ?? [], action.blockId, b =>
          b.kind === 'equation'
            ? { ...b, latex: action.latex, status: 'edited' as const }
            : b,
        ),
      }));
    default:
      return state;
  }
}

export const initialState: StoreState = { currentFixtureId: null, sessions: {} };

interface StoreApi {
  state: StoreState;
  openFixture: (fixtureId: string) => void;
  backToList: () => void;
  selectBlock: (fixtureId: string, blockId: string) => void;
  extractDemo: (fixtureId: string) => void;
  resetDocument: (fixtureId: string) => void;
  updateText: (fixtureId: string, blockId: string, text: string) => void;
  updateCell: (fixtureId: string, blockId: string, cellId: string, text: string) => void;
  updateLatex: (fixtureId: string, blockId: string, latex: string) => void;
}

const StoreContext = createContext<StoreApi | null>(null);

/**
 * Runs the mock extraction flow. Duplicate taps while a request is in flight
 * are ignored; a document with unsaved edits is never overwritten without an
 * explicit reset first (enforced by the review screen, which hides the
 * extract button when blocks already exist).
 */
export function StoreProvider({ children }: { children: React.ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  const api = useMemo<StoreApi>(
    () => ({
      state,
      openFixture: fixtureId => dispatch({ type: 'OPEN_FIXTURE', fixtureId }),
      backToList: () => dispatch({ type: 'BACK_TO_LIST' }),
      selectBlock: (fixtureId, blockId) =>
        dispatch({ type: 'SELECT_BLOCK', fixtureId, blockId }),
      extractDemo: fixtureId => {
        if (state.sessions[fixtureId]?.extraction === 'running') return;
        dispatch({ type: 'EXTRACT_START', fixtureId });
        mockOcrEngine
          .recognize({ type: 'fixture', fixtureId })
          .then(result => {
            dispatch({ type: 'EXTRACT_SUCCESS', fixtureId, blocks: result.blocks });
          })
          .catch((err: unknown) => {
            const message =
              err instanceof Error ? `${(err as OcrError).code ?? 'ERROR'}: ${err.message}` : 'Unknown error';
            dispatch({ type: 'EXTRACT_ERROR', fixtureId, message });
          });
      },
      resetDocument: fixtureId => dispatch({ type: 'RESET_DOCUMENT', fixtureId }),
      updateText: (fixtureId, blockId, text) =>
        dispatch({ type: 'UPDATE_TEXT', fixtureId, blockId, text }),
      updateCell: (fixtureId, blockId, cellId, text) =>
        dispatch({ type: 'UPDATE_CELL', fixtureId, blockId, cellId, text }),
      updateLatex: (fixtureId, blockId, latex) =>
        dispatch({ type: 'UPDATE_LATEX', fixtureId, blockId, latex }),
    }),
    [state],
  );

  return <StoreContext.Provider value={api}>{children}</StoreContext.Provider>;
}

export function useStore(): StoreApi {
  const api = useContext(StoreContext);
  if (!api) throw new Error('useStore must be used inside <StoreProvider>');
  return api;
}

export function getFixtureTitle(fixtureId: string): string {
  return getFixture(fixtureId)?.title ?? fixtureId;
}

/** Test helper: seed a session as if extraction just succeeded. */
export function sessionWithBlocks(fixtureId: string, blocks: Block[]): DocSession {
  return {
    fixtureId,
    blocks: deepClone(blocks),
    selectedBlockId: blocks[0]?.id ?? null,
    extraction: 'done',
    error: null,
    hasEdits: false,
  };
}
