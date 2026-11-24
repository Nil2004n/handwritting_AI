package notebookai.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import notebookai.assist.SafeReplace;
import notebookai.assist.SpellingController;
import notebookai.project.PageData;
import notebookai.project.ProjectSession;
import notebookai.project.ProjectStore;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordFilter;
import notebookai.spelling.WordListBackend;

/**
 * Desktop GUI acceptance of the spelling assistance. Runs the REAL {@link NotebookAiApp} (real OCR engine, real spelling backend, real project store) in-process
 * under a display (Xvfb), on generated non-private images, in an isolated per-user data directory. Every check reads actual state: control contents, the
 * transcript text and selection, project files re-read from disk, exported bytes. Native file/confirm dialogs cannot be driven, so the flows enter at the
 * point right after the chooser (the app code was split there without changing behavior) and the one confirmation dialog (restore raw) is answered through
 * its real DialogPane. Run with: {@code xvfb-run -a ./gradlew spellingGuiAcceptance}.
 */
public final class SpellingGuiAcceptance {

    private static int failures;
    private static int passes;
    private static final List<String> LOG = new ArrayList<>();

    private static NotebookAiApp app;
    private static Stage stage;
    private static Path work;
    private static Path projectPath;

    // ------------------------------------------------------------------ plumbing

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passes++;
        } else {
            failures++;
        }
        String line = (ok ? "PASS  " : "FAIL  ") + name + (detail == null || detail.isEmpty() ? "" : "  [" + detail + "]");
        LOG.add(line);
        System.out.println(line);
    }

    private static <T> T fx(Callable<T> c) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(60, TimeUnit.SECONDS);
    }

    private static void fxRun(Runnable r) throws Exception {
        fx(() -> {
            r.run();
            return null;
        });
    }

    private static void until(String what, Callable<Boolean> cond, int seconds) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            if (fx(cond)) {
                return;
            }
            Thread.sleep(60);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    private static Object get(Object o, String field) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object invoke(Object o, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T node(String id) {
        Node n = stage.getScene().lookup("#" + id);
        if (n == null) {
            throw new IllegalStateException("control not found: #" + id);
        }
        return (T) n;
    }

    private static ProjectSession session() throws Exception {
        return (ProjectSession) get(app, "session");
    }

    private static TextArea transcript() {
        return node("transcript");
    }

    private static SpellingPanel panel() throws Exception {
        return (SpellingPanel) get(app, "spelling");
    }

    private static SpellingController ctl() throws Exception {
        return panel().controller();
    }

    private static PageData page(int i) throws Exception {
        return session().project().pages().get(i);
    }

    private static String activePageId() throws Exception {
        return (String) get(app, "activePageId");
    }

    private static void startApp() throws Exception {
        fxRun(() -> {
            try {
                app = new NotebookAiApp();
                stage = new Stage();
                app.start(stage);
                stage.show();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void stopApp() throws Exception {
        fxRun(() -> {
            app.stop();
            stage.close();
        });
    }

    private static List<String> tokens(List<SpellIssue> issues) {
        return issues.stream().map(SpellIssue::token).toList();
    }

    private static List<SpellIssue> independentCheck(String text, SpellLanguage lang) throws Exception {
        try (WordListBackend b = WordListBackend.bundled()) {
            return b.check(text, lang, WordFilter.NONE).issues();
        }
    }

    private static void waitReady() throws Exception {
        until("spelling READY for the current text", () -> ctl().status() == SpellingController.Status.READY, 30);
    }

    private static void activate(String id) throws Exception {
        fxRun(() -> {
            try {
                invoke(app, "activatePage", new Class<?>[] {String.class}, id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void extractCurrent() throws Exception {
        PageData p = page(indexOfActive());
        fxRun(() -> ((Button) getQuiet("extractButton")).fire());
        until("OCR result for " + p.name(), () -> p.recognized(), 120);
        until("transcript shows the OCR text", () -> !transcript().getText().isEmpty() || p.editedText().isEmpty(), 10);
    }

    private static Object getQuiet(String f) {
        try {
            return get(app, f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOfActive() throws Exception {
        return session().project().indexOf(activePageId());
    }

    // ------------------------------------------------------------------ input images (generated, non-private)

    private static void render(Path file, String... lines) throws Exception {
        BufferedImage img = new BufferedImage(1500, 130 + lines.length * 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("DejaVu Sans", Font.PLAIN, 52));
        int y = 100;
        for (String l : lines) {
            g.drawString(l, 60, y);
            y += 120;
        }
        g.dispose();
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    // ------------------------------------------------------------------ the scenario

    public static void main(String[] args) throws Exception {
        work = Files.createTempDirectory("nbai-spell-gui-");
        Path cfg = work.resolve("cfg");
        System.setProperty("notebookai.config.dir", cfg.toString());
        System.out.println("isolated work directory: " + work);
        Path p1 = work.resolve("in/page1.png");
        Path p2 = work.resolve("in/page2.png");
        render(p1, "I recieve teh book and teh pen.", "We definately seperate wich ones.");
        render(p2, "A short note about my home.", "My adress is teh same as before.");
        Platform.startup(() -> { });
        int exit;
        try {
            mainFlow(p1, p2, cfg);
            backendFailureAndDisabled();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL  scenario aborted: " + t);
            t.printStackTrace(System.out);
        } finally {
            try {
                if (app != null) {
                    stopApp();
                }
            } catch (Throwable ignored) {
                // best effort
            }
            exit = failures == 0 ? 0 : 1;
            System.out.println("\nRESULT: " + passes + " passed, " + failures + " failed");
            System.out.println(failures == 0 ? "ALL GUI ACCEPTANCE CHECKS PASSED" : "SOME GUI ACCEPTANCE CHECKS FAILED");
            Path summary = work.resolve("result.txt");
            Files.write(summary, LOG, StandardCharsets.UTF_8);
        }
        Platform.exit();
        System.exit(exit);
    }

    private static void mainFlow(Path p1, Path p2, Path cfg) throws Exception {
        startApp();
        fxRun(() -> app.addImageFiles(List.of(p1.toFile(), p2.toFile())));
        until("two pages imported and the first active", () -> {
            try {
                return session().project().size() == 2 && activePageId() != null;
            } catch (Exception e) {
                return false;
            }
        }, 60);
        PageData pg1 = page(0);
        PageData pg2 = page(1);
        check("start: spelling panel present with assistance ON and no issues before extraction",
                ((CheckBox) node("spellEnabled")).isSelected() && ctl().issues().isEmpty(), "status=" + ctl().status());

        // ---- A. extract and see issues
        extractCurrent();
        waitReady();
        String ocr1 = fx(() -> transcript().getText());
        String raw1 = pg1.rawText();
        List<SpellIssue> expected = independentCheck(ocr1, SpellLanguage.EN_US);
        List<SpellIssue> shown = fx(() -> new ArrayList<>(ctl().issues()));
        List<SpellIssue> listItems = fx(() -> new ArrayList<>(this_issuesList().getItems()));
        check("A: OCR text read from the real engine", !ocr1.isEmpty(), "ocr=" + ocr1.replace("\n", "|"));
        check("A: the issue list shows exactly what an independent check of the transcript finds", expected.equals(shown) && shown.equals(listItems),
                "issues=" + tokens(shown));
        check("A: at least the deliberate misspellings that OCR preserved are flagged",
                shown.stream().anyMatch(i -> List.of("recieve", "teh", "definately", "seperate", "wich").contains(i.token().toLowerCase())), tokens(shown).toString());
        check("A: raw OCR text equals the transcript before any edit and is shown unchanged in the raw box",
                ocr1.equals(raw1) && fx(() -> ((TextArea) node("rawOutput")).getText()).equals(raw1), "");

        // ---- dirty tracking: checking and selection must not dirty the document
        Path projectFile = work.resolve("project.nbai");
        projectPath = projectFile;
        fxRun(() -> app.saveProjectTo(projectFile, null));
        until("project saved", () -> {
            try {
                return !session().isDirty() && Files.exists(projectFile);
            } catch (Exception e) {
                return false;
            }
        }, 30);
        fxRun(() -> ((Button) node("spellRecheck")).fire());
        waitReady();
        String firstToken = shown.get(0).token();
        fxRun(() -> this_issuesList().getSelectionModel().select(0));
        until("suggestions for the selected issue", () -> !ctl().suggestions().isEmpty(), 20);
        check("dirty: checking, selecting an issue and loading suggestions do NOT mark the project modified", !fx(() -> session().isDirty()), "");

        // ---- B. select a specific occurrence
        SpellIssue target = null;
        int targetIndex = -1;
        for (int i = 0; i < shown.size(); i++) {
            for (int j = i + 1; j < shown.size(); j++) {
                if (shown.get(i).token().equals(shown.get(j).token())) {
                    target = shown.get(j);
                    targetIndex = j;
                }
            }
        }
        if (target == null) { // OCR changed the text: fall back to the last issue
            targetIndex = shown.size() - 1;
            target = shown.get(targetIndex);
        }
        final SpellIssue chosen = target;
        final int chosenIndex = targetIndex;
        fxRun(() -> this_issuesList().getSelectionModel().select(chosenIndex));
        until("selection in the transcript is the chosen occurrence", () -> transcript().getSelection().getStart() == chosen.start(), 10);
        String selectedText = fx(() -> transcript().getSelectedText());
        int firstOccurrence = ocr1.indexOf(chosen.token());
        check("B: the exact current occurrence is selected (not the first matching word)",
                selectedText.equals(chosen.token()) && fx(() -> transcript().getSelection().getStart()) == chosen.start()
                        && (chosen.start() != firstOccurrence || shown.stream().filter(i -> i.token().equals(chosen.token())).count() == 1),
                "token=" + chosen.token() + " start=" + chosen.start() + " firstOccurrenceOfTheWord=" + firstOccurrence);
        check("B: selecting does not dirty the project", !fx(() -> session().isDirty()), "");

        // ---- C. accept a suggestion
        until("suggestions loaded", () -> !ctl().suggestions().isEmpty(), 20);
        List<String> suggestions = fx(() -> new ArrayList<>(this_suggestionList().getItems()));
        String pick = suggestions.get(0);
        fxRun(() -> this_suggestionList().getSelectionModel().select(pick));
        String before = fx(() -> transcript().getText());
        fxRun(() -> ((Button) node("spellReplace")).fire());
        String expectedAfter = SafeReplace.apply(before, chosen, pick);
        until("replacement applied", () -> transcript().getText().equals(expectedAfter), 10);
        check("C: accepting a suggestion replaced only the selected occurrence (surroundings untouched)", true,
                "'" + chosen.token() + "' -> '" + pick + "'  text now: " + expectedAfter.replace("\n", "|"));
        check("C: the replacement marked the project modified and the raw OCR text is unchanged",
                fx(() -> session().isDirty()) && pg1.rawText().equals(raw1) && fx(() -> ((TextArea) node("rawOutput")).getText()).equals(raw1)
                        && pg1.editedText().equals(expectedAfter), "page edited text stored = " + pg1.editedText().replace("\n", "|"));
        waitReady();

        // ---- G. undo / redo
        fxRun(() -> ((Button) node("spellUndo")).fire());
        until("undo restored the earlier text", () -> transcript().getText().equals(before), 10);
        check("G: Undo reverts the replacement exactly", true, "");
        fxRun(() -> ((Button) node("spellRedo")).fire());
        until("redo re-applied the replacement", () -> transcript().getText().equals(expectedAfter), 10);
        check("G: Redo re-applies it", true, "");
        waitReady();

        // ---- D. custom replacement (another occurrence/word)
        List<SpellIssue> now = fx(() -> new ArrayList<>(ctl().issues()));
        if (now.isEmpty()) {
            check("D: precondition (another issue remains)", false, "no issues left");
        } else {
            SpellIssue other = now.get(0);
            fxRun(() -> this_issuesList().getSelectionModel().select(0));
            until("issue selected", () -> transcript().getSelection().getStart() == other.start(), 10);
            String textBefore = fx(() -> transcript().getText());
            fxRun(() -> ((TextField) node("spellCustom")).setText("handwriting"));
            fxRun(() -> ((Button) node("spellReplaceCustom")).fire());
            String exp = SafeReplace.apply(textBefore, other, "handwriting");
            until("custom replacement applied", () -> transcript().getText().equals(exp), 10);
            check("D: a custom replacement replaced only the selected range", true, "'" + other.token() + "' -> 'handwriting'");
            waitReady();
        }

        // ---- E. ignore once / ignore all
        List<SpellIssue> cur = fx(() -> new ArrayList<>(ctl().issues()));
        if (cur.size() >= 2) {
            String textBeforeIgnore = fx(() -> transcript().getText());
            fxRun(() -> this_issuesList().getSelectionModel().select(0));
            until("issue 0 selected", () -> ctl().selected() != null, 10);
            int n0 = cur.size();
            fxRun(() -> ((Button) node("spellIgnoreOnce")).fire());
            until("one occurrence hidden", () -> ctl().issues().size() == n0 - 1, 10);
            check("E: Ignore Once hides exactly one occurrence and does not touch the text",
                    fx(() -> transcript().getText()).equals(textBeforeIgnore) && fx(() -> ctl().issues().size()) == n0 - 1, "remaining=" + tokens(fx(() -> ctl().issues())));
            String ignoreAllToken = fx(() -> ctl().issues().get(0).token());
            long sameBefore = fx(() -> ctl().issues().stream().filter(i -> i.token().equals(ignoreAllToken)).count());
            fxRun(() -> this_issuesList().getSelectionModel().select(0));
            until("issue selected for ignore-all", () -> ctl().selected() != null, 10);
            fxRun(() -> ((Button) node("spellIgnoreAll")).fire());
            until("word hidden everywhere", () -> ctl().issues().stream().noneMatch(i -> i.token().equals(ignoreAllToken)), 10);
            check("E: Ignore All hides every occurrence of the word (" + sameBefore + " here); text untouched",
                    fx(() -> transcript().getText()).equals(textBeforeIgnore), "ignored '" + ignoreAllToken + "'");
        } else {
            check("E: precondition (at least two issues)", false, "issues=" + tokens(cur));
        }

        // ---- F. personal dictionary add / remove
        fxRun(() -> ((TitledPane) node("spellPersonalPane")).setExpanded(true));
        fxRun(() -> ((Button) node("spellResetIgnored")).fire());   // bring ignored words back so there is something to add
        waitReady();
        List<SpellIssue> forDict = fx(() -> new ArrayList<>(ctl().issues()));
        if (forDict.isEmpty()) {
            check("F: precondition (an issue to add)", false, "none");
        } else {
            SpellIssue w = forDict.get(forDict.size() - 1);
            fxRun(() -> this_issuesList().getSelectionModel().select(forDict.size() - 1));
            until("issue selected for add", () -> ctl().selected() != null, 10);
            String textBeforeAdd = fx(() -> transcript().getText());
            fxRun(() -> ((Button) node("spellAddWord")).fire());
            Path dictFile = cfg.resolve("personal-dictionary.txt");
            until("dictionary file written", () -> Files.exists(dictFile), 10);
            String content = Files.readString(dictFile, StandardCharsets.UTF_8);
            check("F: Add to Dictionary stored the word in the PER-USER file (not the project/install)", content.contains(w.token()) && dictFile.startsWith(cfg),
                    "file=" + dictFile.getFileName() + " content=" + content.replace("\n", "|"));
            waitReady();
            check("F: the word is no longer flagged, the list shows it, and the text is unchanged",
                    fx(() -> ctl().issues().stream().noneMatch(i -> i.token().equals(w.token())))
                            && fx(() -> this_personalList().getItems().contains(w.token())) && fx(() -> transcript().getText()).equals(textBeforeAdd), "");
            fxRun(() -> this_personalList().getSelectionModel().select(w.token()));
            fxRun(() -> ((Button) node("spellPersonalRemove")).fire());
            until("word removed from the file", () -> !Files.readString(dictFile, StandardCharsets.UTF_8).contains(w.token()), 10);
            waitReady();
            check("F: removing the word restores the flag", fx(() -> ctl().issues().stream().anyMatch(i -> i.token().equals(w.token()))), "");
            // keep one word for the restart check (M)
            fxRun(() -> ((TextField) node("spellPersonalInput")).setText("Zoltan"));
            fxRun(() -> ((Button) node("spellPersonalAdd")).fire());
            until("Zoltan stored", () -> Files.readString(dictFile, StandardCharsets.UTF_8).contains("Zoltan"), 10);
        }

        // ---- I. edit while checking; stale results can never replace text
        waitReady();
        List<SpellIssue> live = fx(() -> new ArrayList<>(ctl().issues()));
        SpellIssue old = live.isEmpty() ? null : live.get(0);
        if (old == null) {
            check("I: precondition (an issue exists)", false, "");
        } else {
            String[] staleResult = new String[2];
            fxRun(() -> {
                try {
                    ctl().requestCheck();                                     // a check for the current text...
                    transcript().insertText(0, "XX ");                        // ...and the user types before it finishes: every offset shifts
                    SafeReplace.Result r = ctl().replace(old, "zzz");        // a stale issue is applied
                    staleResult[0] = r.name();
                    staleResult[1] = transcript().getText();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            check("I: a stale issue (edit after the check) is refused and changes nothing", "STALE_CONTEXT".equals(staleResult[0]) && !staleResult[1].contains("zzz")
                    && staleResult[1].startsWith("XX "), "result=" + staleResult[0]);
            waitReady();
            String finalText = fx(() -> transcript().getText());
            check("I: after the edit the issues are recomputed for the NEW text only", independentCheckWithIgnores(finalText).equals(tokens(fx(() -> new ArrayList<>(ctl().issues())))),
                    "issues=" + tokens(fx(() -> new ArrayList<>(ctl().issues()))));
        }

        // ---- H. page switching / isolation
        String page1Text = fx(() -> transcript().getText());
        activate(pg2.id());
        until("page 2 shown", () -> pg2.id().equals(activePageId()) && transcript().getText().equals(pg2.editedText()), 20);
        check("H: switching pages shows the other page's own (still empty) transcript and none of page 1's issues",
                fx(() -> transcript().getText()).isEmpty() && fx(() -> ctl().issues().isEmpty()), "issues=" + tokens(fx(() -> new ArrayList<>(ctl().issues()))));
        extractCurrent();
        waitReady();
        String page2Text = fx(() -> transcript().getText());
        check("H: page 2's issues are those of page 2's text", independentCheckWithIgnores(page2Text).equals(tokens(fx(() -> new ArrayList<>(ctl().issues())))),
                "page2=" + page2Text.replace("\n", "|") + " issues=" + tokens(fx(() -> new ArrayList<>(ctl().issues()))));
        check("H: page 1's edits and page 2's text are isolated", !page2Text.equals(page1Text) && pg1.editedText().equals(page1Text) && pg2.editedText().equals(page2Text), "");
        activate(pg1.id());
        until("page 1 shown again", () -> pg1.id().equals(activePageId()) && transcript().getText().equals(pg1.editedText()), 20);
        waitReady();
        check("H: back on page 1 the text is intact (with its replacements) and the issues are recomputed for it",
                fx(() -> transcript().getText()).equals(page1Text)
                        && independentCheckWithIgnores(page1Text).equals(tokens(fx(() -> new ArrayList<>(ctl().issues())))), "");

        // ---- J. save / reopen
        String edited1 = pg1.editedText();
        String rawBefore1 = pg1.rawText();
        String edited2 = pg2.editedText();
        fxRun(() -> app.saveProjectTo(projectFile, null));
        until("saved", () -> !session().isDirty(), 30);
        PageData[] onDisk = ProjectStore.load(projectFile).pages().toArray(new PageData[0]);
        check("J: the saved file holds both pages' edited AND raw text exactly",
                onDisk[0].editedText().equals(edited1) && onDisk[0].rawText().equals(rawBefore1) && onDisk[1].editedText().equals(edited2)
                        && onDisk[0].rawText().equals(raw1), "page1 edited=" + edited1.replace("\n", "|"));
        fxRun(() -> app.openProjectFile(projectFile));
        until("project reopened", () -> {
            try {
                return session().project().size() == 2 && activePageId() != null && transcript().getText().equals(edited1); // already on the FX thread: no nested fx()
            } catch (Exception e) {
                return false;
            }
        }, 60);
        waitReady();
        check("J: reopening restores the reviewed text exactly and spelling checks it again", fx(() -> transcript().getText()).equals(edited1)
                && page(0).rawText().equals(raw1) && !fx(() -> session().isDirty()), "");

        // ---- K. export
        Path exportFile = work.resolve("page1.txt");
        fxRun(() -> app.exportCurrentTo(exportFile.toFile(), 1));
        byte[] exported = Files.readAllBytes(exportFile);
        check("K: the exported UTF-8 file equals the reviewed transcript byte for byte", new String(exported, StandardCharsets.UTF_8).equals(edited1)
                && java.util.Arrays.equals(exported, edited1.getBytes(StandardCharsets.UTF_8)), "bytes=" + exported.length);

        // ---- L. restore raw through the existing confirmation
        Platform.runLater(() -> {
            try {
                invoke(app, "restoreRaw", new Class<?>[0]);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        until("the confirmation dialog appears", () -> Window.getWindows().stream().anyMatch(w -> w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane), 20);
        fxRun(() -> Window.getWindows().stream().filter(w -> w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane).findFirst()
                .ifPresent(w -> ((Button) ((DialogPane) w.getScene().getRoot()).lookupButton(ButtonType.OK)).fire()));
        until("transcript restored to the raw OCR text", () -> transcript().getText().equals(raw1), 20);
        waitReady();
        check("L: Restore Raw (through the existing confirmation) returned the transcript to the exact raw OCR text; spelling rechecked it",
                fx(() -> transcript().getText()).equals(raw1) && independentCheckWithIgnores(raw1).equals(tokens(fx(() -> new ArrayList<>(ctl().issues())))), "");

        // ---- M. restart: preferences and personal dictionary persist
        fxRun(() -> this_languageBox().getSelectionModel().select(SpellLanguage.EN_GB));
        until("language switched", () -> ctl().language() == SpellLanguage.EN_GB, 10);
        stopApp();
        startApp();
        until("restarted app shows its panel", () -> stage.getScene().lookup("#spellPanel") != null, 20);
        String prefs = Files.readString(cfg.resolve("spelling.properties"), StandardCharsets.UTF_8);
        check("M: after a restart the language preference and the personal dictionary are loaded from the per-user directory",
                fx(() -> ctl().language()) == SpellLanguage.EN_GB && fx(() -> ctl().personalEntries()).contains("Zoltan") && prefs.contains("language=en-GB"),
                "prefs=" + prefs.replace("\n", "|") + " personal=" + fx(() -> ctl().personalEntries()));
    }

    /** Issues an independent check would show given the controller's current ignore-all/personal words (none are active when this is used). */
    private static List<String> independentCheckWithIgnores(String text) throws Exception {
        SpellLanguage lang = fx(() -> ctl().language());
        List<String> personal = fx(() -> ctl().personalEntries());
        notebookai.spelling.PersonalWords pw = new notebookai.spelling.PersonalWords();
        pw.replaceAll(personal);
        try (WordListBackend b = WordListBackend.bundled()) {
            return tokens(b.check(text, lang, pw).issues());
        }
    }

    private static ComboBox<SpellLanguage> this_languageBox() {
        return node("spellLanguage");
    }

    private static ListView<SpellIssue> this_issuesList() {
        return node("spellIssues");
    }

    private static ListView<String> this_suggestionList() {
        return node("spellSuggestions");
    }

    private static ListView<String> this_personalList() {
        return node("spellPersonalList");
    }

    // ------------------------------------------------------------------ N: failure and disabled keep editing possible

    private static void backendFailureAndDisabled() throws Exception {
        // a real, failing backend: the bundled loader pointed at resources that do not exist
        Path dir = work.resolve("cfg-failing");
        TextArea[] area = new TextArea[1];
        SpellingPanel[] sp = new SpellingPanel[1];
        Stage[] st = new Stage[1];
        fxRun(() -> {
            area[0] = new TextArea("teh quick text");
            sp[0] = new SpellingPanel(area[0], () -> "page-x", () -> 1L, new WordListBackend(n -> null), dir);
            st[0] = new Stage();
            st[0].setScene(new javafx.scene.Scene(new javafx.scene.layout.VBox(area[0], sp[0].node()), 900, 500));
            st[0].show();
        });
        until("failing backend reported", () -> sp[0].controller().status() == SpellingController.Status.UNAVAILABLE, 20);
        String status = fx(() -> ((Label) st[0].getScene().lookup("#spellStatus")).getText());
        String notice = fx(() -> ((Label) st[0].getScene().lookup("#spellNotice")).getText());
        check("N: a failing backend shows a clear unavailable status and message", status.contains("unavailable") && notice.contains("unavailable"), status + " / " + notice);
        fxRun(() -> area[0].appendText(" and more typing"));
        check("N: manual editing still works while spelling is unavailable", fx(() -> area[0].getText()).equals("teh quick text and more typing"), "");
        fxRun(() -> st[0].close());

        // disabling in the real app (restarted by the previous scenario; reopen the saved project so a page is active)
        startAppIfNeeded();
        fxRun(() -> app.openProjectFile(projectPath));
        until("project reopened for the last scenario", () -> {
            try {
                return session().project().size() == 2 && activePageId() != null;
            } catch (Exception e) {
                return false;
            }
        }, 60);
        waitReady();
        fxRun(() -> ((CheckBox) node("spellEnabled")).fire());
        until("assistance off", () -> !ctl().enabled() && ctl().status() == SpellingController.Status.OFF, 10);
        String before = fx(() -> transcript().getText());
        fxRun(() -> transcript().appendText(" typed while off"));
        Thread.sleep(700);
        check("N: with spelling disabled editing works, no issues appear and the status says it is off",
                fx(() -> transcript().getText()).equals(before + " typed while off") && fx(() -> ctl().issues().isEmpty())
                        && fx(() -> ((Label) node("spellStatus")).getText()).contains("off"), "");
        fxRun(() -> ((CheckBox) node("spellEnabled")).fire());
        until("assistance back on and checked", () -> ctl().enabled() && ctl().status() == SpellingController.Status.READY, 30);
        String finalText = fx(() -> transcript().getText());
        check("N: re-enabling checks the current text again (issues match an independent check)",
                independentCheckWithIgnores(finalText).equals(tokens(fx(() -> new ArrayList<>(ctl().issues())))), "issues=" + tokens(fx(() -> new ArrayList<>(ctl().issues()))));
    }

    private static void startAppIfNeeded() throws Exception {
        if (app == null) {
            startApp();
        }
    }
}
