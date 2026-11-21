package notebookai.project;

/**
 * Unsaved-change tracking with a saved-revision token, so edits made WHILE an asynchronous save
 * runs are not wrongly considered saved: a save captures the revision when it starts and, on
 * success, marks only that revision as saved.
 *
 * <p>Meaningful changes (page import/removal/reorder, image preparation, OCR results that become
 * content, transcript edits) call {@link #markModified()}. Navigation, copying and region selection
 * must not.
 */
public final class DirtyTracker {

    private long revision;
    private long savedRevision;

    public synchronized void markModified() {
        revision++;
    }

    public synchronized boolean isDirty() {
        return revision != savedRevision;
    }

    /** Revision captured when a save starts (use as the token for {@link #saveSucceeded}). */
    public synchronized long beginSave() {
        return revision;
    }

    public synchronized void saveSucceeded(long token) {
        if (token > savedRevision) {
            savedRevision = token;
        }
    }

    /** Fresh state for a new/opened project. */
    public synchronized void reset() {
        revision = 0;
        savedRevision = 0;
    }
}
