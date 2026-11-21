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

    /**
     * EXPERIMENTAL (opt-in, see {@link Experiments}): CTC prefix beam search without a
     * language model over the per-frame output distribution. Candidate classes per frame
     * are the top {@code topK} non-blank classes. The returned score is NOT recomputed:
     * callers keep the greedy score so the drop_score filter is unaffected by the decoder.
     */
    public static String decodeBeam(float[][] probs, List<String> table, int width) {
        final int topK = 10;
        java.util.Map<String, double[]> beams = new java.util.HashMap<>(); // key -> {pBlank, pNonBlank}
        java.util.Map<String, int[]> seqOf = new java.util.HashMap<>();
        beams.put("", new double[] {1.0, 0.0});
        seqOf.put("", new int[0]);
        for (float[] step : probs) {
            Integer[] idx = new Integer[step.length];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = i;
            }
            // Partial selection of the top-K non-blank classes.
            int[] top = new int[topK];
            float[] topV = new float[topK];
            java.util.Arrays.fill(top, -1);
            for (int c = 1; c < step.length; c++) {
                for (int k = 0; k < topK; k++) {
                    if (top[k] < 0 || step[c] > topV[k]) {
                        for (int m = topK - 1; m > k; m--) {
                            top[m] = top[m - 1];
                            topV[m] = topV[m - 1];
                        }
                        top[k] = c;
                        topV[k] = step[c];
                        break;
                    }
                }
            }
            java.util.Map<String, double[]> next = new java.util.HashMap<>();
            java.util.Map<String, int[]> nextSeq = new java.util.HashMap<>();
            for (var e : beams.entrySet()) {
                int[] seq = seqOf.get(e.getKey());
                double pb = e.getValue()[0];
                double pnb = e.getValue()[1];
                double total = pb + pnb;
                double[] same = next.computeIfAbsent(e.getKey(), k -> new double[2]);
                nextSeq.putIfAbsent(e.getKey(), seq);
                same[0] += total * step[0];
                for (int k = 0; k < topK; k++) {
                    int c = top[k];
                    if (c < 0) {
                        break;
                    }
                    double p = step[c];
                    int last = seq.length == 0 ? -1 : seq[seq.length - 1];
                    int[] ext = java.util.Arrays.copyOf(seq, seq.length + 1);
                    ext[seq.length] = c;
                    String extKey = e.getKey() + "," + c;
                    double[] target = next.computeIfAbsent(extKey, kk -> new double[2]);
                    nextSeq.putIfAbsent(extKey, ext);
                    if (c == last) {
                        same[1] += pnb * p; // repeat of the last char without a blank: collapses
                        target[1] += pb * p; // repeat after a blank: a new character
                    } else {
                        target[1] += total * p;
                    }
                }
            }
            java.util.List<java.util.Map.Entry<String, double[]>> ranked = new ArrayList<>(next.entrySet());
            ranked.sort((a, b) -> Double.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
            beams = new java.util.HashMap<>();
            seqOf = new java.util.HashMap<>();
            for (int i = 0; i < Math.min(width, ranked.size()); i++) {
                beams.put(ranked.get(i).getKey(), ranked.get(i).getValue());
                seqOf.put(ranked.get(i).getKey(), nextSeq.get(ranked.get(i).getKey()));
            }
        }
        String bestKey = "";
        double bestP = -1;
        for (var e : beams.entrySet()) {
            double p = e.getValue()[0] + e.getValue()[1];
            if (p > bestP) {
                bestP = p;
                bestKey = e.getKey();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int c : seqOf.get(bestKey)) {
            sb.append(table.get(c));
        }
        return sb.toString();
    }
}
