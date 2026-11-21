package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression tests for the tilted/cursive-line failures found on the real handwriting sample. */
class DetectionRegressionTest {

    /** Rotates (x, y) about the origin and translates. */
    private static double[] rot(double x, double y, double deg, double ox, double oy) {
        double a = Math.toRadians(deg);
        return new double[] {ox + x * Math.cos(a) - y * Math.sin(a), oy + x * Math.sin(a) + y * Math.cos(a)};
    }

    /** TL, TR, BR, BL box of [x0,x1]x[y0,y1] in a frame rotated by deg. */
    private static double[][] box(double x0, double y0, double x1, double y1, double deg) {
        return new double[][] {
            rot(x0, y0, deg, 0, 0), rot(x1, y0, deg, 0, 0), rot(x1, y1, deg, 0, 0), rot(x0, y1, deg, 0, 0)
        };
    }

    // ---- score mode: bent/cursive line has a mostly empty min-area rectangle ----

    private static float[][] lShapedInk() {
        float[][] prob = new float[160][220];
        for (int y = 20; y < 28; y++) { // 150px bar
            for (int x = 30; x < 180; x++) {
                prob[y][x] = 0.95f;
            }
        }
        for (int y = 20; y < 120; y++) { // tall descender-like stroke
            for (int x = 30; x < 38; x++) {
                prob[y][x] = 0.95f;
            }
        }
        return prob;
    }

    @Test
    void fastScoringRejectsBentLineButSlowKeepsIt() {
        float[][] prob = lShapedInk();
        List<DbPostprocess.Quad> fast =
                DbPostprocess.boxesFromBitmap(prob, 220, 160, null, DbPostprocess.ScoreMode.FAST);
        List<DbPostprocess.Quad> slow =
                DbPostprocess.boxesFromBitmap(prob, 220, 160, null, DbPostprocess.ScoreMode.SLOW);
        assertEquals(0, fast.size(), "rect-mean score is diluted by empty rectangle area");
        assertEquals(1, slow.size());
        assertTrue(slow.get(0).score() > 0.9);
    }

    @Test
    void ocrServiceUsesSlowScoring() {
        assertEquals(DbPostprocess.ScoreMode.SLOW, OcrService.SCORE_MODE);
    }

    // ---- unclip padding must not swallow the neighbor's last letter ----

    private static DbPostprocess.Quad quad(double[][] core, double pad) {
        // Expanded box = core grown by pad on every side (same frame), like unclip.
        double[] u = {core[1][0] - core[0][0], core[1][1] - core[0][1]};
        double ul = Math.hypot(u[0], u[1]);
        u[0] /= ul;
        u[1] /= ul;
        double[] v = {-u[1], u[0]};
        double[][] e = new double[4][2];
        double[][] sign = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int k = 0; k < 4; k++) {
            e[k][0] = Math.round(core[k][0] + sign[k][0] * pad * u[0] + sign[k][1] * pad * v[0]);
            e[k][1] = Math.round(core[k][1] + sign[k][0] * pad * u[1] + sign[k][1] * pad * v[1]);
        }
        return new DbPostprocess.Quad(e, 0.9, core);
    }

    private static double along(double[][] q, double deg, boolean max) {
        double a = Math.toRadians(deg);
        double m = max ? -1e18 : 1e18;
        for (double[] p : q) {
            double u = p[0] * Math.cos(a) + p[1] * Math.sin(a);
            m = max ? Math.max(m, u) : Math.min(m, u);
        }
        return m;
    }

    @Test
    void paddingIsCutBackWhereItEntersNeighborCoreOnlyOnThatEnd() {
        double deg = 30;
        // Left word ends at u=300; right word starts at u=290 (cores touch) on the same line band.
        double[][] leftCore = box(100, 0, 300, 60, deg);
        double[][] rightCore = box(290, 0, 900, 60, deg);
        DbPostprocess.Quad left = quad(leftCore, 40);
        DbPostprocess.Quad right = quad(rightCore, 40);
        double rightStartBefore = along(right.points(), deg, false);
        double rightEndBefore = along(right.points(), deg, true);
        double leftStartBefore = along(left.points(), deg, false);
        DbPostprocess.trimPaddingIntoNeighborCores(new ArrayList<>(List.of(left, right)));

        // Right box no longer starts before its own core (u=290); far end untouched.
        assertEquals(290, along(right.points(), deg, false), 1.5);
        assertTrue(along(right.points(), deg, false) > rightStartBefore + 30);
        assertEquals(rightEndBefore, along(right.points(), deg, true), 1.5);
        // Left box: its right padding is cut to its core end (u=300); far end untouched.
        assertEquals(300, along(left.points(), deg, true), 1.5);
        assertEquals(leftStartBefore, along(left.points(), deg, false), 1.5);
    }

    @Test
    void paddingIsKeptWhenNeighborIsFarOrOnAnotherLine() {
        double[][] a = box(100, 0, 300, 60, 0);
        double[][] farSameLine = box(600, 0, 900, 60, 0);
        double[][] lineBelow = box(100, 150, 300, 210, 0);
        DbPostprocess.Quad qa = quad(a, 40);
        DbPostprocess.Quad qb = quad(farSameLine, 40);
        DbPostprocess.Quad qc = quad(lineBelow, 40);
        double[][] before = copy(qa.points());
        DbPostprocess.trimPaddingIntoNeighborCores(new ArrayList<>(List.of(qa, qb, qc)));
        assertTrue(java.util.Arrays.deepEquals(before, qa.points()));
    }

    private static double[][] copy(double[][] p) {
        double[][] c = new double[p.length][];
        for (int i = 0; i < p.length; i++) {
            c[i] = p[i].clone();
        }
        return c;
    }

    // ---- reading order on tilted pages ----

    @Test
    void tiltedLinesAreOrderedAcrossTheTextNotByTopLeftY() {
        double deg = 33;
        // Three stacked lines in a frame rotated by 33 degrees. A lower line starts further left,
        // so its first corner sits ABOVE the previous line's first corner in image y.
        double[][] l1 = box(300, 0, 1300, 70, deg);
        double[][] l2 = box(100, 120, 1100, 190, deg);
        double[][] l3 = box(-200, 240, 800, 310, deg);
        // Upstream rule (first-corner y) would put l3 before l2 here:
        assertTrue(l3[0][1] < l2[0][1], "precondition: top-left y misorders these lines");
        List<List<Integer>> lines = ReadingOrder.group(List.of(l3, l1, l2));
        assertEquals(List.of(List.of(1), List.of(2), List.of(0)), lines);
    }

    @Test
    void splitSentenceOnOneTiltedLineIsGroupedLeftToRightButFarColumnsAreNot() {
        double deg = 30;
        double[][] first = box(0, 0, 200, 60, deg); // "LIFE"
        double[][] second = box(215, 2, 900, 62, deg); // "ITSELF IS COMPLICATED"
        double[][] farCell = box(2600, 0, 2900, 60, deg); // unrelated column, gap >> thickness
        List<List<Integer>> lines = ReadingOrder.group(List.of(second, farCell, first));
        assertEquals(List.of(List.of(2, 0), List.of(1)), lines);
    }

    @Test
    void horizontalRowsStayTopToBottomLeftToRight() {
        double[][] topRight = box(500, 10, 600, 30, 0);
        double[][] topLeft = box(10, 12, 100, 32, 0);
        double[][] bottom = box(10, 200, 100, 220, 0);
        List<List<Integer>> lines = ReadingOrder.group(List.of(bottom, topRight, topLeft));
        // topLeft/topRight are 400px apart (>> thickness) so they stay separate lines, in x order.
        assertEquals(List.of(List.of(2), List.of(1), List.of(0)), lines);
    }

    @Test
    void shortFragmentBesideTallBoxJoinsAcrossAWordGapButWideColumnsDoNot() {
        // Real-sample shape: a tall box (padded thickness ~160) then a short fragment box
        // (~84 thick) after an 86px word gap on the same line. Gap > 0.5*min but < 0.6*max.
        double[][] tall = {{0, 0}, {800, 0}, {800, 160}, {0, 160}};
        double[][] shortFrag = {{886, 40}, {1278, 40}, {1278, 124}, {886, 124}};
        assertEquals(List.of(List.of(0, 1)), ReadingOrder.group(List.of(tall, shortFrag)));
        // A column gap of ~3 line thicknesses stays a separate display line.
        double[][] farColumn = {{2000, 40}, {2392, 40}, {2392, 124}, {2000, 124}};
        assertEquals(List.of(List.of(0), List.of(1)), ReadingOrder.group(List.of(tall, farColumn)));
    }

    @Test
    void decodeReturnsTrueBgrChannelOrder() throws Exception {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000); // pure red
        img.setRGB(1, 0, 0x0000FF); // pure blue
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("chan", ".png");
        try {
            javax.imageio.ImageIO.write(img, "png", tmp.toFile());
            byte[] bgr = ImageOps.decode(tmp).bgr();
            assertEquals(0, bgr[0] & 0xFF, "red pixel: B");
            assertEquals(0, bgr[1] & 0xFF, "red pixel: G");
            assertEquals(255, bgr[2] & 0xFF, "red pixel: R is the LAST byte in BGR");
            assertEquals(255, bgr[3] & 0xFF, "blue pixel: B is the FIRST byte in BGR");
            assertEquals(0, bgr[5] & 0xFF);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}
