package notebookocr.ui;

import java.nio.file.Paths;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import notebookocr.model.ModelConfig;
import notebookocr.ocr.OcrException;
import notebookocr.ocr.OcrSetup;

/**
 * Minimal Phase 0 window: setup status, model identity, a verification
 * button, and a results area. Verification runs on a background thread so
 * the window stays responsive. No extraction, no camera, no persistence.
 */
public class NotebookApp extends Application {

    @Override
    public void start(Stage stage) {
        Label title = new Label("NotebookOCR");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        Label status = new Label("Setup status: not verified yet.");
        Label model = new Label("Model: " + ModelConfig.MODEL_IDENTITY);
        model.setWrapText(true);

        TextArea results = new TextArea();
        results.setEditable(false);
        results.setWrapText(true);
        results.setPrefRowCount(18);

        Button verify = new Button("Verify OCR Setup");
        verify.setOnAction(event -> {
            verify.setDisable(true);
            status.setText("Setup status: verifying…");
            results.setText("");
            Thread worker = new Thread(() -> {
                String text;
                boolean success;
                try {
                    text = OcrSetup.verify(Paths.get("").toAbsolutePath());
                    success = true;
                } catch (OcrException e) {
                    text = "FAILED: " + e.getMessage();
                    success = false;
                }
                final String outcome = text;
                final boolean ok = success;
                Platform.runLater(() -> {
                    results.setText(outcome);
                    status.setText(ok ? "Setup status: valid." : "Setup status: FAILED.");
                    verify.setDisable(false);
                });
            });
            worker.setDaemon(true);
            worker.start();
        });

        VBox root = new VBox(10, title, status, model, verify, results);
        root.setStyle("-fx-padding: 16;");
        stage.setTitle("NotebookOCR");
        stage.setScene(new Scene(root, 560, 520));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
