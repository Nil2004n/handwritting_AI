package notebookai.assist;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The per-user, writable directory for application data that belongs to the user and must never live in the installation, the bundled
 * resources, the source checkout or a shared system location. Resolution order: environment variable {@code NOTEBOOK_AI_CONFIG_DIR}, then the system
 * property {@code notebookai.config.dir} (both exist so tests and portable setups can isolate the data), then the platform default:
 * Linux {@code $XDG_CONFIG_HOME/notebook-ai} or {@code ~/.config/notebook-ai}; Windows {@code %APPDATA%\notebook-ai}; macOS
 * {@code ~/Library/Application Support/notebook-ai}. The directory is created lazily by whoever writes into it.
 */
public final class UserDataDir {

    public static final String ENV = "NOTEBOOK_AI_CONFIG_DIR";
    public static final String PROPERTY = "notebookai.config.dir";

    private UserDataDir() {}

    public static Path resolve() {
        return resolve(System.getenv(), System.getProperties(), System.getProperty("os.name", ""), System.getProperty("user.home", "."));
    }

    /** Pure form for tests. Blank values count as unset; relative XDG/APPDATA values are ignored (the specs require absolute paths). */
    static Path resolve(Map<String, String> env, Properties props, String osName, String home) {
        String fromEnv = env.get(ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv);
        }
        String fromProp = props.getProperty(PROPERTY);
        if (fromProp != null && !fromProp.isBlank()) {
            return Paths.get(fromProp);
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = env.get("APPDATA");
            Path base = appData != null && !appData.isBlank() && Paths.get(appData).isAbsolute()
                    ? Paths.get(appData) : Paths.get(home, "AppData", "Roaming");
            return base.resolve("notebook-ai");
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "notebook-ai");
        }
        String xdg = env.get("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() && Paths.get(xdg).isAbsolute() ? Paths.get(xdg) : Paths.get(home, ".config");
        return base.resolve("notebook-ai");
    }
}
