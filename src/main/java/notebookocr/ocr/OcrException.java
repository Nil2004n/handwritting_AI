package notebookocr.ocr;

/** Checked failure for OCR setup/validation problems with actionable messages. */
public class OcrException extends Exception {

    public OcrException(String message) {
        super(message);
    }

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
