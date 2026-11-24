package notebookai.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import notebookai.assist.PersonalDictionaryStore;
import notebookai.assist.SpellingController;
import notebookai.assist.SpellingPreferences;
import notebookai.assist.UserDataDir;
import notebookai.spelling.SpellBackend;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordListBackend;

/**
 * Spelling assistance as a small issue panel next to the existing transcript {@link TextArea} (the editor is NOT replaced, so manual editing, undo/redo,
 * dirty tracking and the raw-restore flow are untouched). All rules live in {@link SpellingController}; this class only adapts JavaFX to it.
 *
 * <p>The panel tracks a text <i>revision</i> that increases on EVERY change of the transcript text (typing, page switches, OCR results, restore), which is
 * what lets a result computed for older text be recognised and dropped. Checking runs on a background worker after a 400 ms pause; selecting an issue selects
 * its exact range in the transcript and never changes the text. A replacement goes through {@link TextArea#replaceText}, so the built-in undo/redo covers it
 * and the app's existing text listener marks the page modified.
 */
final class SpellingPanel implements SpellingController.Editor, SpellingController.View {

    private static final Duration DEBOUNCE = Duration.millis(400);

    private final TextArea transcript;
    private final Supplier<String> pageId;
    private final LongSupplier epoch;
    private final SpellingController controller;
    private final PauseTransition debounce = new PauseTransition(DEBOUNCE);
    private long revision;
    private boolean refreshing;

    private final VBox root = new VBox(6);
    private final CheckBox enabled = new CheckBox("Spelling assistance");
    private final ComboBox<SpellLanguage> language = new ComboBox<>();
    private final Label status = new Label("");
    private final Label notice = new Label("");
    private final ListView<SpellIssue> issues = new ListView<>();
    private final ListView<String> suggestions = new ListView<>();
    private final TextField custom = new TextField();
    private final Button replace = new Button("Replace");
    private final Button replaceCustom = new Button("Replace with custom");
    private final Button ignoreOnce = new Button("Ignore Once");
    private final Button ignoreAll = new Button("Ignore All");
    private final Button addWord = new Button("Add to Dictionary");
    private final Button undo = new Button("Undo");
    private final Button redo = new Button("Redo");
    private final Button recheck = new Button("Recheck");
    private final ListView<String> personal = new ListView<>();
    private final TextField personalInput = new TextField();
    private final Button personalAdd = new Button("Add");
    private final Button personalRemove = new Button("Remove selected");
    private final Button resetIgnored = new Button("Reset ignored words");
    private final TitledPane personalPane = new TitledPane();

    SpellingPanel(TextArea transcript, Supplier<String> pageId, LongSupplier epoch) {
        this(transcript, pageId, epoch, WordListBackend.bundled(), UserDataDir.resolve());
    }

    /** Explicit backend and data directory (the GUI acceptance test uses a failing backend and an isolated directory). */
    SpellingPanel(TextArea transcript, Supplier<String> pageId, LongSupplier epoch, SpellBackend backend, Path dir) {
        this.transcript = transcript;
        this.pageId = pageId;
        this.epoch = epoch;
        SpellingPreferences prefs = new SpellingPreferences(dir.resolve("spelling.properties"));
        String prefsNotice = prefs.load();
        controller = new SpellingController(this, backend, Platform::runLater, this, prefs,
                new PersonalDictionaryStore(dir.resolve("personal-dictionary.txt")));
        build();
        if (prefsNotice != null) {
            notice.setText(prefsNotice);
        }
        transcript.textProperty().addListener((obs, old, val) -> {
            revision++;
            controller.onTextChanged();
            debounce.playFromStart();
        });
        debounce.setOnFinished(e -> controller.requestCheck());
        transcript.undoableProperty().addListener((o, a, b) -> refresh());
        transcript.redoableProperty().addListener((o, a, b) -> refresh());
        refresh();
        debounce.playFromStart(); // text may already be present
    }

    Node node() {
        return root;
    }

    SpellingController controller() {
        return controller;
    }

    void close() {
        debounce.stop();
        controller.close();
    }

    // ------------------------------------------------------------------ Editor

    @Override public String text() { return transcript.getText(); }
    @Override public long revision() { return revision; }
    @Override public String pageId() { return pageId.get(); }
    @Override public long epoch() { return epoch.getAsLong(); }

    @Override
    public void replaceRange(int start, int end, String replacement) {
        transcript.replaceText(start, end, replacement); // undoable; the app's text listener marks the page modified
        transcript.positionCaret(start + replacement.length());
    }

    // ------------------------------------------------------------------ View

    @Override
    public void changed() {
        refresh();
    }

    // ------------------------------------------------------------------ construction

    private void build() {
        root.setId("spellPanel");
        root.setPadding(new Insets(6, 0, 0, 0));
        enabled.setId("spellEnabled");
        enabled.setSelected(controller.enabled());
        enabled.setOnAction(e -> controller.setEnabled(enabled.isSelected()));
        language.setId("spellLanguage");
        language.setItems(FXCollections.observableArrayList(SpellLanguage.values()));
        language.setValue(controller.language());
        language.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(SpellLanguage l) { return l == null ? "" : l.displayName(); }
            @Override public SpellLanguage fromString(String s) { return SpellLanguage.fromTag(s); }
        });
        language.setOnAction(e -> {
            if (!refreshing && language.getValue() != null) {
                controller.setLanguage(language.getValue());
            }
        });
        status.setId("spellStatus");
        notice.setId("spellNotice");
        notice.setWrapText(true);
        recheck.setId("spellRecheck");
        recheck.setOnAction(e -> controller.requestCheck());
        HBox top = new HBox(10, enabled, language, recheck, status);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        issues.setId("spellIssues");
        issues.setPrefHeight(120);
        issues.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        issues.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(SpellIssue item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : describe(item));
            }
        });
        issues.getSelectionModel().selectedItemProperty().addListener((o, a, issue) -> {
            if (!refreshing && issue != null) {
                controller.select(issue);
                transcript.selectRange(issue.start(), issue.end()); // the exact occurrence, never "the first match"
            }
        });
        suggestions.setId("spellSuggestions");
        suggestions.setPrefHeight(120);
        suggestions.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshButtons());
        custom.setId("spellCustom");
        custom.setPromptText("or type another replacement");
        custom.textProperty().addListener((o, a, b) -> refreshButtons());

        replace.setId("spellReplace");
        replace.setOnAction(e -> {
            SpellIssue i = issues.getSelectionModel().getSelectedItem();
            String s = suggestions.getSelectionModel().getSelectedItem();
            if (i != null && s != null) {
                controller.replace(i, s);
            }
        });
        replaceCustom.setId("spellReplaceCustom");
        replaceCustom.setOnAction(e -> {
            SpellIssue i = issues.getSelectionModel().getSelectedItem();
            if (i != null) {
                controller.replace(i, custom.getText().strip());
            }
        });
        ignoreOnce.setId("spellIgnoreOnce");
        ignoreOnce.setOnAction(e -> act(controller::ignoreOnce));
        ignoreAll.setId("spellIgnoreAll");
        ignoreAll.setOnAction(e -> act(controller::ignoreAll));
        addWord.setId("spellAddWord");
        addWord.setOnAction(e -> act(controller::addToDictionary));
        undo.setId("spellUndo");
        undo.setOnAction(e -> transcript.undo());
        redo.setId("spellRedo");
        redo.setOnAction(e -> transcript.redo());
        ignoreOnce.setTooltip(new Tooltip("Hide this one occurrence on this page until the application is restarted"));
        ignoreAll.setTooltip(new Tooltip("Ignore this word on every page and project until the application is restarted (not saved)"));
        addWord.setTooltip(new Tooltip("Save this word in your personal dictionary (stored in your user profile, never in the project)"));

        VBox left = new VBox(4, new Label("Possible misspellings (unknown is not the same as wrong):"), issues);
        VBox right = new VBox(4, new Label("Suggestions:"), suggestions, custom);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox lists = new HBox(10, left, right);
        HBox actions = new HBox(8, replace, replaceCustom, ignoreOnce, ignoreAll, addWord, undo, redo);

        personal.setId("spellPersonalList");
        personal.setPrefHeight(80);
        personal.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshButtons());
        personalInput.setId("spellPersonalInput");
        personalInput.setPromptText("word to add");
        personalAdd.setId("spellPersonalAdd");
        personalAdd.setOnAction(e -> {
            controller.addWord(personalInput.getText());
            personalInput.clear();
        });
        personalRemove.setId("spellPersonalRemove");
        personalRemove.setOnAction(e -> {
            String s = personal.getSelectionModel().getSelectedItem();
            if (s != null) {
                controller.removeWord(s);
            }
        });
        resetIgnored.setId("spellResetIgnored");
        resetIgnored.setOnAction(e -> controller.resetIgnored());
        personalPane.setId("spellPersonalPane");
        personalPane.setText("Personal dictionary");
        personalPane.setExpanded(false);
        personalPane.setContent(new VBox(4, personal, new HBox(8, personalInput, personalAdd, personalRemove, resetIgnored)));

        root.getChildren().addAll(top, notice, lists, actions, personalPane);
    }

    private void act(java.util.function.Consumer<SpellIssue> action) {
        SpellIssue i = issues.getSelectionModel().getSelectedItem();
        if (i != null) {
            action.accept(i);
        }
    }

    private String describe(SpellIssue i) {
        String t = transcript.getText();
        int from = Math.max(0, i.start() - 14);
        int to = Math.min(t.length(), i.end() + 14);
        if (to <= from) {
            return i.token();
        }
        String context = t.substring(from, to).replace('\n', '¶').replace('\r', ' ');
        String tag = i.kind() == notebookai.spelling.IssueKind.UNKNOWN_CAPITALIZED ? "  (name?)" : "";
        return i.token() + tag + "   …" + context + "…";
    }

    // ------------------------------------------------------------------ refresh (UI thread)

    private void refresh() {
        refreshing = true;
        try {
            enabled.setSelected(controller.enabled());
            if (language.getValue() != controller.language()) {
                language.setValue(controller.language());
            }
            status.setText(statusText());
            status.setTooltip(new Tooltip(controller.backendIdentity()));
            notice.setText(controller.message());
            notice.setManaged(!controller.message().isEmpty());
            notice.setVisible(!controller.message().isEmpty());
            List<SpellIssue> list = controller.issues();
            if (!list.equals(issues.getItems())) {
                issues.getItems().setAll(list);
            }
            SpellIssue sel = controller.selected();
            if (sel == null) {
                issues.getSelectionModel().clearSelection();
            } else if (!sel.equals(issues.getSelectionModel().getSelectedItem())) {
                issues.getSelectionModel().select(sel);
            }
            List<String> sug = controller.suggestions();
            if (!sug.equals(suggestions.getItems())) {
                suggestions.getItems().setAll(sug);
                if (!sug.isEmpty()) {
                    suggestions.getSelectionModel().select(0);
                }
            }
            List<String> entries = controller.personalEntries();
            if (!entries.equals(personal.getItems())) {
                personal.getItems().setAll(entries);
            }
            personalPane.setText("Personal dictionary (" + entries.size() + ")");
            refreshButtons();
        } finally {
            refreshing = false;
        }
    }

    private void refreshButtons() {
        boolean on = controller.enabled();
        boolean hasIssue = on && issues.getSelectionModel().getSelectedItem() != null;
        language.setDisable(false);
        recheck.setDisable(!on);
        issues.setDisable(!on);
        suggestions.setDisable(!on);
        custom.setDisable(!hasIssue);
        replace.setDisable(!hasIssue || suggestions.getSelectionModel().getSelectedItem() == null);
        replaceCustom.setDisable(!hasIssue || custom.getText().strip().isEmpty());
        ignoreOnce.setDisable(!hasIssue);
        ignoreAll.setDisable(!hasIssue);
        addWord.setDisable(!hasIssue);
        undo.setDisable(!transcript.isUndoable());
        redo.setDisable(!transcript.isRedoable());
        personalRemove.setDisable(personal.getSelectionModel().getSelectedItem() == null);
    }

    private String statusText() {
        return switch (controller.status()) {
            case OFF -> "Spelling assistance is off.";
            case IDLE -> "";
            case CHECKING -> "Checking…";
            case READY -> controller.issues().isEmpty() ? "No possible misspellings."
                    : controller.issues().size() + " possible misspelling(s)" + (controller.truncated() ? " (the first 1000 are shown)" : "") + ".";
            case UNAVAILABLE -> "Spelling assistance is unavailable (you can keep editing).";
        };
    }
}
