package notebookai.assist;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalDictionaryStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsUnicodeSortedAndWithAHeader() throws Exception {
        Path f = dir.resolve("sub/dict.txt"); // the directory is created on first save
        PersonalDictionaryStore s = new PersonalDictionaryStore(f);
        assertEquals(List.of(), s.load().entries());
        s.save(List.of("zebra", "café", "O'Neil", "zebra"));
        assertEquals("# notebook-ai personal dictionary v1\nO'Neil\ncafé\nzebra\n", Files.readString(f, StandardCharsets.UTF_8));
        PersonalDictionaryStore.Loaded l = new PersonalDictionaryStore(f).load();
        assertEquals(List.of("O'Neil", "café", "zebra"), l.entries());
        assertNull(l.notice());
        try (var list = Files.list(f.getParent())) {
            assertEquals(1, list.count(), "no temporary file is left behind");
        }
    }

    @Test
    void malformedLinesAreSkippedCountedAndTheOriginalIsBackedUpBeforeTheNextSave() throws Exception {
        Path f = dir.resolve("d.txt");
        Files.writeString(f, "# notebook-ai personal dictionary v1\ngood\n\nnot a word\n12345\n# a comment\nalso-good\n", StandardCharsets.UTF_8);
        PersonalDictionaryStore s = new PersonalDictionaryStore(f);
        PersonalDictionaryStore.Loaded l = s.load();
        assertEquals(List.of("good", "also-good"), l.entries());
        assertEquals(2, l.skipped());
        assertNotNull(l.notice());
        s.save(l.entries());
        assertTrue(Files.readString(dir.resolve("d.txt.bak")).contains("not a word"), "the original survives as a .bak");
        assertFalse(Files.readString(f).contains("not a word"));
        assertEquals(2, new PersonalDictionaryStore(f).load().entries().size());
    }

    @Test
    void undecodableFileYieldsAnEmptyListAndANoticeNotACrash() throws Exception {
        Path f = dir.resolve("bad.txt");
        Files.write(f, new byte[] {'o', 'k', '\n', (byte) 0xC3, (byte) 0x28, '\n'});
        PersonalDictionaryStore.Loaded l = new PersonalDictionaryStore(f).load();
        assertEquals(List.of(), l.entries());
        assertNotNull(l.notice());
    }

    @Test
    void aNewerFormatIsLoadedReadOnlyAndNeverOverwritten() throws Exception {
        Path f = dir.resolve("v2.txt");
        String original = "# notebook-ai personal dictionary v2\nfuture\n";
        Files.writeString(f, original);
        PersonalDictionaryStore s = new PersonalDictionaryStore(f);
        PersonalDictionaryStore.Loaded l = s.load();
        assertTrue(l.readOnly());
        assertEquals(List.of(), l.entries());
        assertThrows(IOException.class, () -> s.save(List.of("mine")));
        assertEquals(original, Files.readString(f), "the newer file is untouched");
    }

    @Test
    void aFailedWriteThrowsAndKeepsThePreviousFile() throws Exception {
        Path f = dir.resolve("keep.txt");
        PersonalDictionaryStore s = new PersonalDictionaryStore(f);
        s.save(List.of("kept"));
        String before = Files.readString(f);
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "i am a file, not a directory");
        PersonalDictionaryStore bad = new PersonalDictionaryStore(blocked.resolve("d.txt")); // parent is a regular file
        assertThrows(IOException.class, () -> bad.save(List.of("x")));
        assertEquals(before, Files.readString(f));
    }
}
