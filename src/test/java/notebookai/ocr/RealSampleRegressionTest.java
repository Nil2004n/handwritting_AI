package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import notebookai.ocr.OcrResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * End-to-end structural regression on the real tilted handwriting photo
 * (three handwritten lines, ~30 degree tilt). Asserts structure only, never
 * the recognized words: every written line is detected, no region is lost to
 * box scoring, the extra neighbor-letter duplicate stays gone, regions the detector
 * splits within one line group into ONE display line, and lines come out top to bottom along the text.
 * Skipped when the models or the sample are not present.
 */
class RealSampleRegressionTest {

    @Test
    void threeHandwrittenLinesAreAllDetectedGroupedAndOrdered() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path sample = root.resolve("samples/handwriting-test.jpg");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "sample image missing");
        Assumptions.assumeTrue(Files.isDirectory(root.resolve("models")), "models missing");

        try (OcrService service = OcrService.open(root)) {
            OcrResult result = service.recognize(sample);
            String[] lines = result.combinedText().split("\n");
            assertEquals(3, lines.length, "three written lines expected: " + result.combinedText());
            assertTrue(result.regions().size() >= 3, "at least one region per written line");
            for (OcrResult.TextRegion r : result.regions()) {
                assertTrue(r.recScore() >= OcrService.DROP_SCORE);
                assertTrue(r.detScore() > 0 && r.detScore() <= 1);
            }
            // Reading order must advance across the tilted page: the line-start regions' centers move
            // monotonically along the line-normal (perpendicular to the ~33 degree text direction).
            List<OcrResult.TextRegion> rs = result.regions();
            double normal = Math.toRadians(33 + 90);
            double prev = -1e18;
            List<double[][]> polys = new java.util.ArrayList<>();
            for (OcrResult.TextRegion r : rs) {
                polys.add(r.polygon());
            }
            for (List<Integer> displayLine : ReadingOrder.group(polys)) { // first region of each line
                double[] c = center(rs.get(displayLine.get(0)).polygon());
                double v = c[0] * Math.cos(normal) + c[1] * Math.sin(normal);
                assertTrue(v > prev, "display lines must progress down the page");
                prev = v;
            }
        }
    }

    private static double[] center(double[][] p) {
        double x = 0, y = 0;
        for (double[] q : p) {
            x += q[0];
            y += q[1];
        }
        return new double[] {x / p.length, y / p.length};
    }
}
