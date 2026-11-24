# notebook-ai OCR engine: architecture, pipeline specification, Android integration

Status of this document: derived from the code at the commit named in `HANDOFF.md` ("Engine portability"). Everything marked
**VERIFIED** was executed and inspected; everything marked **PENDING** was not. Nothing here says the engine is "Android-ready":
Android build integration is verified, Android *runtime* acceptance is pending (see section 5).

---------------------------------------------------------------------------------------------------------------------------------

## 1. Architecture

```
engine/            portable OCR engine: pure Java 17, no AWT / JavaFX / javax.imageio / java.nio.file / System.exit
                   and NO runtime dependencies (the ONNX Runtime *Java API* is compileOnly)
<repo root>        the DESKTOP application (it is also the Gradle root project): JavaFX UI, projects, evaluation tools,
                   desktop image adapter, installers, distributions
android-proof/     separate Gradle build (not part of the desktop build, needs an Android SDK): proof harness, NOT a product UI
```

Package names: the engine and the desktop application **both** use the package `notebookai.ocr` (a split package across two jars,
on the classpath). That is deliberate: desktop diagnostics use package-private engine members. It is not JPMS-safe; if the engine is
ever put on a module path, the desktop adapter classes must move to their own package first.

### Engine public API (everything else in `engine/` is internal)

| Class | Role |
|---|---|
| `PixelImage` | the only image type the engine accepts (contract in section 1.2) |
| `ModelAssets` | where the models come from: a directory (`File`) or in-memory bytes; verification, fingerprint |
| `OcrEngine` | `open(ModelAssets)`, `recognize(PixelImage)`, `recognizeLine(PixelImage)`, `close()` |
| `OcrResult` | immutable result: regions (polygon, box, text, scores), combined text, image size, timings |
| `OcrException` | the one checked exception for recognition/model failures |
| `ImageOps` | pure-Java geometry primitives (resize, perspective warp, rotate) that adapters/tools may reuse |

Desktop adapter classes (NOT in the engine): `DesktopImageIO` (ImageIO + metadata-extractor decode, EXIF read, `BufferedImage`
conversion), `OcrService` (desktop facade: models directory resolution, file decoding, debug output), `DebugTrace`/`DebugImages`
(AWT debug writers), plus tools (`BoundaryFingerprint`, `CrossCheckDump`, `CtcProbe`, `RecognizeCli`, `OcrSetup`).

### 1.1 Dependency selection (why a consumer never gets both native runtimes)

`engine/build.gradle` declares `compileOnly 'com.microsoft.onnxruntime:onnxruntime:1.22.0'`: the engine compiles against the ONNX Runtime
**Java** API but publishes **no** runtime dependency. Each host supplies exactly one runtime with the identical `ai.onnxruntime` API:

* desktop JVM: `com.microsoft.onnxruntime:onnxruntime:1.22.0` (JNI natives for Linux/Windows/macOS in one jar; the distribution generator trims it to one platform)
* Android: `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` (AAR with per-ABI natives)

**VERIFIED**: `./gradlew :engine:verifyEngineDependencies` fails unless the engine's resolved `runtimeClasspath` is empty and its
`compileClasspath` is exactly the ONNX Runtime Java API; `./gradlew -p android-proof :app:verifyProofDependencies` fails if the Android
debug runtime classpath contains the desktop ONNX Runtime, JavaFX, metadata-extractor or xmpcore, or lacks `onnxruntime-android`.
`EngineBoundaryTest` reads the *compiled class files* of the engine (constant pools, so fully-qualified names, descriptors and generics
are all covered) and fails on any reference to `java/awt`, `javax/imageio`, `javax/swing`, `javafx`, `com/drew`, `java/nio/file`,
the desktop packages, or a call to `System.exit`/`Runtime.exit`/`Runtime.halt`. The same test proves the scanner works by running it on
a real JDK AWT class.

Keep the ONNX Runtime **version identical** on both sides (desktop 1.22.0 / Android 1.22.0); the Java API surface the engine uses was
checked in both artifacts (`OrtEnvironment.createSession(String|byte[], SessionOptions)`, `OnnxTensor.createTensor(env, FloatBuffer, long[])`,
`OrtSession.run`, `OrtSession.Result`).

### 1.2 `PixelImage` contract

* 8 bits per channel, 3 channels, order **B, G, R** (like OpenCV `imread`). The detector's ImageNet mean/std are applied per channel *in
  storage order*; a swapped order silently degrades recognition (an earlier bug of exactly this kind is documented in `HANDOFF.md`).
* Packed row-major, pixel (x, y) at byte `(y*width + x)*3`, stride exactly `width*3`, array length exactly `width*height*3`.
* Origin top-left; x right, y down. All result coordinates refer to the image **after** orientation.
* No alpha: adapters composite translucent pixels over **black** (`PixelImage.fromArgbPacked` does this with `round(a*c/255)`, identical to Java2D).
* Display-oriented: the EXIF orientation must already be applied. Reading the tag is the adapter's job; `withExifOrientation(tag)` applies it
  (pure permutation, tags 1-8, anything else = unchanged). Upstream PaddleOCR ignores EXIF; this project applies it deliberately.
* Immutable by contract. `wrapBgr` takes ownership **without copying** (a 12 MP photo is 36 MB); `copyOfBgr` copies; `bgr()` returns the internal
  array (read-only by contract); `toBgrCopy()` copies. Invalid dimensions/lengths/overflow throw `IllegalArgumentException`.
* The engine does not resample the image before the pipeline: **do not** pre-shrink with `inSampleSize` or similar; it changes recognition.

### 1.3 `ModelAssets` / model loading

Five named files: `det.onnx`, `det.yml`, `rec.onnx`, `rec.yml` (contains `character_dict`), `checksums.sha256` (`sha256sum` format).

* `ModelAssets.fromDirectory(File)`: ONNX Runtime reads the model from the path; nothing large is on the Java heap. **Recommended on Android**
  (copy assets to `Context.getFilesDir()` once; see `android-proof/.../ModelInstaller.java`).
* `ModelAssets.fromBytes(Map<String,byte[]>)`: arrays are handed to ONNX Runtime at session creation. The set keeps the references until
  the engine is closed (it does not rely on ONNX Runtime copying them); do not modify them while the engine is open. About 31 MB on the heap.
* `OcrEngine.open` calls `verify()`: all five files present, every file named in the manifest matches its SHA-256 (streamed, constant memory).
  Failure → `OcrException` naming each problem; no native resource is created. Resolution of *where* models live (the desktop's
  `NOTEBOOK_AI_MODELS` > `-Dnotebookai.models` > `<cwd>/models` precedence) is **not** in the engine; it is `ModelConfig` in the desktop app and unchanged.

### 1.4 Usage

```java
ModelAssets models = ModelAssets.fromDirectory(new File(context.getFilesDir(), "models"));   // or any directory
try (OcrEngine engine = OcrEngine.open(models)) {            // slow: loads ~31 MB; create once and reuse
    PixelImage page = /* your platform adapter: decode, composite over black, to BGR, apply EXIF */;
    OcrResult r = engine.recognize(page);                    // seconds: call from a background thread
    for (OcrResult.TextRegion region : r.regions()) {
        region.text(); region.polygon(); region.box(); region.detScore(); region.recScore();
    }
    String text = r.combinedText();                          // regions in reading order, '\n' between lines
}
```

### 1.5 Lifecycle, threading, errors (contract of `OcrEngine`)

* **Reuse**: one engine for many images. Sessions are created once.
* **Close**: the engine owns two native ONNX Runtime sessions; `close()` releases them (idempotent). `ModelAssets`/`PixelImage`/`OcrResult` own nothing native.
* **Threads**: each call runs on the calling thread and holds a shared lock; `close()` takes the exclusive lock, so it **waits for running calls**
  and calls after it throw `OcrException`. **VERIFIED** (`OcrEngineTest.closeWaitsForARunningCallAndLaterCallsFailCleanly`). Overlapping calls from several
  threads are not prohibited but **not verified** and multiply peak memory: serialize them.
* **Cancellation**: none. A started call runs to completion (seconds for a photo). Do not promise interruption in a host UI; discard stale results instead.
* **Errors**: `OcrException` (checked) for model/inference/decoding failures; **no partial result** is ever returned. `IllegalArgumentException`/`NullPointerException`
  for caller bugs. The engine never exits the process and never touches the file system after `open`.
* **Results**: immutable; share no arrays with the engine or the input image.
* **Host pause/shutdown**: let in-flight calls finish, then `close()`. Do not call `close()` from a callback that must return quickly while a call may be running (it waits).

---------------------------------------------------------------------------------------------------------------------------------

## 2. OCR pipeline specification

Source of truth: `engine/src/main/java/notebookai/ocr/` (`DetPreprocess`, `ImageOps`, `DbPostprocess`, `CropOps`, `RecPreprocess`,
`CtcDecoder`, `ReadingOrder`, `OcrEngine`). Upstream reference: PaddleOCR 3.7.0 rules; deviations are listed.

### 2.1 Models (identity and integrity)

PP-OCRv6_small (detector 2.48 M params, recognizer 5.2 M params, ONNX, 50 languages, Apache-2.0 per upstream metadata); `MODEL_IDENTITY` =
`PP-OCRv6_small (det 2.48M params + rec 5.2M params, ONNX, ONNX Runtime CPU)`. Pinned Hugging Face commits: detector `28fe5895…`, recognizer
`b8f84f0b80c529de40b4fbb3544b84fa7233a513` (full SHAs in `tools/download-models.sh`). SHA-256 (from `models/checksums.sha256`):

```
d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e  det.onnx   (9,880,512 bytes)
193f435274bf9f0b5f71a929bbfbcf148282df7e633b34e7c373e8f44741b516  det.yml
5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634  rec.onnx   (21,159,378 bytes)
ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1  rec.yml
```

### 2.2 Tensors (read from the sessions by `./gradlew verifyOcrSetup`)

| Model | Input | Output |
|---|---|---|
| detector | name `x`, `float32 [N,3,H,W]` (dynamic), B,G,R channel order | name `fetch_name_0`, `float32 [N,1,H,W]` probability map, same H,W as the input |
| recognizer | name `x`, `float32 [N,3,48,W]` (W dynamic), B,G,R | name `fetch_name_0`, `float32 [N,T,18710]` (used directly as confidences; they behave as probabilities: clean text scores 0.99+) |

The engine always uses N = 1 (no batching of crops).

### 2.3 Detector preprocessing (`DetPreprocess`)

1. `ratio = 1.0`; if `min(W,H) < 736` then `ratio = 736 / min(W,H)` (upscale only; never downscale for being large).
2. `resizeH = (int)(H*ratio)`, `resizeW = (int)(W*ratio)`; if `max(resizeH,resizeW) > 4000`, scale both by `4000/max` (truncating casts).
3. Each side is rounded to the nearest multiple of 32 with **half-to-even** (`Math.rint`), minimum 32.
4. Resize with **bilinear** interpolation (`ImageOps.resizeBilinear`): source coordinate `src = (dst + 0.5) * scale - 0.5` (OpenCV `INTER_LINEAR`), border
   **replication**, per channel `round-half-up` (`(int)Math.round`) clamped to 0..255.
5. Normalize per channel **in storage order B,G,R**: `(v/255 − mean[c]) / std[c]` with `mean = {0.485, 0.456, 0.406}`, `std = {0.229, 0.224, 0.225}` (double arithmetic, cast to `float32`).
6. Layout CHW, `[1,3,resizeH,resizeW]`, row-major.

### 2.4 Detector postprocessing (`DbPostprocess`, parameters from `det.yml`)

`thresh = 0.2`, `box_thresh = 0.45`, `unclip_ratio = 1.4`, `max_candidates = 3000`, **score mode "slow"** (the project's choice; mean of the probability map over the contour
region, not over the bounding rectangle: upstream's "fast" mode drops long tilted lines).

1. Binarize the map: `prob > 0.2` (strict).
2. Trace the bitmap's contours (8-neighbour border following, `traceContours`); take at most the first 3000.
3. Minimum-area rectangle of each contour; reject if its shorter side `< 3` (map pixels).
4. Score with the slow mode; reject if `score < 0.45`.
5. `unclip` by 1.4 (polygon offset), take the minimum-area rectangle again; reject if shorter side `< 5`.
6. Scale to **source** pixel coordinates (`x * destW / mapW`, same for y), clip to `[0,W] × [0,H]`. Corner order **TL, TR, BR, BL**.
7. Project-specific, not upstream: `trimPaddingIntoNeighborCores`. The unclip padding of a box can reach over the last letter of a collinear neighbour that the detector split off the same line
   (a glyph would then be recognized twice). Each box's padding **along the text axis only** is cut back where it would enter the pre-unclip core quad of another region that is collinear
   (long axes within 15°) and shares the line band (perpendicular overlap > 0.25 of the smaller extent). Cores and non-colliding sides keep upstream geometry. Applied in production and
   recorded in the boundary baseline.

### 2.5 Crops (`CropOps`)

`cropW = (int)max(|P0P1|, |P2P3|)`, `cropH = (int)max(|P0P3|, |P1P2|)`. Perspective-warp the quad to a `cropW × cropH` rectangle (solve the 4-point
system in double precision; **bicubic**, Keys kernel a = −0.75, border replication, `round-half-up` clamp). If `cropH / cropW >= 1.5`, rotate the crop 90° **counter-clockwise** (`np.rot90`),
swapping width and height.

### 2.6 Recognizer preprocessing (`RecPreprocess`)

Height 48. `ratio = w/h`; `maxWhRatio = max(320/48, ratio)`; `imgW = (int)(48 * maxWhRatio)`; `resizedW = min(ceil(48*ratio), imgW)`. Bilinear resize to `resizedW × 48`; value
`(v/255 − 0.5) / 0.5` (BGR kept as is); **right-pad with 0.0** to width `imgW`. One crop per run (padding is to the crop's own width).

### 2.7 Decoding (`CtcDecoder`, `ModelAssets`)

Greedy CTC: per time step take the arg-max class; emit if it differs from the previous step's class **and** is not class 0 (blank); the character string is the
concatenation of table entries. Table = `["blank"] + character_dict + [" "]` from `rec.yml` (18,708 dictionary entries → 18,710 classes: 0 blank, 1…18,708 dictionary, 18,709 space).
A recognition score is the mean of the winning output values over the emitted positions (0.0 for an empty decode). Regions with `recScore < 0.5` (`DROP_SCORE`) are dropped.
(Beam search and crop-padding switches exist only as opt-in experiments via JVM properties `notebookai.exp.*`; defaults are off; the active set appears in `persistableIdentity()`.)

### 2.8 Reading order and result geometry

Kept regions are grouped into display lines by `ReadingOrder` (tilt-aware: project onto the dominant text direction, cluster rows, split a row where the gap along the text exceeds
`0.6 ×` the taller neighbour's padded thickness). Within a line regions are joined with a space; lines with `\n`. `OcrResult.TextRegion`: `polygon[4][2]` doubles (source pixels, TL,TR,BR,BL),
`box[4]` = rounded axis-aligned `[x0,y0,x1,y1]`, `text`, `detScore` (detector), `recScore` (recognizer). Raw text is never edited.
A spell-check/text-assistance layer, where used, is separate and never part of the engine.

---------------------------------------------------------------------------------------------------------------------------------

## 3. Android integration

### 3.1 Toolchain actually used (build verification, section 5)

| Item | Value | Evidence |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | `android-proof/build.gradle` |
| Gradle | 8.14.3 (the repository wrapper) | `./gradlew -version` |
| JDK running Gradle / compiling | 21 (daemon), engine compiled with `--release 17` | `engine/build.gradle` |
| compileSdk / targetSdk / minSdk | `android-37.0` (installed platform) / 36 / **24** | `aapt2 dump badging`: minSdkVersion 24, targetSdkVersion 36 |
| Java language / bytecode | 17 | `compileOptions` |
| Core library desugaring | **not enabled / not needed** | `tools/check-android-api.py`: 246 JDK member references checked against the platform `api-versions.xml`, none above API 24 |
| ONNX Runtime | `onnxruntime-android:1.22.0` (28,515,295 B, SHA-256 `04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2` equals the value published on Maven Central) | manifest `minSdkVersion=24` read from the AAR |
| ABIs in the AAR | arm64-v8a, armeabi-v7a, x86, x86_64 | `unzip -l` |
| ABIs in the proof APK | arm64-v8a, x86_64 (arm64-v8a is the intended device ABI; x86_64 for emulators); armeabi-v7a/x86 not included | `aapt2` native-code |

Minimum API **24** is set by the ONNX Runtime AAR. The engine itself needs no more than API 24: its JDK API use was audited and three newer uses were removed (`java.nio.file` →
`java.io.File`, `String.isBlank` (API 33), `String.join` (API 26)); records and lambdas are desugared by D8 (the dex contains `ExternalSyntheticRecord`/`ExternalSyntheticLambda` classes;
**PENDING**: running them on a device). Android lint's `NewApi` did **not** flag a deliberate `String.isBlank` in a negative control, so it is **not** used as evidence; the exact checker
`tools/check-android-api.py` is (negative control: it flags `String.isBlank` and `String.join` in the pre-fix code).
ONNX Runtime's own classes reference `java.nio.file` in desktop/training code paths (found in the dex); API 24/25 devices have not been tried.

### 3.2 What the host app must do (the adapter contract)

1. **Models**: bundle the five files as assets (store uncompressed: `androidResources { noCompress += ['onnx'] }`), copy once to app-private storage, `ModelAssets.fromDirectory(dir)`,
   `OcrEngine.open` verifies checksums. Reference: `ModelInstaller`.
2. **Images → `PixelImage`**: decode at **original resolution** (no `inSampleSize`), `ARGB_8888`, software bitmap (hardware bitmaps cannot be read), `Bitmap.getPixels` (sRGB,
   **non-premultiplied**) → `PixelImage.fromArgbPacked`, then `.withExifOrientation(tag)` using the framework `ExifInterface(InputStream)` (API 24+). Reference: `AndroidImageAdapter`.
   *Gray sources*: the desktop decoder replicates gray samples into B=G=R (it does **not** gamma-convert); Android's `BitmapFactory` normally yields sRGB with replicated gray, but this
   is **not verified**; compare on a gray fixture before relying on it.
3. **Threads**: call `recognize` from a background thread (seconds; first call is slowest). Do not call from the main thread.
4. **Memory**: the models are ~31 MB on disk; ONNX Runtime allocates native memory for the two sessions and per call. A decoded 12 MP photo needs roughly 130 MB transiently (bitmap 48 MB + `int[]` 48 MB + BGR 36 MB). **Android memory use is not measured** (PENDING). Consider a size policy in the product (a product decision, not made here).
5. **Lifecycle**: keep one engine per process/screen scope, `close()` in `onDestroy`/when the owning scope ends after in-flight work completes.
6. **Execution provider**: CPU only (no NNAPI/XNNPACK tuning in this refactor).

### 3.3 Build and run commands

```sh
# toolchain check, no device needed (needs ANDROID_HOME and models/ from tools/download-models.sh):
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew -p android-proof :app:verifyEngineAndroidApi :app:verifyProofDependencies :app:assembleDebug
# -> android-proof/app/build/outputs/apk/debug/app-debug.apk

# runtime acceptance on a connected device or a running emulator (does not start an emulator):
sh tools/android-proof-run.sh
# expected: JSON with "allPassed":true, recognizedText equal to the fixture's expected text (4 lines), checks listed below
```

The proof app (`ProofActivity`, not a product UI) runs `ProofRunner`: (1) models verify and the engine opens; (2) the committed fixture decodes to 900×500 and its recognized text equals the committed expected text;
(3) five repeated recognitions are stable and native-heap growth stays under 64 MB; (4) EXIF tags 2–8 through the Android adapter equal the permutation of the unrotated decode; (5) `close()` makes later calls fail
with `OcrException`, and the engine re-opens. It contains no OCR logic.

---------------------------------------------------------------------------------------------------------------------------------

## 4. Fixtures, comparison rules, evidence

Non-private, deterministic fixtures (committed): `src/test/resources/fixtures/print-900x500.png` (SHA-256 `a38865f710864022deb17a09e22a6bd0dbf59b3f738979f9b2f7fc993d67dbe0`, generated by
`tools/make-print-fixture.py`, DejaVu Sans 40 pt; **a regenerated fixture**: the earlier 900×500 print image mentioned in older notes was not retained, so this is a *new identity*), its expected text
`print-900x500.expected.txt`, and `samples/handwriting-test.jpg` with the ground truth in `eval/cases.tsv`. `samples/images/*.png` are synthetic scribbles with no ground truth (output is meaningless by design).

**Canonical serialization** (`BoundaryFingerprint`): pixel buffers hash as SHA-256 of `"NBAI-FP1 u8 BGR W H\n"` + raw bytes (row-major, B first, no padding, top-left origin); float tensors hash as SHA-256 of
`"NBAI-FP1 f32 <dims>\n"` + each value as 4 bytes big-endian IEEE-754 bits; coordinates print with exactly 4 decimals, `Locale.ROOT`; text is raw with `\n` escaped. Files are identified by name and SHA-256, never by path.

**Comparison rules**: image decode, working image and detector input tensor are **pure Java** and must match bit for bit on every platform/JVM (`BoundaryBaselineTest` pins them to the recorded baseline for two fixtures). The
detector map and the recognizer logits come out of ONNX Runtime; different native backends or CPUs may differ in the last bits, so across platforms compare them with a tolerance and report **transcript** and **geometry** differences
separately. Within one platform and ORT version they were bit-identical before and after the refactor.

```sh
./gradlew boundaryFingerprint -Pimages=src/test/resources/fixtures/print-900x500.png,samples/handwriting-test.jpg > after.txt
diff src/test/resources/fixtures/boundary-baseline-public.txt after.txt     # baseline: linux-x64, ONNX Runtime 1.22.0 CPU
```

Baseline OCR numbers (this machine, before and after the refactor, identical): print fixture 4/4 lines exact (CER 0); `eval/cases.tsv` handwriting sample CER 0.179 (12 edits / 67 chars), WER 0.385 (5 / 13).

Verified vs pending: see `HANDOFF.md` ("Engine portability") for the executed commands, counts and source identities.

---------------------------------------------------------------------------------------------------------------------------------

## 5. Limitations

* **Accuracy**: strong on clean printed text, limited on cursive/tilted handwriting (the committed handwriting sample reads with 12 character edits of 67). Remaining glyph confusions have not been shown to be
  model-only; nothing here changes models or decoding. Synthetic scribbles read as pseudo-text by design.
* **Android**: build integration verified (resolution, API check, D8, APK, ABIs, no desktop code in the APK). **Runtime acceptance is PENDING**: no device was available, and the only emulator image on the build machine is x86_64 without
  hardware virtualization (`/dev/kvm` absent), so it was not started. Android memory/latency, API 24–25 behaviour, arm64 execution and desktop-vs-Android result comparison are all unmeasured.
* **iOS** is a **separate integration path**: reuse the models and this pipeline specification (section 2) with ONNX Runtime for iOS (or another runtime) and an iOS image adapter. The Java engine is **not** an iOS execution target.
* **Redistribution / release gate** (current, from `packaging/licensing-status.json` and `./gradlew verifyReleaseLicensing`, which still fails and is unchanged): `xmpcore-license` unresolved (`xmpcore` is a transitive dependency of the
  *desktop* metadata-extractor only; neither the engine nor the Android APK contains it) and `ppocrv6-model-redistribution` **partial** (rests on metadata and the project license, not an explicit grant). The models are bundled in
  the Android proof APK too, so the model-redistribution question applies to any Android build that ships them. No installer or APK is signed; publisher/maintainer details are not configured. Nothing here is release-approved.
* **Desktop**: installers unsigned; Windows installers were not rebuilt or re-tested for this refactor (see `HANDOFF.md`).
