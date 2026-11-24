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
     * module path, the ONNX Runtime natives and the OCR models (checksums enforced) - everything a launch needs,
     * without opening a window. Used by the distribution tests; exit status 0 = ready, 1 = problem.
     */
    private static int selfCheck() {
        boolean ok = true;
        System.out.println("notebook-ai self-check");
        System.out.println("Java: " + Runtime.version() + " on " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch"));
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
        System.out.println(ok ? "SELF-CHECK OK" : "SELF-CHECK FAILED");
        return ok ? 0 : 1;
    }
}
