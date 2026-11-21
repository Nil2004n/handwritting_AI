package notebookai.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectWorkflowTest {

    @TempDir Path dir;

    // ------------------------------------------------------------ dirty tracking

    @Test
    void freshTrackerIsCleanAndMeaningfulChangesDirtyIt() {
        DirtyTracker d = new DirtyTracker();
        assertFalse(d.isDirty());
        d.markModified();
        assertTrue(d.isDirty());
        d.reset();
        assertFalse(d.isDirty());
    }

    @Test
    void editsMadeDuringAnAsyncSaveAreNotMarkedSaved() {
        DirtyTracker d = new DirtyTracker();
        d.markModified();
        long token = d.beginSave();
        d.markModified(); // user edits while the save is running
        d.saveSucceeded(token);
        assertTrue(d.isDirty(), "the later edit is still unsaved");
        long token2 = d.beginSave();
        d.saveSucceeded(token2);
        assertFalse(d.isDirty());
    }

    @Test
    void aFailedSaveLeavesTheProjectDirty() {
        DirtyTracker d = new DirtyTracker();
        d.markModified();
        d.beginSave(); // save started...
        // ...failed: saveSucceeded is never called
        assertTrue(d.isDirty());
    }

    @Test
    void sessionSaveCommitsOnlyForTheSameProjectEpoch() throws Exception {
        ProjectSession s = new ProjectSession();
        s.project().add(PageData.create("a.png", TestImages.png(12, 12, 1)));
        s.markModified();
        Path target = dir.resolve("a.nbai");
        ProjectSession.SaveJob job = s.beginSave(target);
        s.markModified(); // edit during save
        ProjectStore.save(job.snapshot(), target);
        assertTrue(s.saveSucceeded(job));
        assertTrue(s.isDirty(), "edit made during the save stays unsaved");
        assertEquals(target, s.path());

        ProjectSession.SaveJob stale = s.beginSave(dir.resolve("b.nbai"));
        s.newProject(); // project replaced while that save was in flight
        assertFalse(s.saveSucceeded(stale), "a result for a replaced project is ignored");
        assertNull(s.path());
        assertFalse(s.isDirty());
    }

    @Test
    void snapshotIsIndependentOfLaterEdits() {
        ProjectSession s = new ProjectSession();
        PageData p = PageData.create("a.png", TestImages.png(12, 12, 1));
        s.project().add(p);
        p.setEditedText("before");
        ProjectSession.SaveJob job = s.beginSave(dir.resolve("x.nbai"));
        p.setEditedText("after");
        assertEquals("before", job.snapshot().pages().get(0).editedText());
        assertArrayEquals(p.original(), job.snapshot().pages().get(0).original());
    }

    @Test
    void openSwapsProjectOnlyOnSuccessAndResetsDirtyAndEpoch() throws Exception {
        ProjectData src = new ProjectData();
        src.add(PageData.create("x.png", TestImages.png(14, 14, 2)));
        Path file = dir.resolve("x.nbai");
        ProjectStore.save(src, file);
        ProjectSession s = new ProjectSession();
        s.markModified();
        long e0 = s.epoch();
        s.open(file);
        assertEquals(1, s.project().size());
        assertFalse(s.isDirty());
        assertEquals(file, s.path());
        assertNotEquals(e0, s.epoch());
    }

    // ------------------------------------------------------------ page order / identity

    @Test
    void pageIdentityIsStableAcrossReorderAndRemoval() {
        ProjectData p = new ProjectData();
        PageData a = PageData.create("a", TestImages.png(8, 8, 1));
        PageData b = PageData.create("b", TestImages.png(8, 8, 2));
        PageData c = PageData.create("c", TestImages.png(8, 8, 3));
        p.add(a);
        p.add(b);
        p.add(c);
        String idB = b.id();
        assertTrue(p.move(c.id(), -2));
        assertEquals(List.of(c.id(), a.id(), b.id()), p.pages().stream().map(PageData::id).toList());
        assertFalse(p.move(c.id(), -1), "already first");
        assertEquals(2, p.indexOf(idB));
        assertEquals(0, p.remove(c.id()));
        assertEquals(1, p.indexOf(idB));
        assertNull(p.find(c.id()));
        assertSame(b, p.find(idB));
    }

    // ------------------------------------------------------------ OCR result ownership

    @Test
    void ocrResultIsAcceptedOnlyWhenEveryIdentityStillMatches() {
        JobTicket t = new JobTicket(5, "page-1", 3, 7);
        assertTrue(t.acceptedBy(5, true, 3, 7));
        assertFalse(t.acceptedBy(6, true, 3, 7), "project replaced (New/Open)");
        assertFalse(t.acceptedBy(5, false, 3, 7), "page removed");
        assertFalse(t.acceptedBy(5, true, 4, 7), "image prepared differently since the job started");
        assertFalse(t.acceptedBy(5, true, 3, 8), "a newer job superseded it");
    }

    @Test
    void ocrResultIsAppliedToItsOwnPageNotTheActiveOne() {
        // The UI applies results by page id: simulate that two pages exist and the result is for page A.
        ProjectData p = new ProjectData();
        PageData a = PageData.create("a", TestImages.png(8, 8, 1));
        PageData b = PageData.create("b", TestImages.png(8, 8, 2));
        p.add(a);
        p.add(b);
        JobTicket ticket = new JobTicket(1, a.id(), 0, 1);
        PageData target = p.find(ticket.pageId());
        target.setRecognition("text for A", "id", List.of());
        assertTrue(a.recognized());
        assertFalse(b.recognized());
        assertNull(b.rawText());
    }

    // ------------------------------------------------------------ edits vs raw

    @Test
    void rawOcrIsPreservedSeparatelyFromEditedText() {
        PageData p = PageData.create("a", TestImages.png(8, 8, 1));
        assertFalse(p.recognized());
        assertFalse(p.hasUserEdits());
        p.setRecognition("raw", "id", List.of());
        assertEquals("raw", p.editedText());
        assertFalse(p.hasUserEdits());
        p.setEditedText("raw edited");
        assertTrue(p.hasUserEdits());
        assertEquals("raw", p.rawText(), "editing never touches raw OCR");
        p.setEditedText("raw");
        assertFalse(p.hasUserEdits());
        p.clearRecognition();
        assertFalse(p.recognized());
        assertNull(p.rawText());
        assertEquals("", p.editedText());
    }

    // ------------------------------------------------------------ action locking

    @Test
    void projectActionsAreLockedWhileATaskRunsButSaveAndExportRemain() {
        ProjectActions idle = ProjectActions.compute(3, 1, false, false);
        assertTrue(idle.addImages() && idle.removePage() && idle.moveUp() && idle.moveDown());
        assertTrue(idle.switchPage() && idle.newProject() && idle.openProject());
        assertTrue(idle.save() && idle.saveAs() && idle.exportCurrent() && idle.exportAll());

        ProjectActions busy = ProjectActions.compute(3, 1, true, false);
        assertFalse(busy.addImages() || busy.removePage() || busy.moveUp() || busy.moveDown());
        assertFalse(busy.switchPage() || busy.newProject() || busy.openProject());
        assertTrue(busy.save() && busy.exportCurrent() && busy.exportAll());

        ProjectActions saving = ProjectActions.compute(3, 1, false, true);
        assertFalse(saving.newProject() || saving.openProject() || saving.save() || saving.saveAs());

        ProjectActions empty = ProjectActions.compute(0, -1, false, false);
        assertFalse(empty.removePage() || empty.exportCurrent() || empty.exportAll());
        assertTrue(empty.addImages() && empty.save());
        assertFalse(ProjectActions.compute(3, 0, false, false).moveUp());
        assertFalse(ProjectActions.compute(3, 2, false, false).moveDown());
        assertFalse(ProjectActions.compute(ProjectStore.MAX_PAGES, 0, false, false).addImages());
    }

    // ------------------------------------------------------------ export

    private static ProjectData three() {
        ProjectData p = new ProjectData();
        PageData a = PageData.create("a", TestImages.png(8, 8, 1));
        a.setRecognition("RAW A", "id", List.of());
        a.setEditedText("Edited A\n"); // trailing newline is meaningful
        PageData b = PageData.create("b", TestImages.png(8, 8, 2)); // unrecognized
        PageData c = PageData.create("c", TestImages.png(8, 8, 3));
        c.setRecognition("RAW C", "id", List.of());
        c.setEditedText("  Edited C  ");
        p.add(a);
        p.add(b);
        p.add(c);
        return p;
    }

    @Test
    void exportAllUsesEditedTextInProjectOrderWithOneBlankLineSeparators() {
        ProjectData p = three();
        String all = TextExport.renderAll(TextExport.pages(p), false);
        assertEquals("Edited A\n" + "\n\n" + "" + "\n\n" + "  Edited C  ", all);
        assertFalse(all.contains("RAW A"), "raw OCR is never substituted for reviewed text");
        // reorder: export order follows project order
        p.move(p.pages().get(2).id(), -2);
        assertEquals("  Edited C  " + "\n\n" + "Edited A\n" + "\n\n" + "", TextExport.renderAll(TextExport.pages(p), false));
    }

    @Test
    void pageLabelsAreOptInAndCurrentPageIsNeverLabelled() {
        ProjectData p = three();
        List<TextExport.ExportPage> pages = TextExport.pages(p);
        String labelled = TextExport.renderAll(pages, true);
        assertTrue(labelled.startsWith("--- Page 1 ---\nEdited A\n"));
        assertTrue(labelled.contains("--- Page 2 ---\n\n\n--- Page 3 ---\n  Edited C  "));
        assertEquals("  Edited C  ", TextExport.renderOne(pages.get(2)));
    }

    @Test
    void incompleteExportsAreFlaggedNotSilentlyPresentedAsComplete() {
        ProjectData p = three();
        PageData d = PageData.create("d", TestImages.png(8, 8, 4));
        d.setRecognition("", "id", List.of()); // recognized, empty
        p.add(d);
        List<String> w = TextExport.warnings(TextExport.pages(p));
        assertEquals(2, w.size());
        assertTrue(w.get(0).contains("page(s) 2"), w.get(0));
        assertTrue(w.get(1).contains("page(s) 4"), w.get(1));
        assertEquals(TextExport.Status.UNRECOGNIZED, TextExport.pages(p).get(1).status());
        assertEquals(TextExport.Status.EMPTY, TextExport.pages(p).get(3).status());
        assertTrue(TextExport.warnings(TextExport.pages(new ProjectData())).isEmpty());
    }

    @Test
    void writeUtf8IsVerbatimWithoutBomAndDoesNotTouchTheProject() throws Exception {
        String text = "café 日本 😀\r\nsecond line  \n";
        Path f = dir.resolve("out.txt");
        TextExport.writeUtf8(f, text);
        byte[] bytes = Files.readAllBytes(f);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), bytes);
        assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB, "no BOM");
        // overwrite replaces atomically; the project object is not involved at all
        TextExport.writeUtf8(f, "second");
        assertEquals("second", Files.readString(f));
        try (var s = Files.list(dir)) {
            assertEquals(List.of("out.txt"), s.map(x -> x.getFileName().toString()).toList());
        }
    }

    @Test
    void writeFailuresAreReportedAndLeaveExistingFilesAlone() throws Exception {
        Path f = dir.resolve("keep.txt");
        Files.writeString(f, "precious");
        assertThrows(ProjectException.class, () -> TextExport.writeUtf8(f, "bad \uD800 surrogate"));
        assertEquals("precious", Files.readString(f));
        assertThrows(ProjectException.class, () -> TextExport.writeUtf8(dir.resolve("nodir").resolve("x.txt"), "x"));
        assertThrows(ProjectException.class, () -> TextExport.writeUtf8(dir, "x"));
    }

    @Test
    void savedAndExportedFilesKeepExistingPermissionsAndNewOnesUseDefaults() throws Exception {
        java.util.Set<java.nio.file.attribute.PosixFilePermission> rw = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");
        Path proj = dir.resolve("perm.nbai");
        ProjectData p = new ProjectData();
        p.add(PageData.create("a.png", TestImages.png(10, 10, 1)));
        ProjectStore.save(p, proj);
        Path baseline = dir.resolve("baseline");
        Files.createFile(baseline);
        assertEquals(Files.getPosixFilePermissions(baseline), Files.getPosixFilePermissions(proj),
                "a NEW project file follows the default umask, not owner-only");
        Files.setPosixFilePermissions(proj, rw);
        ProjectStore.save(p, proj);
        assertEquals(rw, Files.getPosixFilePermissions(proj), "overwriting keeps existing permissions");
        Path txt = dir.resolve("e.txt");
        Files.writeString(txt, "old");
        Files.setPosixFilePermissions(txt, rw);
        TextExport.writeUtf8(txt, "new");
        assertEquals(rw, Files.getPosixFilePermissions(txt));
        Path fresh = dir.resolve("fresh.txt");
        TextExport.writeUtf8(fresh, "x");
        assertEquals(Files.getPosixFilePermissions(baseline), Files.getPosixFilePermissions(fresh));
    }

    @Test
    void permissionErrorsAreDescribedInPlainLanguage() throws Exception {
        Path ro = dir.resolve("ro");
        Files.createDirectory(ro);
        Files.setPosixFilePermissions(ro, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            ProjectData p = new ProjectData();
            p.add(PageData.create("a.png", TestImages.png(10, 10, 1)));
            String m1 = assertThrows(ProjectException.class, () -> ProjectStore.save(p, ro.resolve("x.nbai"))).getMessage();
            String m2 = assertThrows(ProjectException.class, () -> TextExport.writeUtf8(ro.resolve("x.txt"), "t")).getMessage();
            assertTrue(m1.contains("permission denied") && !m1.contains(".tmp"), m1);
            assertTrue(m2.contains("permission denied") && !m2.contains(".tmp"), m2);
            try (var s = Files.list(ro)) {
                assertEquals(0, s.count(), "no temporary file left behind");
            }
        } finally {
            Files.setPosixFilePermissions(ro, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
