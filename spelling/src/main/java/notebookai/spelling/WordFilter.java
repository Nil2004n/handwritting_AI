package notebookai.spelling;

/** Extra words the user has declared acceptable (personal dictionary, ignore-all list). Consulted after the built-in dictionary. */
@FunctionalInterface
public interface WordFilter {

    /** No extra words. */
    WordFilter NONE = token -> false;

    /** @param token the checked token exactly as written (original case, original apostrophe characters) */
    boolean accepts(String token);

    /** A filter accepting what either accepts. */
    default WordFilter or(WordFilter other) {
        return token -> accepts(token) || other.accepts(token);
    }
}
