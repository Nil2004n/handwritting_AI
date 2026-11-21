package notebookai.project;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Test helper: rewrite a saved project archive with controlled corruption. */
final class ZipTools {
    private ZipTools() {}

    static Map<String, byte[]> read(Path zip) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : Collections.list(zf.entries())) {
                try (InputStream in = zf.getInputStream(e)) {
                    m.put(e.getName(), in.readAllBytes());
                }
            }
        }
        return m;
    }

    static void write(Path zip, Map<String, byte[]> entries) throws IOException {
        try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(o)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
    }

    /** Copies {@code in} to {@code out} applying a transformation to the JSON text. */
    static void editJson(Path in, Path out, UnaryOperator<String> edit) throws IOException {
        Map<String, byte[]> m = read(in);
        String json = new String(m.get("project.json"), StandardCharsets.UTF_8);
        m.put("project.json", edit.apply(json).getBytes(StandardCharsets.UTF_8));
        write(out, m);
    }
}
