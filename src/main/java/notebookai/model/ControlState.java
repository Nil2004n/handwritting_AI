package notebookai.model;

/**
 * Pure enable/disable rules for the window's action buttons, so the same
 * rules apply after every state change (open, draw selection, clear, job
 * start/finish, rotate/crop/reset) and can be unit-tested without a display.
 * A value of {@code true} means the control is ENABLED.
 */
public record ControlState(
        boolean open,
        boolean extract,
        boolean recognizeSelection,
        boolean recognizeLine,
        boolean prep,
        boolean cropMode,
        boolean applyCrop,
        boolean clearSelection,
        boolean copyTranscript,
        boolean restoreRaw,
        boolean copySelection,
        boolean regionActions) {

    public static ControlState compute(
            boolean hasImage,
            boolean jobRunning,
            boolean hasSelection,
            boolean hasCropDraft,
            boolean hasTranscript,
            boolean hasRaw,
            boolean hasSelectionText,
            boolean regionHasText) {
        boolean idle = hasImage && !jobRunning;
        return new ControlState(
                !jobRunning,
                idle,
                idle && hasSelection,
                idle && hasSelection,
                idle,
                idle,
                !jobRunning && hasCropDraft,
                !jobRunning && (hasSelection || hasCropDraft),
                hasTranscript,
                !jobRunning && hasRaw,
                hasSelectionText,
                regionHasText);
    }
}
