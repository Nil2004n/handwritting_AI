package notebookai.project;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs background work and ALWAYS delivers exactly one completion to the UI executor, including
 * when the work throws a checked/unchecked exception or an {@link Error} (OutOfMemoryError,
 * linkage errors, ...). A completion that never arrives would leave the application "busy"
 * forever, so nothing is allowed to skip it. Executors are injected so tests are deterministic
 * (a direct executor and latches instead of sleeps); the UI injects a thread starter and
 * {@code Platform.runLater}.
 *
 * <p>This does NOT cancel running work: a started task always runs to completion (native
 * inference cannot be interrupted safely).
 */
public final class BackgroundTasks {

    /** Result delivered to the UI: {@code error == null} means success. */
    public record Outcome<T>(T value, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final Executor worker;
    private final Executor ui;
    private final Function<Throwable, String> describer;

    public BackgroundTasks(Executor worker, Executor ui, Function<Throwable, String> describer) {
        this.worker = worker;
        this.ui = ui;
        this.describer = describer;
    }

    public BackgroundTasks(Executor worker, Executor ui) {
        this(worker, ui, BackgroundTasks::describe);
    }

    public <T> void run(Callable<T> work, Consumer<Outcome<T>> onUi) {
        worker.execute(() -> {
            T value = null;
            String error = null;
            try {
                value = work.call();
            } catch (Throwable t) { // deliberately Throwable: see class comment
                error = describer.apply(t);
            }
            Outcome<T> outcome = new Outcome<>(value, error);
            AtomicBoolean delivered = new AtomicBoolean();
            ui.execute(() -> {
                if (delivered.compareAndSet(false, true)) {
                    onUi.accept(outcome);
                }
            });
        });
    }

    /** Default, user-actionable description of a failure. */
    public static String describe(Throwable t) {
        if (t instanceof ProjectException || t instanceof notebookai.ocr.OcrException) {
            return t.getMessage();
        }
        if (t instanceof OutOfMemoryError) {
            return "Not enough memory to complete this operation. Close other pages/projects or start "
                    + "the application with more memory (e.g. JAVA_OPTS=-Xmx4g).";
        }
        String m = t.getMessage();
        return "Unexpected failure (" + t.getClass().getSimpleName() + (m == null ? "" : ": " + m) + ").";
    }
}
