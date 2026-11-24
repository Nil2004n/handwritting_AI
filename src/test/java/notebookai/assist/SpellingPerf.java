package notebookai.assist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import notebookai.spelling.SpellBackend;
import notebookai.spelling.SpellCheckResult;
import notebookai.spelling.SpellException;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordFilter;
import notebookai.spelling.WordListBackend;

/**
 * Bounded performance/robustness measurements of the spelling assistance on DESKTOP (this JVM, this machine); they say nothing about mobile. Deterministic
 * generated text (fixed seed). Run: {@code ./gradlew spellingPerf}. Prints a plain report.
 */
public final class SpellingPerf {

    private static final String[] COMMON = ("the of and to in is that it was for on are as with his they at be this from have or by one had not but what all were when we there can "
            + "an your which their said if do will each about how up out them then she many some so these would other into has more her two like him see time could no make than first "
            + "been its who now people my made over did down only way find use may water long little very after words called just where most know get through back much before go good "
            + "new write our used me man too any day same right look think also around another came come work three word must because does part even place well such here take why "
            + "things help put years different away again off went old number great tell men say small every found still between name should home big give air line set own under read "
            + "last never us left end along while might next sound below saw something thought both few those always looked show large often together asked house don't world going "
            + "want school important until form food keep children feet land side without boy once animals life enough took sometimes four head above kind began almost live page got "
            + "earth need far hand high year mother light country father let night picture being study second soon story since white ever paper hard near sentence better best across "
            + "during today however sure knew try told young sun thing whole hear example heard several change answer room sea against top turned learn point city play toward five "
            + "himself usually money seen didn't car morning body upon family later turn move face door cut done group true leave color red friend pretty fall").split(" ");

    private static String gen(int words, long seed, int[] injectedOut) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        int injected = 0;
        for (int i = 0; i < words; i++) {
            String w = COMMON[r.nextInt(COMMON.length)];
            if (i % 20 == 7 && w.length() >= 4) { // inject a transposition typo into every 20th word
                char[] c = w.toCharArray();
                char t = c[1];
                c[1] = c[2];
                c[2] = t;
                w = new String(c);
                injected++;
            }
            sb.append(w);
            sb.append(i % 14 == 13 ? ".\n" : " ");
        }
        injectedOut[0] = injected;
        return sb.toString();
    }

    private static long usedMb() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("# Spelling performance (desktop JVM; not mobile)");
        System.out.println("java " + System.getProperty("java.version") + " " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", cores=" + Runtime.getRuntime().availableProcessors()
                + ", maxHeapMB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        int[] inj = new int[1];

        // 1. initialization (cold: first use of the backend in this JVM)
        long mem0 = usedMb();
        WordListBackend backend = WordListBackend.bundled();
        long t0 = System.nanoTime();
        backend.check("warm up", SpellLanguage.EN_US, WordFilter.NONE);
        System.out.printf("init: first check (loads and verifies en-US, ~2 MB of lists): %.1f ms%n", ms(System.nanoTime() - t0));
        t0 = System.nanoTime();
        backend.check("warm up", SpellLanguage.EN_GB, WordFilter.NONE);
        backend.check("warm up", SpellLanguage.EN_CA, WordFilter.NONE);
        System.out.printf("init: en-GB + en-CA loaded: %.1f ms%n", ms(System.nanoTime() - t0));
        long mem1 = usedMb();
        System.out.printf("memory: heap after loading all three dictionaries and GC: +%d MB (from %d MB to %d MB)%n", mem1 - mem0, mem0, mem1);

        // 2. checks of different sizes
        String shortText = gen(50, 1, inj);
        long t = System.nanoTime();
        SpellCheckResult r = backend.check(shortText, SpellLanguage.EN_US, WordFilter.NONE);
        System.out.printf("short page (%d chars, %d typos injected): %.2f ms, %d issues%n", shortText.length(), inj[0], ms(System.nanoTime() - t), r.issues().size());
        for (int words : new int[] {1_000, 10_000, 50_000}) {
            String text = gen(words, 2, inj);
            long best = Long.MAX_VALUE;
            SpellCheckResult last = null;
            for (int k = 0; k < 5; k++) {
                t = System.nanoTime();
                last = backend.check(text, SpellLanguage.EN_US, WordFilter.NONE);
                best = Math.min(best, System.nanoTime() - t);
            }
            System.out.printf("transcript %,d chars (%,d words, %d typos injected): best of 5 = %.1f ms, issues=%d (truncated=%s)%n", text.length(), words, inj[0], ms(best),
                    last.issues().size(), last.truncated());
        }
        String near = gen(60_000, 3, inj).substring(0, SpellBackend.MAX_TEXT_LENGTH);
        t = System.nanoTime();
        r = backend.check(near, SpellLanguage.EN_US, WordFilter.NONE);
        System.out.printf("at the limit (%,d chars): %.1f ms, issues=%d truncated=%s%n", near.length(), ms(System.nanoTime() - t), r.issues().size(), r.truncated());

        // 3. suggestion latency
        String typo = gen(2_000, 4, inj);
        List<SpellIssue> issues = backend.check(typo, SpellLanguage.EN_US, WordFilter.NONE).issues();
        long total = 0;
        long worst = 0;
        int n = Math.min(100, issues.size());
        for (int i = 0; i < n; i++) {
            t = System.nanoTime();
            backend.suggest(issues.get(i).token(), SpellLanguage.EN_US, 8);
            long d = System.nanoTime() - t;
            total += d;
            worst = Math.max(worst, d);
        }
        System.out.printf("suggestions (%d tokens): mean %.1f ms, worst %.1f ms%n", n, ms(total / Math.max(1, n)), ms(worst));

        // 4. rapid edits through the real service: bounded work, newest wins
        AtomicInteger backendChecks = new AtomicInteger();
        SpellBackend counting = new SpellBackend() {
            @Override public String identity(SpellLanguage l) { return "counting"; }
            @Override public boolean supports(SpellLanguage l) { return true; }
            @Override public SpellCheckResult check(String text, SpellLanguage l, WordFilter u) throws SpellException {
                backendChecks.incrementAndGet();
                return backend.check(text, l, u);
            }
            @Override public List<String> suggest(String token, SpellLanguage l, int max) throws SpellException { return backend.suggest(token, l, max); }
            @Override public void close() { }
        };
        AtomicLong delivered = new AtomicLong();
        AtomicInteger maxPending = new AtomicInteger();
        SpellingService[] holder = new SpellingService[1];
        String base = gen(3_000, 5, inj);
        holder[0] = new SpellingService(counting, Runnable::run, new SpellingService.Listener() {
            @Override public void checked(SpellingService.CheckOutcome o) { delivered.incrementAndGet(); }
            @Override public void suggested(SpellingService.SuggestOutcome o) { }
        });
        t = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            holder[0].submit(new SpellingService.Key(1, "p", i, SpellLanguage.EN_US, 0), base + " edit" + i, WordFilter.NONE);
            maxPending.accumulateAndGet(holder[0].pendingCount(), Math::max);
            if (i % 50 == 0) {
                Thread.sleep(2);
            }
        }
        long submitted = System.nanoTime() - t;
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (holder[0].pendingCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(200);
        System.out.printf("rapid edits: 1000 submissions of a %,d-char text in %.0f ms -> %d actual backend checks (coalesced), %d results delivered, max pending observed %d%n",
                base.length(), ms(submitted), backendChecks.get(), delivered.get(), maxPending.get());
        long start = System.nanoTime();
        holder[0].close();
        System.out.printf("service close: %.1f ms%n", ms(System.nanoTime() - start));

        // 5. repeated page switching + checking: memory and threads stay flat
        String pageA = gen(800, 6, inj);
        String pageB = gen(800, 7, inj);
        backend.check(pageA, SpellLanguage.EN_US, WordFilter.NONE); // warm
        long memBefore = usedMb();
        int threadsBefore = Thread.getAllStackTraces().size();
        t = System.nanoTime();
        for (int i = 0; i < 2_000; i++) {
            backend.check(i % 2 == 0 ? pageA : pageB, SpellLanguage.EN_US, WordFilter.NONE);
        }
        double per = ms(System.nanoTime() - t) / 2_000;
        long memAfter = usedMb();
        System.out.printf("2000 alternating page checks (%,d chars each): %.2f ms per check, heap %d MB -> %d MB (%+d MB), threads %d -> %d%n", pageA.length(), per, memBefore, memAfter,
                memAfter - memBefore, threadsBefore, Thread.getAllStackTraces().size());

        // 6. failure: nothing is retained / nothing blocks
        try (WordListBackend broken = new WordListBackend(name -> null)) {
            t = System.nanoTime();
            try {
                broken.check("x", SpellLanguage.EN_US, WordFilter.NONE);
            } catch (SpellException e) {
                System.out.printf("missing resources: reported in %.2f ms (%s)%n", ms(System.nanoTime() - t), e.getMessage());
            }
        }
        backend.close();
        System.out.println("done");
        System.exit(0);
    }

    static List<String> unused() {
        return new ArrayList<>(Arrays.asList(COMMON));
    }
}
