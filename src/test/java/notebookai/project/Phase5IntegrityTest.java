package notebookai.project;

import notebookai.ocr.PixelImage;
import notebookai.ocr.DesktopImageIO;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import notebookai.model.WorkingImage;
import notebookai.ocr.ImageOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase5IntegrityTest {

    @TempDir Path dir;

    // ------------------------------------------------------------------ reconstruction

    private static void assertSameImage(WorkingImage.Rendered a, WorkingImage.Rendered b, String what) {
        assertEquals(a.width(), b.width(), what + " width");
        assertEquals(a.height(), b.height(), what + " height");
        assertArrayEquals(a.bgr(), b.bgr(), what + " pixels");
    }

    /** Applies "what the UI would persist" (turns, fine, crop) to a page. */
    private static void persist(PageData page, WorkingImage w) {
        page.setPrep(w.quarterTurnsCw(), w.fineDegrees(), w.crop());
    }

    private static WorkingImage fresh(byte[] png) throws Exception {
        PixelImage d = DesktopImageIO.decode(png);
        return new WorkingImage(d.bgr(), d.width(), d.height());
    }

    @Test
    void combinedPreparationSequencesRebuildBitExactlyAfterSaveAndReopen() throws Exception {
        byte[] png = TestImages.png(90, 60, 3);
        ProjectData project = new ProjectData();
        WorkingImage[] interactive = new WorkingImage[4];
        String[] names = {"rotate-crop-straighten-crop", "crop-rotate-reset-crop", "repeated-straighten", "straighten-then-crop"};

        // 1) rotate -> crop -> straighten (UI clears the crop) -> crop again in the straightened frame
        WorkingImage w1 = fresh(png);
        w1.rotateSteps(1);
        w1.setCrop(new int[] {5, 8, 50, 70});
        w1.setStraighten(3.5);
        assertFalse(w1.hasCrop(), "straighten intentionally clears the crop");
        int[] f1 = WorkingImage.frameSize(90, 60, 1, 3.5);
        w1.setCrop(new int[] {4, 6, f1[0] - 3, f1[1] - 5});
        interactive[0] = w1;
        // 2) crop -> rotate (clears crop) -> reset -> crop on the original frame
        WorkingImage w2 = fresh(png);
        w2.setCrop(new int[] {10, 10, 70, 50});
        w2.rotateSteps(-1);
        assertFalse(w2.hasCrop());
        w2.reset();
        w2.setCrop(new int[] {3, 2, 80, 55});
        interactive[1] = w2;
        // 3) several straighten/apply operations: the last value wins, they do not accumulate
        WorkingImage w3 = fresh(png);
        w3.setStraighten(1.5);
        w3.setStraighten(-2.25);
        w3.rotateSteps(2);
        w3.setStraighten(0);
        w3.setStraighten(7.0);
        interactive[2] = w3;
        // 4) straighten then crop (crop lives in the straightened frame)
        WorkingImage w4 = fresh(png);
        w4.setStraighten(-6.0);
        int[] f4 = WorkingImage.frameSize(90, 60, 0, -6.0);
        w4.setCrop(new int[] {7, 9, f4[0] - 4, f4[1] - 6});
        interactive[3] = w4;

        for (int i = 0; i < 4; i++) {
            PageData p = PageData.create(names[i] + ".png", png);
            persist(p, interactive[i]);
            project.add(p);
        }
        Path file = dir.resolve("prep.nbai");
        ProjectStore.save(project, file);
        ProjectData back = ProjectStore.load(file);
        for (int i = 0; i < 4; i++) {
            PageData p = back.pages().get(i);
            PixelImage d = DesktopImageIO.decode(p.original());
            PageRestore.Restored r = PageRestore.restore(p, d.bgr(), d.width(), d.height());
            assertTrue(r.notices().isEmpty(), names[i] + ": " + r.notices());
            WorkingImage.Rendered rebuilt = r.working().render();
            assertSameImage(interactive[i].render(), rebuilt, names[i]);
            assertEquals(r.renderedWidth(), rebuilt.width());
            assertEquals(r.renderedHeight(), rebuilt.height());
        }
        // different pages really did get different preparations
        assertFalse(interactive[0].render().width() == interactive[1].render().width()
                && interactive[0].render().height() == interactive[1].render().height());
    }

    @Test
    void frameSizeMatchesWhatRenderProduces() throws Exception {
        byte[] png = TestImages.png(70, 40, 4);
        for (int turns = 0; turns < 4; turns++) {
            for (double fine : new double[] {0, 2.5, -4.75, 10, -10}) {
                WorkingImage w = fresh(png);
                w.rotateSteps(turns);
                if (fine != 0) {
                    w.setStraighten(fine);
                }
                int[] f = WorkingImage.frameSize(70, 40, turns, fine);
                WorkingImage.Rendered r = w.render();
                assertEquals(f[0], r.width(), "turns=" + turns + " fine=" + fine);
                assertEquals(f[1], r.height(), "turns=" + turns + " fine=" + fine);
            }
        }
    }

    @Test
    void anUnfittingSavedCropOrRegionsAreDiscardedVisiblyNotShownStale() throws Exception {
        byte[] png = TestImages.png(80, 50, 5);
        PixelImage d = DesktopImageIO.decode(png);
        // crop larger than the rebuilt frame
        PageData bigCrop = PageData.create("c.png", png);
        bigCrop.setPrep(0, 0.0, new int[] {0, 0, 500, 400});
        bigCrop.setRecognition("txt", "id", List.of(new RegionData(new double[][] {{1, 1}, {9, 1}, {9, 5}, {1, 5}}, new int[] {1, 1, 9, 5}, "t", 0.9, 0.9)));
        PageRestore.Restored r1 = PageRestore.restore(bigCrop, d.bgr(), d.width(), d.height());
        assertFalse(r1.notices().isEmpty());
        assertNull(bigCrop.crop(), "invalid crop dropped");
        assertTrue(bigCrop.regions().isEmpty(), "overlays tied to that crop dropped");
        assertEquals("txt", bigCrop.rawText(), "text is kept");
        assertEquals(80, r1.working().render().width());
        // regions outside the image
        PageData badRegion = PageData.create("r.png", png);
        badRegion.setRecognition("txt", "id", List.of(new RegionData(new double[][] {{0, 0}, {900, 0}, {900, 10}, {0, 10}}, new int[] {0, 0, 900, 10}, "t", 0.9, 0.9)));
        PageRestore.Restored r2 = PageRestore.restore(badRegion, d.bgr(), d.width(), d.height());
        assertTrue(r2.notices().get(0).contains("overlays"));
        assertTrue(badRegion.regions().isEmpty());
        // valid geometry is kept untouched
        PageData good = PageData.create("g.png", png);
        good.setPrep(1, 0.0, new int[] {2, 3, 40, 70});
        good.setRecognition("txt", "id", List.of(new RegionData(new double[][] {{1, 1}, {30, 1}, {30, 9}, {1, 9}}, new int[] {1, 1, 30, 9}, "t", 0.9, 0.9)));
        PageRestore.Restored r3 = PageRestore.restore(good, d.bgr(), d.width(), d.height());
        assertTrue(r3.notices().isEmpty(), String.valueOf(r3.notices()));
        assertEquals(1, good.regions().size());
        assertEquals(38, r3.renderedWidth());
        assertEquals(67, r3.renderedHeight());
    }

    @Test
    void textsSurviveSaveReopenExactlyAndStayDistinct() throws Exception {
        String raw = "Raw:\n½ + 3.5 = 4 − √2 ≤ π °C 10⁻³ €12.50 \"q\" 'a' \\ / 😀\r\nend  ";
        String edited = raw.replace("3.5", "3,5") + "\n\n\t tail　";
        PageData p = PageData.create("t.png", TestImages.png(12, 12, 1));
        p.setRecognition(raw, "id", List.of());
        p.setEditedText(edited);
        PageData unrec = PageData.create("u.png", TestImages.png(12, 12, 2));
        PageData empty = PageData.create("e.png", TestImages.png(12, 12, 3));
        empty.setRecognition("", "id", List.of());
        ProjectData proj = new ProjectData();
        proj.add(p);
        proj.add(unrec);
        proj.add(empty);
        Path f = dir.resolve("t.nbai");
        ProjectStore.save(proj, f);
        ProjectData back = ProjectStore.load(f);
        assertEquals(raw, back.pages().get(0).rawText());
        assertEquals(edited, back.pages().get(0).editedText());
        assertFalse(back.pages().get(1).recognized());
        assertNull(back.pages().get(1).rawText());
        assertTrue(back.pages().get(2).recognized());
        assertEquals("", back.pages().get(2).rawText());
    }

    // ------------------------------------------------------------------ configurable limits: exact boundaries

    private ProjectData onePage(byte[] png) {
        ProjectData p = new ProjectData();
        p.add(new PageData("pg1", "a.png", png));
        return p;
    }

    private static ProjectStore.Limits with(ProjectStore.Limits l, Map<String, Number> over) {
        return new ProjectStore.Limits(
                over.getOrDefault("pages", l.maxPages()).intValue(),
                over.getOrDefault("image", l.maxImageBytes()).longValue(),
                over.getOrDefault("json", l.maxJsonBytes()).longValue(),
                over.getOrDefault("total", l.maxTotalBytes()).longValue(),
                over.getOrDefault("pixels", l.maxPixels()).longValue(),
                over.getOrDefault("regions", l.maxRegionsPerPage()).intValue());
    }

    @Test
    void imageByteLimitIsExactAtTheBoundaryForSaveAndLoad() throws Exception {
        byte[] png = TestImages.png(16, 16, 1);
        ProjectStore.Limits ok = with(ProjectStore.Limits.DEFAULT, Map.of("image", (long) png.length));
        ProjectStore.Limits tooSmall = with(ProjectStore.Limits.DEFAULT, Map.of("image", (long) png.length - 1));
        Path f = dir.resolve("img.nbai");
        ProjectStore.save(onePage(png), f, ok); // exactly N accepted
        ProjectStore.load(f, ok);
        assertThrows(ProjectException.class, () -> ProjectStore.load(f, tooSmall)); // N-1 rejected on load
        assertThrows(ProjectException.class, () -> ProjectStore.save(onePage(png), dir.resolve("img2.nbai"), tooSmall));
        assertFalse(Files.exists(dir.resolve("img2.nbai")));
    }

    @Test
    void pixelAndPageAndRegionLimitsAreExact() throws Exception {
        byte[] png = TestImages.png(10, 10, 1); // 100 px
        Path f = dir.resolve("px.nbai");
        ProjectStore.save(onePage(png), f);
        ProjectStore.load(f, with(ProjectStore.Limits.DEFAULT, Map.of("pixels", 100L)));
        assertThrows(ProjectException.class, () -> ProjectStore.load(f, with(ProjectStore.Limits.DEFAULT, Map.of("pixels", 99L))));
        ProjectData three = new ProjectData();
        for (int i = 0; i < 3; i++) {
            three.add(new PageData("p" + i, "x.png", png));
        }
        Path f3 = dir.resolve("three.nbai");
        ProjectStore.save(three, f3);
        ProjectStore.load(f3, with(ProjectStore.Limits.DEFAULT, Map.of("pages", 3)));
        assertThrows(ProjectException.class, () -> ProjectStore.load(f3, with(ProjectStore.Limits.DEFAULT, Map.of("pages", 2))));
        assertThrows(ProjectException.class, () -> ProjectStore.save(three, dir.resolve("x.nbai"), with(ProjectStore.Limits.DEFAULT, Map.of("pages", 2))));
        PageData reg = new PageData("pr", "r.png", png);
        List<RegionData> two = List.of(
                new RegionData(new double[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}, new int[] {0, 0, 1, 1}, "a", 0.5, 0.5),
                new RegionData(new double[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}, new int[] {0, 0, 1, 1}, "b", 0.5, 0.5));
        reg.setRecognition("t", "id", two);
        ProjectData rp = new ProjectData();
        rp.add(reg);
        Path fr = dir.resolve("reg.nbai");
        ProjectStore.save(rp, fr);
        ProjectStore.load(fr, with(ProjectStore.Limits.DEFAULT, Map.of("regions", 2)));
        assertThrows(ProjectException.class, () -> ProjectStore.load(fr, with(ProjectStore.Limits.DEFAULT, Map.of("regions", 1))));
    }

    @Test
    void jsonAndTotalByteLimitsAreExact() throws Exception {
        byte[] png = TestImages.png(10, 10, 1);
        Path f = dir.resolve("j.nbai");
        ProjectStore.save(onePage(png), f);
        long jsonLen = ZipTools.read(f).get("project.json").length;
        ProjectStore.load(f, with(ProjectStore.Limits.DEFAULT, Map.of("json", jsonLen)));
        assertThrows(ProjectException.class, () -> ProjectStore.load(f, with(ProjectStore.Limits.DEFAULT, Map.of("json", jsonLen - 1))));
        long fileSize = Files.size(f);
        ProjectStore.load(f, with(ProjectStore.Limits.DEFAULT, Map.of("total", fileSize)));
        assertThrows(ProjectException.class, () -> ProjectStore.load(f, with(ProjectStore.Limits.DEFAULT, Map.of("total", fileSize - 1))));
        // expanded total (json + image bytes) is also enforced while reading, independent of header sizes
        long expanded = jsonLen + png.length;
        assertThrows(ProjectException.class, () -> ProjectStore.load(f, with(ProjectStore.Limits.DEFAULT, Map.of("total", fileSize - 1 > expanded - 1 ? expanded - 1 : fileSize - 1))));
    }

    @Test
    void memoryGuardRefusesBeforeAllocatingAtTheBoundary() {
        long heap = 1_000_000_000L;
        long allowed = (long) (heap * MemoryGuard.HEAP_FRACTION) / MemoryGuard.BYTES_PER_PIXEL;
        assertNull(MemoryGuard.check(allowed, 1, heap));
        assertNotNull(MemoryGuard.check(allowed + 1, 1, heap));
        String msg = MemoryGuard.check(20000, 20000, heap);
        assertTrue(msg.contains("too large") && msg.contains("-Xmx"));
        assertNull(MemoryGuard.check(4000, 3000, 2_000_000_000L), "a 12 MP phone photo fits a 2 GB heap");
    }

    // ------------------------------------------------------------------ malformed project handling is atomic

    @Test
    void everyKindOfBrokenProjectFailsWithoutChangingTheOpenProject() throws Exception {
        ProjectSession s = new ProjectSession();
        s.project().add(PageData.create("keep.png", TestImages.png(10, 10, 1)));
        s.markModified();
        ProjectData before = s.project();
        Path good = dir.resolve("good.nbai");
        ProjectData src = new ProjectData();
        src.add(PageData.create("x.png", TestImages.png(10, 10, 2)));
        ProjectStore.save(src, good);
        Map<String, byte[]> z = ZipTools.read(good);
        String img = z.keySet().stream().filter(k -> k.startsWith("pages/")).findFirst().get();
        String json = new String(z.get("project.json"), StandardCharsets.UTF_8);
        java.util.function.Consumer<Map<String, byte[]>> noop = m -> {};
        Path[] broken = new Path[8];
        // unsupported version, hash mismatch, missing entry, duplicate-like extra entry, bad coordinates, truncated, junk, empty
        broken[0] = write("ver", z, json.replace("\"formatVersion\":1", "\"formatVersion\":42"), null);
        Map<String, byte[]> tampered = new java.util.LinkedHashMap<>(z);
        tampered.put(img, TestImages.png(10, 10, 9));
        broken[1] = dir.resolve("hash.nbai");
        ZipTools.write(broken[1], tampered);
        Map<String, byte[]> missing = new java.util.LinkedHashMap<>(z);
        missing.remove(img);
        broken[2] = dir.resolve("missing.nbai");
        ZipTools.write(broken[2], missing);
        Map<String, byte[]> extra = new java.util.LinkedHashMap<>(z);
        extra.put("pages/zzz/original", new byte[] {1});
        broken[3] = dir.resolve("extra.nbai");
        ZipTools.write(broken[3], extra);
        broken[4] = write("coords", z, json.replace("\"quarterTurnsCw\":0", "\"quarterTurnsCw\":9"), null);
        byte[] all = Files.readAllBytes(good);
        broken[5] = dir.resolve("trunc.nbai");
        Files.write(broken[5], java.util.Arrays.copyOf(all, all.length - 30));
        broken[6] = dir.resolve("junk.nbai");
        Files.write(broken[6], "junk".getBytes(StandardCharsets.UTF_8));
        broken[7] = dir.resolve("empty.nbai");
        Files.write(broken[7], new byte[0]);
        for (Path p : broken) {
            assertThrows(ProjectException.class, () -> s.open(p), p.getFileName().toString());
            org.junit.jupiter.api.Assertions.assertSame(before, s.project(), "active project untouched after failed open of " + p.getFileName());
            assertTrue(s.isDirty());
            assertNull(s.path());
        }
    }

    private Path write(String name, Map<String, byte[]> base, String json, Void unused) throws Exception {
        Map<String, byte[]> m = new java.util.LinkedHashMap<>(base);
        m.put("project.json", json.getBytes(StandardCharsets.UTF_8));
        Path p = dir.resolve(name + ".nbai");
        ZipTools.write(p, m);
        return p;
    }
}
