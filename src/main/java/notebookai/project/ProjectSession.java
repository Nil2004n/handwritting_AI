package notebookai.project;

import java.nio.file.Path;

/**
 * The active project plus its identity: file path (null until first saved), dirty state and an
 * epoch that changes whenever the project is replaced (New/Open), so background work started for
 * an earlier project is recognised as stale. UI-independent and unit-tested.
 */
public final class ProjectSession {

    private ProjectData project = new ProjectData();
    private Path path;
    private final DirtyTracker dirty = new DirtyTracker();
    private long epoch;

    public ProjectData project() { return project; }
    public Path path() { return path; }
    public DirtyTracker dirty() { return dirty; }
    public long epoch() { return epoch; }
    public boolean isDirty() { return dirty.isDirty(); }
    public void markModified() { dirty.markModified(); }

    /** Replaces the active project with an empty one. */
    public void newProject() {
        project = new ProjectData();
        path = null;
        dirty.reset();
        epoch++;
    }

    /**
     * Loads {@code file} and, only if loading fully succeeded, makes it the active project. A
     * failed open throws and leaves the active project, its path and its dirty state untouched.
     */
    public void open(Path file) throws ProjectException {
        adopt(ProjectStore.load(file), file);
    }

    /** Installs an already-loaded project (used when the load ran on a background thread). */
    public void adopt(ProjectData loaded, Path file) {
        project = loaded;
        path = file;
        dirty.reset();
        epoch++;
    }

    /** A save in progress: a consistent snapshot plus the tokens needed to commit its result. */
    public record SaveJob(ProjectData snapshot, long revisionToken, long epoch, Path target) {}

    public SaveJob beginSave(Path target) {
        return new SaveJob(project.copy(), dirty.beginSave(), epoch, target);
    }

    /** Marks only the saved revision clean; ignored if the project was replaced meanwhile. */
    public boolean saveSucceeded(SaveJob job) {
        if (job.epoch() != epoch) {
            return false;
        }
        dirty.saveSucceeded(job.revisionToken());
        path = job.target();
        return true;
    }
}
