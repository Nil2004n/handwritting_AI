package notebookai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import notebookai.ocr.OcrException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Setup validation: clear errors when required assets are absent or corrupt. */
class ModelConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void missingAssetsProduceClearError() throws Exception {
        Path empty = tempDir.resolve("empty-models");
        Files.createDirectories(empty);
        OcrException failure =
                assertThrows(OcrException.class, () -> ModelConfig.validateFiles(empty));
        assertTrue(failure.getMessage().contains("missing files"), failure.getMessage());
        assertTrue(failure.getMessage().contains("det.onnx"), failure.getMessage());
        assertTrue(failure.getMessage().contains("download-models"), failure.getMessage());
    }

    @Test
    void checksumMismatchProducesClearError() throws Exception {
        Path dir = tempDir.resolve("bad-models");
        Files.createDirectories(dir);
        Files.write(dir.resolve("det.onnx"), new byte[] {1, 2, 3});
        Files.write(
                dir.resolve(ModelConfig.CHECKSUMS),
                List.of("0000000000000000000000000000000000000000000000000000000000000000  det.onnx"),
                StandardCharsets.UTF_8);
        OcrException failure =
                assertThrows(OcrException.class, () -> ModelConfig.validateChecksums(dir));
        assertTrue(failure.getMessage().contains("checksum"), failure.getMessage());
    }

    @Test
    void recognizerDictionaryIsCounted() throws Exception {
        Path yml = tempDir.resolve("rec.yml");
        Files.write(
                yml,
                List.of(
                        "PostProcess:",
                        "  name: CTCLabelDecode",
                        "  character_dict:",
                        "  - 'a'",
                        "  - 'b'",
                        "  - 'c'"),
                StandardCharsets.UTF_8);
        assertEquals(3, ModelConfig.countRecCharacters(yml));
    }
}
