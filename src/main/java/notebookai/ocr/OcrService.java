package notebookai.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import notebookai.model.ModelConfig;
import notebookai.model.OcrResult;

/**
 * Image-to-text OCR over the bundled PP-OCRv6_small sessions.
 *
 * <p>Pipeline (upstream v3.7.0 rules): EXIF-oriented decode → detector
 * resize/normalize → detector inference → DB quad postprocess → reading-order
 * sort → perspective crops → per-crop resize/normalize → recognizer inference
 * → CTC decode → drop_score filtering. Sessions are created once and reused
 * across images and crops; inference runs sequentially on CPU.
 */
public final class OcrService implements AutoCloseable {

    /** Upstream CLI default: drop regions whose recognition score is lower. */
    public static final double DROP_SCORE = 0.5;

    /**
     * Detector box scoring: upstream {@code score_mode="slow"} (mean of the
     * map over the contour). The tools default "fast" averages over the whole
     * min-area rectangle, which collapses for long tilted/cursive lines
     * (measured 0.341 vs 0.838 on the real handwriting sample) and silently
     * dropped them at box_thresh 0.45. box_thresh itself is unchanged.
     */
    static final DbPostprocess.ScoreMode SCORE_MODE = DbPostprocess.ScoreMode.SLOW;

    /** One-line build identity: application jar, models directory, detector score mode. */
    public String identity() {
        String where;
        try {
            where =
                    java.nio.file.Paths.get(
                                    OcrService.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI())
                            .toString();
        } catch (Exception e) {
            where = "unknown";
        }
        return "build: " + where + " | models: " + modelsDir + " | det score: " + SCORE_MODE
                + (Experiments.any() ? " | " + Experiments.describe() : "");
    }

    private final OrtEnvironment env;
    private final OrtSession det;
    private final OrtSession rec;
    private final List<String> decodeTable;
    private final Path modelsDir;

    public static OcrService open(Path projectRoot) throws OcrException {
        Path modelsDir = ModelConfig.modelsDir(projectRoot);
        try {
            ModelConfig.validateFiles(modelsDir);
            ModelConfig.validateChecksums(modelsDir);
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read model files: " + e.getMessage(), e);
        }
        List<String> decodeTable;
        try {
            decodeTable = ModelConfig.readDecodeTable(modelsDir.resolve(ModelConfig.REC_YML));
        } catch (java.io.IOException e) {
            throw new OcrException("Recognizer config unreadable: " + e.getMessage(), e);
        }
        if (decodeTable.size() < 3) {
            throw new OcrException("Recognizer config has no character_dict entries.");
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession det =
                    env.createSession(
                            modelsDir.resolve(ModelConfig.DET_ONNX).toString(),
                            new OrtSession.SessionOptions());
            OrtSession rec;
            try {
                rec =
                        env.createSession(
                                modelsDir.resolve(ModelConfig.REC_ONNX).toString(),
                                new OrtSession.SessionOptions());
            } catch (OrtException e) {
                try {
                    det.close();
                } catch (OrtException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
            return new OcrService(env, det, rec, decodeTable, modelsDir);
        } catch (OrtException e) {
            throw new OcrException("ONNX Runtime session failed: " + e.getMessage(), e);
        }
    }

    private OcrService(
            OrtEnvironment env, OrtSession det, OrtSession rec, List<String> decodeTable, Path modelsDir) {
        this.env = env;
        this.det = det;
        this.rec = rec;
        this.decodeTable = decodeTable;
        this.modelsDir = modelsDir;
    }

    /** Recognizes one image file. Never downloads models; fails cleanly when absent. */
    public OcrResult recognize(Path imagePath) throws OcrException {
        return recognize(imagePath, null);
    }

    /**
     * Recognizes one image file, optionally writing debug artifacts (source
     * overlay with region boxes + per-region crops) into {@code debugDir}.
     * Debug output is off by default and never goes into the source tree.
     */
    public OcrResult recognize(Path imagePath, Path debugDir) throws OcrException {
        try {
            ImageOps.DecodedImage decoded = ImageOps.decode(imagePath);
            return recognize(decoded.bgr(), decoded.width(), decoded.height(), debugDir, imagePath);
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read image " + imagePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Full detector + recognizer pipeline over in-memory BGR bytes
     * (oriented working-image pixels). Used for full pages and for
     * user-selected regions alike.
     */
    public OcrResult recognize(byte[] bgr, int width, int height, Path debugDir) throws OcrException {
        return recognize(bgr, width, height, debugDir, null);
    }

    private OcrResult recognize(
            byte[] bgr, int width, int height, Path debugDir, Path imagePathForDebug)
            throws OcrException {
        long start = System.nanoTime();
        double decodeMs = 0;

        long t1 = System.nanoTime();
        DetPreprocess.DetInput input =
                DetPreprocess.preprocess(bgr, width, height);
        double detPreMs = (System.nanoTime() - t1) / 1e6;

        long t2 = System.nanoTime();
        float[][] prob = runDetector(input);
        double detInferMs = (System.nanoTime() - t2) / 1e6;

        long t3 = System.nanoTime();
        DebugTrace trace = debugDir == null ? null : new DebugTrace();
        List<DbPostprocess.Quad> quads =
                DbPostprocess.boxesFromBitmap(
                        prob,
                        width,
                        height,
                        trace == null ? null : trace.candidates,
                        SCORE_MODE);
        if (trace != null) {
            trace.srcW = width;
            trace.srcH = height;
            trace.detW = input.w();
            trace.detH = input.h();
            trace.scaleX = (double) width / prob[0].length;
            trace.scaleY = (double) height / prob.length;
            trace.prob = prob;
        }
        List<double[][]> polys = new ArrayList<>();
        List<Double> detScores = new ArrayList<>();
        for (DbPostprocess.Quad q : quads) {
            polys.add(q.points());
            detScores.add(q.score());
        }
        // Detector order; the final reading order is computed after recognition.
        List<double[][]> ordered = polys;
        List<Double> orderedScores = detScores;
        double detPostMs = (System.nanoTime() - t3) / 1e6;

        double cropMs = 0;
        double recMs = 0;
        int n = ordered.size();
        CtcDecoder.Line[] lines = new CtcDecoder.Line[n];
        CropOps.Crop[] crops = new CropOps.Crop[n];
        for (int i = 0; i < n; i++) {
            double[][] poly = ordered.get(i);
            long tc = System.nanoTime();
            crops[i] = CropOps.rotateCrop(bgr, width, height, cropPolygon(quads.get(i), poly));
            cropMs += (System.nanoTime() - tc) / 1e6;

            long tr = System.nanoTime();
            RecPreprocess.RecInput recInput =
                    RecPreprocess.preprocess(crops[i].bgr(), crops[i].width(), crops[i].height());
            lines[i] = recognizeCrop(recInput);
            recMs += (System.nanoTime() - tr) / 1e6;
        }

        // drop_score filter, then tilt-aware reading order over the kept regions.
        long ta = System.nanoTime();
        List<Integer> kept = new ArrayList<>();
        List<double[][]> keptPolys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (lines[i].score() >= DROP_SCORE) {
                kept.add(i);
                keptPolys.add(ordered.get(i));
            }
        }
        List<OcrResult.TextRegion> regions = new ArrayList<>();
        StringBuilder combined = new StringBuilder();
        int[] finalOrder = new int[n];
        java.util.Arrays.fill(finalOrder, -1);
        for (List<Integer> displayLine : ReadingOrder.group(keptPolys)) {
            if (combined.length() > 0) {
                combined.append('\n');
            }
            boolean first = true;
            for (int k : displayLine) {
                int i = kept.get(k);
                double[][] poly = ordered.get(i);
                int[] box = {
                    (int) Math.round(minX(poly)),
                    (int) Math.round(minY(poly)),
                    (int) Math.round(maxX(poly)),
                    (int) Math.round(maxY(poly))
                };
                finalOrder[i] = regions.size();
                regions.add(
                        new OcrResult.TextRegion(
                                copy(poly), box, lines[i].text(), orderedScores.get(i), lines[i].score()));
                if (!first) {
                    combined.append(' ');
                }
                combined.append(lines[i].text());
                first = false;
            }
        }
        if (trace != null) {
            for (int i = 0; i < n; i++) {
                double[][] poly = ordered.get(i);
                int candIdx = -1;
                for (DbPostprocess.Candidate c : trace.candidates) {
                    if (c.quad() == poly) {
                        candIdx = c.index();
                    }
                }
                trace.regions.add(
                        new DebugTrace.RegionTrace(
                                "R" + candIdx,
                                candIdx,
                                finalOrder[i],
                                copy(poly),
                                orderedScores.get(i),
                                crops[i].width(),
                                crops[i].height(),
                                crops[i].rotated(),
                                lines[i].text(),
                                lines[i].score(),
                                lines[i].score() >= DROP_SCORE,
                                crops[i].bgr()));
            }
        }
        double assembleMs = (System.nanoTime() - ta) / 1e6;
        double totalMs = (System.nanoTime() - start) / 1e6;
        OcrResult.Timings timings =
                new OcrResult.Timings(
                        decodeMs, detPreMs, detInferMs, detPostMs, cropMs, recMs, assembleMs, totalMs);
        OcrResult result =
                new OcrResult(width, height, regions, combined.toString(), timings);
        if (debugDir != null) {
            try {
                ImageOps.DecodedImage frame = new ImageOps.DecodedImage(bgr, width, height);
                Path dbgName =
                        imagePathForDebug != null
                                ? imagePathForDebug
                                : java.nio.file.Paths.get("selection.png");
                DebugImages.write(debugDir, dbgName, frame, result);
                trace.finalText = result.combinedText();
                trace.write(debugDir, dbgName, frame, DROP_SCORE);
            } catch (java.io.IOException e) {
                throw new OcrException("Could not write debug output: " + e.getMessage(), e);
            }
        }
        return result;
}

    /**
     * Debug helper: runs ONLY the recognizer (no detection) on an image that
     * is already a text-line crop, using the same preprocessing/decoding as
     * the full pipeline. Returns the raw decode, before any drop_score filter.
     */
    public CtcDecoder.Line recognizeLineImage(Path cropImage) throws OcrException {
        try {
            ImageOps.DecodedImage d = ImageOps.decode(cropImage);
            return recognizeLine(d.bgr(), d.width(), d.height());
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read image " + cropImage + ": " + e.getMessage(), e);
        }
    }

    /**
     * Recognizer-only on in-memory BGR bytes (an already-cropped text line).
     * Same preprocessing and CTC decoding as the full pipeline; no
     * drop_score filtering — the raw decode is returned.
     */
    public CtcDecoder.Line recognizeLine(byte[] bgr, int width, int height) throws OcrException {
        return recognizeCrop(RecPreprocess.preprocess(bgr, width, height));
    }

    private float[][] runDetector(DetPreprocess.DetInput input) throws OcrException {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        FloatBuffer buffer = FloatBuffer.wrap(input.chw());
        try (OnnxTensor tensor =
                OnnxTensor.createTensor(
                        env, buffer, new long[] {input.n(), input.c(), input.h(), input.w()})) {
            inputs.put("x", tensor);
            try (OrtSession.Result result = det.run(inputs)) {
                Object value = result.get(0).getValue();
                if (!(value instanceof float[][][][] maps)) {
                    throw new OcrException(
                            "Unexpected detector output tensor (expected float[1][1][h][w]).");
                }
                return maps[0][0];
            } catch (OrtException e) {
                throw new OcrException("Detector inference failed: " + e.getMessage(), e);
            }
        } catch (OrtException e) {
            throw new OcrException("Detector input tensor failed: " + e.getMessage(), e);
        }
    }

    private CtcDecoder.Line recognizeCrop(RecPreprocess.RecInput input) throws OcrException {
        float[][] logits = runRecognizer(input);
        CtcDecoder.Line greedy = CtcDecoder.decode(logits, decodeTable);
        int beam = Experiments.beamWidth();
        if (beam > 0) { // EXPERIMENT (opt-in): alternate decoder, greedy score kept for filtering.
            return new CtcDecoder.Line(CtcDecoder.decodeBeam(logits, decodeTable, beam), greedy.score());
        }
        return greedy;
    }

    /**
     * Diagnostic: the raw recognizer output [steps][classes] for a crop, using
     * exactly the production preprocessing and session. Production decoding
     * is unchanged (it decodes the same array); this only exposes it.
     */
    public float[][] recognizeLogits(byte[] bgr, int width, int height) throws OcrException {
        return runRecognizer(RecPreprocess.preprocess(bgr, width, height));
    }

    /**
     * Recognizer crop corners. Production: the detected (unclipped) polygon. EXPERIMENT (opt-in,
     * {@link Experiments#cropPad()} &lt; 1): core + lambda * (polygon - core), same corner order.
     */
    private static double[][] cropPolygon(DbPostprocess.Quad quad, double[][] poly) {
        double lambda = Experiments.cropPad();
        if (lambda >= 1.0 || quad.core() == null) {
            return poly;
        }
        double[][] out = new double[4][2];
        for (int k = 0; k < 4; k++) {
            for (int a = 0; a < 2; a++) {
                out[k][a] = quad.core()[k][a] + lambda * (poly[k][a] - quad.core()[k][a]);
            }
        }
        return out;
    }

    private float[][] runRecognizer(RecPreprocess.RecInput input) throws OcrException {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        FloatBuffer buffer = FloatBuffer.wrap(input.chw());
        try (OnnxTensor tensor =
                OnnxTensor.createTensor(
                        env, buffer, new long[] {1, 3, RecPreprocess.REC_HEIGHT, input.width()})) {
            inputs.put("x", tensor);
            try (OrtSession.Result result = rec.run(inputs)) {
                Object value = result.get(0).getValue();
                if (!(value instanceof float[][][] logits)) {
                    throw new OcrException(
                            "Unexpected recognizer output tensor (expected float[1][steps][classes]).");
                }
                return logits[0];
            } catch (OrtException e) {
                throw new OcrException("Recognizer inference failed: " + e.getMessage(), e);
            }
        } catch (OrtException e) {
            throw new OcrException("Recognizer input tensor failed: " + e.getMessage(), e);
        }
    }

    private static double minX(double[][] p) {
        return Math.min(Math.min(p[0][0], p[1][0]), Math.min(p[2][0], p[3][0]));
    }

    private static double maxX(double[][] p) {
        return Math.max(Math.max(p[0][0], p[1][0]), Math.max(p[2][0], p[3][0]));
    }

    private static double minY(double[][] p) {
        return Math.min(Math.min(p[0][1], p[1][1]), Math.min(p[2][1], p[3][1]));
    }

    private static double maxY(double[][] p) {
        return Math.max(Math.max(p[0][1], p[1][1]), Math.max(p[2][1], p[3][1]));
    }

    private static double[][] copy(double[][] p) {
        double[][] out = new double[p.length][2];
        for (int i = 0; i < p.length; i++) {
            out[i][0] = p[i][0];
            out[i][1] = p[i][1];
        }
        return out;
    }

    /** Sessions in use (read-only view for tests/diagnostics). */
    List<String> decodeTable() {
        return Collections.unmodifiableList(decodeTable);
    }

    Path modelsDir() {
        return modelsDir;
    }

    @Override
    public void close() {
        try {
            rec.close();
        } catch (Exception ignored) {
            // Best effort; sessions are already unusable after a failed close.
        }
        try {
            det.close();
        } catch (Exception ignored) {
            // Best effort.
        }
    }
}
