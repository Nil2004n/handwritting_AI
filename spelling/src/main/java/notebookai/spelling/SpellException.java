package notebookai.spelling;

/** A spelling backend could not do what was asked (resources missing/corrupt, text too long, closed). Never carries user text. */
public class SpellException extends Exception {

    private static final long serialVersionUID = 1L;

    public SpellException(String message) {
        super(message);
    }

    public SpellException(String message, Throwable cause) {
        super(message, cause);
    }
}
