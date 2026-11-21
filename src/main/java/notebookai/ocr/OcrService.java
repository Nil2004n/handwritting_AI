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
        long start = System.nanoTime();
        ImageOps.DecodedImage decoded;
        try {
            long t0 = System.nanoTime();
            decoded = ImageOps.decode(imagePath);
            double decodeMs = (System.nanoTime() - t0) / 1e6;

            long t1 = System.nanoTime();
            DetPreprocess.DetInput input =
                    DetPreprocess.preprocess(decoded.bgr(), decoded.width(), decoded.height());
            double detPreMs = (System.nanoTime() - t1) / 1e6;

            long t2 = System.nanoTime();
            float[][] prob = runDetector(input);
            double detInferMs = (System.nanoTime() - t2) / 1e6;

            long t3 = System.nanoTime();
            List<DbPostprocess.Quad> quads =
                    DbPostprocess.boxesFromBitmap(prob, decoded.width(), decoded.height());
            List<double[][]> polys = new ArrayList<>();
            List<Double> detScores = new ArrayList<>();
            for (DbPostprocess.Quad q : quads) {
                polys.add(q.points());
                detScores.add(q.score());
            }
            List<double[][]> ordered = CropOps.sortedBoxes(polys);
            // Realign detector scores with the sorted order.
            List<Double> orderedScores = new ArrayList<>();
            for (double[][] poly : ordered) {
                orderedScores.add(detScores.get(polys.indexOf(poly)));
            }
            double detPostMs = (System.nanoTime() - t3) / 1e6;

            List<OcrResult.TextRegion> regions = new ArrayList<>();
            double cropMs = 0;
            double recMs = 0;
            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < ordered.size(); i++) {
                double[][] poly = ordered.get(i);
                long tc = System.nanoTime();
                CropOps.Crop crop =
                        CropOps.rotateCrop(decoded.bgr(), decoded.width(), decoded.height(), poly);
                cropMs += (System.nanoTime() - tc) / 1e6;

                long tr = System.nanoTime();
                RecPreprocess.RecInput recInput = RecPreprocess.preprocess(crop.bgr(), crop.width(), crop.height());
                CtcDecoder.Line line = recognizeCrop(recInput);
                recMs += (System.nanoTime() - tr) / 1e6;

                if (line.score() < DROP_SCORE) {
                    continue;
                }
                int[] box = {
                    (int) Math.round(minX(poly)),
                    (int) Math.round(minY(poly)),
                    (int) Math.round(maxX(poly)),
                    (int) Math.round(maxY(poly))
                };
                regions.add(
                        new OcrResult.TextRegion(
                                copy(poly), box, line.text(), orderedScores.get(i), line.score()));
                if (combined.length() > 0) {
                    combined.append('\n');
                }
                combined.append(line.text());
            }
            double assembleMs = 0; // Assembly is the loop above; timed inline.
            double totalMs = (System.nanoTime() - start) / 1e6;
            OcrResult.Timings timings =
                    new OcrResult.Timings(
                            decodeMs, detPreMs, detInferMs, detPostMs, cropMs, recMs, assembleMs, totalMs);
            OcrResult result =
                    new OcrResult(decoded.width(), decoded.height(), regions, combined.toString(), timings);
            if (debugDir != null) {
                DebugImages.write(debugDir, imagePath, decoded, result);
            }
            return result;
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read image " + imagePath + ": " + e.getMessage(), e);
        }
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
                return CtcDecoder.decode(logits[0], decodeTable);
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
