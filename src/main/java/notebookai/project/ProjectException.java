package notebookai.project;

/** A project could not be saved, opened or exported; the message is safe to show to the user. */
public final class ProjectException extends Exception {
    public ProjectException(String message) {
        super(message);
    }

    public ProjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
