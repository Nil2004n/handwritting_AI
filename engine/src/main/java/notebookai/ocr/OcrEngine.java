package notebookai.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The OCR engine: PP-OCRv6_small detector + recognizer over ONNX Runtime (CPU). Platform-neutral: it consumes a
 * {@link PixelImage} and a {@link ModelAssets}, and knows nothing about files, windows or Android.
 *
 * <p>Pipeline (upstream v3.7.0 rules): detector resize/normalize → detector inference → DB quad postprocess → perspective
 * crops → per-crop resize/normalize → recognizer inference → CTC decode → drop_score filtering → tilt-aware reading order.
 * Sessions are created once by {@link #open} and reused for every image and crop; inference is sequential.
 *
 * <h2>Lifecycle, threading, errors</h2>
 * <ul>
 *   <li><b>Reuse:</b> create one engine and reuse it; creation loads about 31 MB of models and takes a noticeable fraction
 *       of a second.</li>
 *   <li><b>Resources:</b> the engine owns two native ONNX Runtime sessions. {@link #close()} releases them and must be
 *       called when the engine is no longer needed (it is {@link AutoCloseable}). {@link ModelAssets} and
 *       {@link PixelImage} own nothing native.</li>
 *   <li><b>Threads:</b> a recognition call runs entirely on the calling thread and takes a shared lock; {@link #close()}
 *       takes the exclusive lock, so it waits for calls that are already running (there is no cancellation: a call that
 *       has started runs to completion, typically seconds for a photo) and calls made after it throw
 *       {@link OcrException}. Overlapping calls from several threads are not prohibited by the engine but are NOT verified
 *       and multiply peak memory; serialize them. Call from a background thread: a call blocks for seconds.</li>
 *   <li><b>Errors:</b> a failure throws {@link OcrException} (checked; no partial result is returned). Invalid arguments
 *       throw {@link IllegalArgumentException} / {@link NullPointerException}. The engine never calls
 *       {@code System.exit} and never touches the file system after {@link #open}.</li>
 *   <li><b>Results:</b> {@link OcrResult} is immutable and shares no arrays with the engine or with the input image.</li>
 *   <li><b>Host pause/shutdown:</b> finish or let in-flight calls finish, then {@link #close()}; do not close from a
 *       lifecycle callback that must return quickly while a call may still be running (it would wait).</li>
 * </ul>
 */
public final class OcrEngine implements AutoCloseable {

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

    /**
     * Identity saved with a page's OCR result so it can be interpreted later: model name, model file
     * fingerprints, detector score mode and drop score (and any opt-in experiment). No file paths.
     */
    public String persistableIdentity() {
        return ModelAssets.MODEL_IDENTITY + " | " + assets.fingerprint()
                + " | det score: " + SCORE_MODE + " | drop_score " + DROP_SCORE
                + (Experiments.any() ? " | " + Experiments.describe() : "");
    }

    private final OrtEnvironment env;
    private final OrtSession det;
    private final OrtSession rec;
    private final List<String> decodeTable;
    private final ModelAssets assets;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    /**
     * Verifies the model set (all files present, every checksum matches) and creates the two ONNX Runtime sessions.
     *
     * @throws OcrException if the models are missing, corrupt, or ONNX Runtime cannot load them (no native resource leaks)
     */
    public static OcrEngine open(ModelAssets assets) throws OcrException {
        assets.verify();
        return openVerified(assets);
    }

    /** Same as {@link #open} for a set the caller has already verified (avoids hashing 31 MB twice). */
    static OcrEngine openVerified(ModelAssets assets) throws OcrException {
        List<String> decodeTable;
        try {
            decodeTable = assets.decodeTable();
        } catch (java.io.IOException e) {
            throw new OcrException("Recognizer config unreadable: " + e.getMessage(), e);
        }
        if (decodeTable.size() < 3) {
            throw new OcrException("Recognizer config has no character_dict entries.");
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession det = assets.createSession(env, ModelAssets.DET_ONNX);
            OrtSession rec;
            try {
                rec = assets.createSession(env, ModelAssets.REC_ONNX);
            } catch (OrtException | OcrException | RuntimeException e) {
                try {
                    det.close();
                } catch (OrtException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
            return new OcrEngine(env, det, rec, decodeTable, assets);
        } catch (OrtException e) {
            throw new OcrException("ONNX Runtime session failed: " + e.getMessage(), e);
        }
    }

    private OcrEngine(OrtEnvironment env, OrtSession det, OrtSession rec, List<String> decodeTable, ModelAssets assets) {
        this.env = env;
        this.det = det;
        this.rec = rec;
        this.decodeTable = decodeTable;
        this.assets = assets;
    }

    /**
     * Full detector + recognizer pipeline over one display-oriented image (a whole page or a user-selected region).
     * Coordinates in the result refer to {@code image}.
     *
     * @throws OcrException on any inference/decoding failure, or if the engine is closed
     */
    public OcrResult recognize(PixelImage image) throws OcrException {
        return recognize(image, null);
    }

    /** Package-private diagnostic form: additionally records a {@link RecognitionTrace} when {@code trace} is non-null. */
    OcrResult recognize(PixelImage image, RecognitionTrace trace) throws OcrException {
        java.util.Objects.requireNonNull(image, "image");
        lock.readLock().lock();
        try {
            ensureOpen();
            return recognizeLocked(image.bgr(), image.width(), image.height(), trace);
        } finally {
            lock.readLock().unlock();
        }
    }

    private OcrResult recognizeLocked(byte[] bgr, int width, int height, RecognitionTrace trace) throws OcrException {
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
                        new RecognitionTrace.RegionTrace(
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
        return result;
    }

    /**
     * Recognizer only, on an image that is already a single text-line crop. Same preprocessing and CTC decoding as the full
     * pipeline; no drop_score filtering: the raw decode is returned.
     */
    public CtcDecoder.Line recognizeLine(PixelImage crop) throws OcrException {
        java.util.Objects.requireNonNull(crop, "crop");
        lock.readLock().lock();
        try {
            ensureOpen();
            return recognizeCrop(RecPreprocess.preprocess(crop.bgr(), crop.width(), crop.height()));
        } finally {
            lock.readLock().unlock();
        }
    }

    private void ensureOpen() throws OcrException {
        if (closed) {
            throw new OcrException("The OCR engine is closed.");
        }
    }

    float[][] runDetector(DetPreprocess.DetInput input) throws OcrException {
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
     * Diagnostic: the raw recognizer output [steps][classes] for a crop, using exactly the production preprocessing and
     * session. Production decoding is unchanged (it decodes the same array); this only exposes it.
     */
    public float[][] recognizeLogits(PixelImage crop) throws OcrException {
        lock.readLock().lock();
        try {
            ensureOpen();
            return runRecognizer(RecPreprocess.preprocess(crop.bgr(), crop.width(), crop.height()));
        } finally {
            lock.readLock().unlock();
        }
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

    /** Number of recognition calls currently running (tests of the close-waits-for-running-calls guarantee). */
    int activeCalls() {
        return lock.getReadLockCount();
    }

    /** The CTC decode table (blank + dictionary + space), read-only. */
    public List<String> decodeTable() {
        return Collections.unmodifiableList(decodeTable);
    }

    /** Releases both native sessions. Waits for running calls; idempotent; later calls throw {@link OcrException}. */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
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
        } finally {
            lock.writeLock().unlock();
        }
    }
}
