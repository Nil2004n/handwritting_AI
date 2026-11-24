package notebookai.assist;

import notebookai.spelling.SpellIssue;

/**
 * The checks that must all pass before a suggested/custom replacement may touch the transcript, and the exact edit. Pure functions, no UI.
 * Identity = (project epoch, page id, text revision); then the range must still be inside the text and still hold exactly the flagged token.
 */
public final class SafeReplace {

    private SafeReplace() {}

    /** Where an issue came from / where the editor is now. */
    public record Context(long epoch, String pageId, long revision) {}

    public enum Result {
        /** safe to apply */
        APPLY,
        /** another project, page or text revision than the one the issue was computed for */
        STALE_CONTEXT,
        /** the revision matches but the characters in the range are not the flagged token */
        STALE_TEXT,
        /** the range does not lie inside the current text */
        INVALID_RANGE,
        /** null, empty or multi-line replacement */
        INVALID_REPLACEMENT
    }

    public static Result validate(Context issueContext, Context current, String currentText, SpellIssue issue, String replacement) {
        if (replacement == null || replacement.isEmpty() || replacement.indexOf('\n') >= 0 || replacement.indexOf('\r') >= 0) {
            return Result.INVALID_REPLACEMENT;
        }
        if (issueContext == null || current == null || issueContext.pageId() == null || !issueContext.equals(current)) {
            return Result.STALE_CONTEXT;
        }
        if (issue.start() < 0 || issue.end() > currentText.length() || issue.start() >= issue.end()) {
            return Result.INVALID_RANGE;
        }
        if (!currentText.regionMatches(issue.start(), issue.token(), 0, issue.token().length())) {
            return Result.STALE_TEXT;
        }
        return Result.APPLY;
    }

    /** The text after replacing exactly the issue's range; surrounding characters are untouched. Call only after {@link #validate} returned APPLY. */
    public static String apply(String text, SpellIssue issue, String replacement) {
        return text.substring(0, issue.start()) + replacement + text.substring(issue.end());
    }
}
