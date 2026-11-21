package notebookai.ui;

import javafx.application.Application;

/**
 * Plain entry point. A main class that extends {@code Application} cannot
 * start from the classpath ("JavaFX runtime components are missing"), so
 * this launcher delegates to {@link NotebookAiApp}.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Module fx = javafx.application.Application.class.getModule();
        System.out.println("JavaFX runtime module: "
                + (fx.isNamed() ? fx.getName() + " (named module)" : "unnamed (classpath)"));
        Application.launch(NotebookAiApp.class, args);
    }
}
