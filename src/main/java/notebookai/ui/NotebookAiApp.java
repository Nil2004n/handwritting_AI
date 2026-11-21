package notebookai.ui;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import notebookai.model.ModelConfig;
import notebookai.model.OcrResult;
import notebookai.ocr.OcrException;
import notebookai.ocr.OcrService;

/**
 * Phase 1 window: open image → preview → extract text → edit → copy.
 *
 * <p>Extraction runs on a background thread; all UI updates happen on the
 * JavaFX application thread. A second extraction cannot start while one is
 * running, the image cannot be changed mid-job, and re-extracting over
 * edited text asks for confirmation first.
 */
public class NotebookAiApp extends Application {

    private Label statusLabel;
    private ImageView preview;
    private TextArea transcript;
    private TextArea rawOutput;
    private Button openButton;
    private Button extractButton;
    private Button copyButton;

    private Path currentImage;
    private boolean jobRunning;
    private boolean userEdited;
    private String lastRawText = "";
    private volatile String lastIdentity = "";

    @Override
    public void start(Stage stage) {
        Label title = new Label("notebook-ai");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label model = new Label("Model: " + ModelConfig.MODEL_IDENTITY);
        model.setWrapText(true);
        statusLabel = new Label("Open an image (JPG or PNG) to begin.");

        preview = new ImageView();
        preview.setPreserveRatio(true);
        preview.setFitWidth(520);
        preview.setFitHeight(300);

        openButton = new Button("Open Image…");
        openButton.setOnAction(e -> openImage(stage));
        extractButton = new Button("Extract Text");
        extractButton.setDisable(true);
        extractButton.setOnAction(e -> extractText());
        copyButton = new Button("Copy Text");
        copyButton.setDisable(true);
        copyButton.setOnAction(e -> copyText());
        HBox buttons = new HBox(10, openButton, extractButton, copyButton);

        Label transcriptLabel = new Label("Transcript (editable):");
        transcript = new TextArea();
        transcript.setWrapText(true);
        transcript.setPrefRowCount(8);
        transcript.textProperty().addListener((obs, old, val) -> userEdited = true);

        Label rawLabel = new Label("Raw OCR output (read-only):");
        rawOutput = new TextArea();
        rawOutput.setEditable(false);
        rawOutput.setWrapText(true);
        rawOutput.setPrefRowCount(6);

        VBox root = new VBox(10, title, model, statusLabel, buttons, preview,
                transcriptLabel, transcript, rawLabel, rawOutput);
        root.setPadding(new Insets(16));
        VBox.setVgrow(transcript, Priority.ALWAYS);

        stage.setTitle("notebook-ai");
        stage.setScene(new Scene(root, 580, 760));
        stage.show();
    }

    private void openImage(Stage stage) {
        if (jobRunning) {
            return; // Image is locked while a job runs.
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Source Image");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images (JPG, PNG)", "*.jpg", "*.jpeg", "*.png"),
                new FileChooser.ExtensionFilter("JPEG", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        currentImage = file.toPath();
        userEdited = false;
        lastRawText = "";
        transcript.clear();
        rawOutput.clear();
        copyButton.setDisable(true);
        extractButton.setDisable(false);
        preview.setImage(new Image(file.toURI().toString(), 520, 300, true, true, true));
        statusLabel.setText("Image loaded: " + file.getName());
    }

    private void extractText() {
        if (jobRunning || currentImage == null) {
            return;
        }
        if (userEdited && !transcript.getText().isEmpty()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Extract again?");
            confirm.setHeaderText("The transcript has unsaved edits.");
            confirm.setContentText("Extracting again will replace your edits. Continue?");
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isEmpty() || choice.get() != ButtonType.OK) {
                return;
            }
        }
        Path image = currentImage;
        jobRunning = true;
        userEdited = false;
        openButton.setDisable(true);
        extractButton.setDisable(true);
        copyButton.setDisable(true);
        statusLabel.setText("Extracting text…");
        rawOutput.clear();

        Thread worker = new Thread(() -> {
            OcrResult result;
            try (OcrService service = OcrService.open(Paths.get("").toAbsolutePath())) {
                result = service.recognize(image);
                lastIdentity = service.identity();
            } catch (OcrException e) {
                String message = e.getMessage();
                Platform.runLater(() -> finishJob(null, "Extraction failed: " + message));
                return;
            }
            OcrResult done = result;
            Platform.runLater(() -> finishJob(done, null));
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void finishJob(OcrResult result, String error) {
        jobRunning = false;
        openButton.setDisable(false);
        extractButton.setDisable(currentImage == null);
        if (error != null) {
            statusLabel.setText(error);
            rawOutput.setText(error);
            return;
        }
        assert result != null;
        lastRawText = result.combinedText();
        rawOutput.setText(
                lastRawText.isEmpty() ? "(no text recognized)" : lastRawText);
        transcript.setText(lastRawText);
        userEdited = false; // Programmatic set, not a user edit.
        copyButton.setDisable(lastRawText.isEmpty());
        statusLabel.setText(
                "Done: " + result.regions().size() + " region(s), "
                        + Math.round(result.timings().totalMs()) + " ms total. " + lastIdentity);
    }

    private void copyText() {
        String text = transcript.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
        statusLabel.setText("Transcript copied to clipboard.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
