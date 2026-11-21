package notebookai.project;

/** One saved text region (geometry in working-image pixels at the page's saved prep state). */
public record RegionData(
        double[][] polygon, int[] box, String text, double detScore, double recScore) {}
