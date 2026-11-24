package notebookai.spelling;

import java.util.List;

/**
 * A spelling backend: <b>checks text and ranks replacement candidates, and never changes text</b>. The contract is platform-neutral so a
 * Windows adapter (Windows Spell Checking API), an Android implementation or the bundled word-list implementation can sit behind it.
 *
 * <p>Thread-safety is part of the contract of each implementation; {@link WordListBackend} is safe for concurrent calls after
 * loading (immutable data, no native resources). {@link #close()} must not be called while a call is running unless the implementation says so.
 */
public interface SpellBackend extends AutoCloseable {

    /** Longest text {@link #check} accepts (UTF-16 units). Longer text throws {@link SpellException}: it is never silently truncated. */
    int MAX_TEXT_LENGTH = 300_000;

    /** Most issues one result carries; the result is flagged {@code truncated} when there were more. */
    int MAX_ISSUES = 1_000;

    /** Human-readable identity of the engine and data (for status lines and documentation); never a claim about another product. */
    String identity(SpellLanguage language);

    /** Whether {@code language} can be checked right now. Loading problems surface from {@link #check} as {@link SpellException}. */
    boolean supports(SpellLanguage language);

    /**
     * Finds suspected misspellings. Pure: the text is not modified, normalized or retained.
     *
     * @param user extra accepted words (personal dictionary and ignore-all); never null (use {@link WordFilter#NONE})
     * @throws SpellException if the text is longer than {@link #MAX_TEXT_LENGTH}, resources are missing/corrupt, or the backend is closed
     */
    SpellCheckResult check(String text, SpellLanguage language, WordFilter user) throws SpellException;

    /**
     * Ranked replacement candidates for one token (best first, at most {@code max}). Deterministic for a given dictionary version,
     * but the ranking is a heuristic: it is NOT a probability and NOT an OCR confidence.
     */
    List<String> suggest(String token, SpellLanguage language, int max) throws SpellException;

    /** Releases resources; later calls throw {@link SpellException}. Idempotent. */
    @Override
    void close();
}
