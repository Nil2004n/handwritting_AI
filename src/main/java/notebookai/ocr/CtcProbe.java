package notebookai.ocr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import notebookai.eval.Alignment;
import notebookai.eval.Evaluate;
import notebookai.model.OcrResult;
import notebookai.model.WorkingImage;

/**
 * Diagnostic only ({@code ./gradlew ctcProbe -PevalManifest=... [-PdebugDir=...]}).
 * For each manifest case it runs the production page pipeline, then for every
 * recognized region re-runs the recognizer on the identical crop to expose the
 * raw output: tensor shape, valid time steps, emitted tokens with their frames,
 * top-2 alternatives, and where the EXPECTED character sits for each aligned
 * error. It asserts that decoding the exposed logits reproduces the production
 * text, so it cannot drift from production. Values are the model's raw output
 * numbers, NOT calibrated probabilities; nothing here selects or alters output.
 */
public final class CtcProbe {

    private record Token(int t0, int t1, int cls, float p, int charOffset, int charLen) {}

    private CtcProbe() {}

    public static void main(String[] args) throws Exception {
        String manifest = null;
        String debug = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--manifest") && i + 1 < args.length) {
                manifest = args[++i];
            } else if (args[i].equals("--debug-dir") && i + 1 < args.length) {
                debug = args[++i];
            }
        }
        if (manifest == null) {
            System.err.println("Usage: ctcProbe -PevalManifest=eval/cases.tsv [-PdebugDir=DIR]");
            System.exit(2);
        }
        Path root = Paths.get("").toAbsolutePath();
        List<Evaluate.Case> cases =
                Evaluate.parseManifest(Files.readString(Paths.get(manifest)), root);
        try (OcrService service = OcrService.open(root)) {
            System.out.println(service.identity());
            for (Evaluate.Case c : cases) {
                probe(service, c, debug == null ? null : Paths.get(debug));
            }
        }
    }

    private static void probe(OcrService service, Evaluate.Case c, Path debugDir) throws Exception {
        ImageOps.DecodedImage d = ImageOps.decode(c.image());
        WorkingImage wi = new WorkingImage(d.bgr(), d.width(), d.height());
        if (c.rotateCw() != 0) {
            wi.rotateSteps(c.rotateCw());
        }
        if (c.straighten() != 0) {
            wi.setStraighten(c.straighten());
        }
        WorkingImage.Rendered r = wi.render();
        OcrResult result = service.recognize(r.bgr(), r.width(), r.height(), null);
        List<String> table = service.decodeTable();
        Map<String, Integer> classOf = new HashMap<>();
        for (int i = 0; i < table.size(); i++) {
            classOf.putIfAbsent(table.get(i), i);
        }
        System.out.println("\n=== " + c.image() + " rotate=" + c.rotateCw() + " straighten=" + c.straighten()
                + " working=" + r.width() + "x" + r.height());
        List<OcrResult.TextRegion> regions = result.regions();
        List<float[][]> logits = new ArrayList<>();
        List<List<Token>> tokens = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            OcrResult.TextRegion reg = regions.get(i);
            CropOps.Crop crop = CropOps.rotateCrop(r.bgr(), r.width(), r.height(), reg.polygon());
            RecPreprocess.RecInput in = RecPreprocess.preprocess(crop.bgr(), crop.width(), crop.height());
            float[][] lg = service.recognizeLogits(crop.bgr(), crop.width(), crop.height());
            List<Token> toks = tokenize(lg, table);
            StringBuilder sb = new StringBuilder();
            for (Token t : toks) {
                sb.append(table.get(t.cls()));
            }
            boolean consistent = sb.toString().equals(reg.text());
            double minSum = 1e9;
            double maxSum = -1e9;
            int blankFrames = 0;
            for (float[] step : lg) {
                double sum = 0;
                int best = 0;
                for (int k = 0; k < step.length; k++) {
                    sum += step[k];
                    if (step[k] > step[best]) {
                        best = k;
                    }
                }
                minSum = Math.min(minSum, sum);
                maxSum = Math.max(maxSum, sum);
                if (best == 0) {
                    blankFrames++;
                }
            }
            int resizedW = (int) Math.min(in.width(), Math.ceil(48.0 * crop.width() / crop.height()));
            System.out.printf(Locale.ROOT,
                    "R%d \"%s\" det=%.3f rec=%.3f | crop %dx%d%s -> rec input 3x48x%d (resized width %d, zero-pad %d) | "
                            + "output [%d steps x %d classes] (width/steps=%.1f) valid steps~%d, blank-argmax frames %d, "
                            + "row-sum min/max %.4f/%.4f | decode-consistent=%s%n",
                    i, reg.text(), reg.detScore(), reg.recScore(), crop.width(), crop.height(),
                    crop.rotated() ? " (rot90)" : "", in.width(), resizedW, in.width() - resizedW,
                    lg.length, lg[0].length, (double) in.width() / lg.length,
                    (int) Math.ceil((double) resizedW * lg.length / in.width()), blankFrames, minSum, maxSum,
                    consistent);
            if (!consistent) {
                System.out.println("  !! MISMATCH: probe decode=\"" + sb + "\"");
            }
            if (debugDir != null) {
                Files.createDirectories(debugDir);
                ImageIO.write(ImageOps.toBufferedImage(crop.bgr(), crop.width(), crop.height()), "png",
                        debugDir.resolve("probe_R" + i + "_crop.png").toFile());
            }
            logits.add(lg);
            tokens.add(toks);
        }

        // Display lines exactly as production assembles them.
        List<double[][]> polys = new ArrayList<>();
        for (OcrResult.TextRegion reg : regions) {
            polys.add(reg.polygon());
        }
        List<List<Integer>> lines = ReadingOrder.group(polys);
        String[] expLines = c.expected().split("\n", -1);
        for (int li = 0; li < lines.size() && li < expLines.length; li++) {
            StringBuilder act = new StringBuilder();
            List<int[]> owner = new ArrayList<>(); // per actual char: {region, charOffsetInRegion}
            for (int k = 0; k < lines.get(li).size(); k++) {
                int ri = lines.get(li).get(k);
                if (k > 0) {
                    act.append(' ');
                    owner.add(new int[] {-1, -1});
                }
                int off = 0;
                for (int cp : regions.get(ri).text().codePoints().toArray()) {
                    act.appendCodePoint(cp);
                    owner.add(new int[] {ri, off++});
                }
            }
            List<Alignment.Op> ops =
                    Alignment.align(expLines[li].codePoints().toArray(), act.toString().codePoints().toArray());
            System.out.printf("%nLine %d expected: %s%n        actual  : %s%n        edits=%d%n", li + 1,
                    expLines[li], act, Alignment.errors(ops));
            for (Alignment.Op op : ops) {
                if (op.type() == '=') {
                    continue;
                }
                explain(op, owner, tokens, logits, table, classOf);
            }
        }
        if (lines.size() != expLines.length) {
            System.out.println("NOTE: display lines=" + lines.size() + " expected lines=" + expLines.length);
        }
    }

    private static void explain(Alignment.Op op, List<int[]> owner, List<List<Token>> tokens,
            List<float[][]> logits, List<String> table, Map<String, Integer> classOf) {
        String ref = op.ref() < 0 ? "-" : new String(Character.toChars(op.ref()));
        String hyp = op.hyp() < 0 ? "-" : new String(Character.toChars(op.hyp()));
        Integer expCls = op.ref() < 0 ? null : classOf.get(ref);
        String kind = switch (op.type()) {
            case 'S' -> "SUBSTITUTION";
            case 'I' -> "INSERTION";
            default -> "DELETION";
        };
        StringBuilder sb = new StringBuilder(String.format("  %s expected '%s' got '%s'", kind, ref, hyp));
        if (op.hypIdx() >= 0) {
            int[] own = owner.get(op.hypIdx());
            if (own[0] >= 0) {
                List<Token> toks = tokens.get(own[0]);
                Token tk = null;
                for (Token t : toks) {
                    if (own[1] >= t.charOffset() && own[1] < t.charOffset() + t.charLen()) {
                        tk = t;
                    }
                }
                float[][] lg = logits.get(own[0]);
                sb.append(String.format(" | region R%d token frames %d-%d, top1 %.3f", own[0], tk.t0(), tk.t1(), tk.p()));
                int bestFrame = tk.t0();
                for (int t = tk.t0(); t <= tk.t1(); t++) {
                    if (lg[t][tk.cls()] > lg[bestFrame][tk.cls()]) {
                        bestFrame = t;
                    }
                }
                sb.append(" | top-3 at frame ").append(bestFrame).append(": ").append(top(lg[bestFrame], table, 3));
                if (expCls != null) {
                    sb.append(expectation(lg, expCls, tk.t0(), tk.t1(), ref));
                }
            }
        } else if (op.type() == 'D' && op.refIdx() >= 0) {
            sb.append(" | (no emitted token; see neighbouring frames of the aligned region)");
        }
        System.out.println(sb);
    }

    private static String expectation(float[][] lg, int expCls, int t0, int t1, String ref) {
        float within = -1;
        int rank = Integer.MAX_VALUE;
        for (int t = t0; t <= t1; t++) {
            within = Math.max(within, lg[t][expCls]);
            int rk = 1;
            for (float v : lg[t]) {
                if (v > lg[t][expCls]) {
                    rk++;
                }
            }
            rank = Math.min(rank, rk);
        }
        float near = -1;
        for (int t = Math.max(0, t0 - 3); t <= Math.min(lg.length - 1, t1 + 3); t++) {
            near = Math.max(near, lg[t][expCls]);
        }
        return String.format(Locale.ROOT, " | expected '%s': max %.4f within token frames (best rank %d), max %.4f within +-3 frames",
                ref, within, rank, near);
    }

    private static String top(float[] step, List<String> table, int k) {
        Integer[] idx = new Integer[step.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(step[b], step[a]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k; i++) {
            String name = idx[i] == 0 ? "<blank>" : "'" + table.get(idx[i]) + "'";
            sb.append(String.format(Locale.ROOT, "%s=%.3f ", name, step[idx[i]]));
        }
        return sb.toString().trim();
    }

    /** Same collapse rule as CtcDecoder, but keeping frame spans for diagnostics. */
    private static List<Token> tokenize(float[][] lg, List<String> table) {
        List<Token> out = new ArrayList<>();
        int prev = -1;
        int charOff = 0;
        Token open = null;
        int openStart = 0;
        for (int t = 0; t < lg.length; t++) {
            int best = 0;
            for (int c = 1; c < lg[t].length; c++) {
                if (lg[t][c] > lg[t][best]) {
                    best = c;
                }
            }
            if (best != prev) {
                if (open != null) {
                    out.add(open);
                    open = null;
                }
                if (best != 0) {
                    int len = table.get(best).codePointCount(0, table.get(best).length());
                    open = new Token(t, t, best, lg[t][best], charOff, len);
                    charOff += len;
                    openStart = t;
                }
            } else if (open != null) {
                open = new Token(openStart, t, open.cls(), open.p(), open.charOffset(), open.charLen());
            }
            prev = best;
        }
        if (open != null) {
            out.add(open);
        }
        return out;
    }
}
