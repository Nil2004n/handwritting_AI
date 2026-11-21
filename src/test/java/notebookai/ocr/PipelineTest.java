package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preprocessing contracts + service error paths (no models needed). */
class PipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void detectorSnapAndRatio() {
        // 900x1200: min side 900 >= 736, no upscale; multiples of 32.
        assertEquals(1.0, DetPreprocess.minResizeRatio(900, 1200), 1e-12);
        int[] snapped = DetPreprocess.snap32(900, 1200);
        assertEquals(896, snapped[0]);
        assertEquals(1216, snapped[1]);
        // 900x700: min side 700 < 736, upscale to min 736.
        assertEquals(736.0 / 700.0, DetPreprocess.minResizeRatio(900, 700), 1e-12);
        // normalize: black pixel maps to -mean/std per channel.
        float[] out = DetPreprocess.normalize(new byte[] {0, 0, 0}, 1, 1);
        assertEquals((float) ((0 - 0.485) / 0.229), out[0], 1e-6);
        assertEquals((float) ((0 - 0.456) / 0.224), out[1], 1e-6);
        assertEquals((float) ((0 - 0.406) / 0.225), out[2], 1e-6);
    }

    @Test
    void recognizerPadsRightAndKeepsRange() throws Exception {
        // 200x50 crop: ratio 4 -> imgW = 320, resized to ceil(48*4) = 192.
        byte[] crop = new byte[200 * 50 * 3];
        for (int i = 0; i < crop.length; i++) {
            crop[i] = (byte) 255;
        }
        RecPreprocess.RecInput in = RecPreprocess.preprocess(crop, 200, 50);
        assertEquals(320, in.width());
        assertEquals(3 * 48 * 320, in.chw().length);
        // White maps to (1 - 0.5) / 0.5 = 1.0 in the valid region.
        assertEquals(1.0f, in.chw()[0], 1e-6);
        // Right padding (x >= 192) is zeros.
        assertEquals(0.0f, in.chw()[193], 1e-9);
        for (float v : in.chw()) {
            assertTrue(v >= -1.0f && v <= 1.0f);
        }
    }

    @Test
    void combinedTextJoinsRegionsWithLineBreaks() {
        var regions =
                java.util.List.of(
                        new notebookai.model.OcrResult.TextRegion(
                                new double[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}},
                                new int[] {0, 0, 1, 1},
                                "hello",
                                0.9,
                                0.95),
                        new notebookai.model.OcrResult.TextRegion(
                                new double[][] {{0, 2}, {1, 2}, {1, 3}, {0, 3}},
                                new int[] {0, 2, 1, 3},
                                "world",
                                0.9,
                                0.95));
        var result =
                new notebookai.model.OcrResult(
                        10,
                        10,
                        regions,
                        "hello\nworld",
                        new notebookai.model.OcrResult.Timings(0, 0, 0, 0, 0, 0, 0, 0));
        assertEquals("hello\nworld", result.combinedText());
        assertEquals(2, result.regions().size());
    }

    @Test
    void missingImageFailsCleanly() {
        Path missing = tempDir.resolve("nope.jpg");
        OcrException failure =
                assertThrows(
                        OcrException.class,
                        () -> {
                            try (OcrService service = OcrService.open(Paths.get("").toAbsolutePath())) {
                                service.recognize(missing);
                            }
                        });
        // Either models are missing here or the image is; both must be actionable.
        assertTrue(
                failure.getMessage().contains("models")
                        || failure.getMessage().contains("Could not read image")
                        || failure.getMessage().contains("Unreadable")
                        || failure.getMessage().contains("incomplete"),
                failure.getMessage());
    }

    @Test
    void unsupportedExtensionFailsCleanly() throws Exception {
        Path text = tempDir.resolve("note.txt");
        Files.write(text, "not an image".getBytes());
        OcrException failure =
                assertThrows(OcrException.class, () -> ImageOps.decode(text));
        assertTrue(failure.getMessage().contains("JPG/JPEG and PNG"), failure.getMessage());
    }
}
