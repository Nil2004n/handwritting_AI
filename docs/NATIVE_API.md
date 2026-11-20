# Native API (proposed — NOT built yet)

Status: design sketch for Phase 1+. No Kotlin bridge code exists; the
template's `MainActivity.kt`/`MainApplication.kt` are untouched. Nothing
here is callable.

## Proposed JS -> Kotlin contract
Mirrors `src/domain/ocr.ts`, with `device-image` requests:

```ts
recognize({ type: 'device-image', imageUri: string, region?: SourceBounds })
  : Promise<OcrResult>
```

- `imageUri`: opaque handle produced by the future camera/gallery flow
  (content URI or app-private file path — decided in Phase 1, not here).
- `region` (optional): user-selected crop in source-pixel space.
- Result: same `OcrResult` shape (`documentId`, `engineId`, `Block[]`).

## Proposed native responsibilities
- Model initialization lifecycle: load-once PaddleOCR detector + recognizer
  on first use (not on app start), with explicit release; surface init
  failures as `RECOGNITION_FAILED`, never silent.
- Background execution: inference off the JS thread (e.g. Kotlin
  coroutines / NativeModule promise), progress for large pages if needed.
- Image handling: resolve the URI natively, apply EXIF orientation so
  coordinates match the displayed-orientation convention, then run detection.
- Detector polygons: returned in the optional `Block.polygon` field, same
  pixel space as `bounds`.

## Error mapping
| Native situation        | JS error code        |
|-------------------------|----------------------|
| File/URI not readable   | `UNSUPPORTED_SOURCE` |
| Bad request shape       | `INVALID_REQUEST`    |
| Model missing / crash   | `RECOGNITION_FAILED` |

## Non-goals for the bridge
No table-structure or equation recognition in the first native cut: PaddleOCR
text recognition must NOT be presented as table reconstruction or
LaTeX output. Those are later workflows on top of block primitives.
