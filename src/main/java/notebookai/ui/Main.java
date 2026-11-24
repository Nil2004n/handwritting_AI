package notebookai.ui;

import java.nio.file.Paths;
import javafx.application.Application;
import notebookai.ocr.OcrException;
import notebookai.ocr.OcrSetup;

/**
 * Plain entry point. A main class that extends {@code Application} cannot
 * start from the classpath ("JavaFX runtime components are missing"), so
 * this launcher delegates to {@link NotebookAiApp}.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--self-check")) {
            System.exit(selfCheck());
        }
        Module fx = javafx.application.Application.class.getModule();
        System.out.println("JavaFX runtime module: "
                + (fx.isNamed() ? fx.getName() + " (named module)" : "unnamed (classpath)"));
        Application.launch(NotebookAiApp.class, args);
    }

    /**
     * Headless installation check ({@code notebook-ai --self-check}): the Java runtime, the JavaFX modules on the
     * module path, the ONNX Runtime natives and the OCR models (checksums enforced) - everything a launch needs
     * (plus the bundled spelling dictionaries), without opening a window. Used by the distribution tests; exit status 0 = ready, 1 = problem.
     */
    private static int selfCheck() {
        boolean ok = true;
        System.out.println("notebook-ai self-check");
        System.out.println("Java: " + Runtime.version() + " on " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
        System.out.println("Java home: " + System.getProperty("java.home"));
        for (String m : new String[] {"javafx.base", "javafx.graphics", "javafx.controls"}) {
            boolean present = ModuleLayer.boot().findModule(m).isPresent();
            System.out.println("JavaFX module " + m + ": " + (present ? "present" : "MISSING"));
            ok &= present;
        }
        try {
            System.out.print(OcrSetup.verify(Paths.get("").toAbsolutePath()));
        } catch (OcrException e) {
            System.err.println("OCR setup FAILED: " + e.getMessage());
            ok = false;
        }
        // Spelling assistance resources (bundled word lists inside the spelling jar): found regardless of the working directory.
        try (notebookai.spelling.WordListBackend spelling = notebookai.spelling.WordListBackend.bundled()) {
            for (notebookai.spelling.SpellLanguage l : notebookai.spelling.SpellLanguage.values()) {
                int issues = spelling.check("The quick brown fox jumps over the lazy dog.", l, notebookai.spelling.WordFilter.NONE).issues().size();
                if (issues != 0) {
                    throw new notebookai.spelling.SpellException("a sentence of common words was flagged (" + issues + " issue(s))");
                }
                System.out.println("Spelling " + l.tag() + ": " + spelling.identity(l));
            }
        } catch (notebookai.spelling.SpellException e) {
            System.err.println("Spelling dictionaries FAILED: " + e.getMessage());
            ok = false;
        }
        System.out.println(ok ? "SELF-CHECK OK" : "SELF-CHECK FAILED");
        return ok ? 0 : 1;
    }
}
