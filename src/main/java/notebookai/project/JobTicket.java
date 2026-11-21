package notebookai.project;

/**
 * Ownership of a background OCR job. A result may only be applied when EVERY identity still
 * matches: the project (epoch), the page it was started on (id, still present), that page's
 * image-preparation revision, and the job generation. Page switches, project actions and image
 * changes therefore can never route a result into the wrong page, independent of what is displayed.
 */
public record JobTicket(long projectEpoch, String pageId, int prepRevision, int generation) {

    public boolean acceptedBy(long currentEpoch, boolean pageStillExists, int currentPrepRevision,
            int currentGeneration) {
        return projectEpoch == currentEpoch
                && pageStillExists
                && prepRevision == currentPrepRevision
                && generation == currentGeneration;
    }
}
