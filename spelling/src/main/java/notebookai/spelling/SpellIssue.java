package notebookai.spelling;

/**
 * A suspected misspelling: information only, never a text change.
 *
 * <p>{@code start} (inclusive) and {@code end} (exclusive) are <b>UTF-16 code-unit offsets into the exact text that was checked</b>,
 * which is what {@code java.lang.String} and JavaFX {@code TextArea} use. They are only meaningful for that text: any edit invalidates
 * them, so a consumer must tie the issue to the text revision it was computed for and confirm {@code text.substring(start, end).equals(token)}
 * before acting on it.
 */
public record SpellIssue(int start, int end, String token, IssueKind kind) {

    public SpellIssue {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid range " + start + ".." + end);
        }
        if (token == null || token.length() != end - start) {
            throw new IllegalArgumentException("token length must equal the range length");
        }
    }

    public int length() {
        return end - start;
    }
}
