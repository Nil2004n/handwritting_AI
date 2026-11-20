# Handoff (Phase 0 complete, with one environmental blocker)

## Completed
- React Native 0.88.1 + TypeScript scaffold (Kotlin Android kept as
  generated), single npm lockfile, provisional name NotebookOCR.
- Strongly typed domain + OCR interface; contain-fit coordinate helpers.
- 5 synthetic fixtures (generated PNGs + typed docs) covering prose,
  numericals, table, equation, mixed; all visibly demo-labeled.
- Deterministic `MockOcrEngine` (fixture-only; device images rejected).
- Session store: per-document edits, guarded extraction, armed reset.
- Review UI: demo list, extract flow, region overlay selection, raw-vs-edit
  editors, offline LaTeX-subset preview with local errors.
- Docs: PLAN, ARCHITECTURE, NATIVE_API (not-built boundary), MODEL_MANIFEST
  (no invented hashes), TEST_MATRIX, KNOWN_ISSUES, this HANDOFF, README.

## Files changed
All new except template scaffold (moved in) + `tsconfig.json` (node types),
`jest.config.js` (+setupFiles), `jest.setup.js` (new), `package.json`
(+typecheck, +@types/node). No unrelated files touched (repo was empty
apart from LICENSE/README).

## Commands actually run
- `npx @react-native-community/cli@latest init NotebookOCR --skip-install --skip-git-init` (in /tmp/opencode; npm 12 workaround)
- `npm install` → 859 packages, 0 vulns; `npm install --save-dev @types/node`
- `node scripts/generate-fixtures.mjs` → 5 PNGs, dims verified from IHDR
- `npx tsc --noEmit` → clean
- `npx eslint .` → 0 errors, 0 warnings
- `npx jest` → 6 suites, 38 tests, all pass
- `cd android && ./gradlew assembleDebug` → FAILED (SDK location not found)

## Tests passed / not run
- Passed: typecheck, lint, all 38 unit/UI tests (see TEST_MATRIX).
- Not run: Android build (no SDK), emulator/device manual flows, physical
  verification. Nothing of this kind is claimed.

## Known failures
- Gradle build: "SDK location not found" (no ANDROID_HOME/platforms).
- JDK risk: env Java is 25; RN 0.88 build will likely need JDK 17.

## Next exact task (Phase 1)
Validate the official PaddleOCR Android demo (upstream repo) on a machine
with the Android SDK + JDK 17: build it, run PP-OCRv6_small on handwriting
samples, record accuracy/latency findings. Do NOT write the bridge or bundle
models until that validation fixes the integration approach. Suggested first
command on a provisioned machine: `cd android && ./gradlew assembleDebug`.

## Commit
Phase 0 commit on branch `main` (see `git log`). Nothing pushed.
