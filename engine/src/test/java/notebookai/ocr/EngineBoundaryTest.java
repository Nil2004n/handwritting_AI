package notebookai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Boundary proof for the portable engine, on COMPILED classes (not source text): the constant pool of every engine class is
 * read and must not reference desktop-only APIs, the UI/application packages, or process exit. Source imports are not enough:
 * fully-qualified names, descriptors and generic signatures are all in the constant pool and are checked here.
 */
class EngineBoundaryTest {

    static final List<String> FORBIDDEN_PREFIXES = List.of(
            "java/awt/", "javax/imageio/", "javax/swing/", "javafx/", "com/drew/", "java/beans/", "java/applet/",
            "java/nio/file/", // keeps the engine usable at Android minSdk 24 without the nio desugaring spec
            "notebookai/ui/", "notebookai/project/", "notebookai/eval/", "notebookai/model/", "notebookai/desktop/");

    /** Every compiled class of the engine module (the whole module IS the engine). */
    static List<Path> engineClassFiles() throws IOException {
        Path dir = Paths.get("").toAbsolutePath().resolve("build/classes/java/main");
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> out = new ArrayList<>();
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.getFileName().toString().endsWith(".class")) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    /** Every UTF-8 constant, class name and (class, method) reference in a class file. */
    static List<String> constants(Path classFile) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(classFile); DataInputStream in = new DataInputStream(raw)) {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int n = in.readUnsignedShort();
            String[] utf = new String[n];
            int[] classIdx = new int[n];
            int[] refClass = new int[n];
            int[] refNat = new int[n];
            int[] natName = new int[n];
            int[] tag = new int[n];
            for (int i = 1; i < n; i++) {
                int t = in.readUnsignedByte();
                tag[i] = t;
                switch (t) {
                    case 1 -> utf[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7 -> classIdx[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11 -> {
                        refClass[i] = in.readUnsignedShort();
                        refNat[i] = in.readUnsignedShort();
                    }
                    case 12 -> {
                        natName[i] = in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    case 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("unknown constant tag " + t + " in " + classFile);
                }
            }
            for (int i = 1; i < n; i++) {
                if (tag[i] == 1) {
                    out.add(utf[i]);
                } else if (tag[i] == 10 || tag[i] == 11) {
                    out.add("CALL " + utf[classIdx[refClass[i]]] + "." + utf[natName[refNat[i]]]);
                }
            }
        }
        return out;
    }

    @Test
    void engineClassesReferenceNoDesktopApisAndNeverExitTheProcess() throws Exception {
        List<Path> files = engineClassFiles();
        assertTrue(files.size() >= 15, "expected the engine classes to be found, got " + files.size());
        List<String> violations = new ArrayList<>();
        for (Path f : files) {
            for (String c : constants(f)) {
                for (String bad : FORBIDDEN_PREFIXES) {
                    if (c.contains(bad)) {
                        violations.add(f.getFileName() + " -> " + c);
                    }
                }
                if (c.equals("CALL java/lang/System.exit") || c.equals("CALL java/lang/Runtime.exit") || c.equals("CALL java/lang/Runtime.halt")) {
                    violations.add(f.getFileName() + " -> " + c);
                }
            }
        }
        assertEquals(List.of(), violations, "the engine must stay free of desktop dependencies");
    }

    @Test
    void theScannerActuallyDetectsDesktopReferences() throws Exception {
        // Proof that the check has teeth: a real JDK AWT class must trip it (read from the JDK, not from this module).
        try (InputStream in = java.awt.Point.class.getResourceAsStream("Point.class")) {
            Path tmp = Files.createTempFile("Point", ".class");
            try {
                Files.write(tmp, in.readAllBytes());
                boolean found = constants(tmp).stream().anyMatch(c -> c.contains("java/awt/"));
                assertTrue(found, "the scanner must see java/awt/ in java.awt.Point");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
