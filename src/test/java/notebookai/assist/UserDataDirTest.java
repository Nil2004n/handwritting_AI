package notebookai.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class UserDataDirTest {

    @Test
    void precedenceAndPlatformDefaults() {
        Properties none = new Properties();
        assertEquals(Paths.get("/env/dir"), UserDataDir.resolve(Map.of(UserDataDir.ENV, "/env/dir"), none, "Linux", "/home/u"));
        Properties withProp = new Properties();
        withProp.setProperty(UserDataDir.PROPERTY, "/prop/dir");
        assertEquals(Paths.get("/prop/dir"), UserDataDir.resolve(Map.of(), withProp, "Linux", "/home/u"));
        assertEquals(Paths.get("/env/dir"), UserDataDir.resolve(Map.of(UserDataDir.ENV, "/env/dir"), withProp, "Linux", "/home/u"), "environment wins");
        assertEquals(Paths.get("/home/u/.config/notebook-ai"), UserDataDir.resolve(Map.of(), none, "Linux", "/home/u"));
        assertEquals(Paths.get("/xdg/notebook-ai"), UserDataDir.resolve(Map.of("XDG_CONFIG_HOME", "/xdg"), none, "Linux", "/home/u"));
        assertEquals(Paths.get("/home/u/.config/notebook-ai"), UserDataDir.resolve(Map.of("XDG_CONFIG_HOME", "relative"), none, "Linux", "/home/u"), "relative XDG ignored");
        assertEquals(Paths.get("/home/u/.config/notebook-ai"), UserDataDir.resolve(Map.of(UserDataDir.ENV, "  "), none, "Linux", "/home/u"), "blank counts as unset");
        assertEquals(Paths.get("/home/u/Library/Application Support/notebook-ai"), UserDataDir.resolve(Map.of(), none, "Mac OS X", "/home/u"));
        assertEquals(Paths.get("/home/u/AppData/Roaming/notebook-ai"), UserDataDir.resolve(Map.of(), none, "Windows 11", "/home/u"));
    }
}
