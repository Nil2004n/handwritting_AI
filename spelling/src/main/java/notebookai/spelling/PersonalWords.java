package notebookai.spelling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A set of user-accepted words with explicit matching rules. In memory only: persistence is the host application's job (the desktop app stores it
 * in a per-user file). Used both as the personal dictionary and, in a second instance that is never saved, as the session's Ignore-All list.
 *
 * <p><b>Entry form</b>: NFC-normalized, typographic apostrophes stored as {@code '}, 2-{@value #MAX_ENTRY} characters, only letters, combining marks,
 * apostrophes and hyphens, at least two letters, no leading/trailing apostrophe or hyphen. Anything else is {@link AddResult#INVALID}.
 * <br><b>Case</b>: an all-lower-case entry matches the word in any case ({@code ocr} accepts OCR, Ocr, ocr); a Title-case entry ({@code Zoltan}) matches
 * itself and its UPPER-CASE form (ZOLTAN) but not the lower-case form; a mixed-case entry matches exactly.
 * <br><b>Duplicates</b>: adding an existing entry is {@link AddResult#DUPLICATE} (no change, no revision bump). Entries differing only in case are different entries.
 * <br><b>Scope</b>: language-independent (shared by all English variants).
 * <br>Thread-safe; {@link #revision()} changes on every effective change so callers can invalidate cached check results.
 */
public final class PersonalWords implements WordFilter {

    public static final int MAX_ENTRY = 64;

    /** Result of {@link #add}. */
    public enum AddResult {
        ADDED, DUPLICATE, INVALID
    }

    private final Set<String> entries = new TreeSet<>();
    private final Set<String> exact = new HashSet<>();
    private final Set<String> lowerForms = new HashSet<>();   // all-lower-case entries
    private final Set<String> titleForms = new HashSet<>();   // Title-case entries (also match their UPPER-CASE token)
    private long revision;

    /** The canonical entry for {@code raw}, or {@code null} when it is not a valid entry. */
    public static String normalizeEntry(String raw) {
        if (raw == null) {
            return null;
        }
        String e = Words.key(raw.trim());
        if (e.isEmpty() || e.length() > MAX_ENTRY) {
            return null;
        }
        int first = e.codePointAt(0);
        int last = e.codePointBefore(e.length());
        if (Words.isApostrophe(first) || Words.isHyphen(first) || Words.isApostrophe(last) || Words.isHyphen(last)) {
            return null;
        }
        for (int i = 0; i < e.length(); ) {
            int cp = e.codePointAt(i);
            i += Character.charCount(cp);
            if (!(Words.isLetterOrMark(cp) || Words.isApostrophe(cp) || Words.isHyphen(cp))) {
                return null; // digits, spaces, punctuation, symbols
            }
        }
        return Words.letterCount(e) >= 2 ? e : null;
    }

    public synchronized AddResult add(String raw) {
        String e = normalizeEntry(raw);
        if (e == null) {
            return AddResult.INVALID;
        }
        if (!entries.add(e)) {
            return AddResult.DUPLICATE;
        }
        index(e);
        revision++;
        return AddResult.ADDED;
    }

    /** Removes the entry equal to {@code raw} after normalization; false when absent. */
    public synchronized boolean remove(String raw) {
        String e = normalizeEntry(raw);
        if (e == null || !entries.remove(e)) {
            return false;
        }
        reindex();
        revision++;
        return true;
    }

    /** Replaces all entries (invalid ones are skipped and counted). Returns the number skipped. */
    public synchronized int replaceAll(Collection<String> raws) {
        Set<String> next = new TreeSet<>();
        int skipped = 0;
        for (String r : raws) {
            String e = normalizeEntry(r);
            if (e == null) {
                skipped++;
            } else {
                next.add(e);
            }
        }
        if (!next.equals(entries)) {
            entries.clear();
            entries.addAll(next);
            reindex();
            revision++;
        }
        return skipped;
    }

    public synchronized void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            reindex();
            revision++;
        }
    }

    /** Sorted copy of the entries. */
    public synchronized List<String> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long revision() {
        return revision;
    }

    @Override
    public synchronized boolean accepts(String token) {
        if (entries.isEmpty() || token == null) {
            return false;
        }
        String k = Words.key(token);
        if (exact.contains(k)) {
            return true;
        }
        String lower = Words.lower(k);
        if (lowerForms.contains(lower)) {
            return true;
        }
        return !titleForms.isEmpty() && Words.shape(k) == Words.Shape.UPPER && titleForms.contains(Words.title(lower));
    }

    private void index(String e) {
        exact.add(e);
        Words.Shape s = Words.shape(e);
        if (s == Words.Shape.LOWER) {
            lowerForms.add(e);
        } else if (s == Words.Shape.TITLE) {
            titleForms.add(e);
        }
    }

    private void reindex() {
        exact.clear();
        lowerForms.clear();
        titleForms.clear();
        for (String e : entries) {
            index(e);
        }
    }
}
