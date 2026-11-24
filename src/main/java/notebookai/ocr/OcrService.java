package notebookai.ocr;

import java.nio.file.Path;
import java.util.List;
import notebookai.model.ModelConfig;

/**
 * DESKTOP facade over the portable {@link OcrEngine}: resolves the models directory with the desktop conventions
 * (environment variable, system property, project root: see {@link ModelConfig#modelsDir}), decodes image files with the
 * AWT adapter, and writes optional debug artifacts. The recognition itself is {@link OcrEngine}'s; every method here keeps
 * the behavior and signature it had before the engine was separated.
 */
public final class OcrService implements AutoCloseable {

    /** Upstream CLI default: drop regions whose recognition score is lower. */
    public static final double DROP_SCORE = OcrEngine.DROP_SCORE;

    static final DbPostprocess.ScoreMode SCORE_MODE = OcrEngine.SCORE_MODE;

    private final OcrEngine engine;
    private final Path modelsDir;

    private OcrService(OcrEngine engine, Path modelsDir) {
        this.engine = engine;
        this.modelsDir = modelsDir;
    }

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

    /** See {@link OcrEngine#persistableIdentity()}. */
    public String persistableIdentity() {
        return engine.persistableIdentity();
    }

    public static OcrService open(Path projectRoot) throws OcrException {
        Path modelsDir = ModelConfig.modelsDir(projectRoot);
        try {
            ModelConfig.validateFiles(modelsDir);
            ModelConfig.validateChecksums(modelsDir);
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read model files: " + e.getMessage(), e);
        }
        // Files and checksums were just verified with the desktop messages; skip the engine's second 31 MB hash pass.
        return new OcrService(OcrEngine.openVerified(ModelAssets.fromDirectory(modelsDir)), modelsDir);
    }

    /** Recognizes one image file. Never downloads models; fails cleanly when absent. */
    public OcrResult recognize(Path imagePath) throws OcrException {
        return recognize(imagePath, null);
    }

    /**
     * Recognizes one image file, optionally writing debug artifacts (source overlay with region boxes + per-region crops)
     * into {@code debugDir}. Debug output is off by default and never goes into the source tree.
     */
    public OcrResult recognize(Path imagePath, Path debugDir) throws OcrException {
        try {
            PixelImage decoded = DesktopImageIO.decode(imagePath);
            return recognize(decoded, debugDir, imagePath);
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read image " + imagePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Full detector + recognizer pipeline over in-memory BGR bytes (oriented working-image pixels). Used for full pages and
     * for user-selected regions alike.
     */
    public OcrResult recognize(byte[] bgr, int width, int height, Path debugDir) throws OcrException {
        return recognize(PixelImage.wrapBgr(bgr, width, height), debugDir, null);
    }

    private OcrResult recognize(PixelImage image, Path debugDir, Path imagePathForDebug) throws OcrException {
        RecognitionTrace trace = debugDir == null ? null : new RecognitionTrace();
        if (trace != null) {
            trace.srcW = image.width();
            trace.srcH = image.height();
        }
        OcrResult result = engine.recognize(image, trace);
        if (debugDir != null) {
            try {
                Path dbgName = imagePathForDebug != null ? imagePathForDebug : java.nio.file.Paths.get("selection.png");
                DebugImages.write(debugDir, dbgName, image, result);
                trace.finalText = result.combinedText();
                DebugTrace.write(trace, debugDir, dbgName, image, DROP_SCORE);
            } catch (java.io.IOException e) {
                throw new OcrException("Could not write debug output: " + e.getMessage(), e);
            }
        }
        return result;
    }

    /**
     * Debug helper: runs ONLY the recognizer (no detection) on an image that is already a text-line crop, using the same
     * preprocessing/decoding as the full pipeline. Returns the raw decode, before any drop_score filter.
     */
    public CtcDecoder.Line recognizeLineImage(Path cropImage) throws OcrException {
        try {
            return engine.recognizeLine(DesktopImageIO.decode(cropImage));
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read image " + cropImage + ": " + e.getMessage(), e);
        }
    }

    /** Recognizer-only on in-memory BGR bytes (an already-cropped text line); raw decode, no drop_score filtering. */
    public CtcDecoder.Line recognizeLine(byte[] bgr, int width, int height) throws OcrException {
        return engine.recognizeLine(PixelImage.wrapBgr(bgr, width, height));
    }

    float[][] runDetector(DetPreprocess.DetInput input) throws OcrException {
        return engine.runDetector(input);
    }

    /** Diagnostic: the raw recognizer output [steps][classes] for a crop (production preprocessing and session). */
    public float[][] recognizeLogits(byte[] bgr, int width, int height) throws OcrException {
        return engine.recognizeLogits(PixelImage.wrapBgr(bgr, width, height));
    }

    /** Sessions in use (read-only view for tests/diagnostics). */
    public List<String> decodeTable() {
        return engine.decodeTable();
    }

    Path modelsDir() {
        return modelsDir;
    }

    @Override
    public void close() {
        engine.close();
    }
}
