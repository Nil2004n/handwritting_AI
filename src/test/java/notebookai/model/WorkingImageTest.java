package notebookai.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Image prep, display mapping, selection rules, literal preservation. */
class WorkingImageTest {

    private static byte[] gray(int w, int h, int v) {
        byte[] b = new byte[w * h * 3];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) v;
        }
        return b;
    }

    @Test
    void identityRenderKeepsDimensions() {
        WorkingImage img = new WorkingImage(gray(90, 120, 7), 90, 120);
        WorkingImage.Rendered r = img.render();
        assertEquals(90, r.width());
        assertEquals(120, r.height());
        assertEquals(90 * 120 * 3, r.bgr().length);
        assertEquals(0, img.revision());
    }

    @Test
    void quarterTurnSwapsDimensionsAndBumpsRevision() {
        WorkingImage img = new WorkingImage(gray(90, 120, 7), 90, 120);
        img.rotateSteps(1);
        assertEquals(1, img.revision());
        WorkingImage.Rendered r = img.render();
        assertEquals(120, r.width());
        assertEquals(90, r.height());
        img.rotateSteps(3);
        WorkingImage.Rendered back = img.render();
        assertEquals(90, back.width());
        assertEquals(120, back.height());
    }

    @Test
    void rotateStepsWrapModuloFour() {
        WorkingImage img = new WorkingImage(gray(10, 20, 1), 10, 20);
        img.rotateSteps(5);
        assertEquals(1, img.quarterTurnsCw());
        img.rotateSteps(-2);
        assertEquals(3, img.quarterTurnsCw());
    }

    @Test
    void straightenClampedAndCounted() {
        WorkingImage img = new WorkingImage(gray(10, 10, 1), 10, 10);
        img.setStraighten(25);
        assertEquals(10.0, img.fineDegrees(), 1e-12);
        img.setStraighten(-30);
        assertEquals(-10.0, img.fineDegrees(), 1e-12);
        assertEquals(2, img.revision());
    }

    @Test
    void fineRotationExpandsCanvas() {
        WorkingImage img = new WorkingImage(gray(100, 40, 128), 100, 40);
        img.setStraighten(10); // clamped to slider range
        WorkingImage.Rendered r = img.render();
        // ceil(100*cos10 + 40*sin10) = 106, ceil(100*sin10 + 40*cos10) = 57.
        assertTrue(Math.abs(r.width() - 106) <= 1, "w=" + r.width());
        assertTrue(Math.abs(r.height() - 57) <= 1, "h=" + r.height());
    }

    @Test
    void cropCutsWorkingImage() {
        WorkingImage img = new WorkingImage(gray(100, 80, 9), 100, 80);
        img.setCrop(new int[] {10, 10, 60, 50});
        assertTrue(img.hasCrop());
        WorkingImage.Rendered r = img.render();
        assertEquals(50, r.width());
        assertEquals(40, r.height());
        img.clearCrop();
        assertFalse(img.hasCrop());
        WorkingImage.Rendered full = img.render();
        assertEquals(100, full.width());
    }

    @Test
    void rotateOrStraightenClearsAStaleCropButResetRestoresOriginal() {
        WorkingImage img = new WorkingImage(gray(100, 80, 9), 100, 80);
        img.setCrop(new int[] {10, 10, 60, 50});
        int rev = img.revision();
        img.rotateSteps(1);
        assertFalse(img.hasCrop(), "crop rect belongs to the old frame");
        assertEquals(80, img.render().width());
        assertEquals(100, img.render().height());
        assertTrue(img.revision() > rev);
        img.setCrop(new int[] {0, 0, 40, 40});
        img.setStraighten(2.0);
        assertFalse(img.hasCrop());
        img.setCrop(new int[] {0, 0, 40, 40});
        img.reset();
        WorkingImage.Rendered r = img.render();
        assertEquals(100, r.width());
        assertEquals(80, r.height());
        assertEquals(0, img.quarterTurnsCw());
        assertEquals(0.0, img.fineDegrees(), 0.0);
    }

    @Test
    void everyWorkingImageMutationInvalidatesAnInFlightJob() {
        // A job captures (generation, revision) at start; the UI applies its result only if
        // WorkingImage.isCurrentJob(...) still holds. Each real mutator must break that.
        Runnable[] mutations = new Runnable[4];
        WorkingImage img = new WorkingImage(gray(100, 80, 9), 100, 80);
        mutations[0] = () -> img.rotateSteps(1);
        mutations[1] = () -> img.setStraighten(1.5);
        mutations[2] = () -> img.setCrop(new int[] {5, 5, 50, 50});
        mutations[3] = img::reset;
        for (Runnable mutate : mutations) {
            int gen = 7;
            int jobRev = img.revision();
            assertTrue(WorkingImage.isCurrentJob(gen, 7, jobRev, img.revision()));
            mutate.run(); // user changes the image while the job is "running"
            assertFalse(
                    WorkingImage.isCurrentJob(gen, 7, jobRev, img.revision()),
                    "stale result must be discarded after a working-image change");
        }
        // A newer job started after this one also supersedes it.
        assertFalse(WorkingImage.isCurrentJob(7, 8, img.revision(), img.revision()));
    }

    @Test
    void displayMappingAccountsForLetterbox() {
        // 200x100 image in a 200x200 fit box: scale 1, 50px bars top/bottom.
        Optional<double[]> center = WorkingImage.mapDisplayToWorking(100, 100, 200, 200, 200, 100);
        assertTrue(center.isPresent());
        assertEquals(100.0, center.get()[0], 1e-9);
        assertEquals(50.0, center.get()[1], 1e-9);
        // Letterbox bar maps to nothing.
        assertTrue(WorkingImage.mapDisplayToWorking(100, 10, 200, 200, 200, 100).isEmpty());
        assertTrue(WorkingImage.mapDisplayToWorking(-5, 100, 200, 200, 200, 100).isEmpty());
        assertTrue(WorkingImage.mapDisplayToWorking(0, 0, 0, 200, 200, 100).isEmpty());
    }

    @Test
    void normalizeAndClipSelection() {
        assertArrayEquals(new int[] {10, 20, 30, 40}, WorkingImage.normalizeRect(30, 40, 10, 20));
        // Inside bounds, big enough.
        assertTrue(WorkingImage.clipRect(new int[] {10, 10, 100, 100}, 200, 200).isPresent());
        // Clipped to bounds.
        assertEquals(
                java.util.List.of(0, 0, 200, 200).toString(),
                java.util.Arrays.toString(
                        WorkingImage.clipRect(new int[] {-50, -50, 500, 500}, 200, 200).orElseThrow()));
        // Too small in either dimension: rejected as a click, not a region.
        assertTrue(WorkingImage.clipRect(new int[] {10, 10, 14, 100}, 200, 200).isEmpty());
        assertTrue(WorkingImage.clipRect(new int[] {10, 10, 100, 12}, 200, 200).isEmpty());
        assertTrue(WorkingImage.clipRect(new int[] {300, 300, 310, 310}, 200, 200).isEmpty());
    }

    @Test
    void pointInQuadHitsInsideOnly() {
        double[][] quad = {{10, 10}, {50, 10}, {50, 30}, {10, 30}};
        assertTrue(WorkingImage.pointInQuad(new double[] {30, 20}, quad));
        assertFalse(WorkingImage.pointInQuad(new double[] {0, 0}, quad));
        assertFalse(WorkingImage.pointInQuad(new double[] {60, 20}, quad));
    }

    @Test
    void staleGuardComparesGenerationAndRevision() {
        assertTrue(WorkingImage.isCurrentJob(3, 3, 7, 7));
        assertFalse(WorkingImage.isCurrentJob(3, 4, 7, 7));
        assertFalse(WorkingImage.isCurrentJob(3, 3, 7, 8));
    }

    @Test
    void cropBytesExtractsRows() {
        byte[] img = new byte[4 * 4 * 3];
        for (int i = 0; i < 16; i++) {
            img[i * 3] = (byte) i;
            img[i * 3 + 1] = (byte) (i + 1);
            img[i * 3 + 2] = (byte) (i + 2);
        }
        byte[] cut = WorkingImage.cropBytes(img, 4, 4, new int[] {1, 1, 3, 3});
        assertEquals(2 * 2 * 3, cut.length);
        // Pixel (1,1) -> index 5.
        assertEquals(5, cut[0]);
        assertEquals(6, cut[1]);
        assertEquals(7, cut[2]);
    }

    @Test
    void numericalTextPassesThroughUntouched() throws Exception {
        // The decode layer must preserve digits, signs, decimals, units and
        // exponents verbatim: no autocorrect, no unit rewriting, no guessing.
        java.util.List<String> table =
                java.util.List.of("blank", "-", "7", "8", ".", "5", " ", "m", "/", "s", "^", "2");
        int[] best = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        float[] conf = new float[best.length];
        java.util.Arrays.fill(conf, 0.9f);
        float[][] logits = new float[best.length][12];
        for (int t = 0; t < best.length; t++) {
            java.util.Arrays.fill(logits[t], 0.01f);
            logits[t][best[t]] = conf[t];
        }
        notebookai.ocr.CtcDecoder.Line line = notebookai.ocr.CtcDecoder.decode(logits, table);
        assertEquals("-78.5 m/s^2", line.text());
    }
}
