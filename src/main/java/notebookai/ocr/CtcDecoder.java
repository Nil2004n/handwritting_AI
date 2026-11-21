package notebookai.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * CTC greedy decoder replicating upstream {@code CTCLabelDecode.decode}
 * (is_remove_duplicate=True, ignored token {@code [0]} = blank).
 *
 * <p>Mapping (exact): the decode table is {@code ["blank"] + dict + [" "]}
 * (see {@code ModelConfig.readDecodeTable}; the trailing space comes from
 * the upstream {@code use_space_char=True} inference default). Output class
 * {@code i} decodes to {@code table.get(i)}; class 0 is dropped along with
 * consecutive duplicates. Scores are the mean kept-position confidence
 * (0 for empty decodes), exactly like upstream.
 */
public final class CtcDecoder {

    /** Decoded line: text plus mean confidence over the kept positions. */
    public record Line(String text, double score) {}

    private CtcDecoder() {}

    /**
     * @param logits time-major scores [steps][classes] (argmax + max per step)
     * @param table full decode table with "blank" at index 0
     */
    public static Line decode(float[][] logits, List<String> table) throws OcrException {
        if (table.isEmpty() || !"blank".equals(table.get(0))) {
            throw new OcrException("Decode table must start with the blank class.");
        }
        List<String> chars = new ArrayList<>();
        List<Double> confs = new ArrayList<>();
        int prev = -1;
        for (float[] step : logits) {
            int best = 0;
            float bestVal = step[0];
            for (int c = 1; c < step.length; c++) {
                if (step[c] > bestVal) {
                    bestVal = step[c];
                    best = c;
                }
            }
            if (best != prev) {
                if (best != 0) {
                    if (best < 0 || best >= table.size()) {
                        throw new OcrException(
                                "Recognizer emitted class "
                                        + best
                                        + " outside the "
                                        + table.size()
                                        + "-entry decode table.");
                    }
                    chars.add(table.get(best));
                    confs.add((double) bestVal);
                }
            }
            prev = best;
        }
        String text = String.join("", chars);
        double score = confs.isEmpty() ? 0.0 : confs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new Line(text, score);
    }
}
