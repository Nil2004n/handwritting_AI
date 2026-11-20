# Test matrix (Phase 0)

## Automated (all run, all pass — 38 tests, 6 suites)
- `npx tsc --noEmit` — clean.
- `npx eslint .` — clean (0 errors, 0 warnings).
- `npx jest` — 38/38 pass:
  - `src/__tests__/fixtures.test.ts` — validation, PNG-dim match, blank
    cell, reading order, LaTeX construct coverage, no formula-model claims.
  - `src/__tests__/coordinates.test.ts` — portrait-in-wide, landscape-in-tall,
    degenerate inputs, rect mapping, bounds checks.
  - `src/__tests__/mockEngine.test.ts` — determinism, copy independence
    (result mutation can't poison fixtures/later results), UNSUPPORTED_SOURCE
    for device images, INVALID_REQUEST for unknown/malformed requests.
  - `src/__tests__/editing.test.ts` — raw never mutated, unrelated cells
    untouched, rawSeed preserved, per-document independence, extraction
    re-entry guard, reset semantics.
  - `src/__tests__/equationPreview.test.tsx` — parser coverage + local-error
    rendering for unsupported/broken input.
  - `__tests__/App.test.tsx` — list -> open -> extract (loading, no
    duplicate) -> edit (raw preserved) -> armed reset -> pre-extract state.
    Uses `react-native-safe-area-context/jest/mock` via `jest.setup.js`.

## Manual flows (not yet run — no device/emulator available)
- Open each of the 5 samples, run Extract demo, select each block, verify
  the highlight tracks the source region on a real screen size.
- Edit text/table/equation per sample; background/foreground the app;
  confirm session edits survive navigation within the session.
- Equation preview: valid input renders; `\integrate{x}` shows the local
  error box without losing input.
- Import buttons remain visibly disabled.

## Device checks pending
- Android debug build + install (blocked: no SDK — see KNOWN_ISSUES).
- Physical-device verification: none performed, none claimed.

## Fixture coverage
prose (transcription error) / numericals (ambiguity flag, units, decimal,
negative, exponent) / table (4x3, blank cell, decimal) / equation
(frac, sup, sub, sqrt) / mixed (text+table+equation order).
