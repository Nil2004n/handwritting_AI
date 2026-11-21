package notebookai.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import notebookai.model.OcrResult;
import notebookai.model.WorkingImage;
import notebookai.ocr.ImageOps;
import notebookai.ocr.OcrException;
import notebookai.ocr.OcrService;

/**
 * Reproducible accuracy harness: runs every case in a manifest through the
 * production {@link OcrService} and reports verbatim CER/WER plus line
 * structure, timings, and (optionally) debug artifacts.
 *
 * <p>Manifest format (TSV, one case per line, {@code #} comments, blank lines
 * ignored):
 * <pre>
 * image-path &lt;TAB&gt; rotate-cw-steps &lt;TAB&gt; straighten-deg &lt;TAB&gt; expected-text
 * </pre>
 * Image paths are resolved against the repository root unless absolute.
 * Expected text uses {@code \n} for line breaks and {@code \\} for a literal
 * backslash. Empty fields default rotate/straighten to 0 and expected to "".
 */
public final class Evaluate {

    /** One manifest case. */
    public record Case(Path image, int rotateCw, double straighten, String expected) {}

    /** Per-case outcome. */
    public record Result(
            Case kase,
            String actual,
            double decodeMs,
            int refChars,
            int hypChars,
            int charEdits,
            double cer,
            int refWords,
            int hypWords,
            int wordEdits,
            double wer,
            int refLines,
            int hypLines,
            double meanLineCer,
            OcrResult.Timings timings) {}

    private Evaluate() {}

    /** Parses manifest text (also used by unit tests). */
    public static List<Case> parseManifest(String text, Path repoRoot) throws OcrException {
        List<Case> cases = new ArrayList<>();
        int lineNo = 0;
        for (String raw : text.split("\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length < 1 || cols[0].isBlank()) {
                throw new OcrException("Manifest line " + lineNo + ": missing image path.");
            }
            Path image = Paths.get(cols[0].strip());
            if (!image.isAbsolute()) {
                image = repoRoot.resolve(image.toString());
            }
            int rotate = 0;
            double straighten = 0;
            try {
                if (cols.length > 1 && !cols[1].isBlank()) {
                    rotate = Integer.parseInt(cols[1].strip());
                }
                if (cols.length > 2 && !cols[2].isBlank()) {
                    straighten = Double.parseDouble(cols[2].strip());
                }
            } catch (NumberFormatException e) {
                throw new OcrException("Manifest line " + lineNo + ": bad rotate/straighten number.");
            }
            String expected = cols.length > 3 ? unescape(cols[3]) : "";
            cases.add(new Case(image, rotate, straighten, expected));
        }
        if (cases.isEmpty()) {
            throw new OcrException("Manifest has no cases.");
        }
        return cases;
    }

    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'n') {
                    out.append('\n');
                    i++;
                    continue;
                }
                if (n == '\\') {
                    out.append('\\');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    public static Result evaluateCase(OcrService service, Case kase, Path debugDir)
            throws OcrException {
        ImageOps.DecodedImage decoded;
        long decodeStart = System.nanoTime();
        try {
            decoded = ImageOps.decode(kase.image());
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read image " + kase.image() + ": " + e.getMessage(), e);
        }
        double decodeMs = (System.nanoTime() - decodeStart) / 1e6;
        WorkingImage working = new WorkingImage(decoded.bgr(), decoded.width(), decoded.height());
        if (kase.rotateCw() != 0) {
            working.rotateSteps(kase.rotateCw());
        }
        if (kase.straighten() != 0) {
            working.setStraighten(kase.straighten());
        }
        WorkingImage.Rendered rendered = working.render();
        OcrResult result = service.recognize(rendered.bgr(), rendered.width(), rendered.height(), debugDir);
        String actual = result.combinedText();
        String expected = kase.expected();
        int[] refChars = expected.codePoints().toArray();
        int[] hypChars = actual.codePoints().toArray();
        int charEdits = EditDistance.levenshtein(refChars, hypChars);
        String[] refWords = EditDistance.tokens(expected);
        String[] hypWords = EditDistance.tokens(actual);
        String[] refLines = EditDistance.lines(expected);
        String[] hypLines = EditDistance.lines(actual);
        double lineCerSum = 0;
        for (int i = 0; i < Math.max(refLines.length, hypLines.length); i++) {
            String r = i < refLines.length ? refLines[i] : "";
            String h = i < hypLines.length ? hypLines[i] : "";
            lineCerSum += EditDistance.cer(r, h);
        }
        return new Result(
                kase,
                actual,
                decodeMs,
                refChars.length,
                hypChars.length,
                charEdits,
                EditDistance.cer(expected, actual),
                refWords.length,
                hypWords.length,
                wordEdits(expected, actual),
                EditDistance.wer(expected, actual),
                refLines.length,
                hypLines.length,
                lineCerSum / Math.max(1, Math.max(refLines.length, hypLines.length)),
                result.timings());
    }

    static int wordEdits(String expected, String actual) {
        String[] r = EditDistance.tokens(expected);
        String[] h = EditDistance.tokens(actual);
        int[] prev = new int[h.length + 1];
        int[] curr = new int[h.length + 1];
        for (int j = 0; j <= h.length; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= r.length; i++) {
            curr[0] = i;
            for (int j = 1; j <= h.length; j++) {
                int cost = r[i - 1].equals(h[j - 1]) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[h.length];
    }

    public static void main(String[] args) {
        Path manifest = null;
        Path debugDir = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--manifest") && i + 1 < args.length) {
                manifest = Paths.get(args[++i]);
            } else if (args[i].equals("--debug-dir") && i + 1 < args.length) {
                debugDir = Paths.get(args[++i]);
            }
        }
        if (manifest == null) {
            System.err.println("Usage: evaluate --manifest <cases.tsv> [--debug-dir <dir>]");
            System.exit(2);
        }
        Path repoRoot = Paths.get("").toAbsolutePath();
        try {
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            List<Case> cases = parseManifest(text, repoRoot);
            long initStart = System.nanoTime();
            try (OcrService service = OcrService.open(repoRoot)) {
                double initMs = (System.nanoTime() - initStart) / 1e6;
                System.out.printf("Cases: %d  init (model load): %.0f ms%n", cases.size(), initMs);
                double cerSum = 0;
                double werSum = 0;
                int n = 0;
                for (Case kase : cases) {
                    Path caseDebug =
                            debugDir == null
                                    ? null
                                    : debugDir.resolve(
                                            kase.image().getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"));
                    OcrResult.Timings t0 = null;
                    Result r;
                    try {
                        r = evaluateCase(service, kase, caseDebug);
                    } catch (OcrException e) {
                        System.out.println("=== " + kase.image() + " ===");
                        System.out.println("ERROR: " + e.getMessage());
                        continue;
                    }
                    t0 = r.timings();
                    System.out.println("=== " + kase.image() + " ===");
                    System.out.printf("transform: rotate=%d straighten=%.1f%n",
                            kase.rotateCw(), kase.straighten());
                    System.out.printf("expected[%d chars, %d words, %d lines]:%n%s%n",
                            r.refChars(), r.refWords(), r.refLines(), kase.expected());
                    System.out.printf("actual  [%d chars, %d words, %d lines]:%n%s%n",
                            r.hypChars(), r.hypWords(), r.hypLines(),
                            r.actual().isEmpty() ? "(empty)" : r.actual());
                    System.out.printf("CER=%.3f (%d edits)  WER=%.3f (%d edits)  lines %d->%d meanLineCer=%.3f%n",
                            r.cer(), r.charEdits(), r.wer(), r.wordEdits(), r.refLines(),
                            r.hypLines(), r.meanLineCer());
                    System.out.printf("timings ms: decode=%.0f detPre=%.0f detInfer=%.0f detPost=%.0f crop=%.0f rec=%.0f total=%.0f%n",
                            r.decodeMs(), t0.detPreMs(), t0.detInferMs(), t0.detPostMs(),
                            t0.cropMs(), t0.recMs(), t0.totalMs());
                    cerSum += r.cer();
                    werSum += r.wer();
                    n++;
                }
                if (n > 0) {
                    System.out.printf("AGGREGATE over %d/%d cases: meanCER=%.3f meanWER=%.3f%n",
                            n, cases.size(), cerSum / n, werSum / n);
                } else {
                    System.out.println("No cases completed.");
                    System.exit(1);
                }
            }
        } catch (OcrException e) {
            System.err.println("Evaluation FAILED: " + e.getMessage());
            System.exit(1);
        } catch (java.io.IOException e) {
            System.err.println("Evaluation FAILED (I/O): " + e.getMessage());
            System.exit(1);
        }
    }
}
