package notebookai.assist;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import notebookai.spelling.SpellBackend;
import notebookai.spelling.SpellCheckResult;
import notebookai.spelling.SpellException;
import notebookai.spelling.SpellIssue;
import notebookai.spelling.SpellLanguage;
import notebookai.spelling.WordFilter;

/**
 * Runs spelling work off the UI thread, with BOUNDED work: one worker thread, at most one running request, at most ONE pending check and ONE pending
 * suggestion request. A newer request replaces a pending older one of the same kind ("newest wins"; the replaced request is never delivered), so rapid edits
 * can never build a queue. Each delivered completion carries the {@link Key} it was computed for; the receiver must compare it with the current key and drop
 * mismatches (edits, page/project switches, language or dictionary changes all change the key). No result is delivered after {@link #close()}.
 *
 * <p>Failures (including {@link Error}s) are delivered as an {@code error} string; nothing here can leave a caller waiting. The backend decides its own
 * thread-safety; this class only ever calls it from its single worker thread, and closes it only after that thread has stopped (or after a bounded wait).
 */
public final class SpellingService implements AutoCloseable {

    /** Everything a result depends on. Two keys equal means "same project, page, text, language and word lists". */
    public record Key(long epoch, String pageId, long revision, SpellLanguage language, long wordsRevision) {}

    public record CheckOutcome(Key key, SpellCheckResult result, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public record SuggestOutcome(Key key, SpellIssue issue, List<String> suggestions, String error) {}

    /** Called on the UI executor. */
    public interface Listener {
        void checked(CheckOutcome outcome);

        void suggested(SuggestOutcome outcome);
    }

    private record CheckRequest(Key key, String text, WordFilter filter) {}

    private record SuggestRequest(Key key, SpellIssue issue, int max) {}

    private final SpellBackend backend;
    private final Executor ui;
    private final Listener listener;
    private final ExecutorService worker;
    private final Object lock = new Object();
    private CheckRequest pendingCheck;
    private SuggestRequest pendingSuggest;
    private boolean draining;
    private volatile boolean closed;
    private volatile long lastCheckNanos;

    public SpellingService(SpellBackend backend, Executor ui, Listener listener) {
        this(backend, ui, listener, Executors.newSingleThreadExecutor(daemonThreads()));
    }

    /** For tests: inject the worker (for example a manually driven executor). */
    SpellingService(SpellBackend backend, Executor ui, Listener listener, ExecutorService worker) {
        this.backend = backend;
        this.ui = ui;
        this.listener = listener;
        this.worker = worker;
    }

    private static ThreadFactory daemonThreads() {
        return r -> {
            Thread t = new Thread(r, "notebook-ai-spelling");
            t.setDaemon(true);
            return t;
        };
    }

    /** Asks for a check of {@code text}; replaces a pending (not yet started) check. */
    public void submit(Key key, String text, WordFilter filter) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            pendingCheck = new CheckRequest(key, text, filter);
            schedule();
        }
    }

    /** Asks for ranked candidates for one issue; replaces a pending suggestion request. */
    public void suggest(Key key, SpellIssue issue, int max) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            pendingSuggest = new SuggestRequest(key, issue, max);
            schedule();
        }
    }

    /** Requests waiting to run (0-2); running work is not counted. For tests and diagnostics. */
    public int pendingCount() {
        synchronized (lock) {
            return (pendingCheck == null ? 0 : 1) + (pendingSuggest == null ? 0 : 1);
        }
    }

    /** Duration of the last completed check in nanoseconds (0 before the first). */
    public long lastCheckNanos() {
        return lastCheckNanos;
    }

    private void schedule() { // caller holds the lock
        if (!draining) {
            draining = true;
            try {
                worker.execute(this::drain);
            } catch (RuntimeException rejected) { // executor already shut down
                draining = false;
            }
        }
    }

    private void drain() {
        while (true) {
            CheckRequest check = null;
            SuggestRequest suggest = null;
            synchronized (lock) {
                if (closed) {
                    draining = false;
                    return;
                }
                if (pendingSuggest != null) { // cheap and the user is waiting for it
                    suggest = pendingSuggest;
                    pendingSuggest = null;
                } else if (pendingCheck != null) {
                    check = pendingCheck;
                    pendingCheck = null;
                } else {
                    draining = false;
                    return;
                }
            }
            if (suggest != null) {
                runSuggest(suggest);
            } else {
                runCheck(check);
            }
        }
    }

    private void runCheck(CheckRequest r) {
        SpellCheckResult result = null;
        String error = null;
        long t0 = System.nanoTime();
        try {
            result = backend.check(r.text(), r.key().language(), r.filter());
        } catch (SpellException e) {
            error = e.getMessage();
        } catch (Throwable t) { // an Error must not silently kill the worker
            error = "Unexpected spelling failure (" + t.getClass().getSimpleName() + ").";
        }
        lastCheckNanos = System.nanoTime() - t0;
        CheckOutcome outcome = new CheckOutcome(r.key(), result, error);
        deliver(() -> listener.checked(outcome));
    }

    private void runSuggest(SuggestRequest r) {
        List<String> list = List.of();
        String error = null;
        try {
            list = backend.suggest(r.issue().token(), r.key().language(), r.max());
        } catch (SpellException e) {
            error = e.getMessage();
        } catch (Throwable t) {
            error = "Unexpected spelling failure (" + t.getClass().getSimpleName() + ").";
        }
        SuggestOutcome outcome = new SuggestOutcome(r.key(), r.issue(), list, error);
        deliver(() -> listener.suggested(outcome));
    }

    private void deliver(Runnable r) {
        try {
            ui.execute(() -> {
                if (!closed) {
                    r.run();
                }
            });
        } catch (RuntimeException ignored) { // UI executor gone (shutdown)
        }
    }

    /**
     * Stops accepting work, drops pending requests, waits up to two seconds for the running one, then closes the backend. If the worker is still busy after
     * the wait the backend is NOT closed under it (the process is ending anyway).
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pendingCheck = null;
            pendingSuggest = null;
        }
        worker.shutdown();
        boolean stopped;
        try {
            stopped = worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = false;
        }
        if (stopped) {
            backend.close();
        }
    }
}
