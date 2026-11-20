package notebookocr.ui;

import javafx.application.Application;

/**
 * Plain entry point. A main class that extends {@code Application} cannot
 * start from the classpath ("JavaFX runtime components are missing"), so
 * this launcher delegates to {@link NotebookApp}.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Application.launch(NotebookApp.class, args);
    }
}
