package notebookai.assist;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** An ExecutorService that runs tasks only when the test says so: deterministic ordering for stale/coalescing tests (no sleeps). */
final class ManualWorker extends AbstractExecutorService {

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    @Override
    public synchronized void execute(Runnable r) {
        if (shutdown) {
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        }
        tasks.add(r);
    }

    /** Runs queued tasks until none is left (tasks queued while running are run too). */
    void runAll() {
        while (true) {
            Runnable r;
            synchronized (this) {
                r = tasks.poll();
            }
            if (r == null) {
                return;
            }
            r.run();
        }
    }

    synchronized int queued() {
        return tasks.size();
    }

    @Override public synchronized void shutdown() { shutdown = true; }
    @Override public synchronized List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
    @Override public synchronized boolean isShutdown() { return shutdown; }
    @Override public synchronized boolean isTerminated() { return shutdown && tasks.isEmpty(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
}
