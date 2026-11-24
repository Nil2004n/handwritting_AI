package notebookai.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import notebookai.assist.SpellingController.Status;
import notebookai.spelling.PersonalWords;
import notebookai.spelling.SpellBackend;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordListBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The identity/staleness/scope rules of the controller, with a fake editor and a manually driven worker. */
class SpellingControllerTest {

    /** Behaves like the transcript TextArea: every change bumps the revision, page switches too. */
    static final class FakeEditor implements SpellingController.Editor {
        String text = "";
        long revision = 1;
        String page = "p1";
        long epoch = 1;
        int mutations;

        @Override public String text() { return text; }
        @Override public long revision() { return revision; }
        @Override public String pageId() { return page; }
        @Override public long epoch() { return epoch; }

        @Override
        public void replaceRange(int s, int e, String r) {
            text = text.substring(0, s) + r + text.substring(e);
            revision++;
            mutations++;
        }

        void type(String newText) { // user typing / programmatic load
            text = newText;
            revision++;
        }
    }

    @TempDir
    Path dir;

    private final FakeEditor editor = new FakeEditor();
    private ManualWorker worker = new ManualWorker();
    private int viewChanges;
    private SpellingController ctl;
    private SpellBackend backend;

    private static final List<String> WORDS = Arrays.asList("the", "dog", "cat", "word", "words", "hello", "world", "and", "color", "which", "with", "wish");

    private static SpellBackend freshBackend() {
        return WordListBackend.ofWords(SpellLanguage.EN_US, WORDS, List.of("the", "dog"));
    }

    @BeforeEach
    void setUp() {
        backend = freshBackend();
        build(backend, new PersonalDictionaryStore(dir.resolve("dict.txt")));
    }

    private void build(SpellBackend b, PersonalDictionaryStore store) {
        worker = new ManualWorker(); // each controller gets its own (closing a service shuts its worker down)
        SpellingPreferences prefs = new SpellingPreferences(dir.resolve("prefs.properties"));
        ctl = new SpellingController(editor, b, Runnable::run, () -> viewChanges++, prefs, store, worker);
    }

    @AfterEach
    void tearDown() {
        ctl.close();
    }

    private void checkNow() {
        ctl.requestCheck();
        worker.runAll();
    }

    private static List<String> tokens(List<SpellIssue> l) {
        return l.stream().map(SpellIssue::token).toList();
    }

    @Test
    void checkingFindsIssuesWithoutTouchingTheText() {
        editor.text = "the teh dog wrd";
        long rev = editor.revision;
        assertEquals(Status.IDLE, ctl.status());
        ctl.requestCheck();
        assertEquals(Status.CHECKING, ctl.status());
        worker.runAll();
        assertEquals(Status.READY, ctl.status());
        assertEquals(List.of("teh", "wrd"), tokens(ctl.issues()));
        ctl.select(ctl.issues().get(0));
        worker.runAll();
        assertTrue(ctl.suggestions().contains("the"));
        assertEquals("the teh dog wrd", editor.text);
        assertEquals(rev, editor.revision, "checking and selecting change nothing");
        assertEquals(0, editor.mutations);
    }

    @Test
    void replacesExactlyTheSelectedOccurrenceOfARepeatedWord() {
        editor.text = "teh teh teh";
        checkNow();
        SpellIssue middle = ctl.issues().get(1);
        assertEquals(4, middle.start());
        ctl.select(middle);
        worker.runAll();
        assertEquals(SafeReplace.Result.APPLY, ctl.replace(middle, "the"));
        assertEquals("teh the teh", editor.text);
        worker.runAll();
        assertEquals(Status.READY, ctl.status());
        assertEquals(List.of(0, 8), ctl.issues().stream().map(SpellIssue::start).toList(), "rechecked after the replacement");
    }

    @Test
    void customReplacementIsAppliedLikeASuggestion() {
        editor.text = "a wrd here";
        checkNow();
        assertEquals(SafeReplace.Result.APPLY, ctl.replace(ctl.issues().get(0), "a longer phrase"));
        assertEquals("a a longer phrase here", editor.text);
    }

    @Test
    void unusableCustomReplacementsAreRejectedAndChangeNothing() {
        editor.text = "a wrd here";
        checkNow();
        SpellIssue issue = ctl.issues().get(0);
        assertEquals(SafeReplace.Result.INVALID_REPLACEMENT, ctl.replace(issue, "x\ny"));
        assertEquals(SafeReplace.Result.INVALID_REPLACEMENT, ctl.replace(issue, ""));
        assertEquals("a wrd here", editor.text);
        assertEquals(0, editor.mutations);
    }

    @Test
    void anEditAfterTheCheckMakesTheIssueStaleAndNothingIsReplaced() {
        editor.text = "the teh dog";
        checkNow();
        SpellIssue issue = ctl.issues().get(0);
        editor.type("XX the teh dog");               // the user typed: offsets moved, revision changed
        ctl.onTextChanged();
        assertEquals(SafeReplace.Result.STALE_CONTEXT, ctl.replace(issue, "the"));
        assertEquals("XX the teh dog", editor.text, "text and user edits are preserved");
        assertEquals(0, editor.mutations);
        worker.runAll();                              // replace() asked for a recheck of the new text
        assertEquals(Status.READY, ctl.status());
        assertEquals(List.of(7), ctl.issues().stream().map(SpellIssue::start).toList());
    }

    @Test
    void aResultComputedBeforeAnEditIsDroppedAndANewCheckStarts() {
        editor.text = "the teh dog";
        ctl.requestCheck();                           // request for revision r
        editor.type("the dog");                       // edit while the check is queued/running
        ctl.onTextChanged();
        worker.runAll();                              // the stale result arrives and is dropped; the controller asks again
        worker.runAll();
        assertEquals(Status.READY, ctl.status());
        assertEquals(List.of(), ctl.issues(), "the result for the old text must not appear");
    }

    @Test
    void pageSwitchAndProjectSwitchDiscardResultsAndNeverLeakAcrossPages() {
        editor.text = "teh teh";
        ctl.requestCheck();
        editor.page = "p2";                           // page switch: the editor loads other text
        editor.type("the dog");
        ctl.onTextChanged();
        worker.runAll();
        worker.runAll();
        assertEquals(Status.READY, ctl.status());
        assertEquals(List.of(), ctl.issues());
        // project replaced (epoch), same page id and same text
        editor.type("teh");
        checkNow();
        SpellIssue issue = ctl.issues().get(0);
        editor.epoch = 2;
        assertEquals(SafeReplace.Result.STALE_CONTEXT, ctl.replace(issue, "the"));
        assertEquals("teh", editor.text);
    }

    @Test
    void noPageMeansIdleAndNoWork() {
        editor.page = null;
        ctl.requestCheck();
        assertEquals(Status.IDLE, ctl.status());
        assertEquals(0, worker.queued());
    }

    @Test
    void disablingDuringACheckDropsTheResultAndReenablingChecksAgain() {
        editor.text = "teh";
        ctl.requestCheck();
        ctl.setEnabled(false);
        worker.runAll();
        assertEquals(Status.OFF, ctl.status());
        assertEquals(List.of(), ctl.issues());
        ctl.setEnabled(true);
        worker.runAll();
        assertEquals(List.of("teh"), tokens(ctl.issues()));
        assertTrue(loadPrefs().enabled(), "re-enabled and persisted as on");
    }

    @Test
    void shutdownDuringACheckDeliversNothingAndChangesNothing() {
        editor.text = "teh";
        ctl.requestCheck();
        int before = viewChanges;
        ctl.close();
        worker.runAll();
        assertEquals(before, viewChanges, "no UI update after shutdown");
        assertEquals("teh", editor.text);
        assertEquals(0, editor.mutations);
    }

    @Test
    void languageChangeDropsTheOldResultAndUsesTheNewDictionary() {
        try (WordListBackend real = WordListBackend.bundled()) {
            ctl.close();
            build(real, null);
            editor.text = "color colour";
            checkNow();
            assertEquals(List.of("colour"), tokens(ctl.issues()), "en-US");
            ctl.requestCheck();                       // idempotent: same key, already answered
            ctl.setLanguage(SpellLanguage.EN_GB);
            worker.runAll();
            assertEquals(List.of("color"), tokens(ctl.issues()), "en-GB");
            assertEquals(SpellLanguage.EN_GB, loadPrefs().language(), "the choice is persisted");
        }
    }

    private SpellingPreferences loadPrefs() {
        SpellingPreferences p = new SpellingPreferences(dir.resolve("prefs.properties"));
        p.load();
        return p;
    }

    @Test
    void ignoreOnceHidesOneOccurrenceAndIgnoreAllHidesTheWordEverywhere() {
        editor.text = "teh wrd teh";
        checkNow();
        ctl.ignoreOnce(ctl.issues().get(2));          // the second "teh"
        assertEquals(List.of("teh", "wrd"), tokens(ctl.issues()));
        ctl.ignoreAll(ctl.issues().get(1));           // "wrd" everywhere
        worker.runAll();
        assertEquals(List.of("teh"), tokens(ctl.issues()));
        editor.page = "p2";
        editor.type("wrd teh");                       // another page: ignore-all still applies, ignore-once does not
        ctl.onTextChanged();
        checkNow();
        assertEquals(List.of("teh"), tokens(ctl.issues()));
        ctl.resetIgnored();
        worker.runAll();
        assertEquals(List.of("wrd", "teh"), tokens(ctl.issues()));
    }

    @Test
    void personalDictionaryAddRemovePersistsAndRechecks() throws Exception {
        editor.text = "teh Zoltan teh";
        checkNow();
        SpellIssue zoltan = ctl.issues().get(1);
        ctl.addToDictionary(zoltan);
        worker.runAll();
        assertEquals(List.of("teh", "teh"), tokens(ctl.issues()));
        assertTrue(Files.readString(dir.resolve("dict.txt")).contains("Zoltan"));
        assertEquals(List.of("Zoltan"), ctl.personalEntries());
        // a new controller (restart) sees the saved word
        ctl.close();
        build(freshBackend(), new PersonalDictionaryStore(dir.resolve("dict.txt")));
        checkNow();
        assertEquals(List.of("Zoltan"), ctl.personalEntries());
        assertEquals(List.of("teh", "teh"), tokens(ctl.issues()));
        assertTrue(ctl.removeWord("Zoltan"));
        worker.runAll();
        assertEquals(List.of("teh", "Zoltan", "teh"), tokens(ctl.issues()), "an accidentally added word can be removed again");
        assertFalse(ctl.removeWord("Zoltan"));
        assertEquals(PersonalWords.AddResult.INVALID, ctl.addWord("not a word"));
        assertEquals(PersonalWords.AddResult.ADDED, ctl.addWord("teh"));
        assertEquals(PersonalWords.AddResult.DUPLICATE, ctl.addWord("teh"));
    }

    @Test
    void aDictionaryWriteFailureNeverAffectsTheTextAndTheWordWorksForTheSession() throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where a directory is needed");
        ctl.close();
        build(freshBackend(), new PersonalDictionaryStore(blocker.resolve("dict.txt")));
        editor.text = "teh teh";
        checkNow();
        ctl.addToDictionary(ctl.issues().get(0));
        worker.runAll();
        assertTrue(ctl.message().contains("could not be saved"), ctl.message());
        assertEquals("teh teh", editor.text);
        assertEquals(0, editor.mutations);
        assertEquals(List.of(), ctl.issues(), "the word is accepted for this session");
    }

    @Test
    void backendFailureIsReportedAndEditingStaysPossible() {
        ctl.close();
        SpellBackend broken = new WordListBackend(n -> null);
        build(broken, null);
        editor.text = "teh";
        checkNow();
        assertEquals(Status.UNAVAILABLE, ctl.status());
        assertTrue(ctl.message().contains("unavailable"), ctl.message());
        editor.type("teh more typing");               // the editor is not blocked
        ctl.onTextChanged();
        assertEquals("teh more typing", editor.text);
        assertEquals(List.of(), ctl.issues());
    }

    @Test
    void aTranscriptBeyondTheDocumentedLimitIsReportedNotTruncatedAndEditingContinues() {
        editor.text = "teh ".repeat(SpellBackend.MAX_TEXT_LENGTH / 4 + 10);
        assertTrue(editor.text.length() > SpellBackend.MAX_TEXT_LENGTH);
        checkNow();
        assertEquals(Status.UNAVAILABLE, ctl.status());
        assertTrue(ctl.message().contains("too long"), ctl.message());
        assertEquals(List.of(), ctl.issues(), "no partial result is shown for text that was not checked");
        String before = editor.text;
        editor.type(before + "more");
        ctl.onTextChanged();
        assertEquals(before + "more", editor.text, "editing is unaffected");
        editor.type("teh");                         // shorter again: checking works again
        ctl.onTextChanged();
        checkNow();
        assertEquals(Status.READY, ctl.status());
        assertEquals(List.of("teh"), tokens(ctl.issues()));
    }

    @Test
    void requestsAreBoundedWhileTypingQuickly() {
        for (int i = 0; i < 500; i++) {
            editor.type("teh " + i);
            ctl.onTextChanged();
            ctl.requestCheck();
            assertTrue(ctl.pendingWork() <= 2);
            assertTrue(worker.queued() <= 1);
        }
        worker.runAll();
        worker.runAll();
        assertEquals(Status.READY, ctl.status());
        assertNotNull(ctl.issues());
    }
}
