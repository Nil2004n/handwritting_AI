package notebookai.project;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** In-memory project: an ordered list of pages. Pure data, no UI, no I/O. */
public final class ProjectData {

    private final String projectId;
    private final List<PageData> pages = new ArrayList<>();

    public ProjectData() {
        this(UUID.randomUUID().toString());
    }

    ProjectData(String projectId) {
        this.projectId = projectId;
    }

    public String projectId() { return projectId; }
    public List<PageData> pages() { return pages; }
    public int size() { return pages.size(); }

    public PageData find(String pageId) {
        for (PageData p : pages) {
            if (p.id().equals(pageId)) {
                return p;
            }
        }
        return null;
    }

    public int indexOf(String pageId) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id().equals(pageId)) {
                return i;
            }
        }
        return -1;
    }

    public void add(PageData p) {
        pages.add(p);
    }

    /** Removes the page; returns its former index or -1. */
    public int remove(String pageId) {
        int i = indexOf(pageId);
        if (i >= 0) {
            pages.remove(i);
        }
        return i;
    }

    /** Moves a page by delta positions (clamped); returns true when the order changed. */
    public boolean move(String pageId, int delta) {
        int i = indexOf(pageId);
        if (i < 0) {
            return false;
        }
        int j = Math.max(0, Math.min(pages.size() - 1, i + delta));
        if (j == i) {
            return false;
        }
        PageData p = pages.remove(i);
        pages.add(j, p);
        return true;
    }

    /** Deep copy for background saves (page originals are shared, they are immutable). */
    public ProjectData copy() {
        ProjectData c = new ProjectData(projectId);
        for (PageData p : pages) {
            c.pages.add(p.copy());
        }
        return c;
    }
}
