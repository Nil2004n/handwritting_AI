# NotebookOCR (Phase 0 — demo slice)

Offline Android handwriting-to-digital-text app. **Phase 0 contains NO real
OCR**: every result is deterministic mock data, always labeled
"Demo mode — OCR is not connected."

## What works
- Demo document list (prose, numericals, table, equation, mixed page).
- "Extract demo" through the shared OCR interface (simulated latency).
- Document review: source image + selectable region overlay, ordered blocks,
  raw output shown separately from editable results.
- Text / table-cell / LaTeX editing; edits persist per document for the app
  session. Reset requires explicit confirmation.
- Offline equation preview (small LaTeX subset, bundled renderer).

## Prerequisites
- Node >= 22.11, npm 12+ (repo pins a lockfile; use npm, not yarn).
- For the APK: JDK 17 and the Android SDK with `ANDROID_HOME` set
  (NOT present in this dev container — build is blocked here).

## Setup & run
```sh
npm install
npm start            # Metro bundler
npm run android      # needs Android SDK + device/emulator
npm test             # jest — 38 tests
npm run typecheck    # tsc --noEmit
npm run lint         # eslint
node scripts/generate-fixtures.mjs   # regenerate synthetic sample images
```

Note (npm 12): scaffolding used
`rnc-cli init --skip-install --skip-git-init` followed by plain
`npm install`, because the CLI's template download trips npm 12's
project-scoped `--allow-scripts` restriction. See `docs/KNOWN_ISSUES.md`.

## Docs
`docs/PLAN.md` · `ARCHITECTURE.md` · `NATIVE_API.md` (proposed, not built) ·
`MODEL_MANIFEST.md` (planned only) · `TEST_MATRIX.md` · `KNOWN_ISSUES.md` ·
`HANDOFF.md`.

## Boundaries
No camera/gallery, cloud, auth, database, export, signing, or real models.
PaddleOCR text output must never be presented as table reconstruction or
equation recognition — the UI labels all fixture equations manual/demo.
