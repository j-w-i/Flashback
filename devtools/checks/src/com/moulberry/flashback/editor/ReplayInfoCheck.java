package com.moulberry.flashback.editor;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The editor's Replay Info window, checked against the code rather than by opening it.
 *
 * <p>A window that is never registered, or whose translations are missing, does not fail to build: it is simply
 * not there, or reads as a raw key like {@code flashback.replay_info.era}. Both are invisible from the inside of
 * the game, so they are checked here.
 */
public class ReplayInfoCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/java");

        Path window = root.resolve("com/moulberry/flashback/editor/ui/windows/ReplayInfoWindow.java");
        check("the window exists", Files.isRegularFile(window));
        String windowSource = Files.isRegularFile(window) ? Files.readString(window) : "";

        Path windowType = root.resolve("com/moulberry/flashback/editor/ui/windows/WindowType.java");
        String windowTypeSource = Files.readString(windowType);
        check("it is registered as a window, so it can be opened and remembered",
            windowTypeSource.contains("ReplayInfoWindow::render"));

        Path menuBar = root.resolve("com/moulberry/flashback/editor/ui/windows/MainMenuBar.java");
        String menuBarSource = Files.readString(menuBar);
        check("the editor menu can open it", menuBarSource.contains("WindowType.REPLAY_INFO.toggle()"));

        Path lang = Path.of("src/main/resources/assets/flashback/lang/en_us.json");
        String en = Files.readString(lang);
        int keys = 0;
        for (String key : new String[]{"flashback.replay_info", "flashback.replay_info.name", "flashback.replay_info.world",
            "flashback.replay_info.recorded_on", "flashback.replay_info.length", "flashback.replay_info.ticks",
            "flashback.replay_info.markers", "flashback.replay_info.era", "flashback.replay_info.no_era",
            "flashback.replay_info.era_without_mod", "flashback.replay_info.no_replay"}) {
            if (en.contains("\"" + key + "\"")) {
                keys++;
            } else {
                System.out.println("  missing translation: " + key);
            }
        }
        check("every string it draws is translated", keys == 11);

        check("the era is on its own line, so a long id cannot run off the window",
            windowSource.contains("textWrapped(era)"));
        check("it reports the era the recording was made in",
            windowSource.contains("ModIntegrations.VERSION_EMULATOR") && windowSource.contains("integrationState"));
        check("it says so when the emulator is not installed to present that era",
            windowSource.contains("readEmulatorVersion") && windowSource.contains("era_without_mod"));
        check("begin/end are written in all three paths (no replay, shown, closed)",
            count(windowSource, "ImGui.begin(") == 1 && count(windowSource, "ImGui.end()") == 2);

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All replay info window checks passed");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
