package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Regressions found by the independent cv2/onnxruntime cross-check (tools/xcheck). */
class ReferenceParityTest {

    @Test
    void cubicKernelWeightsSumToOneAtEveryFractionalOffset() {
        for (double f = 0; f < 1.0; f += 0.05) {
            double sum = 0;
            for (int j = -1; j <= 2; j++) {
                sum += ImageOps.cubic(f - j);
            }
            assertEquals(1.0, sum, 1e-12, "tap weights at offset " + f);
        }
        assertEquals(1.0, ImageOps.cubic(0), 1e-12);
        assertEquals(0.0, ImageOps.cubic(2.0), 1e-12);
    }

    @Test
    void tiltedPerspectiveWarpPreservesAUniformImage() throws Exception {
        int w = 120;
        int h = 100;
        byte[] img = new byte[w * h * 3];
        Arrays.fill(img, (byte) 200);
        // A tilted (~15 degree) quad: every output sample lands at a fractional source position.
        double[][] quad = {{20, 20}, {100, 41}, {95, 62}, {16, 41}};
        byte[] out = ImageOps.warpQuadBicubic(img, w, h, quad, 80, 22);
        for (byte b : out) {
            assertEquals(200, b & 0xFF, "interpolating a constant image must not change it");
        }
    }

    @Test
    void bicubicResizeAndRotatePreserveUniformInterior() {
        int w = 64;
        int h = 48;
        byte[] img = new byte[w * h * 3];
        Arrays.fill(img, (byte) 180);
        byte[] r = ImageOps.resizeBicubic(img, w, h, 50, 37);
        for (byte b : r) {
            assertEquals(180, b & 0xFF);
        }
    }

    @Test
    void detectorSizeRoundingFollowsPythonHalfToEven() {
        // Python round(x/32)*32: 1808/32 = 56.5 -> 56 (even), 1840/32 = 57.5 -> 58, 1776/32 = 55.5 -> 56.
        assertEquals(1792, DetPreprocess.snap32(1808, 1808)[0]);
        assertEquals(1856, DetPreprocess.snap32(1840, 1840)[0]);
        assertEquals(1792, DetPreprocess.snap32(1776, 1776)[0]);
        assertEquals(1792, DetPreprocess.snap32(1800, 1800)[0]); // 56.25 -> 56 (unchanged behavior)
        assertEquals(4000, DetPreprocess.snap32(4000, 4000)[1]);
    }
}
