package com.moulberry.flashback.utils;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous native file and folder pickers for replay workflows.
 *
 * Delegates to the NFD (native file dialog) implementation. A previous Swing JFileChooser version threw
 * HeadlessException on macOS, where Minecraft runs AWT headless, so no dialog could ever be shown there.
 */
public final class AsyncFileDialogs {

    private AsyncFileDialogs() {
    }

    public static boolean hasDialog() {
        return com.moulberry.flashback.exporting.AsyncFileDialogs.hasDialog();
    }

    public static CompletableFuture<String> saveFileDialog(String defaultPath, String defaultName, String filterDescription, String... filters) {
        return com.moulberry.flashback.exporting.AsyncFileDialogs.saveFileDialog(defaultPath, defaultName, filterDescription, filters);
    }

    public static CompletableFuture<String> openFileDialog(String defaultPath, String filterDescription, String... filters) {
        return com.moulberry.flashback.exporting.AsyncFileDialogs.openFileDialog(defaultPath, filterDescription, filters);
    }

    public static CompletableFuture<String> openFolderDialog(String defaultPath) {
        return com.moulberry.flashback.exporting.AsyncFileDialogs.openFolderDialog(defaultPath);
    }

    public static String filter(CharSequence in) {
        return com.moulberry.flashback.exporting.AsyncFileDialogs.filter(in);
    }

    public static String filterLT20(CharSequence in) {
        return com.moulberry.flashback.exporting.AsyncFileDialogs.filterLT20(in);
    }
}
