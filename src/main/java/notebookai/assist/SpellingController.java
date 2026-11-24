package notebookai.assist;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import notebookai.spelling.PersonalWords;
import notebookai.spelling.SpellBackend;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordFilter;

/**
 * The spelling-assistance state machine, independent of JavaFX so every rule is unit-testable. It never changes text by itself: a replacement happens only
 * when the user's action calls {@link #replace}, after {@link SafeReplace} has confirmed project, page, text revision, range and token. All methods must be
 * called on the UI thread; results arrive on the UI executor given at construction.
 *
 * <p>Auto-replacement is deliberately NOT implemented (suggestion-first only; see docs/SPELLING.md).
 */
public final class SpellingController implements AutoCloseable {

    /** The editor, as seen by the controller. */
    public interface Editor {
        String text();

        /** Increases on EVERY change of the editor's text, including page switches and programmatic loads. */
        long revision();

        /** The page being edited, or null when there is none. */
        String pageId();

        /** Changes whenever the project is replaced (new/open). */
        long epoch();

        /** Replaces [start, end) through the editor's own undoable edit path. */
        void replaceRange(int start, int end, String replacement);
    }

    public enum Status {
        /** assistance switched off */
        OFF,
        /** no page, or nothing requested yet */
        IDLE,
        /** a check was requested and has not been answered for the current text */
        CHECKING,
        /** issues (possibly none) for the current text are shown */
        READY,
        /** the backend failed; text and editing are unaffected */
        UNAVAILABLE
    }

    /** Notified (on the UI thread) whenever anything shown to the user changed. */
    public interface View {
        void changed();
    }

    private final Editor editor;
    private final SpellBackend backend;
    private final SpellingService service;
    private final View view;
    private final SpellingPreferences prefs;
    private final PersonalDictionaryStore store; // may be null (tests / no persistence)
    private final PersonalWords personal = new PersonalWords();
    private final PersonalWords ignoredAll = new PersonalWords();
    private final IgnoredOccurrences ignoredOnce = new IgnoredOccurrences();

    private boolean enabled;
    private SpellLanguage language;
    private Status status = Status.IDLE;
    private String statusMessage = ""; // backend status (unavailable...), cleared by a successful check
    private String notice = "";        // feedback about the user's last action; persists until the next action
    private SpellingService.Key issuesKey;
    private SpellingService.Key lastRequested;
    private List<SpellIssue> rawIssues = List.of();
    private List<SpellIssue> visibleIssues = List.of();
    private boolean truncated;
    private SpellIssue selected;
    private List<String> suggestions = List.of();
    private long lastEpoch;
    private boolean closed;

    public SpellingController(Editor editor, SpellBackend backend, Executor ui, View view, SpellingPreferences prefs, PersonalDictionaryStore store) {
        this(editor, backend, ui, view, prefs, store, null);
    }

    SpellingController(Editor editor, SpellBackend backend, Executor ui, View view, SpellingPreferences prefs, PersonalDictionaryStore store,
            java.util.concurrent.ExecutorService worker) {
        this.editor = editor;
        this.backend = backend;
        this.view = view;
        this.prefs = prefs;
        this.store = store;
        SpellingService.Listener l = new SpellingService.Listener() {
            @Override
            public void checked(SpellingService.CheckOutcome o) {
                onChecked(o);
            }

            @Override
            public void suggested(SpellingService.SuggestOutcome o) {
                onSuggested(o);
            }
        };
        this.service = worker == null ? new SpellingService(backend, ui, l) : new SpellingService(backend, ui, l, worker);
        this.enabled = prefs.enabled();
        this.language = prefs.language();
        this.lastEpoch = editor.epoch();
        this.status = enabled ? Status.IDLE : Status.OFF;
        if (store != null) {
            PersonalDictionaryStore.Loaded loaded = store.load();
            personal.replaceAll(loaded.entries());
            if (loaded.notice() != null) {
                notice = loaded.notice();
            }
        }
    }

    // ------------------------------------------------------------------ state for the view

    public boolean enabled() { return enabled; }
    public SpellLanguage language() { return language; }
    public Status status() { return status; }
    /** What to show the user: feedback about the last action if any, otherwise the backend status message. */
    public String message() { return notice.isEmpty() ? statusMessage : notice; }
    public List<SpellIssue> issues() { return visibleIssues; }
    public boolean truncated() { return truncated; }
    public SpellIssue selected() { return selected; }
    public List<String> suggestions() { return suggestions; }
    public List<String> personalEntries() { return personal.entries(); }
    public int ignoredOnceCount() { return ignoredOnce.size(); }
    public int ignoredAllCount() { return ignoredAll.size(); }
    public String backendIdentity() { return backend.identity(language); }

    /** The check/selection key for "now": what a result must match to be shown or applied. */
    SpellingService.Key currentKey() {
        return new SpellingService.Key(editor.epoch(), editor.pageId(), editor.revision(), language, personal.revision() + ignoredAll.revision());
    }

    // ------------------------------------------------------------------ settings

    public void setEnabled(boolean on) {
        if (enabled == on) {
            return;
        }
        enabled = on;
        persistPrefs();
        clearIssues();
        if (!on) {
            status = Status.OFF;
            statusMessage = "";
            view.changed();
        } else {
            requestCheck();
        }
    }

    public void setLanguage(SpellLanguage l) {
        if (l == null || l == language) {
            return;
        }
        language = l;
        persistPrefs();
        clearIssues();
        requestCheck();
    }

    private void persistPrefs() {
        prefs.set(enabled, language);
        try {
            prefs.save();
        } catch (IOException e) {
            notice = "Spelling preferences could not be saved (they apply to this session only): " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ checking

    /** The editor text changed (typing, page switch, programmatic load): everything shown is stale. The view debounces and then calls {@link #requestCheck}. */
    public void onTextChanged() {
        clearIssues();
        if (closed) {
            return;
        }
        status = !enabled ? Status.OFF : editor.pageId() == null ? Status.IDLE : Status.CHECKING;
        view.changed();
    }

    /** Starts a check of the current text (no-op if assistance is off, there is no page, or this exact check is already running/answered). */
    public void requestCheck() {
        if (closed) {
            return;
        }
        long epoch = editor.epoch();
        if (epoch != lastEpoch) { // project replaced: per-page ignores belong to the old project
            lastEpoch = epoch;
            ignoredOnce.clear();
        }
        if (!enabled) {
            clearIssues();
            status = Status.OFF;
            view.changed();
            return;
        }
        if (editor.pageId() == null) {
            clearIssues();
            status = Status.IDLE;
            view.changed();
            return;
        }
        SpellingService.Key key = currentKey();
        if (key.equals(lastRequested) && (status == Status.CHECKING || (status == Status.READY && key.equals(issuesKey)))) {
            return;
        }
        lastRequested = key;
        clearIssues();
        status = Status.CHECKING;
        statusMessage = "";
        service.submit(key, editor.text(), personal.or(ignoredAll));
        view.changed();
    }

    private void onChecked(SpellingService.CheckOutcome o) {
        if (closed || !enabled) {
            return;
        }
        SpellingService.Key now = currentKey();
        if (!o.key().equals(now)) { // stale: the text, page, project, language or word lists changed meanwhile
            if (!now.equals(lastRequested) && now.pageId() != null) {
                requestCheck();
            }
            return;
        }
        if (!o.ok()) {
            clearIssues();
            status = Status.UNAVAILABLE;
            statusMessage = "Spelling assistance is unavailable: " + o.error();
            view.changed();
            return;
        }
        issuesKey = o.key();
        rawIssues = o.result().issues();
        truncated = o.result().truncated();
        visibleIssues = ignoredOnce.visible(now.pageId(), rawIssues);
        status = Status.READY;
        statusMessage = "";
        selected = null;
        suggestions = List.of();
        view.changed();
    }

    // ------------------------------------------------------------------ selection and suggestions

    /** Marks {@code issue} (one of {@link #issues()}) as the current one and asks for its ranked candidates. */
    public void select(SpellIssue issue) {
        if (closed || issue == null || issuesKey == null || !issuesKey.equals(currentKey())) {
            return;
        }
        selected = issue;
        suggestions = List.of();
        service.suggest(issuesKey, issue, 8);
        view.changed();
    }

    private void onSuggested(SpellingService.SuggestOutcome o) {
        if (closed || selected == null || !o.issue().equals(selected) || !o.key().equals(currentKey())) {
            return;
        }
        if (o.error() != null) {
            notice = "Suggestions are unavailable: " + o.error();
        } else {
            suggestions = Collections.unmodifiableList(new ArrayList<>(o.suggestions()));
        }
        view.changed();
    }

    // ------------------------------------------------------------------ actions

    /**
     * Replaces the issue's range with {@code replacement} if, and only if, nothing relevant changed since the issue was computed. Returns what happened.
     * A stale request changes nothing and triggers a fresh check.
     */
    public SafeReplace.Result replace(SpellIssue issue, String replacement) {
        if (closed) {
            return SafeReplace.Result.STALE_CONTEXT;
        }
        if (issuesKey == null) { // the issue list was already invalidated (an edit, a page switch...): nothing to replace
            notice = "That issue is out of date, so nothing was replaced. Rechecking.";
            requestCheck();
            view.changed();
            return SafeReplace.Result.STALE_CONTEXT;
        }
        if (replacement == null || replacement.isEmpty() || replacement.indexOf('\n') >= 0 || replacement.indexOf('\r') >= 0) {
            notice = "The replacement must be non-empty and on one line. The text was not changed.";
            view.changed(); // the issue list stays: nothing about the text or the check changed
            return SafeReplace.Result.INVALID_REPLACEMENT;
        }
        SafeReplace.Context was = new SafeReplace.Context(issuesKey.epoch(), issuesKey.pageId(), issuesKey.revision());
        SafeReplace.Context now = new SafeReplace.Context(editor.epoch(), editor.pageId(), editor.revision());
        SafeReplace.Result r = SafeReplace.validate(was, now, editor.text(), issue, replacement);
        if (r == SafeReplace.Result.APPLY) {
            clearIssues();
            editor.replaceRange(issue.start(), issue.end(), replacement); // the editor's text listener marks the page modified
            notice = "Replaced \"" + issue.token() + "\" with \"" + replacement + "\" (Ctrl+Z undoes it).";
            requestCheck();
        } else {
            notice = r == SafeReplace.Result.INVALID_REPLACEMENT
                    ? "The replacement must be non-empty and on one line. The text was not changed."
                    : "That issue is out of date, so nothing was replaced. Rechecking.";
            clearIssues();
            requestCheck();
        }
        view.changed();
        return r;
    }

    /** Hides this one occurrence on this page until the application is restarted or the project is replaced. */
    public void ignoreOnce(SpellIssue issue) {
        if (closed || issuesKey == null || !issuesKey.equals(currentKey())) {
            return;
        }
        if (ignoredOnce.ignore(issuesKey.pageId(), rawIssues, issue)) {
            visibleIssues = ignoredOnce.visible(issuesKey.pageId(), rawIssues);
            if (issue.equals(selected)) {
                selected = null;
                suggestions = List.of();
            }
            notice = "Ignored this occurrence of \"" + issue.token() + "\" (this session only).";
            view.changed();
        }
    }

    /** Ignores the word everywhere (all pages and projects) until the application is restarted; not saved. */
    public void ignoreAll(SpellIssue issue) {
        if (closed || issuesKey == null || !issuesKey.equals(currentKey())) {
            return;
        }
        if (ignoredAll.add(issue.token()) == PersonalWords.AddResult.INVALID) {
            notice = "\"" + issue.token() + "\" cannot be ignored (not a plain word).";
            view.changed();
            return;
        }
        notice = "Ignoring \"" + issue.token() + "\" everywhere until the application is restarted.";
        requestCheck();
    }

    /** Adds the token to the personal dictionary (persistent). A failed write keeps the word for this session and never touches the text. */
    public void addToDictionary(SpellIssue issue) {
        if (closed || issuesKey == null || !issuesKey.equals(currentKey())) {
            return;
        }
        addWord(issue.token());
    }

    /** Adds a word typed in the dictionary manager. Returns what happened. */
    public PersonalWords.AddResult addWord(String word) {
        PersonalWords.AddResult r = personal.add(word);
        if (r == PersonalWords.AddResult.INVALID) {
            notice = "\"" + word + "\" is not a valid dictionary word (letters, apostrophes and hyphens; at least two letters).";
            view.changed();
            return r;
        }
        if (r == PersonalWords.AddResult.DUPLICATE) {
            notice = "\"" + word + "\" is already in the personal dictionary.";
            view.changed();
            return r;
        }
        saveDictionary("Added \"" + PersonalWords.normalizeEntry(word) + "\" to the personal dictionary.");
        requestCheck();
        return r;
    }

    /** Removes an accidentally added word. */
    public boolean removeWord(String entry) {
        boolean removed = personal.remove(entry);
        if (removed) {
            saveDictionary("Removed \"" + entry + "\" from the personal dictionary.");
            requestCheck();
        }
        return removed;
    }

    /** Forgets all Ignore-All words (this session's). */
    public void resetIgnored() {
        ignoredAll.clear();
        ignoredOnce.clear();
        notice = "Ignored words were reset.";
        requestCheck();
    }

    private void saveDictionary(String okMessage) {
        if (store == null) {
            notice = okMessage + " (not saved: no storage)";
            return;
        }
        try {
            store.save(personal.entries());
            notice = okMessage;
        } catch (IOException e) {
            notice = okMessage + " But the dictionary file could not be saved (the change applies to this session only): " + e.getMessage();
        }
    }

    private void clearIssues() {
        issuesKey = null;
        rawIssues = List.of();
        visibleIssues = List.of();
        truncated = false;
        selected = null;
        suggestions = List.of();
    }

    /** Stops all work; no result is delivered afterwards. Does not touch the text or the personal dictionary file. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        service.close();
    }

    /** For tests: the service's pending-work count. */
    int pendingWork() {
        return service.pendingCount();
    }

    WordFilter userFilter() {
        return personal.or(ignoredAll);
    }
}
