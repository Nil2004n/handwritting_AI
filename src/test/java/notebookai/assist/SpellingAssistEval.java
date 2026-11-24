package notebookai.assist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import notebookai.eval.Evaluate;
import notebookai.eval.EditDistance;
import notebookai.ocr.OcrService;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordFilter;
import notebookai.spelling.WordListBackend;

/**
 * SIMULATION of assisted review, reported SEPARATELY from raw OCR: for each case the raw OCR transcript is taken as is (raw CER/WER), then every flagged token
 * is replaced by the backend's FIRST suggestion (what a user would get by always clicking the top candidate; NOT a human choice and NOT the product's behavior,
 * which never replaces automatically) and CER/WER are recomputed against the same ground truth. A replacement is counted helpful if the replacement is a word of
 * the ground truth and the original was not, harmful if the original WAS a ground-truth word, otherwise neutral. This is a tiny-sample illustration, not evidence
 * of general improvement, and it says nothing about the OCR model.
 *
 * <p>Usage: {@code ./gradlew spellingAssistEval -PevalManifest=eval/cases.tsv [-PaggregateOnly=true]}. With aggregateOnly no text is printed (private cases).
 */
public final class SpellingAssistEval {

    private SpellingAssistEval() {}

    private static Set<String> words(String text) {
        Set<String> s = new HashSet<>();
        for (String w : text.split("[^\\p{L}'\\u2019-]+")) {
            if (!w.isEmpty()) {
                s.add(w.toLowerCase(Locale.ROOT));
            }
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        String manifest = args[0];
        boolean aggregateOnly = args.length > 1 && args[1].equals("--aggregate-only");
        Path root = Paths.get("").toAbsolutePath();
        List<Evaluate.Case> cases = Evaluate.parseManifest(Files.readString(Paths.get(manifest)), root);
        int n = 0;
        int replacements = 0;
        int helpful = 0;
        int harmful = 0;
        int refChars = 0;
        int rawEdits = 0;
        int assistedEdits = 0;
        int refWords = 0;
        int rawWordEdits = 0;
        int assistedWordEdits = 0;
        try (OcrService ocr = OcrService.open(root); WordListBackend spell = WordListBackend.bundled()) {
            for (Evaluate.Case c : cases) {
                Evaluate.Result r = Evaluate.evaluateCase(ocr, c, null);
                String raw = r.actual();
                List<SpellIssue> issues = spell.check(raw, SpellLanguage.EN_US, WordFilter.NONE).issues();
                StringBuilder assisted = new StringBuilder(raw);
                Set<String> truth = words(c.expected());
                for (int i = issues.size() - 1; i >= 0; i--) { // right to left keeps earlier offsets valid
                    SpellIssue issue = issues.get(i);
                    List<String> s = spell.suggest(issue.token(), SpellLanguage.EN_US, 1);
                    if (s.isEmpty()) {
                        continue;
                    }
                    replacements++;
                    boolean origOk = truth.contains(issue.token().toLowerCase(Locale.ROOT));
                    boolean newOk = truth.contains(s.get(0).toLowerCase(Locale.ROOT));
                    if (origOk) {
                        harmful++;
                    } else if (newOk) {
                        helpful++;
                    }
                    assistedText(assisted, issue, s.get(0));
                }
                String after = assisted.toString();
                int[] ref = c.expected().codePoints().toArray();
                int refW = c.expected().trim().isEmpty() ? 0 : c.expected().trim().split("\\s+").length;
                int e0 = (int) Math.round(EditDistance.cer(c.expected(), raw) * ref.length);
                int e1 = (int) Math.round(EditDistance.cer(c.expected(), after) * ref.length);
                int w0 = (int) Math.round(EditDistance.wer(c.expected(), raw) * refW);
                int w1 = (int) Math.round(EditDistance.wer(c.expected(), after) * refW);
                n++;
                refChars += ref.length;
                rawEdits += e0;
                assistedEdits += e1;
                refWords += refW;
                rawWordEdits += w0;
                assistedWordEdits += w1;
                if (!aggregateOnly) {
                    System.out.println("case " + c.image().getFileName() + ": flagged=" + issues.size() + " char edits raw=" + e0 + " assisted=" + e1 + " word edits raw=" + w0 + " assisted=" + w1);
                    System.out.println("  raw     : " + raw.replace("\n", " | "));
                    System.out.println("  assisted: " + after.replace("\n", " | "));
                }
            }
        }
        System.out.printf("AGGREGATE over %d case(s), %d reference chars, %d reference words%n", n, refChars, refWords);
        System.out.printf("RAW OCR      : CER %.3f (%d edits)  WER %.3f (%d edits)%n", rawEdits / (double) refChars, rawEdits, rawWordEdits / (double) refWords, rawWordEdits);
        System.out.printf("SIMULATED top-1 acceptance: CER %.3f (%d edits)  WER %.3f (%d edits)%n", assistedEdits / (double) refChars, assistedEdits, assistedWordEdits / (double) refWords, assistedWordEdits);
        System.out.printf("replacements=%d  helpful=%d  harmful(original was a ground-truth word)=%d  neutral/other=%d%n", replacements, helpful, harmful, replacements - helpful - harmful);
    }

    private static void assistedText(StringBuilder sb, SpellIssue issue, String replacement) {
        sb.replace(issue.start(), issue.end(), replacement);
    }

    @SuppressWarnings("unused")
    private static List<String> unused() {
        return new ArrayList<>();
    }
}
