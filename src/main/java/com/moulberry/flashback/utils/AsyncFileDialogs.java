package com.moulberry.flashback.utils;

import com.moulberry.flashback.Flashback;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.SwingUtilities;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Asynchronous native file and folder pickers for replay workflows. */
public final class AsyncFileDialogs {

    private static volatile CompletableFuture<String> currentSaveOrOpenFileDialog;

    private AsyncFileDialogs() {
    }

    public static boolean hasDialog() {
        return currentSaveOrOpenFileDialog != null;
    }

    private static CompletableFuture<String> showDialog(Function<JFileChooser, String> dialog) {
        synchronized (AsyncFileDialogs.class) {
            if (currentSaveOrOpenFileDialog != null) {
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<String> future = new CompletableFuture<>();
            currentSaveOrOpenFileDialog = future;
            SwingUtilities.invokeLater(() -> {
                try {
                    JFileChooser chooser = new JFileChooser();
                    String selected = dialog.apply(chooser);
                    future.complete(selected);
                } catch (Throwable t) {
                    Flashback.LOGGER.error("Could not open file dialog", t);
                    future.complete(null);
                } finally {
                    synchronized (AsyncFileDialogs.class) {
                        if (currentSaveOrOpenFileDialog == future) {
                            currentSaveOrOpenFileDialog = null;
                        }
                    }
                }
            });
            return future;
        }
    }

    private static void setDirectory(JFileChooser chooser, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        File file = new File(path);
        if (file.isDirectory()) {
            chooser.setCurrentDirectory(file);
        } else if (file.getParentFile() != null) {
            chooser.setCurrentDirectory(file.getParentFile());
        }
    }

    private static void addFilters(JFileChooser chooser, String description, String... filters) {
        if (filters.length == 0) {
            return;
        }
        chooser.setAcceptAllFileFilterUsed(true);
        String[] extensions = new String[filters.length];
        for (int i = 0; i < filters.length; i++) {
            String extension = filters[i].trim();
            while (extension.startsWith("*.")) {
                extension = extension.substring(2);
            }
            while (extension.startsWith(".")) {
                extension = extension.substring(1);
            }
            extensions[i] = extension;
        }
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(filter(description), extensions));
    }

    public static CompletableFuture<String> saveFileDialog(String defaultPath, String defaultName, String filterDescription, String... filters) {
        return showDialog(chooser -> {
            setDirectory(chooser, defaultPath);
            addFilters(chooser, filterDescription, filters);
            chooser.setSelectedFile(new File(defaultName == null ? "" : filter(defaultName)));
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
                return null;
            }
            File selected = chooser.getSelectedFile();
            String extension = filters.length == 1 ? filters[0] : null;
            String result = selected.getAbsolutePath();
            if (extension != null && !extension.isBlank() && !selected.getName().contains(".")) {
                result += "." + extension.replaceFirst("^\\*?\\.?", "");
            }
            return result;
        });
    }

    public static CompletableFuture<String> openFileDialog(String defaultPath, String filterDescription, String... filters) {
        return showDialog(chooser -> {
            setDirectory(chooser, defaultPath);
            addFilters(chooser, filterDescription, filters);
            return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile().getAbsolutePath() : null;
        });
    }

    public static CompletableFuture<String> openFolderDialog(String defaultPath) {
        return showDialog(chooser -> {
            setDirectory(chooser, defaultPath);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile().getAbsolutePath() : null;
        });
    }

    public static String filter(CharSequence in) {
        return filterLT20(in.toString()
                .replace("'", "")
                .replace("\"", "")
                .replace("$", "")
                .replace("`", ""));
    }

    public static String filterLT20(CharSequence in) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c >= 32 || c == '\n') {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
