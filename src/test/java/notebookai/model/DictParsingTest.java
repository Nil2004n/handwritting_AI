package notebookai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Dictionary parsing: yml quoting rules plus the real bundled dictionary. */
class DictParsingTest {

    @Test
    void unquotesYamlScalars() throws Exception {
        Path yml = Files.createTempFile("rec", ".yml");
        Files.write(
                yml,
                List.of(
                        "PostProcess:",
                        "  name: CTCLabelDecode",
                        "  character_dict:",
                        "  - '!'",
                        "  - $",
                        "  - ''''",
                        "  - \\",
                        "  - 'ab'"));
        List<String> dict = ModelConfig.readRecDictionary(yml);
        assertEquals(List.of("!", "$", "'", "\\", "ab"), dict);
    }

    static boolean recYmlPresent() {
        return Files.isRegularFile(Paths.get("models/rec.yml"));
    }

    @Test
    @EnabledIf("recYmlPresent")
    void bundledDictionaryHasExpectedSizeAndHead() throws Exception {
        List<String> dict = ModelConfig.readRecDictionary(Paths.get("models/rec.yml"));
        // 18,708 entries: classes 1..18708 address this list.
        assertEquals(18708, dict.size());
        assertEquals("!", dict.get(0));
        assertEquals("\"", dict.get(1));
        assertTrue(dict.contains("A"));
        assertTrue(dict.contains("0"));
    }

    @Test
    @EnabledIf("recYmlPresent")
    void decodeTableAddsBlankAndSpace() throws Exception {
        // Mirrors CTCLabelDecode with the use_space_char=True inference default:
        // class 0 is blank, 1..18708 are the dictionary, 18709 is space.
        List<String> table = ModelConfig.readDecodeTable(Paths.get("models/rec.yml"));
        assertEquals(18710, table.size());
        assertEquals("blank", table.get(0));
        assertEquals("!", table.get(1));
        assertEquals(" ", table.get(18709));
    }
}
