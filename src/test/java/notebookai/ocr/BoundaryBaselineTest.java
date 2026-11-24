package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import notebookai.model.WorkingImage;
import org.junit.jupiter.api.Test;

/**
 * Pins the PURE-JAVA pipeline stages (image decode, working image, detector input tensor) to the hashes recorded BEFORE the
 * engine was separated (src/test/resources/fixtures/boundary-baseline-public.txt, produced by {@code boundaryFingerprint}).
 * These stages involve no ONNX Runtime, so they must be bit-identical on every platform and JVM; the ONNX-dependent stages
 * (detector map, logits) are compared by the {@code boundaryFingerprint} diff procedure documented in the handoff, because
 * native backends may legitimately differ in the last bits.
 */
class BoundaryBaselineTest {

    private static final Path ROOT = Paths.get("").toAbsolutePath();

    /** Baseline lines of one case, up to the next blank line. */
    private static List<String> baseline(String caseName) throws Exception {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : Files.readAllLines(ROOT.resolve("src/test/resources/fixtures/boundary-baseline-public.txt"))) {
            if (line.startsWith("CASE " + caseName + " ")) {
                in = true;
            } else if (in && line.isBlank()) {
                break;
            }
            if (in) {
                out.add(line);
            }
        }
        assertTrue(!out.isEmpty(), "baseline case missing: " + caseName);
        return out;
    }

    private static String line(List<String> lines, String prefix) {
        return lines.stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }

    private void check(String caseName, String imagePath) throws Exception {
        List<String> base = baseline(caseName);
        PixelImage d = DesktopImageIO.decode(ROOT.resolve(imagePath));
        assertEquals(line(base, "decoded "), "decoded " + d.width() + "x" + d.height() + " " + BoundaryFingerprint.pixelHash(d.bgr(), d.width(), d.height()));
        WorkingImage.Rendered r = new WorkingImage(d.bgr(), d.width(), d.height()).render();
        assertEquals(line(base, "working "), "working " + r.width() + "x" + r.height() + " " + BoundaryFingerprint.pixelHash(r.bgr(), r.width(), r.height()));
        DetPreprocess.DetInput in = DetPreprocess.preprocess(r.bgr(), r.width(), r.height());
        assertEquals(line(base, "det_input "), "det_input n=" + in.n() + " c=" + in.c() + " h=" + in.h() + " w=" + in.w() + " "
                + BoundaryFingerprint.floatHash(in.chw(), in.n() + "," + in.c() + "," + in.h() + "," + in.w()));
    }

    @Test
    void renderedPrintFixtureStagesMatchTheRecordedBaseline() throws Exception {
        check("print-900x500.png", "src/test/resources/fixtures/print-900x500.png");
    }

    @Test
    void exifRotatedHandwritingSampleStagesMatchTheRecordedBaseline() throws Exception {
        check("handwriting-test.jpg", "samples/handwriting-test.jpg");
    }
}
