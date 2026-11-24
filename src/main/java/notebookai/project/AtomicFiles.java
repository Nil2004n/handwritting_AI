package notebookai.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/**
 * Shared helper for "write a temporary sibling, then rename over the target". The temporary file
 * is created with the process's normal default permissions (umask), NOT the owner-only mode of
 * {@link Files#createTempFile}; when a file is being replaced, its existing permissions are kept.
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    public static Path createTemp(Path dir, Path target, String prefix) throws IOException {
        Path tmp = dir.resolve(prefix + UUID.randomUUID() + ".tmp");
        Files.createFile(tmp); // fails if it exists (random name), default umask permissions
        if (Files.exists(target)) {
            PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class);
            if (view != null) {
                try {
                    Set<PosixFilePermission> perms = view.readAttributes().permissions();
                    Files.setPosixFilePermissions(tmp, perms);
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Keep the default permissions if the original's cannot be read/applied.
                }
            }
        }
        return tmp;
    }

    /** Plain-language description of an I/O failure while writing into {@code dir}. */
    public static String describe(IOException e, Path dir) {
        String where = dir == null ? "the folder" : "the folder \"" + dir + "\"";
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "permission denied: cannot write in " + where;
        }
        if (e instanceof java.nio.file.NoSuchFileException) {
            return "the destination folder no longer exists";
        }
        if (e instanceof java.nio.file.FileSystemException fse && fse.getReason() != null) {
            return fse.getReason();
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
