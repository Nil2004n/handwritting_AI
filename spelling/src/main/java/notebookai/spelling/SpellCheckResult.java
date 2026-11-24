package notebookai.spelling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one check found. Immutable. Offsets refer to the text that was checked (see {@link SpellIssue}).
 *
 * @param language       the dictionary used
 * @param textLength     length in UTF-16 units of the checked text
 * @param issues         suspected misspellings in text order; at most {@link SpellBackend#MAX_ISSUES}
 * @param truncated      true when more issues existed than were returned
 * @param tokensChecked  how many word tokens were looked up
 * @param tokensSkipped  how many tokens/chunks were deliberately not checked (URLs, e-mail, paths, code-like, identifiers with digits,
 *                       mixed-case words, short acronyms, single letters, other scripts)
 */
public record SpellCheckResult(SpellLanguage language, int textLength, List<SpellIssue> issues, boolean truncated,
        int tokensChecked, int tokensSkipped) {

    public SpellCheckResult {
        issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }
}
