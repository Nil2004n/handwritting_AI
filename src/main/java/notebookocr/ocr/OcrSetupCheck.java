package notebookocr.ocr;

import java.nio.file.Path;
import java.nio.file.Paths;

/** CLI entry for {@code ./gradlew verifyOcrSetup}. Exit 0 on success, 1 with a clear error otherwise. */
public final class OcrSetupCheck {

    private OcrSetupCheck() {}

    public static void main(String[] args) {
        Path projectRoot = Paths.get("").toAbsolutePath();
        try {
            System.out.print(OcrSetup.verify(projectRoot));
        } catch (OcrException e) {
            System.err.println("OCR setup FAILED: " + e.getMessage());
            System.exit(1);
        }
    }
}
