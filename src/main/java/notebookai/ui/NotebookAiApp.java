package notebookai.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import notebookai.model.ControlState;
import notebookai.model.ModelConfig;
import notebookai.model.OcrResult;
import notebookai.model.WorkingImage;
import notebookai.ocr.ImageOps;
import notebookai.project.BackgroundTasks;
import notebookai.project.JobTicket;
import notebookai.project.MemoryGuard;
import notebookai.project.PageRestore;
import notebookai.project.ShutdownPolicy;
import notebookai.project.PageData;
import notebookai.project.ProjectActions;
import notebookai.project.ProjectData;
import notebookai.project.ProjectException;
import notebookai.project.ProjectSession;
import notebookai.project.ProjectStore;
import notebookai.project.RegionData;
import notebookai.project.TextExport;
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
    private static final double FIT_H = 360;

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

    // ---- active page runtime state (only the ACTIVE page is decoded; the rest live in PageData) ----
    private WorkingImage working;
    private WorkingImage.Rendered rendered;
    private List<OcrResult.TextRegion> regions = new ArrayList<>();
    private int selectedRegion = -1;
    private int[] selection;     // transient: never persisted, never dirties the project
    private int[] cropDraft;     // transient
    private String selectionText = ""; // transient review result
    private String lastIdentity = "Model: " + ModelConfig.MODEL_IDENTITY;
    private OcrService sharedService;

    // ---- project state ----
    private final ProjectSession session = new ProjectSession();
    private String activePageId;
    private final Map<String, Image> thumbs = new HashMap<>();
    private Stage stage;
    private Label projectLabel;
    private ListView<PageData> pageList;
    private Button removePageButton;
    private Button moveUpButton;
    private Button moveDownButton;
    private MenuItem miNew;
    private MenuItem miOpen;
    private MenuItem miSave;
    private MenuItem miSaveAs;
    private MenuItem miExportCurrent;
    private MenuItem miExportAll;
    private boolean suppressPageSelection;
    private boolean loadingPage; // programmatic UI refresh: must not count as an edit

    private int jobGen;
    private boolean jobRunning; // OCR job, import or project open in flight (locks page/project actions)
    private boolean saving;
    private boolean closing;
    private boolean closeRequested; // user asked to close while background work runs: close when it finishes
    private final BackgroundTasks tasks = new BackgroundTasks(
            r -> {
                Thread t = new Thread(r, "notebook-ai-worker");
                t.setDaemon(true);
                t.start();
            },
            Platform::runLater);

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Label title = new Label("notebook-ai");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        identityLabel = new Label(lastIdentity);
        identityLabel.setWrapText(true);
        statusLabel = new Label("Add images (JPG or PNG) as pages to begin.");
        projectLabel = new Label();
        projectLabel.setStyle("-fx-font-weight: bold;");

        openButton = new Button("Add Images…");
        openButton.setOnAction(e -> addImages());
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
            drawOverlay(null);
            updateControls();
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
        regionList.setPrefHeight(120);
        regionList.getSelectionModel().selectedIndexProperty().addListener(
                (obs, o, v) -> selectRegion(v.intValue()));
        regionRaw = new TextArea();
        regionRaw.setEditable(false);
        regionRaw.setWrapText(true);
        regionRaw.setPrefRowCount(2);
        regionScores = new Label("No region selected.");
        cropThumb = new ImageView();
        cropThumb.setPreserveRatio(true);
        cropThumb.setFitWidth(260);
        cropThumb.setFitHeight(64);
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
        pageList = new ListView<>();
        pageList.setPrefWidth(210);
        pageList.setMinWidth(210);
        pageList.setPrefHeight(FIT_H);
        pageList.setCellFactory(lv -> new PageCell());
        pageList.getSelectionModel().selectedItemProperty().addListener((obs, o, v) -> {
            if (!suppressPageSelection && v != null && !v.id().equals(activePageId)) {
                if (jobRunning) {
                    refreshPageList(); // locked mid-job: snap back to the active page
                } else {
                    activatePage(v.id());
                }
            }
        });
        removePageButton = new Button("Remove");
        removePageButton.setOnAction(e -> removeActivePage());
        moveUpButton = new Button("\u25B2");
        moveUpButton.setOnAction(e -> moveActivePage(-1));
        moveDownButton = new Button("\u25BC");
        moveDownButton.setOnAction(e -> moveActivePage(1));
        VBox pagePane = new VBox(6, new Label("Pages:"), pageList,
                new HBox(6, removePageButton, moveUpButton, moveDownButton));
        pagePane.setPrefWidth(210);
        HBox middle = new HBox(12, pagePane, previewPane, reviewBox);

        selectionKind = new Label("Selection: none (drag on the preview, or enable Crop mode).");
        selectionOutput = new TextArea();
        selectionOutput.setEditable(false);
        selectionOutput.setWrapText(true);
        selectionOutput.setPrefRowCount(2);
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
        transcript.textProperty().addListener((obs, old, val) -> {
            if (loadingPage) {
                return; // programmatic refresh (page switch, OCR result): not an edit
            }
            PageData page = currentPage();
            if (page != null && !val.equals(page.editedText())) {
                page.setEditedText(val); // the reviewed transcript lives in the page, raw OCR stays separate
                session.markModified();
            }
            updateControls();
        });

        Label rawLabel = new Label("Raw OCR output (read-only):");
        rawOutput = new TextArea();
        rawOutput.setEditable(false);
        rawOutput.setWrapText(true);
        rawOutput.setPrefRowCount(4);

        VBox root = new VBox(8, title, projectLabel, identityLabel, statusLabel, runRow, prepRow, editRow,
                middle, selectionKind, selectionOutput, selectionTiming, selRow,
                transcriptLabel, transcript, rawLabel, rawOutput);
        root.setPadding(new Insets(12));
        VBox.setVgrow(transcript, Priority.ALWAYS);

        transcript.setMinHeight(110);
        rawOutput.setMinHeight(80);
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        BorderPane frame = new BorderPane();
        frame.setTop(buildMenuBar());
        frame.setCenter(scroll);
        stage.setScene(new Scene(frame, 1290, 900));
        stage.setOnCloseRequest(ev -> {
            if (closing) {
                return;
            }
            ev.consume();
            requestClose();
        });
        stage.show();
        refreshPageList();
        updateControls();
    }

    /**
     * Close policy. Background work (OCR inference, import, project open, save) cannot be cancelled
     * safely, so closing while it runs WAITS: the window closes automatically once it has finished
     * and the unsaved-changes prompt still applies afterwards. Nothing is cancelled.
     */
    private void requestClose() {
        switch (ShutdownPolicy.onCloseRequest(jobRunning, saving, session.isDirty())) {
            case CLOSE_NOW -> {
                closing = true;
                stage.close();
            }
            case PROMPT_UNSAVED -> confirmUnsavedThen(() -> {
                closing = true;
                stage.close();
            });
            case WAIT_FOR_BACKGROUND -> {
                if (closeRequested) {
                    statusLabel.setText("Waiting for the running operation to finish, then the window will close.");
                    return;
                }
                ButtonType wait = new ButtonType("Close when finished", ButtonBar.ButtonData.YES);
                ButtonType keep = new ButtonType("Keep working", ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", wait, keep);
                a.setTitle("Operation in progress");
                a.setHeaderText("An operation is still running.");
                a.setContentText("It cannot be cancelled safely, so it will be allowed to finish. The window will "
                        + "then close (you will still be asked about unsaved changes).");
                Optional<ButtonType> r = a.showAndWait();
                if (r.isPresent() && r.get() == wait) {
                    closeRequested = true;
                    statusLabel.setText("Waiting for the running operation to finish, then the window will close.");
                    closeIfRequested(); // it may already have finished while the dialog was open
                }
            }
        }
    }

    private void closeIfRequested() {
        if (closeRequested && !jobRunning && !saving) {
            closeRequested = false;
            requestClose();
        }
    }

    @Override
    public void stop() {
        // Reached only when idle (see requestClose): release the OCR sessions.
        if (sharedService != null) {
            sharedService.close();
            sharedService = null;
        }
    }

    private MenuBar buildMenuBar() {
        miNew = new MenuItem("New Project");
        miNew.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        miNew.setOnAction(e -> newProject());
        miOpen = new MenuItem("Open Project…");
        miOpen.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
        miOpen.setOnAction(e -> openProject());
        miSave = new MenuItem("Save");
        miSave.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));
        miSave.setOnAction(e -> saveProject(false, null));
        miSaveAs = new MenuItem("Save As…");
        miSaveAs.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        miSaveAs.setOnAction(e -> saveProject(true, null));
        MenuItem quit = new MenuItem("Quit");
        quit.setOnAction(e -> stage.fireEvent(new javafx.stage.WindowEvent(stage,
                javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        Menu project = new Menu("Project");
        project.getItems().addAll(miNew, miOpen, new SeparatorMenuItem(), miSave, miSaveAs,
                new SeparatorMenuItem(), quit);
        miExportCurrent = new MenuItem("Current Page as Text…");
        miExportCurrent.setOnAction(e -> exportCurrent());
        miExportAll = new MenuItem("All Pages as Text…");
        miExportAll.setOnAction(e -> exportAll());
        Menu export = new Menu("Export");
        export.getItems().addAll(miExportCurrent, miExportAll);
        return new MenuBar(project, export);
    }

    // ------------------------------------------------------------------
    // Project, pages, image preparation.
    // ------------------------------------------------------------------

    private PageData currentPage() {
        return activePageId == null ? null : session.project().find(activePageId);
    }

    /** A meaningful change that becomes project content: marks the project unsaved. */
    private void modified() {
        session.markModified();
        updateControls();
    }

    private boolean pageHasEdits() {
        PageData p = currentPage();
        return p != null && p.hasUserEdits() && !p.editedText().isEmpty();
    }

    private static String rawDisplay(PageData p) {
        if (!p.recognized()) {
            return "";
        }
        return p.rawText().isEmpty() ? "(no text recognized)" : p.rawText();
    }

    private static String pageStatus(PageData p) {
        if (!p.recognized()) {
            return p.editedText().isEmpty() ? "not recognized" : "not recognized (has typed text)";
        }
        if (p.rawText().isEmpty() && p.editedText().isEmpty()) {
            return "recognized: no text";
        }
        return p.hasUserEdits() ? "recognized, edited" : "recognized";
    }

    private static String cleanName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= 0x20 && c != 0x7f && c != '/' && c != '\\') {
                sb.append(c);
            }
        }
        String n = sb.toString().strip();
        if (n.length() > ProjectStore.MAX_NAME_CHARS) {
            n = n.substring(0, ProjectStore.MAX_NAME_CHARS);
        }
        return n.isEmpty() ? "image" : n;
    }

    private static List<OcrResult.TextRegion> toTextRegions(List<RegionData> data) {
        List<OcrResult.TextRegion> out = new ArrayList<>();
        for (RegionData r : data) {
            out.add(new OcrResult.TextRegion(r.polygon(), r.box(), r.text(), r.detScore(), r.recScore()));
        }
        return out;
    }

    private static List<RegionData> toRegionData(List<OcrResult.TextRegion> regs) {
        List<RegionData> out = new ArrayList<>();
        for (OcrResult.TextRegion r : regs) {
            out.add(new RegionData(r.polygon(), r.box(), r.text(), r.detScore(), r.recScore()));
        }
        return out;
    }

    /** Small thumbnail from decoded BGR pixels (background-thread safe). */
    private static Image thumbnail(byte[] bgr, int w, int h) {
        double scale = Math.min(88.0 / w, 112.0 / h);
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        return toFxImage(ImageOps.resizeBilinear(bgr, w, h, tw, th), tw, th);
    }

    /**
     * Rebuilds the working image for a page (original bytes + saved prep) after checking that the
     * decode fits in memory BEFORE allocating. May discard saved geometry that does not fit.
     */
    private static PageRestore.Restored buildWorking(PageData page) throws Exception {
        int[] dim = ImageOps.peekDimensions(page.original());
        if (dim == null) {
            throw new IllegalStateException("the image data is unreadable");
        }
        String refuse = MemoryGuard.checkAgainstRuntime(dim[0], dim[1]);
        if (refuse != null) {
            throw new IllegalStateException(refuse);
        }
        ImageOps.DecodedImage d = ImageOps.decode(page.original());
        return PageRestore.restore(page, d.bgr(), d.width(), d.height());
    }

    /** Rendered view for a thumbnail; never mutates the page (runs off the UI thread). */
    private static WorkingImage.Rendered thumbnailSource(PageData page) throws Exception {
        int[] dim = ImageOps.peekDimensions(page.original());
        if (dim == null || MemoryGuard.checkAgainstRuntime(dim[0], dim[1]) != null) {
            throw new IllegalStateException("cannot render a thumbnail");
        }
        ImageOps.DecodedImage d = ImageOps.decode(page.original());
        int[] frame = WorkingImage.frameSize(d.width(), d.height(), page.quarterTurnsCw(), page.fineDegrees());
        int[] crop = page.crop();
        if (crop != null && !(crop[0] >= 0 && crop[1] >= 0 && crop[2] <= frame[0] && crop[3] <= frame[1]
                && crop[2] > crop[0] && crop[3] > crop[1])) {
            crop = null;
        }
        return WorkingImage.restore(d.bgr(), d.width(), d.height(), page.quarterTurnsCw(), page.fineDegrees(),
                crop).render();
    }

    private void refreshPageList() {
        suppressPageSelection = true;
        try {
            pageList.getItems().setAll(session.project().pages());
            int idx = activePageId == null ? -1 : session.project().indexOf(activePageId);
            if (idx >= 0) {
                pageList.getSelectionModel().select(idx);
            } else {
                pageList.getSelectionModel().clearSelection();
            }
        } finally {
            suppressPageSelection = false;
        }
        pageList.refresh();
    }

    /** Page cell: thumbnail, "N. name" and recognition status. */
    private final class PageCell extends ListCell<PageData> {
        private final ImageView iv = new ImageView();
        private final Label name = new Label();
        private final Label state = new Label();
        private final HBox box;

        PageCell() {
            iv.setPreserveRatio(true);
            iv.setFitWidth(44);
            iv.setFitHeight(56);
            state.setStyle("-fx-font-size: 10px; -fx-text-fill: #555;");
            name.setMaxWidth(130);
            box = new HBox(6, iv, new VBox(2, name, state));
        }

        @Override
        protected void updateItem(PageData item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            iv.setImage(thumbs.get(item.id()));
            name.setText((getIndex() + 1) + ". " + item.name());
            state.setText(pageStatus(item));
            setGraphic(box);
        }
    }

    // ---- activating / deactivating the displayed page ----

    private void activatePage(String id) {
        PageData page = session.project().find(id);
        if (page == null) {
            deactivatePage();
            return;
        }
        PageRestore.Restored restored;
        try {
            restored = buildWorking(page);
        } catch (Throwable e) { // includes OutOfMemoryError: report instead of leaving a half-loaded page
            statusLabel.setText("Could not load page \"" + page.name() + "\": "
                    + BackgroundTasks.describe(e instanceof IllegalStateException ? new notebookai.ocr.OcrException(e.getMessage()) : e));
            refreshPageList();
            return;
        }
        WorkingImage w = restored.working();
        if (!restored.notices().isEmpty()) {
            page.setLoadNotice(String.join(" ", restored.notices()));
            modified(); // discarding unusable saved geometry changes the project content
        }
        activePageId = id;
        working = w;
        loadingPage = true;
        try {
            straightenSlider.setValue(page.fineDegrees());
            cropMode.setSelected(false);
            clearTransientUi();
            transcript.setText(page.editedText());
            rawOutput.setText(rawDisplay(page));
            regions = toTextRegions(page.regions());
            refreshRegionList();
            refreshPreview();
        } finally {
            loadingPage = false;
        }
        statusLabel.setText(page.loadNotice() != null
                ? "Page " + (session.project().indexOf(id) + 1) + ": " + page.loadNotice()
                : "Page " + (session.project().indexOf(id) + 1) + ": " + page.name() + " (" + pageStatus(page) + ")");
        refreshPageList();
        updateControls();
    }

    private void deactivatePage() {
        activePageId = null;
        working = null;
        rendered = null;
        loadingPage = true;
        try {
            clearResults();
            preview.setImage(null);
            drawOverlay(null);
        } finally {
            loadingPage = false;
        }
        refreshPageList();
        updateControls();
    }

    /** Clears selection/crop-draft/review state: transient, never persisted, never an edit. */
    private void clearTransientUi() {
        selection = null;
        cropDraft = null;
        selectedRegion = -1;
        selectionText = "";
        regionList.getItems().clear();
        regionRaw.clear();
        regionScores.setText("No region selected.");
        cropThumb.setImage(null);
        selectionOutput.clear();
        selectionKind.setText("Selection: none (drag on the preview, or enable Crop mode).");
        selectionTiming.setText("");
    }

    // ---- adding pages ----

    private record Imported(PageData page, Image thumb) {}

    private record ImportResult(List<Imported> pages, List<String> errors) {}

    private void addImages() {
        if (jobRunning) {
            return;
        }
        int room = ProjectStore.MAX_PAGES - session.project().size();
        if (room <= 0) {
            statusLabel.setText("A project holds at most " + ProjectStore.MAX_PAGES + " pages.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Images as Pages (JPG or PNG)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images (JPG, PNG)", "*.jpg", "*.jpeg", "*.png"),
                new FileChooser.ExtensionFilter("JPEG", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("PNG", "*.png"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        List<File> take = files.size() > room ? new ArrayList<>(files.subList(0, room)) : files;
        String limitNote = files.size() > room
                ? " Only " + room + " more page(s) fit (limit " + ProjectStore.MAX_PAGES + ")."
                : "";
        runTask("Importing " + take.size() + " image(s)…", () -> importFiles(take), result -> {
            for (Imported im : result.pages()) {
                session.project().add(im.page());
                thumbs.put(im.page().id(), im.thumb());
            }
            if (!result.pages().isEmpty()) {
                modified();
                activatePage(result.pages().get(0).page().id());
            } else {
                refreshPageList();
            }
            statusLabel.setText("Added " + result.pages().size() + " page(s)"
                    + (result.errors().isEmpty() ? "." : "; " + result.errors().size() + " file(s) skipped.")
                    + limitNote);
            if (!result.errors().isEmpty()) {
                Alert warn = new Alert(Alert.AlertType.WARNING);
                warn.setTitle("Some images were not added");
                warn.setHeaderText(result.errors().size() + " file(s) could not be added as pages.");
                warn.setContentText(String.join("\n", result.errors()));
                warn.showAndWait();
            }
        });
    }

    private ImportResult importFiles(List<File> files) {
        List<Imported> ok = new ArrayList<>();
        List<String> errs = new ArrayList<>();
        for (File f : files) {
            String name = cleanName(f.getName());
            try {
                long size = Files.size(f.toPath());
                if (size > ProjectStore.MAX_IMAGE_BYTES) {
                    errs.add(name + ": larger than " + (ProjectStore.MAX_IMAGE_BYTES >> 20) + " MiB");
                    continue;
                }
                byte[] data = Files.readAllBytes(f.toPath());
                if (PageData.detectFormat(data) == null) {
                    errs.add(name + ": not a JPEG or PNG image");
                    continue;
                }
                int[] dim = ImageOps.peekDimensions(data);
                if (dim == null || (long) dim[0] * dim[1] > ProjectStore.MAX_PIXELS) {
                    errs.add(name + ": unreadable or larger than " + (ProjectStore.MAX_PIXELS / 1_000_000) + " megapixels");
                    continue;
                }
                String tooBig = MemoryGuard.checkAgainstRuntime(dim[0], dim[1]);
                if (tooBig != null) {
                    errs.add(name + ": " + tooBig);
                    continue;
                }
                ImageOps.DecodedImage d = ImageOps.decode(data);
                ok.add(new Imported(PageData.create(name, data), thumbnail(d.bgr(), d.width(), d.height())));
            } catch (java.nio.file.NoSuchFileException e) {
                errs.add(name + ": file not found");
            } catch (java.nio.file.AccessDeniedException e) {
                errs.add(name + ": permission denied");
            } catch (Exception e) {
                errs.add(name + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new ImportResult(ok, errs);
    }

    // ---- removing / reordering ----

    private void removeActivePage() {
        PageData page = currentPage();
        if (page == null || jobRunning) {
            return;
        }
        int idx = session.project().indexOf(page.id());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove page?");
        confirm.setHeaderText("Remove page " + (idx + 1) + " (" + page.name() + ") from the project?");
        confirm.setContentText("Its image, transcript and results are removed from the project (the original image file "
                + "on disk is not touched). This is part of the unsaved changes until you save.");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        session.project().remove(page.id());
        thumbs.remove(page.id());
        modified();
        int n = session.project().size();
        if (n == 0) {
            deactivatePage();
        } else {
            activatePage(session.project().pages().get(Math.min(idx, n - 1)).id());
        }
        statusLabel.setText("Page removed.");
    }

    private void moveActivePage(int delta) {
        PageData page = currentPage();
        if (page == null || jobRunning) {
            return;
        }
        if (session.project().move(page.id(), delta)) {
            refreshPageList();
            modified();
            statusLabel.setText("Page moved to position " + (session.project().indexOf(page.id()) + 1) + ".");
        }
    }

    // ---- image preparation (per page) ----

    private void mutateWorking(String what, Runnable mutation) {
        PageData page = currentPage();
        if (page == null || working == null || jobRunning) {
            return;
        }
        if (pageHasEdits() && !confirmDiscard()) {
            return;
        }
        boolean hadCrop = working.hasCrop();
        String before = page.prepKey();
        boolean hadState = page.recognized() || !page.editedText().isEmpty();
        mutation.run();
        applyPrepToPage(page);
        loadingPage = true;
        try {
            clearResults();
        } finally {
            loadingPage = false;
        }
        refreshPreview();
        refreshActiveThumbnail();
        if (!before.equals(page.prepKey()) || hadState) {
            modified();
        }
        statusLabel.setText("Working image updated (" + what + "). Previous results cleared."
                + (hadCrop && !working.hasCrop() ? " Crop cleared (it applied to the previous orientation)." : ""));
    }

    /** Copies the working image's prep state into the page and returns it to "not recognized". */
    private void applyPrepToPage(PageData page) {
        page.setPrep(working.quarterTurnsCw(), working.fineDegrees(), working.crop());
        page.clearRecognition();
    }

    private void refreshActiveThumbnail() {
        PageData page = currentPage();
        if (page != null && rendered != null) {
            thumbs.put(page.id(), thumbnail(rendered.bgr(), rendered.width(), rendered.height()));
            pageList.refresh();
        }
    }

    private boolean confirmDiscard() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Discard edits?");
        confirm.setHeaderText("This page has transcript edits.");
        confirm.setContentText("Continue and replace them?");
        Optional<ButtonType> choice = confirm.showAndWait();
        return choice.isPresent() && choice.get() == ButtonType.OK;
    }

    private void applyCrop() {
        PageData page = currentPage();
        if (page == null || working == null || jobRunning || cropDraft == null) {
            return;
        }
        if (pageHasEdits() && !confirmDiscard()) {
            return;
        }
        working.setCrop(cropDraft);
        cropDraft = null;
        applyPrepToPage(page);
        loadingPage = true;
        try {
            clearResults();
        } finally {
            loadingPage = false;
        }
        refreshPreview();
        refreshActiveThumbnail();
        modified();
        statusLabel.setText("Crop applied. Previous results cleared.");
    }

    /** Clears all result displays (callers set loadingPage so this is never mistaken for an edit). */
    private void clearResults() {
        regions = new ArrayList<>();
        clearTransientUi();
        transcript.clear();
        rawOutput.clear();
        updateControls();
    }

    // ---- project actions: new / open / save / close ----

    private void confirmUnsavedThen(Runnable proceed) {
        if (!session.isDirty()) {
            proceed.run();
            return;
        }
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", save, discard, cancel);
        a.setTitle("Unsaved changes");
        a.setHeaderText("This project has unsaved changes.");
        a.setContentText("Save them first, discard them, or cancel and keep working?");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() == cancel) {
            return;
        }
        if (r.get() == discard) {
            proceed.run();
            return;
        }
        saveProject(false, proceed); // proceeds only if the save succeeds
    }

    private void newProject() {
        if (jobRunning || saving) {
            return;
        }
        confirmUnsavedThen(() -> {
            session.newProject();
            thumbs.clear();
            deactivatePage();
            statusLabel.setText("New project.");
            updateControls();
        });
    }

    private void openProject() {
        if (jobRunning || saving) {
            return;
        }
        confirmUnsavedThen(this::chooseAndOpenProject);
    }

    private void chooseAndOpenProject() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Project");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("notebook-ai projects (*" + ProjectStore.EXTENSION + ")", "*" + ProjectStore.EXTENSION));
        File f = chooser.showOpenDialog(stage);
        if (f == null) {
            return;
        }
        java.nio.file.Path path = f.toPath();
        // Load fully on a background thread; the active project is replaced only if this succeeds.
        runTask("Opening project…", () -> ProjectStore.load(path), loaded -> {
            session.adopt(loaded, path);
            thumbs.clear();
            activePageId = null;
            if (loaded.size() > 0) {
                activatePage(loaded.pages().get(0).id());
            } else {
                deactivatePage();
            }
            startThumbnailLoader();
            statusLabel.setText("Opened " + path.getFileName() + " (" + loaded.size() + " page(s)).");
            updateControls();
        });
    }

    /** Generates thumbnails for a freshly opened project one page at a time (never all pages decoded at once). */
    private void startThumbnailLoader() {
        long epoch = session.epoch();
        List<PageData> snapshot = new ArrayList<>(session.project().pages());
        Thread t = new Thread(() -> {
            for (PageData p : snapshot) {
                try {
                    WorkingImage.Rendered r = thumbnailSource(p);
                    Image img = thumbnail(r.bgr(), r.width(), r.height());
                    Platform.runLater(() -> {
                        if (session.epoch() == epoch) {
                            thumbs.put(p.id(), img);
                            pageList.refresh();
                        }
                    });
                } catch (Exception ignored) {
                    // A page that cannot render simply keeps a blank thumbnail; activating it reports the error.
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private java.nio.file.Path chooseSaveFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Project As");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("notebook-ai projects (*" + ProjectStore.EXTENSION + ")", "*" + ProjectStore.EXTENSION));
        chooser.setInitialFileName(session.path() == null ? "Untitled" + ProjectStore.EXTENSION
                : session.path().getFileName().toString());
        File f = chooser.showSaveDialog(stage);
        if (f == null) {
            return null;
        }
        java.nio.file.Path p = f.toPath();
        if (!p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(ProjectStore.EXTENSION)) {
            p = p.resolveSibling(p.getFileName() + ProjectStore.EXTENSION);
        }
        return p;
    }

    /**
     * Saves (or Save As). A consistent snapshot is taken on the UI thread and written on a worker;
     * only the snapshot's revision is marked saved, so edits made during the save stay unsaved.
     * {@code afterSuccess} runs only if the save succeeded (cancel/failure never proceed).
     */
    private void saveProject(boolean saveAs, Runnable afterSuccess) {
        if (saving) {
            return;
        }
        java.nio.file.Path target = (saveAs || session.path() == null) ? chooseSaveFile() : session.path();
        if (target == null) {
            return; // cancelled: nothing changes, nothing proceeds
        }
        ProjectSession.SaveJob job = session.beginSave(target);
        saving = true;
        updateControls();
        statusLabel.setText("Saving " + target.getFileName() + "…");
        tasks.run(() -> {
            ProjectStore.save(job.snapshot(), job.target());
            return Boolean.TRUE;
        }, outcome -> {
            saving = false;
            updateControls(); // refresh BEFORE any modal dialog so the window is not left greyed/"saving…"
            boolean ok = false;
            if (outcome.ok()) {
                ok = session.saveSucceeded(job);
                statusLabel.setText("Saved " + target.getFileName() + ".");
            } else {
                statusLabel.setText("Save failed: " + outcome.error());
                Alert a = new Alert(Alert.AlertType.ERROR);
                a.setTitle("Save failed");
                a.setHeaderText("The project was not saved.");
                a.setContentText(outcome.error());
                a.showAndWait();
            }
            updateControls();
            if (afterSuccess != null) {
                if (ShutdownPolicy.mayProceedAfterSave(ok, session.isDirty())) {
                    afterSuccess.run();
                } else if (ok) {
                    // The user kept editing while the save ran: those edits are NOT in the file.
                    statusLabel.setText("Saved, but the project changed during the save; changes made meanwhile are still unsaved.");
                    confirmUnsavedThen(afterSuccess);
                }
            }
            closeIfRequested();
        });
    }

    // ---- export ----

    private void exportCurrent() {
        PageData page = currentPage();
        if (page == null) {
            return;
        }
        int number = session.project().indexOf(page.id()) + 1;
        if (!page.recognized() && page.editedText().isEmpty()) {
            if (!confirmExport("This page has not been recognized, so the exported file would be empty.")) {
                return;
            }
        } else if (page.recognized() && page.editedText().isEmpty()) {
            if (!confirmExport("This page was recognized but its transcript is empty.")) {
                return;
            }
        }
        File f = chooseTextFile("page-" + number + ".txt");
        if (f == null) {
            return;
        }
        try {
            TextExport.writeUtf8(f.toPath(), TextExport.renderOne(TextExport.pages(session.project()).get(number - 1)));
            statusLabel.setText("Exported page " + number + " to " + f.getName() + " (UTF-8).");
        } catch (ProjectException e) {
            exportFailed(e);
        }
    }

    private void exportAll() {
        if (session.project().size() == 0) {
            return;
        }
        List<TextExport.ExportPage> pages = TextExport.pages(session.project());
        List<String> warnings = TextExport.warnings(pages);
        CheckBox labels = new CheckBox("Add \"--- Page N ---\" labels before each page");
        VBox content = new VBox(8);
        content.getChildren().add(new Label(pages.size() + " page(s) in project order, separated by one blank line."));
        for (String w : warnings) {
            Label l = new Label("⚠ " + w);
            l.setWrapText(true);
            content.getChildren().add(l);
        }
        content.getChildren().add(labels);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Export all pages as text");
        a.setHeaderText(warnings.isEmpty() ? "Export the reviewed transcripts?"
                : "Some pages will contribute empty text.");
        a.getDialogPane().setContent(content);
        Optional<ButtonType> choice = a.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        File f = chooseTextFile("all-pages.txt");
        if (f == null) {
            return;
        }
        try {
            TextExport.writeUtf8(f.toPath(), TextExport.renderAll(pages, labels.isSelected()));
            statusLabel.setText("Exported " + pages.size() + " page(s) to " + f.getName() + " (UTF-8).");
        } catch (ProjectException e) {
            exportFailed(e);
        }
    }

    private boolean confirmExport(String reason) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Export page text");
        a.setHeaderText(reason);
        a.setContentText("Export anyway?");
        Optional<ButtonType> choice = a.showAndWait();
        return choice.isPresent() && choice.get() == ButtonType.OK;
    }

    private File chooseTextFile(String suggestion) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Text (UTF-8)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text (*.txt)", "*.txt"));
        String base = session.path() == null ? "Untitled"
                : session.path().getFileName().toString().replaceFirst("\\.nbai$", "");
        chooser.setInitialFileName(base + "-" + suggestion);
        return chooser.showSaveDialog(stage);
    }

    private void exportFailed(ProjectException e) {
        statusLabel.setText("Export failed: " + e.getMessage());
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Export failed");
        a.setHeaderText("The text was not exported.");
        a.setContentText(e.getMessage());
        a.showAndWait();
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
            statusLabel.setText("Crop drafted — Apply Crop to commit, Clear to drop it.");
        } else {
            selection = clipped.get();
            selectedRegion = -1;
            regionList.getSelectionModel().clearSelection();
            statusLabel.setText("Selection ready — Recognize Selection or Recognize Line.");
        }
        drawOverlay(null);
        updateControls();
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
        } else {
            OcrResult.TextRegion r = regions.get(index);
            regionRaw.setText(r.text());
            regionScores.setText(String.format("det=%.3f  rec=%.3f  (detector score is box quality; rec score is mean kept-char confidence)",
                    r.detScore(), r.recScore()));
            int[] box = r.box();
            byte[] cut = WorkingImage.cropBytes(rendered.bgr(), rendered.width(), rendered.height(), box);
            cropThumb.setImage(toFxImage(cut, box[2] - box[0], box[3] - box[1]));
        }
        drawOverlay(null);
        updateControls();
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
        PageData page = currentPage();
        if (page == null || working == null || jobRunning || rendered == null) {
            return;
        }
        if (pageHasEdits() && !confirmReplace()) {
            return;
        }
        String pageId = page.id();
        WorkingImage.Rendered snap = rendered;
        runJob("Extracting text…", () -> {
            OcrService svc = service();
            return svc.recognize(snap.bgr(), snap.width(), snap.height(), null);
        }, result -> {
            // The ticket check in runJob already proved this result belongs to the CURRENT page of the
            // CURRENT project at the SAME image-preparation revision; it is stored on that page by id.
            PageData owner = session.project().find(pageId);
            if (owner == null) {
                return;
            }
            String identity = "unknown";
            try {
                identity = service().persistableIdentity();
                lastIdentity = service().identity();
            } catch (OcrException ignored) {
                // Keep the previous identity line.
            }
            owner.setRecognition(result.combinedText(), identity, toRegionData(result.regions()));
            loadingPage = true;
            try {
                regions = new ArrayList<>(result.regions());
                selectedRegion = -1;
                selection = null;
                cropDraft = null;
                refreshRegionList();
                rawOutput.setText(rawDisplay(owner));
                transcript.setText(owner.editedText());
            } finally {
                loadingPage = false;
            }
            modified();
            pageList.refresh();
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
            // Same assembly as page extraction (reading order + word-gap grouping).
            String assembled = r.combinedText();
            double best = 0;
            for (OcrResult.TextRegion t : r.regions()) {
                best = Math.max(best, t.recScore());
            }
            return new SelResult(kind + " — " + r.regions().size() + " region(s)",
                    assembled, best, r.timings().totalMs(), cw, ch, crop);
        }, result -> {
            // Review results are transient: shown, copyable, insertable, but NOT project content.
            selectionText = result.text;
            selectionKind.setText(result.kind + (result.text.isEmpty() ? " (no text)" : ""));
            selectionOutput.setText(result.text.isEmpty() ? "(no text recognized)" : result.text);
            String time = result.totalMs < 0 ? "" : Math.round(result.totalMs) + " ms total. ";
            selectionTiming.setText(time + String.format("rec=%.3f, crop %dx%d",
                    result.score, result.cw, result.ch));
            statusLabel.setText("Selection done: " + (result.text.isEmpty() ? "no text." : result.text.length() + " chars."));
        });
    }

    private record SelResult(String kind, String text, double score, double totalMs,
            int cw, int ch, byte[] crop) {}

    /**
     * Runs an OCR job owned by a {@link JobTicket}. Page switching and every project action are
     * locked while it runs, AND the result is only applied if the ticket still matches the current
     * project epoch, page, image-preparation revision and job generation, so a result can never land
     * on a different page or project.
     */
    private <T> void runJob(String busyText, JobWork<T> work, JobDone<T> onDone) {
        int gen = ++jobGen;
        JobTicket ticket = new JobTicket(session.epoch(), activePageId, working.revision(), gen);
        jobRunning = true;
        updateControls();
        statusLabel.setText(busyText);
        tasks.run(work::run, outcome -> {
            jobRunning = false; // always restored: the runner delivers exactly one completion, even after an Error
            try {
                boolean pageActive = ticket.pageId() != null && ticket.pageId().equals(activePageId)
                        && working != null && session.project().find(ticket.pageId()) != null;
                boolean accepted = ticket.acceptedBy(session.epoch(), pageActive,
                        pageActive ? working.revision() : -1, jobGen);
                if (!accepted) {
                    statusLabel.setText("Ignored stale result (page, project or image changed mid-job).");
                } else if (!outcome.ok()) {
                    statusLabel.setText("Failed: " + outcome.error());
                } else {
                    try {
                        onDone.accept(outcome.value());
                    } catch (RuntimeException e) {
                        statusLabel.setText("Could not apply the result: " + BackgroundTasks.describe(e));
                    }
                }
            } finally {
                updateControls(); // After onDone so it sees the new results.
                closeIfRequested();
            }
        });
    }

    /** Runs a non-OCR background task (import, project open) under the same UI lock. */
    private <T> void runTask(String busyText, TaskWork<T> work, JobDone<T> onDone) {
        jobRunning = true;
        updateControls();
        statusLabel.setText(busyText);
        tasks.run(work::run, outcome -> {
            jobRunning = false;
            try {
                if (!outcome.ok()) {
                    updateControls(); // refresh BEFORE the modal dialog
                    statusLabel.setText("Failed: " + outcome.error());
                    Alert a = new Alert(Alert.AlertType.ERROR);
                    a.setTitle("Operation failed");
                    a.setHeaderText("The current project was not changed.");
                    a.setContentText(outcome.error());
                    a.showAndWait();
                } else {
                    try {
                        onDone.accept(outcome.value());
                    } catch (RuntimeException e) {
                        statusLabel.setText("Could not apply the result: " + BackgroundTasks.describe(e));
                    }
                }
            } finally {
                updateControls();
                closeIfRequested();
            }
        });
    }

    private interface JobWork<T> {
        T run() throws OcrException;
    }

    private interface TaskWork<T> {
        T run() throws Exception;
    }

    private interface JobDone<T> {
        void accept(T result);
    }

    private boolean confirmReplace() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Extract again?");
        confirm.setHeaderText("This page's transcript has edits.");
        confirm.setContentText("Extracting again will replace your edits. Continue?");
        Optional<ButtonType> choice = confirm.showAndWait();
        return choice.isPresent() && choice.get() == ButtonType.OK;
    }

    /** Re-applies every button's enabled state from current fields (single source of truth). */
    private void updateControls() {
        if (openButton == null || transcript == null || insertSelectionButton == null || pageList == null
                || miNew == null || miExportAll == null || projectLabel == null || stage == null) {
            return; // Window not fully built yet.
        }
        PageData page = currentPage();
        boolean hasRaw = page != null && page.recognized() && !page.rawText().isEmpty();
        ControlState c = ControlState.compute(
                working != null,
                jobRunning,
                selection != null,
                cropDraft != null,
                !transcript.getText().isEmpty(),
                hasRaw,
                !selectionText.isEmpty(),
                !selectedRegionText().isEmpty());
        ProjectActions pa = ProjectActions.compute(session.project().size(),
                activePageId == null ? -1 : session.project().indexOf(activePageId), jobRunning, saving);
        openButton.setDisable(!pa.addImages());
        removePageButton.setDisable(!pa.removePage());
        moveUpButton.setDisable(!pa.moveUp());
        moveDownButton.setDisable(!pa.moveDown());
        pageList.setDisable(!pa.switchPage());
        miNew.setDisable(!pa.newProject());
        miOpen.setDisable(!pa.openProject());
        miSave.setDisable(!pa.save());
        miSaveAs.setDisable(!pa.saveAs());
        miExportCurrent.setDisable(!pa.exportCurrent());
        miExportAll.setDisable(!pa.exportAll());
        extractButton.setDisable(!c.extract());
        recognizeButton.setDisable(!c.recognizeSelection());
        recognizeLineButton.setDisable(!c.recognizeLine());
        rotLeftButton.setDisable(!c.prep());
        rotRightButton.setDisable(!c.prep());
        straightenSlider.setDisable(!c.prep());
        straightenApply.setDisable(!c.prep());
        resetButton.setDisable(!c.prep());
        cropMode.setDisable(!c.cropMode());
        applyCropButton.setDisable(!c.applyCrop());
        clearSelectionButton.setDisable(!c.clearSelection());
        copyButton.setDisable(!c.copyTranscript());
        restoreButton.setDisable(!c.restoreRaw());
        copySelectionButton.setDisable(!c.copySelection());
        insertSelectionButton.setDisable(!c.copySelection());
        copyRegionButton.setDisable(!c.regionActions());
        insertRegionButton.setDisable(!c.regionActions());
        updateTitle();
        pageList.refresh();
    }

    private void updateTitle() {
        String name = session.path() == null ? "Untitled" : session.path().getFileName().toString();
        boolean dirty = session.isDirty();
        stage.setTitle("notebook-ai — " + name + (dirty ? " *" : ""));
        projectLabel.setText("Project: " + name + (session.path() == null ? " (not saved yet)" : "")
                + " — " + session.project().size() + " page(s) — "
                + (saving ? "saving…" : dirty ? "UNSAVED CHANGES" : "all changes saved"));
        projectLabel.setStyle(dirty ? "-fx-font-weight: bold; -fx-text-fill: #b00020;"
                : "-fx-font-weight: bold; -fx-text-fill: #1b5e20;");
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
        PageData page = currentPage();
        if (page == null || !page.recognized() || page.rawText().isEmpty()) {
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
        transcript.setText(page.rawText()); // listener records it as the page's edited text
        statusLabel.setText("Transcript restored to raw OCR output.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
