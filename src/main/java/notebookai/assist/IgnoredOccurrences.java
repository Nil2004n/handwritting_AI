package notebookai.assist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import notebookai.spelling.SpellIssue;

/**
 * "Ignore Once": hides ONE occurrence of a token on ONE page for the rest of the session (never persisted). An occurrence is identified as
 * (page id, token exactly as written, ordinal among the same-token issues of the page, counted in text order). Because offsets move when the text is edited,
 * the ordinal - not the offset - is the stable part; the documented limitation is that an edit that adds or removes an EARLIER identical flagged token can shift
 * which later occurrence is hidden. Distinct from "Ignore All" (all pages, until restart, as a {@code PersonalWords} filter) and from the personal dictionary
 * (persistent).
 */
public final class IgnoredOccurrences {

    private record Occurrence(String pageId, String token, int ordinal) {}

    private final Set<Occurrence> hidden = new HashSet<>();

    /** Records that {@code issue} (one of {@code rawIssues}, the unfiltered issues of {@code pageId}) is ignored. */
    public synchronized boolean ignore(String pageId, List<SpellIssue> rawIssues, SpellIssue issue) {
        int ordinal = ordinalOf(rawIssues, issue);
        return ordinal >= 0 && hidden.add(new Occurrence(pageId, issue.token(), ordinal));
    }

    /** The issues of {@code pageId} that are not ignored. */
    public synchronized List<SpellIssue> visible(String pageId, List<SpellIssue> rawIssues) {
        List<SpellIssue> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (SpellIssue i : rawIssues) {
            int ordinal = seen.merge(i.token(), 1, Integer::sum) - 1;
            if (!hidden.contains(new Occurrence(pageId, i.token(), ordinal))) {
                out.add(i);
            }
        }
        return out;
    }

    public synchronized void clear() {
        hidden.clear();
    }

    public synchronized void clearPage(String pageId) {
        hidden.removeIf(o -> o.pageId().equals(pageId));
    }

    public synchronized int size() {
        return hidden.size();
    }

    private static int ordinalOf(List<SpellIssue> rawIssues, SpellIssue issue) {
        int ordinal = 0;
        for (SpellIssue i : rawIssues) {
            if (i.start() == issue.start() && i.end() == issue.end() && i.token().equals(issue.token())) {
                return ordinal;
            }
            if (i.token().equals(issue.token())) {
                ordinal++;
            }
        }
        return -1;
    }
}
