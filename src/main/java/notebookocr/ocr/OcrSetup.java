package notebookocr.ocr;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.file.Path;
import java.util.Map;
import notebookocr.model.ModelConfig;

/**
 * Phase 0 OCR gate: validates assets, initializes ONNX Runtime, loads the
 * detector and recognizer sessions, prints their I/O metadata, and closes
 * everything. No image is decoded, no inference runs, no network is used.
 *
 * <p>Successful loading proves the models are present and ORT-compatible. It
 * does NOT prove working OCR — real image-to-text comes in a later phase.
 */
public final class OcrSetup {

    private OcrSetup() {}

    public static String verify(Path projectRoot) throws OcrException {
        Path modelsDir = ModelConfig.modelsDir(projectRoot);
        StringBuilder log = new StringBuilder();
        log.append("Model: ").append(ModelConfig.MODEL_IDENTITY).append('\n');
        log.append("Models dir: ").append(modelsDir.toAbsolutePath()).append('\n');

        ModelConfig.validateFiles(modelsDir);
        log.append("Files present: det.onnx, det.yml, rec.onnx, rec.yml, checksums.sha256\n");
        try {
            ModelConfig.validateChecksums(modelsDir);
        } catch (java.io.IOException e) {
            throw new OcrException("Could not read model files: " + e.getMessage(), e);
        }
        log.append("Checksums: OK\n");

        int dictEntries;
        try {
            dictEntries = ModelConfig.countRecCharacters(modelsDir.resolve(ModelConfig.REC_YML));
        } catch (java.io.IOException e) {
            throw new OcrException("Recognizer config unreadable: " + e.getMessage(), e);
        }
        if (dictEntries <= 0) {
            throw new OcrException("Recognizer config has no character_dict entries.");
        }
        log.append("Recognizer dictionary entries: ").append(dictEntries).append('\n');

        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
                OrtSession det = env.createSession(
                        modelsDir.resolve(ModelConfig.DET_ONNX).toString(),
                        new OrtSession.SessionOptions());
                OrtSession rec = env.createSession(
                        modelsDir.resolve(ModelConfig.REC_ONNX).toString(),
                        new OrtSession.SessionOptions())) {
            log.append("ONNX Runtime: sessions created (CPU)\n");
            describeSession(log, "detector", det);
            describeSession(log, "recognizer", rec);
        } catch (OrtException e) {
            throw new OcrException("ONNX Runtime session failed: " + e.getMessage(), e);
        }
        log.append("Native resources closed. Setup valid.\n");
        return log.toString();
    }

    private static void describeSession(StringBuilder log, String name, OrtSession session)
            throws OrtException {
        log.append("[").append(name).append("]\n");
        appendIO(log, "input", session.getInputInfo());
        appendIO(log, "output", session.getOutputInfo());
    }

    private static void appendIO(StringBuilder log, String kind, Map<String, NodeInfo> infoMap)
            throws OrtException {
        for (Map.Entry<String, NodeInfo> entry : infoMap.entrySet()) {
            String detail;
            if (entry.getValue().getInfo() instanceof TensorInfo tensor) {
                detail = "shape=" + java.util.Arrays.toString(tensor.getShape()) + " type=" + tensor.type;
            } else {
                detail = entry.getValue().getInfo().toString();
            }
            log.append("  ").append(kind).append(" '").append(entry.getKey()).append("': ")
                    .append(detail).append('\n');
        }
    }
}
