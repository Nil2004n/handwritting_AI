package notebookai.project;

/**
 * Pure enable/disable rules for the project and page actions (true = enabled), kept free of UI so
 * they are unit-tested. Policy: while an OCR/import/open task runs, every action that could change
 * which page or project is active is locked; Save and Export stay available because they only read
 * a consistent snapshot.
 */
public record ProjectActions(
        boolean addImages,
        boolean removePage,
        boolean moveUp,
        boolean moveDown,
        boolean switchPage,
        boolean newProject,
        boolean openProject,
        boolean save,
        boolean saveAs,
        boolean exportCurrent,
        boolean exportAll) {

    public static ProjectActions compute(
            int pageCount, int activeIndex, boolean taskRunning, boolean saving) {
        boolean hasActive = activeIndex >= 0 && activeIndex < pageCount;
        boolean idle = !taskRunning;
        return new ProjectActions(
                idle && pageCount < ProjectStore.MAX_PAGES,
                idle && hasActive,
                idle && hasActive && activeIndex > 0,
                idle && hasActive && activeIndex < pageCount - 1,
                idle,
                idle && !saving,
                idle && !saving,
                !saving,
                !saving,
                hasActive,
                pageCount > 0);
    }
}
