package notebookai.project;

/**
 * What to do when the user asks to close the window. Background work (OCR inference, import,
 * project open, save) cannot be cancelled safely, so closing while it runs means WAIT: the window
 * closes when the work has finished, and the unsaved-changes prompt then still applies. Nothing
 * here claims cancellation.
 */
public final class ShutdownPolicy {

    public enum Action {
        /** Nothing running, nothing unsaved: close. */
        CLOSE_NOW,
        /** Idle but unsaved changes: ask Save / Discard / Cancel. */
        PROMPT_UNSAVED,
        /** A task or save is running: do not close yet; close (with the prompt) once it finishes. */
        WAIT_FOR_BACKGROUND
    }

    private ShutdownPolicy() {}

    public static Action onCloseRequest(boolean taskRunning, boolean saving, boolean dirty) {
        if (taskRunning || saving) {
            return Action.WAIT_FOR_BACKGROUND;
        }
        return dirty ? Action.PROMPT_UNSAVED : Action.CLOSE_NOW;
    }

    /**
     * After "Save" in the unsaved-changes prompt: the follow-up action (New/Open/Close) may only run
     * if the saved snapshot is still the current state. Edits made while the save was running stay
     * unsaved, so they are never silently discarded by the follow-up action.
     */
    public static boolean mayProceedAfterSave(boolean saveSucceeded, boolean stillDirty) {
        return saveSucceeded && !stillDirty;
    }
}
