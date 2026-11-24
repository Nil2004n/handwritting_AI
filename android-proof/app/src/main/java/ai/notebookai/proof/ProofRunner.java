package ai.notebookai.proof;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Debug;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import notebookai.ocr.ModelAssets;
import notebookai.ocr.OcrEngine;
import notebookai.ocr.OcrException;
import notebookai.ocr.OcrResult;
import notebookai.ocr.PixelImage;

/**
 * Runs the engine on a device and records structured results. It contains NO OCR logic: it installs models, decodes through the Android adapter,
 * and calls {@link OcrEngine}. Call from a background thread (recognition takes seconds).
 *
 * <p>Checks: (1) models verify and engine opens; (2) the committed deterministic fixture (assets/fixtures/print-900x500.png) decodes to 900x500 and
 * its recognized text equals the committed expected text; (3) repeated recognition is stable and native-heap growth stays bounded; (4) EXIF
 * orientation via the Android adapter equals the permutation of the unrotated decode; (5) close() releases the engine and later calls fail cleanly,
 * and re-opening works.
 */
public final class ProofRunner {

    private static final int REPEATS = 5;
    private static final long MAX_NATIVE_HEAP_GROWTH = 64L * 1024 * 1024; // bounded-memory check: far above noise, far below a leak of 5 runs

    private ProofRunner() {}

    public static ProofResult run(Context context) {
        ProofResult r = new ProofResult();
        try {
            ModelAssets models = ModelInstaller.install(context);
            byte[] png = readAsset(context, "fixtures/print-900x500.png");
            r.expectedText = new String(readAsset(context, "fixtures/print-900x500.expected.txt"), StandardCharsets.UTF_8).trim();

            long t0 = System.nanoTime();
            OcrEngine engine = OcrEngine.open(models);
            r.loadMillis = (System.nanoTime() - t0) / 1_000_000;
            r.check("engine opens from verified app-private models", true, r.loadMillis + " ms");

            try {
                PixelImage image = AndroidImageAdapter.decode(png);
                r.check("fixture decodes to 900x500", image.width() == 900 && image.height() == 500, image.width() + "x" + image.height());

                long t1 = System.nanoTime();
                OcrResult first = engine.recognize(image);
                r.firstRecognizeMillis = (System.nanoTime() - t1) / 1_000_000;
                r.recognizedText = first.combinedText();
                r.regionCount = first.regions().size();
                r.check("recognized text equals the committed expected text", r.expectedText.equals(r.recognizedText), r.recognizedText.replace('\n', '|'));

                long heapBefore = Debug.getNativeHeapAllocatedSize();
                long total = 0;
                boolean stable = true;
                for (int i = 0; i < REPEATS; i++) {
                    long t = System.nanoTime();
                    OcrResult again = engine.recognize(image);
                    total += (System.nanoTime() - t) / 1_000_000;
                    stable &= again.combinedText().equals(r.recognizedText);
                }
                r.repeatedMeanMillis = total / REPEATS;
                r.nativeHeapGrowthBytes = Debug.getNativeHeapAllocatedSize() - heapBefore;
                r.check("repeated recognition is stable (" + REPEATS + " runs)", stable, r.repeatedMeanMillis + " ms mean");
                r.check("native heap growth over repeated runs is bounded", r.nativeHeapGrowthBytes < MAX_NATIVE_HEAP_GROWTH, r.nativeHeapGrowthBytes + " bytes");

                orientationChecks(r, png);
            } finally {
                engine.close();
            }

            boolean closedFails = false;
            try {
                engine.recognize(PixelImage.wrapBgr(new byte[3 * 40 * 40], 40, 40));
            } catch (OcrException expected) {
                closedFails = true;
            }
            r.check("calls after close() fail with OcrException", closedFails, "");
            try (OcrEngine second = OcrEngine.open(models)) {
                r.check("engine can be re-opened after close()", second.recognize(AndroidImageAdapter.decode(png)).combinedText().equals(r.recognizedText), "");
            }
        } catch (Throwable t) {
            r.check("unexpected failure", false, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return r;
    }

    /** JPEG with an EXIF Orientation tag, decoded through the adapter, must equal the unrotated decode permuted by the same tag. */
    private static void orientationChecks(ProofResult r, byte[] png) throws Exception {
        Bitmap src = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
        Bitmap small = Bitmap.createBitmap(src, 0, 0, 120, 70); // non-square so a wrong axis swap changes the dimensions
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 95, jpeg);
        PixelImage normal = AndroidImageAdapter.decode(withOrientation(jpeg.toByteArray(), 1));
        boolean all = true;
        StringBuilder detail = new StringBuilder();
        for (int tag = 2; tag <= 8; tag++) {
            PixelImage got = AndroidImageAdapter.decode(withOrientation(jpeg.toByteArray(), tag));
            PixelImage want = normal.withExifOrientation(tag);
            boolean ok = got.width() == want.width() && got.height() == want.height() && java.util.Arrays.equals(got.bgr(), want.bgr());
            all &= ok;
            if (!ok) {
                detail.append("tag ").append(tag).append(' ');
            }
        }
        r.check("EXIF orientation tags 2-8 via the Android adapter", all, all ? "all equal" : "mismatch: " + detail);
    }

    /** Inserts a minimal EXIF APP1 segment (Orientation only) right after the JPEG SOI marker. */
    static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        byte[] exif = new byte[6 + tiff.length];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, exif, 0, 6);
        System.arraycopy(tiff, 0, exif, 6, tiff.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((exif.length + 2) >> 8);
        out.write((exif.length + 2) & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] readAsset(Context context, String name) throws java.io.IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
