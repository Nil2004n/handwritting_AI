package notebookocr.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Geometry: hull/rect, reading order, scaling, warp identity, unclip growth. */
class GeometryTest {

    private static void assertBoxSetEquals(double[][] actual, double[][] expected, double eps) {
        assertEquals(expected.length, actual.length);
        boolean[] used = new boolean[actual.length];
        for (double[] e : expected) {
            boolean found = false;
            for (int i = 0; i < actual.length; i++) {
                if (!used[i]
                        && Math.abs(actual[i][0] - e[0]) < eps
                        && Math.abs(actual[i][1] - e[1]) < eps) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            assertTrue(found, "missing corner near (" + e[0] + "," + e[1] + ")");
        }
    }

    @Test
    void minAreaRectOfAxisAlignedRectangle() {
        double[][] pts = {{10, 20}, {30, 20}, {30, 40}, {10, 40}};
        double[][] rect = DbPostprocess.minAreaRect(pts);
        assertBoxSetEquals(rect, pts, 1e-6);
    }

    @Test
    void minAreaRectOfRotatedSquare() {
        // Diamond with "radius" 10: area must be 200, corners recovered.
        double[][] pts = {{0, 10}, {10, 0}, {0, -10}, {-10, 0}};
        double[][] rect = DbPostprocess.minAreaRect(pts);
        assertBoxSetEquals(rect, pts, 1e-6);
    }

    @Test
    void getMiniBoxesOrdersTopLeftFirst() {
        double[][] quad = {{30, 20}, {10, 20}, {10, 40}, {30, 40}};
        double[][] ordered = DbPostprocess.getMiniBoxes(quad);
        assertEquals(10.0, ordered[0][0], 1e-6); // left column first
        assertEquals(20.0, ordered[0][1], 1e-6); // ... top row first
        assertEquals(30.0, ordered[2][0], 1e-6); // opposite corner
        assertEquals(40.0, ordered[2][1], 1e-6);
    }

    @Test
    void sortedBoxesReadsTopToBottomLeftToRight() {
        double[][] topRight = {{500, 10}, {600, 10}, {600, 30}, {500, 30}};
        double[][] topLeft = {{10, 12}, {100, 12}, {100, 32}, {10, 32}};
        double[][] bottom = {{10, 200}, {100, 200}, {100, 220}, {10, 220}};
        List<double[][]> ordered = CropOps.sortedBoxes(List.of(bottom, topRight, topLeft));
        assertTrue(Arrays.equals(topLeft, ordered.get(0)));
        assertTrue(Arrays.equals(topRight, ordered.get(1)));
        assertTrue(Arrays.equals(bottom, ordered.get(2)));
    }

    @Test
    void warpIdentityMapsImageToItself() throws Exception {
        int w = 32;
        int h = 24;
        byte[] img = new byte[w * h * 3];
        for (int i = 0; i < img.length; i++) {
            img[i] = (byte) (i % 251);
        }
        double[][] quad = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        byte[] out = ImageOps.warpQuadBicubic(img, w, h, quad, w, h);
        // Center pixels must round-trip (borders may differ by replicate rule).
        for (int y = 4; y < h - 4; y++) {
            for (int x = 4; x < w - 4; x++) {
                for (int c = 0; c < 3; c++) {
                    int expected = img[(y * w + x) * 3 + c] & 0xFF;
                    int actual = out[(y * w + x) * 3 + c] & 0xFF;
                    assertTrue(
                            Math.abs(expected - actual) <= 2,
                            "pixel drift at " + x + "," + y + ": " + expected + " vs " + actual);
                }
            }
        }
    }

    @Test
    void unclipExpandsConvexQuad() {
        double[][] quad = {{10, 10}, {50, 10}, {50, 30}, {10, 30}};
        double[][] expanded = DbPostprocess.unclip(quad, 1.4);
        assertTrue(expanded != null && expanded.length >= 4);
        double minX = Arrays.stream(expanded).mapToDouble(p -> p[0]).min().orElseThrow();
        double maxX = Arrays.stream(expanded).mapToDouble(p -> p[0]).max().orElseThrow();
        double minY = Arrays.stream(expanded).mapToDouble(p -> p[1]).min().orElseThrow();
        double maxY = Arrays.stream(expanded).mapToDouble(p -> p[1]).max().orElseThrow();
        assertTrue(minX < 10 && maxX > 50 && minY < 10 && maxY > 30);
    }

    @Test
    void boxScoreFastScoresFullMaskAsMean() {
        float[][] prob = new float[10][10];
        for (float[] row : prob) {
            Arrays.fill(row, 0.8f);
        }
        double[][] box = {{0, 0}, {9, 0}, {9, 9}, {0, 9}};
        // float32 accumulation: tolerance, not exact equality.
        assertEquals(0.8, DbPostprocess.boxScoreFast(prob, box), 1e-6);
    }
}
