# Architecture (Phase 0)

## Project structure
```
App.tsx                          entry: StoreProvider + list/review switch
src/
  app/imageAssets.ts             app-layer map: assetKey -> require(png)
  domain/                        pure TS, no RN/Kotlin deps
    document.ts                  Document, blocks, cells, coordinates
    ocr.ts                       OcrEngine interface + error contract
  utils/coordinates.ts           contain-fit mapping, bounds checks
  fixtures/
    images/*.png                 synthetic bundled samples (generated)
    documents/*.ts               prose, numericals, table, equation, mixed
    index.ts                     registry (createdAt stamped at load)
    validate.ts                  structural validator + deepClone
  services/ocr/mockOcrEngine.ts  deterministic fixture-only engine
  state/DocumentStore.tsx        React context + useReducer session store
  components/                    DemoBanner, SourceImageView, BlockEditors,
                                 EquationPreview (local LaTeX subset)
  screens/                       DocumentListScreen, DocumentReviewScreen
  testUtils/queries.ts           jest query helpers (mock-aware)
scripts/generate-fixtures.mjs    PNG generator (node builtins, seeded)
```

## State ownership
- `DocumentStore` owns: current fixture, per-fixture sessions
  (blocks | null, selected block, extraction state, error, hasEdits).
- Fixture registry is read-only; the engine deep-copies on every call.
- Edits mutate only the session copy; `rawText`/`rawSeed` are never written.
- No persistence: navigating away and back keeps edits; restarting the app
  drops them (by design for Phase 0).

## Data flow
1. List screen: user opens fixture -> `OPEN_FIXTURE`.
2. Review screen (blocks null): "Extract demo" -> `EXTRACT_START` ->
   `mockOcrEngine.recognize({type:'fixture',...})` -> `EXTRACT_SUCCESS`
   (or `EXTRACT_ERROR`). Duplicate taps ignored (UI + reducer guards).
3. Block tap -> `SELECT_BLOCK`; overlay highlight follows `selectedBlockId`.
4. Editing -> `UPDATE_*` (sets `hasEdits`, marks block `edited`).
5. Reset is two-step armed confirm -> `RESET_DOCUMENT` (blocks -> null).
   Overwrite without reset is impossible: the extract button only exists
   when `blocks === null`.

## Source-coordinate convention
Top-left origin, source pixels, displayed-orientation image, pre-UI-scale.
The review image uses `resizeMode="contain"`; overlays go through
`computeContainMapping` + `mapBoundsToDisplay` so letterbox offsets are
honored. Future detector polygons stay in the optional `polygon` field.

## Why no real OCR runtime
Phase 0 validates contracts and UX with deterministic mocks before spending
integration effort. PaddleOCR needs native bundling, model assets and a
bridge — all Phase 1+ after upstream demo validation. The `OcrEngine`
interface is the seam the Kotlin module will implement.
