package notebookai.ui;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import notebookai.model.ModelConfig;
import notebookai.model.OcrResult;
import notebookai.model.WorkingImage;
import notebookai.ocr.CtcDecoder;
import notebookai.ocr.OcrException;
import notebookai.ocr.OcrService;

/**
 * Phase 2 window: image preparation (rotate / straighten / crop), page OCR,
 * selected-region OCR (detector + recognizer) and single-line recognition
 * (recognizer only), region review with source overlay, and editable
 * transcripts that preserve numerical text verbatim.
 *
 * <p>Coordinate convention: the working image is the oriented source after
 * preparation (90-degree steps, fine straightening, crop). All region boxes
 * are working-image pixels, origin top-left. Overlays and mouse selection
 * map through the same contain fit (uniform scale, centered image,
 * letterbox excluded); see {@link WorkingImage#mapDisplayToWorking}.
 *
 * <p>Background jobs carry a generation counter plus the working-image
 * revision they started from; stale completions are discarded instead of
 * overwriting newer state.
 */
public class NotebookAiApp extends Application {

    private static final double FIT_W = 560;
    private static final double FIT_H = 480;

    private Label statusLabel;
    private Label identityLabel;
    private ImageView preview;
    private Canvas overlay;
    private ListView<String> regionList;
    private TextArea regionRaw;
    private Label regionScores;
    private ImageView cropThumb;
    private TextArea transcript;
    private TextArea rawOutput;
    private TextArea selectionOutput;
    private Label selectionKind;
    private Label selectionTiming;
    private Button openButton;
    private Button extractButton;
    private Button recognizeButton;
    private Button recognizeLineButton;
    private Button copyButton;
    private Button copySelectionButton;
    private Button insertSelectionButton;
    private Button copyRegionButton;
    private Button insertRegionButton;
    private Button restoreButton;
    private Button rotLeftButton;
    private Button rotRightButton;
    private Slider straightenSlider;
    private Button straightenApply;
    private Button resetButton;
    private CheckBox cropMode;
    private Button applyCropButton;
    private Button clearSelectionButton;

    private WorkingImage working;
    private WorkingImage.Rendered rendered;
    private List<OcrResult.TextRegion> regions = new ArrayList<>();
    private int selectedRegion = -1;
    private int[] selection;
    private int[] cropDraft;
    private String selectionText = "";
    private String lastRawText = "";
    private String lastIdentity = "Model: " + ModelConfig.MODEL_IDENTITY;
    private OcrService sharedService;

    private int jobGen;
    private boolean jobRunning;
    private boolean userEdited;

    @Override
    public void start(Stage stage) {
        Label title = new Label("notebook-ai");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        identityLabel = new Label(lastIdentity);
        identityLabel.setWrapText(true);
        statusLabel = new Label("Open an image (JPG or PNG) to begin.");

        openButton = new Button("Open Image…");
        openButton.setOnAction(e -> openImage(stage));
        extractButton = new Button("Extract Page");
        extractButton.setDisable(true);
        extractButton.setOnAction(e -> extractPage());
        recognizeButton = new Button("Recognize Selection");
        recognizeButton.setDisable(true);
        recognizeButton.setOnAction(e -> recognizeSelection(false));
        recognizeLineButton = new Button("Recognize Line");
        recognizeLineButton.setDisable(true);
        recognizeLineButton.setOnAction(e -> recognizeSelection(true));
        HBox runRow = new HBox(10, openButton, extractButton, recognizeButton, recognizeLineButton);

        rotLeftButton = new Button("\u27F2 Left");
        rotLeftButton.setOnAction(e -> mutateWorking("rotate", () -> working.rotateSteps(-1)));
        rotRightButton = new Button("Right \u27F3");
        rotRightButton.setOnAction(e -> mutateWorking("rotate", () -> working.rotateSteps(1)));
        straightenSlider = new Slider(-10, 10, 0);
        straightenSlider.setShowTickMarks(true);
        straightenSlider.setMajorTickUnit(5);
        straightenSlider.setBlockIncrement(0.5);
        Label straightenValue = new Label("0.0\u00B0");
        straightenSlider.valueProperty().addListener((obs, o, v) ->
                straightenValue.setText(String.format("%.1f\u00B0", v.doubleValue())));
        straightenApply = new Button("Apply Straighten");
        straightenApply.setOnAction(e -> mutateWorking("straighten",
                () -> working.setStraighten(straightenSlider.getValue())));
        resetButton = new Button("Reset Working");
        resetButton.setOnAction(e -> mutateWorking("reset", () -> working.reset()));
        HBox prepRow = new HBox(10, rotLeftButton, rotRightButton,
                new Label("Straighten:"), straightenSlider, straightenValue,
                straightenApply, resetButton);

        cropMode = new CheckBox("Crop mode");
        applyCropButton = new Button("Apply Crop");
        applyCropButton.setDisable(true);
        applyCropButton.setOnAction(e -> applyCrop());
        clearSelectionButton = new Button("Clear Selection");
        clearSelectionButton.setDisable(true);
        clearSelectionButton.setOnAction(e -> {
            selection = null;
            cropDraft = null;
            applyCropButton.setDisable(true);
            clearSelectionButton.setDisable(true);
            drawOverlay(null);
        });
        copyButton = new Button("Copy Text");
        copyButton.setDisable(true);
        copyButton.setOnAction(e -> copyText());
        restoreButton = new Button("Restore Raw");
        restoreButton.setDisable(true);
        restoreButton.setOnAction(e -> restoreRaw());
        HBox editRow = new HBox(10, cropMode, applyCropButton, clearSelectionButton,
                restoreButton, copyButton);

        preview = new ImageView();
        preview.setPreserveRatio(true);
        preview.setFitWidth(FIT_W);
        preview.setFitHeight(FIT_H);
        overlay = new Canvas(FIT_W, FIT_H);
        overlay.setOnMousePressed(this::onPress);
        overlay.setOnMouseDragged(this::onDrag);
        overlay.setOnMouseReleased(this::onRelease);
        StackPane previewPane = new StackPane(preview, overlay);
        previewPane.setMinSize(FIT_W, FIT_H);
        previewPane.setMaxSize(FIT_W, FIT_H);

        regionList = new ListView<>();
        regionList.setPrefHeight(180);
        regionList.getSelectionModel().selectedIndexProperty().addListener(
                (obs, o, v) -> selectRegion(v.intValue()));
        regionRaw = new TextArea();
        regionRaw.setEditable(false);
        regionRaw.setWrapText(true);
        regionRaw.setPrefRowCount(3);
        regionScores = new Label("No region selected.");
        cropThumb = new ImageView();
        cropThumb.setPreserveRatio(true);
        cropThumb.setFitWidth(260);
        cropThumb.setFitHeight(80);
        copyRegionButton = new Button("Copy Region");
        copyRegionButton.setDisable(true);
        copyRegionButton.setOnAction(e -> copyRegionText());
        insertRegionButton = new Button("Insert at Caret");
        insertRegionButton.setDisable(true);
        insertRegionButton.setOnAction(e -> insertAtCaret(selectedRegionText()));
        VBox reviewBox = new VBox(6, new Label("Regions (click list or image):"), regionList,
                regionScores, new Label("Region raw text:"), regionRaw,
                new Label("Recognizer crop:"), cropThumb,
                new HBox(10, copyRegionButton, insertRegionButton));
        reviewBox.setPrefWidth(380);
        HBox middle = new HBox(12, previewPane, reviewBox);

        selectionKind = new Label("Selection: none (drag on the preview, or enable Crop mode).");
        selectionOutput = new TextArea();
        selectionOutput.setEditable(false);
        selectionOutput.setWrapText(true);
        selectionOutput.setPrefRowCount(3);
        selectionTiming = new Label("");
        copySelectionButton = new Button("Copy Selection");
        copySelectionButton.setDisable(true);
        copySelectionButton.setOnAction(e -> copySelection());
        insertSelectionButton = new Button("Insert at Caret");
        insertSelectionButton.setDisable(true);
        insertSelectionButton.setOnAction(e -> insertAtCaret(selectionText));
        HBox selRow = new HBox(10, copySelectionButton, insertSelectionButton);

        Label transcriptLabel = new Label("Transcript (editable, verbatim):");
        transcript = new TextArea();
        transcript.setWrapText(true);
        transcript.setPrefRowCount(6);
        transcript.textProperty().addListener((obs, old, val) -> userEdited = true);

        Label rawLabel = new Label("Raw OCR output (read-only):");
        rawOutput = new TextArea();
        rawOutput.setEditable(false);
        rawOutput.setWrapText(true);
        rawOutput.setPrefRowCount(4);

        VBox root = new VBox(8, title, identityLabel, statusLabel, runRow, prepRow, editRow,
                middle, selectionKind, selectionOutput, selectionTiming, selRow,
                transcriptLabel, transcript, rawLabel, rawOutput);
        root.setPadding(new Insets(12));
        VBox.setVgrow(transcript, Priority.ALWAYS);

        stage.setTitle("notebook-ai");
        stage.setScene(new Scene(root, 1000, 900));
        stage.show();
        setControlsEnabled(false);
    }

    // ------------------------------------------------------------------
    // Image + preparation.
    // ------------------------------------------------------------------

    private void openImage(Stage stage) {
        if (jobRunning) {
            return;
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
        if (userEdited && !transcript.getText().isEmpty() && !confirmDiscard()) {
            return;
        }
        try {
            notebookai.ocr.ImageOps.DecodedImage decoded =
                    notebookai.ocr.ImageOps.decode(file.toPath());
            working = new WorkingImage(decoded.bgr(), decoded.width(), decoded.height());
        } catch (Exception e) {
            statusLabel.setText("Could not open image: " + e.getMessage());
            return;
        }
        straightenSlider.setValue(0);
        cropMode.setSelected(false);
        clearResults();
        userEdited = false;
        refreshPreview();
        statusLabel.setText("Image loaded: " + file.getName());
        setControlsEnabled(true);
    }

    private void mutateWorking(String what, Runnable mutation) {
        if (working == null || jobRunning) {
            return;
        }
        if (userEdited && !transcript.getText().isEmpty() && !confirmDiscard()) {
            return;
        }
        mutation.run();
        clearResults();
        userEdited = false;
        refreshPreview();
        statusLabel.setText("Working image updated (" + what + "). Previous results cleared.");
    }

    private boolean confirmDiscard() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Discard edits?");
        confirm.setHeaderText("Unsaved transcript edits exist.");
        confirm.setContentText("Continue and replace them?");
        Optional<ButtonType> choice = confirm.showAndWait();
        return choice.isPresent() && choice.get() == ButtonType.OK;
    }

    private void applyCrop() {
        if (working == null || jobRunning || cropDraft == null) {
            return;
        }
        if (userEdited && !transcript.getText().isEmpty() && !confirmDiscard()) {
            return;
        }
        working.setCrop(cropDraft);
        cropDraft = null;
        applyCropButton.setDisable(true);
        clearResults();
        userEdited = false;
        refreshPreview();
        statusLabel.setText("Crop applied. Previous results cleared.");
    }

    private void clearResults() {
        regions = new ArrayList<>();
        selectedRegion = -1;
        selection = null;
        cropDraft = null;
        selectionText = "";
        lastRawText = "";
        regionList.getItems().clear();
        regionRaw.clear();
        regionScores.setText("No region selected.");
        cropThumb.setImage(null);
        transcript.clear();
        rawOutput.clear();
        selectionOutput.clear();
        selectionKind.setText("Selection: none (drag on the preview, or enable Crop mode).");
        selectionTiming.setText("");
        copyButton.setDisable(true);
        copyRegionButton.setDisable(true);
        insertRegionButton.setDisable(true);
        copySelectionButton.setDisable(true);
        insertSelectionButton.setDisable(true);
        restoreButton.setDisable(true);
        applyCropButton.setDisable(true);
        clearSelectionButton.setDisable(true);
    }

    // ------------------------------------------------------------------
    // Preview, overlay, selection.
    // ------------------------------------------------------------------

    private void refreshPreview() {
        if (working == null) {
            preview.setImage(null);
            drawOverlay(null);
            return;
        }
        rendered = working.render();
        preview.setImage(toFxImage(rendered.bgr(), rendered.width(), rendered.height()));
        drawOverlay(null);
    }

    private static Image toFxImage(byte[] bgr, int w, int h) {
        int[] argb = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int b = bgr[i * 3] & 0xFF;
            int g = bgr[i * 3 + 1] & 0xFF;
            int r = bgr[i * 3 + 2] & 0xFF;
            argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h,
                PixelFormat.getIntArgbInstance(), argb, 0, w);
        return img;
    }

    private double[] displayScale() {
        double scale = Math.min(FIT_W / rendered.width(), FIT_H / rendered.height());
        double dispW = rendered.width() * scale;
        double dispH = rendered.height() * scale;
        return new double[] {scale, (FIT_W - dispW) / 2, (FIT_H - dispH) / 2};
    }

    private void drawOverlay(double[] rubber) {
        GraphicsContext g = overlay.getGraphicsContext2D();
        g.clearRect(0, 0, FIT_W, FIT_H);
        if (rendered == null) {
            return;
        }
        double[] m = displayScale();
        double s = m[0];
        double ox = m[1];
        double oy = m[2];
        g.setLineWidth(2);
        g.setStroke(Color.RED);
        for (double[][] poly : regionPolys()) {
            g.beginPath();
            for (int i = 0; i < poly.length; i++) {
                double x = ox + poly[i][0] * s;
                double y = oy + poly[i][1] * s;
                if (i == 0) {
                    g.moveTo(x, y);
                } else {
                    g.lineTo(x, y);
                }
            }
            g.closePath();
            g.stroke();
        }
        if (selectedRegion >= 0 && selectedRegion < regions.size()) {
            double[][] poly = regions.get(selectedRegion).polygon();
            g.setStroke(Color.ORANGE);
            g.setLineWidth(3);
            g.beginPath();
            for (int i = 0; i < poly.length; i++) {
                double x = ox + poly[i][0] * s;
                double y = oy + poly[i][1] * s;
                if (i == 0) {
                    g.moveTo(x, y);
                } else {
                    g.lineTo(x, y);
                }
            }
            g.closePath();
            g.stroke();
        }
        if (selection != null) {
            g.setStroke(Color.LIMEGREEN);
            g.setLineWidth(2);
            g.strokeRect(ox + selection[0] * s, oy + selection[1] * s,
                    (selection[2] - selection[0]) * s, (selection[3] - selection[1]) * s);
        }
        if (cropDraft != null) {
            g.setStroke(Color.PURPLE);
            g.setLineWidth(2);
            g.setLineDashes(6);
            g.strokeRect(ox + cropDraft[0] * s, oy + cropDraft[1] * s,
                    (cropDraft[2] - cropDraft[0]) * s, (cropDraft[3] - cropDraft[1]) * s);
            g.setLineDashes();
        }
        if (rubber != null) {
            g.setStroke(cropMode.isSelected() ? Color.PURPLE : Color.LIMEGREEN);
            g.setLineWidth(2);
            g.setLineDashes(6);
            g.strokeRect(rubber[0], rubber[1], rubber[2] - rubber[0], rubber[3] - rubber[1]);
            g.setLineDashes();
        }
    }

    private List<double[][]> regionPolys() {
        List<double[][]> out = new ArrayList<>();
        for (OcrResult.TextRegion r : regions) {
            out.add(r.polygon());
        }
        return out;
    }

    private double pressX;
    private double pressY;

    private void onPress(MouseEvent e) {
        pressX = e.getX();
        pressY = e.getY();
    }

    private void onDrag(MouseEvent e) {
        if (working == null || rendered == null) {
            return;
        }
        double x0 = Math.min(pressX, e.getX());
        double y0 = Math.min(pressY, e.getY());
        double x1 = Math.max(pressX, e.getX());
        double y1 = Math.max(pressY, e.getY());
        drawOverlay(new double[] {x0, y0, x1, y1});
    }

    private void onRelease(MouseEvent e) {
        if (working == null || rendered == null) {
            drawOverlay(null);
            return;
        }
        double dx = e.getX() - pressX;
        double dy = e.getY() - pressY;
        if (Math.hypot(dx, dy) < 4) {
            pickRegion(e.getX(), e.getY());
            drawOverlay(null);
            return;
        }
        double[] a = WorkingImage.displayToWorking(
                Math.min(pressX, e.getX()), Math.min(pressY, e.getY()),
                FIT_W, FIT_H, rendered.width(), rendered.height());
        double[] b = WorkingImage.displayToWorking(
                Math.max(pressX, e.getX()), Math.max(pressY, e.getY()),
                FIT_W, FIT_H, rendered.width(), rendered.height());
        if (a == null || b == null) {
            a = clampDisplay(Math.min(pressX, e.getX()), Math.min(pressY, e.getY()));
            b = clampDisplay(Math.max(pressX, e.getX()), Math.max(pressY, e.getY()));
        }
        int[] rect = WorkingImage.normalizeRect(a[0], a[1], b[0], b[1]);
        Optional<int[]> clipped = WorkingImage.clipRect(rect, rendered.width(), rendered.height());
        if (clipped.isEmpty()) {
            statusLabel.setText("Selection too small or outside the image.");
            drawOverlay(null);
            return;
        }
        if (cropMode.isSelected()) {
            cropDraft = clipped.get();
            applyCropButton.setDisable(false);
            clearSelectionButton.setDisable(false);
            statusLabel.setText("Crop drafted — Apply Crop to commit, Clear to drop it.");
        } else {
            selection = clipped.get();
            selectedRegion = -1;
            regionList.getSelectionModel().clearSelection();
            clearSelectionButton.setDisable(false);
            statusLabel.setText("Selection ready — Recognize Selection or Recognize Line.");
        }
        drawOverlay(null);
    }

    private double[] clampDisplay(double mx, double my) {
        double[] m = displayScale();
        double s = m[0];
        return new double[] {
            Math.min(Math.max((mx - m[1]) / s, 0), rendered.width() - 1),
            Math.min(Math.max((my - m[2]) / s, 0), rendered.height() - 1)};
    }

    private void pickRegion(double mx, double my) {
        Optional<double[]> p = WorkingImage.mapDisplayToWorking(
                mx, my, FIT_W, FIT_H, rendered.width(), rendered.height());
        if (p.isEmpty()) {
            selectRegion(-1);
            return;
        }
        for (int i = regions.size() - 1; i >= 0; i--) {
            if (WorkingImage.pointInQuad(p.get(), regions.get(i).polygon())) {
                selectRegion(i);
                return;
            }
        }
        selectRegion(-1);
    }

    private void selectRegion(int index) {
        selectedRegion = index;
        regionList.getSelectionModel().clearAndSelect(index);
        if (index < 0 || index >= regions.size()) {
            regionRaw.clear();
            regionScores.setText("No region selected.");
            cropThumb.setImage(null);
            copyRegionButton.setDisable(true);
            insertRegionButton.setDisable(true);
        } else {
            OcrResult.TextRegion r = regions.get(index);
            regionRaw.setText(r.text());
            regionScores.setText(String.format("det=%.3f  rec=%.3f  (detector score is box quality; rec score is mean kept-char confidence)",
                    r.detScore(), r.recScore()));
            int[] box = r.box();
            byte[] cut = WorkingImage.cropBytes(rendered.bgr(), rendered.width(), rendered.height(), box);
            cropThumb.setImage(toFxImage(cut, box[2] - box[0], box[3] - box[1]));
            copyRegionButton.setDisable(r.text().isEmpty());
            insertRegionButton.setDisable(r.text().isEmpty());
        }
        drawOverlay(null);
    }

    private String selectedRegionText() {
        if (selectedRegion < 0 || selectedRegion >= regions.size()) {
            return "";
        }
        return regions.get(selectedRegion).text();
    }

    private OcrService service() throws OcrException {
        if (sharedService == null) {
            sharedService = OcrService.open(Paths.get("").toAbsolutePath());
        }
        return sharedService;
    }

    private void extractPage() {
        if (working == null || jobRunning || rendered == null) {
            return;
        }
        if (userEdited && !transcript.getText().isEmpty() && !confirmReplace()) {
            return;
        }
        WorkingImage.Rendered snap = rendered;
        runJob("Extracting text…", () -> {
            OcrService svc = service();
            return svc.recognize(snap.bgr(), snap.width(), snap.height(), null);
        }, result -> {
            regions = new ArrayList<>(result.regions());
            selectedRegion = -1;
            selection = null;
            cropDraft = null;
            refreshRegionList();
            lastRawText = result.combinedText();
            rawOutput.setText(lastRawText.isEmpty() ? "(no text recognized)" : lastRawText);
            transcript.setText(lastRawText);
            userEdited = false;
            copyButton.setDisable(lastRawText.isEmpty());
            restoreButton.setDisable(lastRawText.isEmpty());
            try {
                lastIdentity = service().identity();
            } catch (OcrException ignored) {
                // Keep the previous identity line.
            }
            statusLabel.setText("Done: " + regions.size() + " region(s), "
                    + Math.round(result.timings().totalMs()) + " ms total. " + lastIdentity);
            drawOverlay(null);
        });
    }

    private void recognizeSelection(boolean singleLine) {
        if (working == null || jobRunning || rendered == null || selection == null) {
            if (selection == null) {
                statusLabel.setText("Draw a selection on the preview first.");
            }
            return;
        }
        int[] rect = selection.clone();
        WorkingImage.Rendered snap = rendered;
        byte[] crop = WorkingImage.cropBytes(snap.bgr(), snap.width(), snap.height(), rect);
        int cw = rect[2] - rect[0];
        int ch = rect[3] - rect[1];
        String kind = singleLine ? "Single line (recognizer only)" : "Selection (detector + recognizer)";
        runJob(singleLine ? "Recognizing line…" : "Recognizing selection…", () -> {
            OcrService svc = service();
            if (singleLine) {
                CtcDecoder.Line line = svc.recognizeLine(crop, cw, ch);
                return new SelResult(kind, line.text(), line.score(), -1, cw, ch, crop);
            }
            OcrResult r = svc.recognize(crop, cw, ch, null);
            StringBuilder sb = new StringBuilder();
            for (OcrResult.TextRegion t : r.regions()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
            double best = 0;
            for (OcrResult.TextRegion t : r.regions()) {
                best = Math.max(best, t.recScore());
            }
            return new SelResult(kind + " — " + r.regions().size() + " region(s)",
                    sb.toString(), best, r.timings().totalMs(), cw, ch, crop);
        }, result -> {
            selectionText = result.text;
            selectionKind.setText(result.kind + (result.text.isEmpty() ? " (no text)" : ""));
            selectionOutput.setText(result.text.isEmpty() ? "(no text recognized)" : result.text);
            String time = result.totalMs < 0 ? "" : Math.round(result.totalMs) + " ms total. ";
            selectionTiming.setText(time + String.format("rec=%.3f, crop %dx%d",
                    result.score, result.cw, result.ch));
            copySelectionButton.setDisable(result.text.isEmpty());
            insertSelectionButton.setDisable(result.text.isEmpty());
            statusLabel.setText("Selection done: " + (result.text.isEmpty() ? "no text." : result.text.length() + " chars."));
        });
    }

    private record SelResult(String kind, String text, double score, double totalMs,
            int cw, int ch, byte[] crop) {}

    private <T> void runJob(String busyText, JobWork<T> work, JobDone<T> onDone) {
        int gen = ++jobGen;
        int rev = working.revision();
        jobRunning = true;
        setControlsEnabled(false);
        statusLabel.setText(busyText);
        Thread worker = new Thread(() -> {
            T result;
            String error = null;
            try {
                result = work.run();
            } catch (OcrException e) {
                result = null;
                error = e.getMessage();
            } catch (RuntimeException e) {
                result = null;
                error = "Unexpected failure: " + e.getMessage();
            }
            T done = result;
            String err = error;
            Platform.runLater(() -> {
                jobRunning = false;
                setControlsEnabled(working != null);
                if (!WorkingImage.isCurrentJob(gen, jobGen, rev, working.revision())) {
                    statusLabel.setText("Ignored stale result (image changed mid-job).");
                    return;
                }
                if (err != null) {
                    statusLabel.setText("Failed: " + err);
                    return;
                }
                onDone.accept(done);
            });
        });
        worker.setDaemon(true);
        worker.start();
    }

    private interface JobWork<T> {
        T run() throws OcrException;
    }

    private interface JobDone<T> {
        void accept(T result);
    }

    private boolean confirmReplace() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Extract again?");
        confirm.setHeaderText("The transcript has unsaved edits.");
        confirm.setContentText("Extracting again will replace your edits. Continue?");
        Optional<ButtonType> choice = confirm.showAndWait();
        return choice.isPresent() && choice.get() == ButtonType.OK;
    }

    private void setControlsEnabled(boolean hasImage) {
        openButton.setDisable(jobRunning);
        extractButton.setDisable(jobRunning || !hasImage);
        recognizeButton.setDisable(jobRunning || !hasImage || selection == null);
        recognizeLineButton.setDisable(jobRunning || !hasImage || selection == null);
        rotLeftButton.setDisable(jobRunning || !hasImage);
        rotRightButton.setDisable(jobRunning || !hasImage);
        straightenSlider.setDisable(jobRunning || !hasImage);
        straightenApply.setDisable(jobRunning || !hasImage);
        resetButton.setDisable(jobRunning || !hasImage);
        cropMode.setDisable(jobRunning || !hasImage);
        applyCropButton.setDisable(jobRunning || cropDraft == null);
        clearSelectionButton.setDisable(jobRunning || (selection == null && cropDraft == null));
        copyButton.setDisable(lastRawText.isEmpty());
        restoreButton.setDisable(jobRunning || lastRawText.isEmpty());
    }

    // ------------------------------------------------------------------
    // Transcript, raw, clipboard.
    // ------------------------------------------------------------------

    private void refreshRegionList() {
        regionList.getItems().clear();
        for (int i = 0; i < regions.size(); i++) {
            OcrResult.TextRegion r = regions.get(i);
            String t = r.text().length() > 42 ? r.text().substring(0, 42) + "…" : r.text();
            regionList.getItems().add(i + ": " + t);
        }
        selectRegion(-1);
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

    private void copySelection() {
        if (selectionText.isEmpty()) {
            return;
        }
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(selectionText);
        clipboard.setContent(content);
        statusLabel.setText("Selection copied to clipboard.");
    }

    private void copyRegionText() {
        String text = selectedRegionText();
        if (text.isEmpty()) {
            return;
        }
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
        statusLabel.setText("Region text copied to clipboard.");
    }

    private void insertAtCaret(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        transcript.insertText(transcript.getCaretPosition(), text);
        statusLabel.setText("Inserted into transcript (unedited raw kept below).");
    }

    private void restoreRaw() {
        if (lastRawText.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Restore raw text?");
        confirm.setHeaderText("Replace the transcript with the raw OCR output?");
        confirm.setContentText("Your edits will be discarded.");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        transcript.setText(lastRawText);
        userEdited = false;
        statusLabel.setText("Transcript restored to raw OCR output.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
