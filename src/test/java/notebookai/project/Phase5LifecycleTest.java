package notebookai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic lifecycle/concurrency tests: injected executors and latches, no sleeps. */
class Phase5LifecycleTest {

    @TempDir Path dir;

    /** Manual executor: tasks run only when the test says so. */
    private static final class Manual implements java.util.concurrent.Executor {
        final Queue<Runnable> q = new ArrayDeque<>();
        @Override public void execute(Runnable r) { q.add(r); }
        void runAll() { while (!q.isEmpty()) { q.poll().run(); } }
    }

    // ------------------------------------------------------------------ BackgroundTasks

    @Test
    void successIsDeliveredToTheUiExecutorNotTheWorker() {
        Manual worker = new Manual();
        Manual ui = new Manual();
        List<BackgroundTasks.Outcome<String>> got = new ArrayList<>();
        new BackgroundTasks(worker, ui).run(() -> "result", got::add);
        assertTrue(got.isEmpty(), "nothing before the worker runs");
        worker.runAll();
        assertTrue(got.isEmpty(), "worker finished but the UI executor has not run the completion yet");
        ui.runAll();
        assertEquals(1, got.size());
        assertTrue(got.get(0).ok());
        assertEquals("result", got.get(0).value());
    }

    @Test
    void everyKindOfFailureStillProducesExactlyOneCompletion() {
        Throwable[] failures = {
            new ProjectException("disk full"),
            new notebookai.ocr.OcrException("models missing"),
            new IllegalStateException("bug"),
            new OutOfMemoryError("heap"),
            new LinkageError("native lib"),
            new StackOverflowError(),
        };
        for (Throwable t : failures) {
            Manual worker = new Manual();
            Manual ui = new Manual();
            List<BackgroundTasks.Outcome<Object>> got = new ArrayList<>();
            new BackgroundTasks(worker, ui).run(() -> { throw sneaky(t); }, got::add);
            worker.runAll();
            ui.runAll();
            ui.runAll(); // a second drain must not deliver again
            assertEquals(1, got.size(), t.getClass().getSimpleName() + ": exactly one completion (never stuck busy)");
            assertFalse(got.get(0).ok());
            assertNull(got.get(0).value());
            assertTrue(got.get(0).error() != null && !got.get(0).error().isBlank());
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    void failureMessagesAreActionable() {
        assertEquals("disk full", BackgroundTasks.describe(new ProjectException("disk full")));
        assertEquals("models missing", BackgroundTasks.describe(new notebookai.ocr.OcrException("models missing")));
        assertTrue(BackgroundTasks.describe(new OutOfMemoryError()).contains("-Xmx"));
        assertTrue(BackgroundTasks.describe(new IllegalStateException("x")).contains("IllegalStateException"));
    }

    @Test
    void tasksCompleteInSubmissionOrderOnAnOrderedUiExecutor() {
        Manual worker = new Manual();
        Manual ui = new Manual();
        BackgroundTasks bg = new BackgroundTasks(worker, ui);
        List<String> order = new ArrayList<>();
        bg.run(() -> "first", o -> order.add(o.value()));
        bg.run(() -> "second", o -> order.add(o.value()));
        worker.runAll();
        ui.runAll();
        assertEquals(List.of("first", "second"), order);
    }

    // ------------------------------------------------------------------ shutdown policy

    @Test
    void closeWhileBackgroundWorkRunsWaitsInsteadOfClosingOrCancelling() {
        assertEquals(ShutdownPolicy.Action.WAIT_FOR_BACKGROUND, ShutdownPolicy.onCloseRequest(true, false, false));
        assertEquals(ShutdownPolicy.Action.WAIT_FOR_BACKGROUND, ShutdownPolicy.onCloseRequest(true, false, true));
        assertEquals(ShutdownPolicy.Action.WAIT_FOR_BACKGROUND, ShutdownPolicy.onCloseRequest(false, true, true));
        assertEquals(ShutdownPolicy.Action.PROMPT_UNSAVED, ShutdownPolicy.onCloseRequest(false, false, true));
        assertEquals(ShutdownPolicy.Action.CLOSE_NOW, ShutdownPolicy.onCloseRequest(false, false, false));
    }

    @Test
    void followUpActionAfterSaveRequiresThatNothingNewIsUnsaved() {
        assertTrue(ShutdownPolicy.mayProceedAfterSave(true, false));
        assertFalse(ShutdownPolicy.mayProceedAfterSave(true, true), "edits made during the save must not be discarded");
        assertFalse(ShutdownPolicy.mayProceedAfterSave(false, false));
    }

    // ------------------------------------------------------------------ saving while state changes

    private ProjectSession sessionWithPage(String text) {
        ProjectSession s = new ProjectSession();
        PageData p = PageData.create("a.png", TestImages.png(24, 18, 1));
        p.setRecognition("raw", "id", List.of());
        p.setEditedText(text);
        s.project().add(p);
        s.markModified();
        return s;
    }

    @Test
    void aSavedSnapshotStaysConsistentWhileTheProjectIsEditedInsideTheSave() throws Exception {
        ProjectSession s = sessionWithPage("v1");
        Path target = dir.resolve("snap.nbai");
        ProjectSession.SaveJob job = s.beginSave(target);
        CountDownLatch insideSave = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        Thread saver = new Thread(() -> {
            try {
                ProjectStore.save(job.snapshot(), job.target(), ProjectStore.Limits.DEFAULT, () -> {
                    insideSave.countDown(); // temp file written and validated, rename not done yet
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new java.io.IOException(e);
                    }
                });
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        saver.start();
        assertTrue(insideSave.await(10, TimeUnit.SECONDS));
        // The user edits and adds a page while the save is in flight.
        s.project().pages().get(0).setEditedText("v2 edited during save");
        s.project().add(PageData.create("b.png", TestImages.png(10, 10, 2)));
        s.markModified();
        release.countDown();
        saver.join(10_000);
        assertNull(failure[0], String.valueOf(failure[0]));

        assertTrue(s.saveSucceeded(job));
        assertTrue(s.isDirty(), "the edits made after the snapshot must remain unsaved");
        ProjectData onDisk = ProjectStore.load(target);
        assertEquals(1, onDisk.size(), "file holds the snapshot, not the later page");
        assertEquals("v1", onDisk.pages().get(0).editedText());
        assertFalse(ShutdownPolicy.mayProceedAfterSave(true, s.isDirty()), "New/Open/Quit must not proceed");
    }

    @Test
    void anOlderCompletedSaveCannotClearTheDirtyStateOfNewerEdits() {
        DirtyTracker d = new DirtyTracker();
        d.markModified();
        long t1 = d.beginSave();
        d.markModified();
        long t2 = d.beginSave();
        d.markModified(); // edit after the second snapshot
        d.saveSucceeded(t2);
        assertTrue(d.isDirty());
        d.saveSucceeded(t1); // older save finishes last
        assertTrue(d.isDirty(), "older completion neither regresses nor clears the newer edit");
        long t3 = d.beginSave();
        d.saveSucceeded(t3);
        assertFalse(d.isDirty());
        d.saveSucceeded(t1);
        assertFalse(d.isDirty(), "stale completion cannot make a clean project dirty either");
    }

    @Test
    void aFailedSaveInsideTheWindowKeepsThePreviousFileAndTheDirtyState() throws Exception {
        ProjectSession s = sessionWithPage("good");
        Path target = dir.resolve("keep.nbai");
        ProjectStore.save(s.beginSave(target).snapshot(), target);
        s.saveSucceeded(s.beginSave(target));
        byte[] before = java.nio.file.Files.readAllBytes(target);
        s.project().pages().get(0).setEditedText("unsaved");
        s.markModified();
        ProjectSession.SaveJob job = s.beginSave(target);
        org.junit.jupiter.api.Assertions.assertThrows(ProjectException.class, () ->
                ProjectStore.save(job.snapshot(), job.target(), ProjectStore.Limits.DEFAULT, () -> {
                    throw new java.io.IOException("simulated failure while renaming");
                }));
        assertTrue(s.isDirty(), "a failed save never marks anything saved");
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, java.nio.file.Files.readAllBytes(target));
    }
}
