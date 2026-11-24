# Mobile developer handoff checklist

Scope frozen: no new features, **automatic spelling replacement is NOT implemented**, no new backend. Statuses: **Android = build-verified, runtime-PENDING** (nothing has run on an Android device
or emulator). iOS is a separate integration path (reuse the pipeline specification and models; the Java engine is not an iOS target).

## 1. Read these first

| Document | What it gives you |
|---|---|
| `docs/ENGINE.md` | Engine API and lifecycle contract, `PixelImage` contract (true BGR, EXIF applied, alpha over black), model loading, **OCR pipeline specification** (tensors, normalization, resize, DB postprocess, crops, CTC decoding, reading order), Android toolchain actually used, adapter responsibilities |
| `docs/SPELLING.md` | Spelling module contract (`SpellBackend`), token rules, ranges (UTF-16), SCOWL word lists and licence, personal-dictionary format, concurrency/staleness rules, what is deferred |
| `HANDOFF.md` ("Engine portability", "Offline English spelling assistance") | Executed commands, counts, source identities, pending checks |
| `android-proof/` | Minimal harness: `AndroidImageAdapter` (Bitmap → `PixelImage`, EXIF), `ModelInstaller` (assets → app storage, checksum verified), `ProofRunner` (structured checks). Not a product UI |

## 2. Example commands (Linux host; none needs a device except the last)

```sh
./gradlew test                                              # engine + spelling + desktop unit tests
./gradlew :engine:build :spelling:build                     # module tests, dependency check, compiled-class boundary scan
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew -p android-proof :app:verifyEngineAndroidApi :app:verifyProofDependencies :app:assembleDebug
./gradlew boundaryFingerprint -Pimages=src/test/resources/fixtures/print-900x500.png,samples/handwriting-test.jpg > after.txt
diff src/test/resources/fixtures/boundary-baseline-public.txt after.txt      # same platform/ORT version: identical
sh tools/verify-spelling-dictionaries.sh                    # word lists vs SHA256SUMS
sh tools/android-proof-run.sh                               # NEEDS a device/running emulator: runtime acceptance (PENDING)
```

## 3. Public fixtures and expected outputs

* `src/test/resources/fixtures/print-900x500.png` (SHA-256 `a38865f710864022deb17a09e22a6bd0dbf59b3f738979f9b2f7fc993d67dbe0`, a *regenerated* fixture, see ENGINE.md) must read exactly
  `Hello world 123` / `Pack my box 456` / `Quick brown fox -78.5` / `Price: $12.50 total` (file `print-900x500.expected.txt`). Desktop: exact.
* `samples/handwriting-test.jpg` (EXIF-rotated, decodes to 1800×4000), ground truth in `eval/cases.tsv`. Desktop raw OCR today: `This is how it's done / LIFE ITSELF IS COMPLLCATED / Sty Nove to leiy`, CER 0.179 (12/67), WER 0.385 (5/13).
  This is the *measured* output, not a correct transcript; use it to compare platforms, not as a target.
* `src/test/resources/fixtures/boundary-baseline-public.txt`: canonical per-boundary hashes (decode, detector tensor, geometry, crops, raw text) on linux-x64 / ONNX Runtime 1.22.0 CPU.
  Pure-Java stages must match bit for bit everywhere; ONNX-dependent stages (detector map, logits) may differ in the last bits across backends: compare with a tolerance and report **transcript** and **geometry** differences separately.
* Spelling (desktop, deterministic): a sentence of common words has no issues in en-US/en-GB/en-CA; `colour` is flagged in en-US and `color` in en-GB; `recieve`, `wich`, `teh` are flagged and their suggestions contain `receive`, `which`, `the`; `I come form London` produces no issue.

## 4. Android device tests STILL REQUIRED (none has been run)

**What the proof harness asserts today** (`android-proof/.../ProofRunner.java`, run by `tools/android-proof-run.sh`): the engine opens from verified app-private models; the committed print fixture decodes to 900x500 and its recognized text
equals the committed expected text; five repeated recognitions are stable and native-heap growth stays under 64 MB; EXIF tags 2-8 through the Android adapter equal the permutation of the unrotated decode; `close()` makes later calls fail with
`OcrException` and the engine can be re-opened. **It asserts nothing else**: no spelling, no desktop comparison, no real photos, no API 24/25 or memory-limit behavior. None of this has run on a device.

1. `sh tools/android-proof-run.sh` on an **arm64-v8a** device (API ≥ 24): expect `"allPassed":true`; recognized text equals the print fixture's expected text; engine opens from app-private models; repeated runs stable; native heap growth under 64 MB; EXIF tags 2–8 via the Android adapter; `close()` then re-open.
2. Same on one **API 24/25** device (ONNX Runtime's own classes reference `java.nio.file` in desktop/training paths; untested there) and one current API.
3. **Desktop vs Android comparison** on both public fixtures with the fingerprint rules above (transcripts and geometry first; tensors with tolerance). This is a **manual procedure, not a proof-harness assertion**: the proof app compares only against the committed expected text and does **not** emit boundary fingerprints, so the Android-side values must be produced by new test code or logging (canonical serialization: `docs/ENGINE.md` section 4), then compared by hand against `src/test/resources/fixtures/boundary-baseline-public.txt` and the desktop output.
4. Real phone **photos**: EXIF orientations 1–8 with a rotated handwriting photo; confirm no pre-shrinking (`inSampleSize`) is used; **gray-image decode parity** (desktop replicates gray into B=G=R; not verified on Android).
5. **12 MP photo**: peak memory (about 130 MB transient estimated, unmeasured) and latency; a bounded low-memory run; run on a background thread; engine `close()` after in-flight work on pause/destroy.
6. **Spelling on device: PENDING, and not yet executable.** The current Android proof harness does **not** test spelling (no spelling code exists in `android-proof/app/src`). A small spelling test/harness must be **added first**; only then can device checks run: `WordListBackend.bundled()` resource loading from the APK, checksum verification, results on the sentences listed in section 3, first-check time, plus an Android-side personal-dictionary store. The proof APK does include the spelling module (it is dexed with the engine), but **building it proves only that it compiles and packages for Android, not its runtime behavior there.**
7. Records/lambdas desugared by D8 behave at runtime (they compile; not run).

## 5. Known limitations

* **Accuracy:** good on clean print; limited on cursive/tilted handwriting (numbers above). A dictionary cannot catch valid-word OCR errors ("form"/"from"); spelling suggestions are a weak, deterministic ranking, not a confidence. Nothing here changes the models or decoding.
* **Licensing (release blockers unchanged):** `xmpcore-license` unresolved (desktop metadata-extractor dependency only; not in the engine or the APK); **`ppocrv6-model-redistribution` partial**: the models are bundled in any APK, so it applies to a mobile release. The SCOWL notice (`licenses/scowl/SCOWL-COPYRIGHT.txt`, resource in the spelling jar) must ship with the word lists. No legal review was performed.
* **Not done:** signing, publisher/maintainer details, GA runtime validation, lintian, any Android runtime evidence, and any Windows check beyond the smoke test (GUI, real OCR, upgrade, uninstall, non-admin install).

## 6. Installer provenance flag (known issue, NOT fixed here)

Installer reports from the CI smoke workflow (`release-output/ci-smoke-*/**/smoke/report.txt`, and `native-package.json` inside the packages) contain the reason **"source tree had uncommitted changes"**. The cause: the `installer-smoke.yml` jobs write
scratch files (`jdk.txt`, `smoke/`) **into the checkout before packaging**, so the build's cleanliness check sees an untracked file. The source commit itself was verified separately (the run's head SHA and the `commit:` line of each report equal the pushed commit), but that does not remove the
inaccurate dirty-tree classification: treat the flag as a **false positive of the workflow, recorded here, not a statement that the tree had edits**. This documentation change records the issue only; it does not change the workflow and no existing artifact was rebuilt.
Future remediation: move the scratch files outside the checkout (for example under `$RUNNER_TEMP`), check that the tree is clean **before** packaging, and verify the generated metadata (`dirty: false` / no "uncommitted changes" reason) in the report.

The installer smoke evidence retained (on the build machine, in git-ignored `release-output/`; it is not in the repository, and the GitHub workflow artifacts expire after 7 days) in `release-output/ci-smoke-bf03ea2/` (first run, Windows failed on CRLF-converted word lists) and `release-output/ci-smoke-80f5f2e/` (second run, both jobs passed: build, silent install, installed `--self-check` only) belongs to source commits **`bf03ea2` and `80f5f2e`**, not to any later docs-only commit.
