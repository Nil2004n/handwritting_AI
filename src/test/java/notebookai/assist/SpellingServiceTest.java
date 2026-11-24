package notebookai.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import notebookai.assist.SpellingService.CheckOutcome;
import notebookai.assist.SpellingService.Key;
import notebookai.assist.SpellingService.SuggestOutcome;
import notebookai.spelling.IssueKind;
import notebookai.spelling.SpellBackend;
import notebookai.spelling.SpellCheckResult;
import notebookai.spelling.SpellException;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordFilter;
import org.junit.jupiter.api.Test;

class SpellingServiceTest {

    /** Records calls; behavior is scripted per test. */
    static class FakeBackend implements SpellBackend {
        final List<String> checked = new ArrayList<>();
        final List<String> suggested = new ArrayList<>();
        final AtomicInteger closed = new AtomicInteger();
        volatile Throwable failWith;
        volatile CountDownLatch block;

        @Override public String identity(SpellLanguage l) { return "fake"; }
        @Override public boolean supports(SpellLanguage l) { return true; }

        @Override
        public SpellCheckResult check(String text, SpellLanguage l, WordFilter u) throws SpellException {
            synchronized (checked) { checked.add(text); }
            await();
            fail();
            return new SpellCheckResult(l, text.length(), List.of(new SpellIssue(0, 1, text.substring(0, 1), IssueKind.UNKNOWN_WORD)), false, 1, 0);
        }

        @Override
        public List<String> suggest(String token, SpellLanguage l, int max) throws SpellException {
            synchronized (suggested) { suggested.add(token); }
            fail();
            return List.of(token + "!");
        }

        private void await() {
            CountDownLatch b = block;
            if (b != null) {
                try { b.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        private void fail() throws SpellException {
            Throwable t = failWith;
            if (t instanceof SpellException se) { throw se; }
            if (t instanceof Error err) { throw err; }
            if (t instanceof RuntimeException re) { throw re; }
        }

        @Override public void close() { closed.incrementAndGet(); }
    }

    private static Key key(long rev) {
        return new Key(1, "p", rev, SpellLanguage.EN_US, 0);
    }

    private final List<CheckOutcome> checks = new ArrayList<>();
    private final List<SuggestOutcome> suggests = new ArrayList<>();
    private final SpellingService.Listener listener = new SpellingService.Listener() {
        @Override public void checked(CheckOutcome o) { checks.add(o); }
        @Override public void suggested(SuggestOutcome o) { suggests.add(o); }
    };

    @Test
    void newestPendingCheckWinsAndOlderOnesAreNeverRunOrDelivered() throws Exception {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        SpellingService s = new SpellingService(backend, Runnable::run, listener, worker);
        s.submit(key(1), "one", WordFilter.NONE);
        s.submit(key(2), "two", WordFilter.NONE);
        s.submit(key(3), "three", WordFilter.NONE);
        assertEquals(1, s.pendingCount(), "at most one pending check");
        assertEquals(1, worker.queued(), "one drain task, not one per request");
        worker.runAll();
        assertEquals(List.of("three"), backend.checked);
        assertEquals(1, checks.size());
        assertEquals(key(3), checks.get(0).key());
        assertTrue(checks.get(0).ok());
        assertEquals(0, s.pendingCount());
    }

    @Test
    void workIsBoundedUnderARequestStorm() throws Exception {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        SpellingService s = new SpellingService(backend, Runnable::run, listener, worker);
        for (int i = 0; i < 20_000; i++) {
            s.submit(key(i), "t" + i, WordFilter.NONE);
            s.suggest(key(i), new SpellIssue(0, 1, "t", IssueKind.UNKNOWN_WORD), 3);
            assertTrue(s.pendingCount() <= 2);
            assertTrue(worker.queued() <= 1);
        }
        worker.runAll();
        assertEquals(1, backend.checked.size());
        assertEquals(1, backend.suggested.size());
    }

    @Test
    void suggestionsRunBeforeAPendingCheck() throws Exception {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        SpellingService s = new SpellingService(backend, Runnable::run, listener, worker);
        s.submit(key(1), "text", WordFilter.NONE);
        s.suggest(key(1), new SpellIssue(0, 3, "teh", IssueKind.UNKNOWN_WORD), 5);
        List<String> order = new ArrayList<>();
        SpellingService ordered = new SpellingService(backend, Runnable::run, new SpellingService.Listener() {
            @Override public void checked(CheckOutcome o) { order.add("check"); }
            @Override public void suggested(SuggestOutcome o) { order.add("suggest"); }
        }, worker);
        ordered.submit(key(2), "x", WordFilter.NONE);
        ordered.suggest(key(2), new SpellIssue(0, 1, "x", IssueKind.UNKNOWN_WORD), 5);
        worker.runAll();
        assertEquals(List.of("suggest", "check"), order);
    }

    @Test
    void backendFailuresIncludingErrorsAreDeliveredAndTheWorkerSurvives() throws Exception {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        SpellingService s = new SpellingService(backend, Runnable::run, listener, worker);
        backend.failWith = new SpellException("dictionary is corrupt");
        s.submit(key(1), "a", WordFilter.NONE);
        worker.runAll();
        assertEquals("dictionary is corrupt", checks.get(0).error());
        assertNull(checks.get(0).result());
        backend.failWith = new OutOfMemoryError("simulated");
        s.submit(key(2), "b", WordFilter.NONE);
        worker.runAll();
        assertNotNull(checks.get(1).error());
        assertFalse(checks.get(1).ok());
        backend.failWith = new IllegalStateException("boom");
        s.suggest(key(2), new SpellIssue(0, 1, "b", IssueKind.UNKNOWN_WORD), 3);
        worker.runAll();
        assertNotNull(suggests.get(0).error());
        backend.failWith = null;
        s.submit(key(3), "c", WordFilter.NONE);
        worker.runAll();
        assertTrue(checks.get(2).ok(), "still usable after failures");
    }

    @Test
    void closeDropsPendingWorkSuppressesDeliveryAndClosesTheBackendOnce() throws Exception {
        FakeBackend backend = new FakeBackend();
        ManualWorker worker = new ManualWorker();
        SpellingService s = new SpellingService(backend, Runnable::run, listener, worker);
        s.submit(key(1), "a", WordFilter.NONE);
        s.close();
        assertEquals(0, s.pendingCount());
        worker.runAll();
        assertTrue(backend.checked.isEmpty(), "pending work is dropped, not run");
        assertTrue(checks.isEmpty());
        assertEquals(1, backend.closed.get());
        s.submit(key(2), "b", WordFilter.NONE);   // ignored after close
        s.suggest(key(2), new SpellIssue(0, 1, "b", IssueKind.UNKNOWN_WORD), 3);
        assertEquals(0, s.pendingCount());
        s.close();
        assertEquals(1, backend.closed.get(), "idempotent");
    }

    @Test
    void aResultFinishingAfterCloseIsNotDelivered() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.block = new CountDownLatch(1);
        SpellingService s = new SpellingService(backend, Runnable::run, listener);
        s.submit(key(1), "a", WordFilter.NONE);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (backend.checked.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, backend.checked.size(), "the check is running");
        Thread closer = new Thread(s::close);
        closer.start();
        Thread.sleep(100);
        backend.block.countDown();                 // the running check completes while close() waits
        closer.join(10_000);
        assertTrue(checks.isEmpty(), "no delivery after close");
        assertEquals(1, backend.closed.get(), "the backend is closed only after the worker stopped");
    }

    @Test
    void closeNeverClosesTheBackendUnderARunningCall() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.block = new CountDownLatch(1);
        SpellingService s = new SpellingService(backend, Runnable::run, listener);
        s.submit(key(1), "a", WordFilter.NONE);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (backend.checked.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        long t0 = System.nanoTime();
        s.close();                                 // the call is stuck: close gives up waiting after about two seconds
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms < 4_000, "close is bounded, took " + ms + " ms");
        assertEquals(0, backend.closed.get(), "the backend is NOT closed while a call is still running");
        backend.block.countDown();
    }
}
