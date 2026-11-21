package notebookai.project;

import java.util.ArrayList;
import java.util.List;
import notebookai.model.WorkingImage;

/**
 * Rebuilds a page's working image from its saved state and validates that the saved geometry fits
 * it. A saved crop that does not lie fully inside the rebuilt frame, or saved region geometry that
 * does not fit the rebuilt image, is DISCARDED with a notice (text is kept): stale geometry is
 * never shown. No pixel work happens here beyond constructing the lightweight working image.
 */
public final class PageRestore {

    /** Result: the working image plus human-readable notices about anything discarded. */
    public record Restored(WorkingImage working, List<String> notices, int renderedWidth, int renderedHeight) {}

    private PageRestore() {}

    public static Restored restore(PageData page, byte[] originalBgr, int width, int height) {
        List<String> notices = new ArrayList<>();
        int[] frame = WorkingImage.frameSize(width, height, page.quarterTurnsCw(), page.fineDegrees());
        int[] crop = page.crop();
        if (crop != null && !(crop[0] >= 0 && crop[1] >= 0 && crop[2] <= frame[0] && crop[3] <= frame[1]
                && crop[2] > crop[0] && crop[3] > crop[1])) {
            notices.add("The saved crop did not fit the image (" + frame[0] + "x" + frame[1]
                    + ") and was ignored.");
            crop = null;
            page.setPrep(page.quarterTurnsCw(), page.fineDegrees(), null);
            page.dropRegions();
        }
        int rw = crop == null ? frame[0] : crop[2] - crop[0];
        int rh = crop == null ? frame[1] : crop[3] - crop[1];
        if (!page.regions().isEmpty() && !regionsFit(page.regions(), rw, rh)) {
            page.dropRegions();
            notices.add("Saved region overlays did not fit the image and were discarded; extract again to inspect regions.");
        }
        WorkingImage w = WorkingImage.restore(originalBgr, width, height, page.quarterTurnsCw(),
                page.fineDegrees(), crop);
        return new Restored(w, notices, rw, rh);
    }

    /** True if every region polygon point and box lies within the image (1 px tolerance for rounding). */
    public static boolean regionsFit(List<RegionData> regions, int w, int h) {
        for (RegionData r : regions) {
            for (double[] p : r.polygon()) {
                if (p[0] < -1 || p[1] < -1 || p[0] > w + 1 || p[1] > h + 1) {
                    return false;
                }
            }
            int[] b = r.box();
            if (b[0] < -1 || b[1] < -1 || b[2] > w + 1 || b[3] > h + 1 || b[2] < b[0] || b[3] < b[1]) {
                return false;
            }
        }
        return true;
    }
}
