package notebookai.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStoreTest {

    @TempDir Path dir;

    private static final String TRICKY =
            "Line one\nLine two\r\n  indented  \ttabbed\n\nUnicode: caf\u00e9 \u00fc\u00df \u65e5\u672c\u8a9e \uD83D\uDE00 "
                    + "quote\" backslash\\ lone\uD800 trailing   \n";

    private ProjectData sample() {
        ProjectData p = new ProjectData();
        PageData a = PageData.create("alpha.png", TestImages.png(40, 30, 1));
        a.setPrep(1, 2.5, new int[] {2, 3, 20, 25});
        a.setRecognition("raw A line\nsecond", "identity-A", List.of(
                new RegionData(new double[][] {{1, 2}, {30.5, 2}, {30.5, 9.25}, {1, 9.25}}, new int[] {1, 2, 31, 10},
                        "raw A line", 0.93, 0.987)));
        a.setEditedText(TRICKY);
        PageData b = PageData.create("beta.jpg", TestImages.jpeg(64, 48, 2));
        // b: never recognized (distinct from recognized-empty)
        PageData c = PageData.create("gamma.png", TestImages.png(32, 32, 3));
        c.setRecognition("", "identity-A", List.of()); // recognized, no text
        c.setEditedText("typed by hand");
        p.add(a);
        p.add(b);
        p.add(c);
        return p;
    }

    @Test
    void multiPageRoundTripPreservesEverythingExactly() throws Exception {
        ProjectData src = sample();
        Path file = dir.resolve("p" + ProjectStore.EXTENSION);
        ProjectStore.save(src, file);
        ProjectData back = ProjectStore.load(file);

        assertEquals(src.projectId(), back.projectId());
        assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) {
            PageData s = src.pages().get(i);
            PageData t = back.pages().get(i);
            assertEquals(s.id(), t.id(), "stable id, same order");
            assertEquals(s.name(), t.name());
            assertArrayEquals(s.original(), t.original(), "original image bytes");
            assertEquals(s.sha256(), t.sha256());
            assertEquals(s.quarterTurnsCw(), t.quarterTurnsCw());
            assertEquals(s.fineDegrees(), t.fineDegrees(), 0.0);
            assertArrayEquals(s.crop(), t.crop());
            assertEquals(s.recognized(), t.recognized());
            assertEquals(s.rawText(), t.rawText());
            assertEquals(s.editedText(), t.editedText(), "edited text must be identical");
            assertEquals(s.ocrIdentity(), t.ocrIdentity());
            assertEquals(s.regions().size(), t.regions().size());
        }
        // exact text incl. Unicode, CRLF, lone surrogate, trailing newline
        assertEquals(TRICKY, back.pages().get(0).editedText());
        assertEquals("raw A line\nsecond", back.pages().get(0).rawText());
        // regions round trip with geometry
        RegionData r = back.pages().get(0).regions().get(0);
        assertEquals(30.5, r.polygon()[1][0], 0.0);
        assertEquals(9.25, r.polygon()[2][1], 0.0);
        assertArrayEquals(new int[] {1, 2, 31, 10}, r.box());
        assertEquals(0.987, r.recScore(), 0.0);
        assertEquals("raw A line", r.text());
    }

    @Test
    void unrecognizedIsDistinctFromRecognizedEmpty() throws Exception {
        ProjectData src = sample();
        Path file = dir.resolve("p.nbai");
        ProjectStore.save(src, file);
        ProjectData back = ProjectStore.load(file);
        PageData unrecognized = back.pages().get(1);
        PageData recognizedEmpty = back.pages().get(2);
        assertFalse(unrecognized.recognized());
        assertNull(unrecognized.rawText());
        assertTrue(recognizedEmpty.recognized());
        assertEquals("", recognizedEmpty.rawText());
        assertEquals("typed by hand", recognizedEmpty.editedText());
        assertTrue(recognizedEmpty.hasUserEdits());
    }

    @Test
    void savedImagesAreOwnedByTheProjectFile() throws Exception {
        Path src = dir.resolve("source.png");
        byte[] bytes = TestImages.png(20, 20, 4);
        Files.write(src, bytes);
        ProjectData p = new ProjectData();
        p.add(PageData.create("source.png", Files.readAllBytes(src)));
        Path file = dir.resolve("own.nbai");
        ProjectStore.save(p, file);
        Files.delete(src); // original file gone
        assertArrayEquals(bytes, ProjectStore.load(file).pages().get(0).original());
        // no absolute paths are stored
        String json = new String(ZipTools.read(file).get("project.json"), StandardCharsets.UTF_8);
        assertFalse(json.contains(dir.toString()));
    }

    @Test
    void failedSaveKeepsPreviousFileAndCleansTemp() throws Exception {
        Path file = dir.resolve("keep.nbai");
        ProjectData v1 = sample();
        ProjectStore.save(v1, file);
        byte[] before = Files.readAllBytes(file);

        ProjectData v2 = sample();
        v2.pages().get(0).setEditedText("changed");
        ProjectException e = assertThrows(ProjectException.class, () -> ProjectStore.save(v2, file, () -> {
            throw new IOException("simulated disk failure");
        }));
        assertTrue(e.getMessage().contains("previous file"), e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(file), "previous valid save must be intact");
        try (var s = Files.list(dir)) {
            assertEquals(List.of("keep.nbai"), s.map(p -> p.getFileName().toString()).sorted().toList(),
                    "temporary file must be removed");
        }
        // and a later save works
        ProjectStore.save(v2, file);
        assertEquals("changed", ProjectStore.load(file).pages().get(0).editedText());
    }

    @Test
    void invalidProjectContentFailsBeforeReplacingAnything() throws Exception {
        Path file = dir.resolve("keep2.nbai");
        ProjectStore.save(sample(), file);
        byte[] before = Files.readAllBytes(file);
        ProjectData bad = new ProjectData();
        bad.add(PageData.create("notimage.png", "this is not an image".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ProjectException.class, () -> ProjectStore.save(bad, file));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertThrows(ProjectException.class,
                () -> ProjectStore.save(sample(), dir.resolve("no-such-folder").resolve("x.nbai")));
    }

    @Test
    void failedOpenLeavesActiveProjectAndDirtyStateUntouched() throws Exception {
        ProjectSession s = new ProjectSession();
        s.project().add(PageData.create("a.png", TestImages.png(10, 10, 1)));
        s.markModified();
        ProjectData before = s.project();
        long epoch = s.epoch();
        Path junk = dir.resolve("junk.nbai");
        Files.write(junk, "definitely not a zip".getBytes(StandardCharsets.UTF_8));
        assertThrows(ProjectException.class, () -> s.open(junk));
        assertThrows(ProjectException.class, () -> s.open(dir.resolve("missing.nbai")));
        assertSame(before, s.project());
        assertTrue(s.isDirty());
        assertEquals(epoch, s.epoch());
        assertNull(s.path());
    }

    private static void assertSame(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertSame(a, b);
    }

    // ------------------------------------------------------------ corrupt / hostile archives

    @Test
    void rejectsCorruptTruncatedAndNonProjectFiles() throws Exception {
        Path good = dir.resolve("good.nbai");
        ProjectStore.save(sample(), good);
        byte[] all = Files.readAllBytes(good);
        Path truncated = dir.resolve("trunc.nbai");
        Files.write(truncated, Arrays.copyOf(all, all.length / 2));
        assertThrows(ProjectException.class, () -> ProjectStore.load(truncated));
        Path random = dir.resolve("random.nbai");
        Files.write(random, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(ProjectException.class, () -> ProjectStore.load(random));
        Path empty = dir.resolve("empty.nbai");
        Files.write(empty, new byte[0]);
        assertThrows(ProjectException.class, () -> ProjectStore.load(empty));
        assertThrows(ProjectException.class, () -> ProjectStore.load(dir));
    }

    @Test
    void rejectsMissingProjectJsonBadJsonAndWrongFormat() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        Map<String, byte[]> m = ZipTools.read(good);
        m.remove("project.json");
        Path noJson = dir.resolve("nojson.nbai");
        ZipTools.write(noJson, m);
        assertTrue(assertThrows(ProjectException.class, () -> ProjectStore.load(noJson)).getMessage().contains("project.json"));

        Path badJson = dir.resolve("badjson.nbai");
        ZipTools.editJson(good, badJson, j -> j.substring(0, j.length() / 2));
        assertThrows(ProjectException.class, () -> ProjectStore.load(badJson));

        Path wrongFormat = dir.resolve("wrongfmt.nbai");
        ZipTools.editJson(good, wrongFormat, j -> j.replace("notebook-ai-project", "something-else"));
        assertTrue(assertThrows(ProjectException.class, () -> ProjectStore.load(wrongFormat)).getMessage()
                .contains("not a notebook-ai project"));

        Path badUtf8 = dir.resolve("badutf8.nbai");
        Map<String, byte[]> m2 = ZipTools.read(good);
        m2.put("project.json", new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'});
        ZipTools.write(badUtf8, m2);
        assertThrows(ProjectException.class, () -> ProjectStore.load(badUtf8));
    }

    @Test
    void rejectsUnsupportedAndInvalidVersions() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        Path newer = dir.resolve("newer.nbai");
        ZipTools.editJson(good, newer, j -> j.replace("\"formatVersion\":1", "\"formatVersion\":99"));
        String msg = assertThrows(ProjectException.class, () -> ProjectStore.load(newer)).getMessage();
        assertTrue(msg.contains("newer version") && msg.contains("v99"), msg);
        for (String bad : new String[] {"0", "-1", "\"1\"", "1.5", "null"}) {
            Path p = dir.resolve("v" + bad.hashCode() + ".nbai");
            ZipTools.editJson(good, p, j -> j.replace("\"formatVersion\":1", "\"formatVersion\":" + bad));
            assertThrows(ProjectException.class, () -> ProjectStore.load(p), bad);
        }
    }

    @Test
    void rejectsUnsafeDuplicateAndUnreferencedEntries() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        for (String evil : new String[] {"../evil.txt", "/abs/path", "pages/../../x/original", "pages//original",
                "pages/a b/original", "extra.bin", "pages/zzz/other", "pages/" + "x".repeat(70) + "/original"}) {
            Map<String, byte[]> m = ZipTools.read(good);
            m.put(evil, new byte[] {1});
            Path p = dir.resolve("evil" + Math.abs(evil.hashCode()) + ".nbai");
            ZipTools.write(p, m);
            assertThrows(ProjectException.class, () -> ProjectStore.load(p), evil);
        }
        // well-formed name but not referenced by project.json
        Map<String, byte[]> m = ZipTools.read(good);
        m.put("pages/orphan/original", TestImages.png(8, 8, 1));
        Path orphan = dir.resolve("orphan.nbai");
        ZipTools.write(orphan, m);
        assertTrue(assertThrows(ProjectException.class, () -> ProjectStore.load(orphan)).getMessage()
                .contains("unreferenced"));
        // a referenced image missing
        Map<String, byte[]> m3 = ZipTools.read(good);
        String firstImage = m3.keySet().stream().filter(k -> k.startsWith("pages/")).findFirst().get();
        m3.remove(firstImage);
        Path missing = dir.resolve("missingimg.nbai");
        ZipTools.write(missing, m3);
        assertThrows(ProjectException.class, () -> ProjectStore.load(missing));
    }

    @Test
    void rejectsDuplicateZipEntryNames() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectData p = new ProjectData();
        PageData x = new PageData("pagexa", "a.png", TestImages.png(10, 10, 1));
        PageData y = new PageData("pagexb", "b.png", TestImages.png(10, 10, 2));
        p.add(x);
        p.add(y);
        ProjectStore.save(p, good);
        byte[] all = Files.readAllBytes(good);
        byte[] from = "pages/pagexb/original".getBytes(StandardCharsets.UTF_8);
        byte[] to = "pages/pagexa/original".getBytes(StandardCharsets.UTF_8);
        int patched = 0;
        for (int i = 0; i + from.length <= all.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(all, i, i + from.length), from)) {
                System.arraycopy(to, 0, all, i, to.length);
                patched++;
            }
        }
        assertTrue(patched >= 2, "local + central headers patched");
        Path dup = dir.resolve("dup.nbai");
        Files.write(dup, all);
        assertThrows(ProjectException.class, () -> ProjectStore.load(dup));
    }

    @Test
    void rejectsTamperedImageAndMismatchedMetadata() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        Map<String, byte[]> m = ZipTools.read(good);
        String img = m.keySet().stream().filter(k -> k.startsWith("pages/")).findFirst().get();
        byte[] tampered = TestImages.png(40, 30, 9); // a different valid image
        m.put(img, tampered);
        Path p = dir.resolve("tamper.nbai");
        ZipTools.write(p, m);
        String msg = assertThrows(ProjectException.class, () -> ProjectStore.load(p)).getMessage();
        assertTrue(msg.contains("integrity") || msg.contains("wrong size"), msg);
    }

    @Test
    void rejectsInvalidPrepAndRecognitionStateFlags() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        String[][] edits = {
            {"\"quarterTurnsCw\":1", "\"quarterTurnsCw\":7"},
            {"\"fineDegrees\":2.5", "\"fineDegrees\":55.0"},
            {"\"crop\":[2,3,20,25]", "\"crop\":[20,3,2,25]"},
            {"\"recognized\":true", "\"recognized\":\"yes\""},
        };
        for (String[] e : edits) {
            Path p = dir.resolve("bad" + Math.abs(e[1].hashCode()) + ".nbai");
            ZipTools.editJson(good, p, j -> {
                assertTrue(j.contains(e[0]), "fixture contains " + e[0]);
                return j.replaceFirst(java.util.regex.Pattern.quote(e[0]), e[1].replace("\\", "\\\\"));
            });
            assertThrows(ProjectException.class, () -> ProjectStore.load(p), e[1]);
        }
        // unrecognized page carrying OCR data is inconsistent
        Path p = dir.resolve("inconsistent.nbai");
        ZipTools.editJson(good, p, j -> j.replaceFirst("\"recognized\":false,\"rawText\":null",
                "\"recognized\":false,\"rawText\":\"x\""));
        assertThrows(ProjectException.class, () -> ProjectStore.load(p));
    }

    @Test
    void staleRegionOverlaysAreDroppedWithANotice() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        Path changed = dir.resolve("changedprep.nbai");
        // prep changed in the file but regions were saved for the old prep key
        ZipTools.editJson(good, changed, j -> j.replaceFirst("\"quarterTurnsCw\":1", "\"quarterTurnsCw\":2"));
        PageData a = ProjectStore.load(changed).pages().get(0);
        assertTrue(a.recognized(), "text state is kept");
        assertTrue(a.regions().isEmpty(), "stale overlays must not be shown");
        assertNotNull(a.loadNotice());
        assertEquals("raw A line\nsecond", a.rawText());
    }

    // ------------------------------------------------------------ limits

    private static byte[] hugePngHeader(int w, int h) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        o.writeBytes(sig);
        byte[] ihdr = new byte[13];
        java.nio.ByteBuffer.wrap(ihdr).putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(o, "IHDR", ihdr);
        chunk(o, "IDAT", new byte[] {0x78, (byte) 0x9c});
        chunk(o, "IEND", new byte[0]);
        return o.toByteArray();
    }

    private static void chunk(java.io.ByteArrayOutputStream o, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        o.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
        o.writeBytes(t);
        o.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        o.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void rejectsImagesBeyondThePixelLimitWithoutDecodingThem() throws Exception {
        byte[] bomb = hugePngHeader(20000, 20000); // 400 megapixels declared, header only
        // 1) The writer re-validates what it wrote, so such a project cannot even be saved.
        ProjectData p = new ProjectData();
        p.add(new PageData("bigpage", "bomb.png", bomb));
        Path target = dir.resolve("bomb.nbai");
        String msg = assertThrows(ProjectException.class, () -> ProjectStore.save(p, target)).getMessage();
        assertTrue(msg.contains("megapixels"), msg);
        assertFalse(Files.exists(target));
        // 2) The loader enforces the same bound on a hand-built archive (hash/size made consistent).
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        Map<String, byte[]> m = ZipTools.read(good);
        String json = new String(m.get("project.json"), StandardCharsets.UTF_8);
        String firstImg = m.keySet().stream().filter(k -> k.startsWith("pages/")).findFirst().get();
        byte[] old = m.get(firstImg);
        json = json.replace("\"imageSha256\":\"" + PageData.sha256Hex(old) + "\"", "\"imageSha256\":\"" + PageData.sha256Hex(bomb) + "\"")
                .replace("\"imageBytes\":" + old.length, "\"imageBytes\":" + bomb.length);
        m.put(firstImg, bomb);
        m.put("project.json", json.getBytes(StandardCharsets.UTF_8));
        Path bombArchive = dir.resolve("bomb2.nbai");
        ZipTools.write(bombArchive, m);
        assertTrue(assertThrows(ProjectException.class, () -> ProjectStore.load(bombArchive)).getMessage()
                .contains("megapixels"));
    }

    @Test
    void tooManyPagesAndEntriesAreRejected() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        ProjectData big = new ProjectData();
        for (int i = 0; i <= ProjectStore.MAX_PAGES; i++) {
            big.add(new PageData("p" + i, "x", new byte[] {1}));
        }
        assertThrows(ProjectException.class, () -> ProjectStore.save(big, dir.resolve("big.nbai")));
        Map<String, byte[]> m = ZipTools.read(good);
        for (int i = 0; i < ProjectStore.MAX_PAGES + 2; i++) {
            m.put("pages/e" + i + "/original", new byte[] {1});
        }
        Path many = dir.resolve("many.nbai");
        ZipTools.write(many, m);
        assertTrue(assertThrows(ProjectException.class, () -> ProjectStore.load(many)).getMessage().contains("too many"));
    }

    @Test
    void oversizedJsonIsRejected() throws Exception {
        Path good = dir.resolve("g.nbai");
        ProjectStore.save(sample(), good);
        Map<String, byte[]> m = ZipTools.read(good);
        byte[] huge = new byte[(int) ProjectStore.MAX_JSON_BYTES + 10];
        Arrays.fill(huge, (byte) ' ');
        m.put("project.json", huge);
        Path p = dir.resolve("hugejson.nbai");
        ZipTools.write(p, m);
        assertTrue(assertThrows(ProjectException.class, () -> ProjectStore.load(p)).getMessage().contains("limit"));
    }

    @Test
    void imageDecodeFromBytesMatchesDecodeFromFile() throws Exception {
        byte[] png = TestImages.png(33, 21, 5);
        Path f = dir.resolve("x.png");
        Files.write(f, png);
        var a = notebookai.ocr.ImageOps.decode(f);
        var b = notebookai.ocr.ImageOps.decode(png);
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        assertArrayEquals(a.bgr(), b.bgr());
        assertArrayEquals(new int[] {33, 21}, notebookai.ocr.ImageOps.peekDimensions(png));
        assertNull(notebookai.ocr.ImageOps.peekDimensions(new byte[] {1, 2, 3}));
    }
}
