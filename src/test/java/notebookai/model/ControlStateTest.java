package notebookai.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression: Recognize Selection/Line must enable as soon as a selection exists. */
class ControlStateTest {

    @Test
    void recognizeButtonsEnableWhenSelectionDrawnOnLoadedIdleImage() {
        ControlState none = ControlState.compute(true, false, false, false, false, false, false, false);
        ControlState drawn = ControlState.compute(true, false, true, false, false, false, false, false);
        assertFalse(none.recognizeSelection());
        assertFalse(none.recognizeLine());
        assertTrue(drawn.recognizeSelection());
        assertTrue(drawn.recognizeLine());
        assertTrue(drawn.clearSelection());
    }

    @Test
    void nothingRunsWithoutAnImageOrDuringAJob() {
        ControlState noImage = ControlState.compute(false, false, true, false, false, false, false, false);
        assertFalse(noImage.extract());
        assertFalse(noImage.recognizeSelection());
        ControlState busy = ControlState.compute(true, true, true, true, true, true, true, true);
        assertFalse(busy.open());
        assertFalse(busy.extract());
        assertFalse(busy.recognizeSelection());
        assertFalse(busy.prep());
        assertFalse(busy.applyCrop());
        assertFalse(busy.restoreRaw());
        // Copying text stays available while a job runs.
        assertTrue(busy.copyTranscript());
    }

    @Test
    void cropDraftEnablesApplyAndClearButNotRecognition() {
        ControlState c = ControlState.compute(true, false, false, true, false, false, false, false);
        assertTrue(c.applyCrop());
        assertTrue(c.clearSelection());
        assertFalse(c.recognizeSelection());
    }

    @Test
    void copyAndRestoreFollowTheirOwnContent() {
        ControlState c = ControlState.compute(true, false, false, false, true, true, true, true);
        assertTrue(c.copyTranscript());
        assertTrue(c.restoreRaw());
        assertTrue(c.copySelection());
        assertTrue(c.regionActions());
        ControlState empty = ControlState.compute(true, false, false, false, false, false, false, false);
        assertFalse(empty.copyTranscript());
        assertFalse(empty.copySelection());
        assertFalse(empty.regionActions());
    }
}
