package com.moulberry.flashback.editor.ui.windows;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.compat.ModIntegrations;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImGuiViewport;
import imgui.moulberry90.flag.ImGuiCond;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import imgui.moulberry90.type.ImBoolean;
import net.minecraft.client.resources.language.I18n;

/**
 * What the replay being edited is, in one place.
 *
 * <p>The editor shows a picture of a recording and a timeline of it, and nothing said which recording: which world it
 * came from, how long it is, or - with the Version Emulator installed - which era of the game it was recorded in. That
 * last one is the difference between a Beta 1.7.3 replay showing hearts and no hunger bar and a modern one showing
 * both, and it is not visible anywhere else in the editor.
 */
public final class ReplayInfoWindow {

    private ReplayInfoWindow() {
    }

    public static void render(ImBoolean open, boolean newlyOpened) {
        ImGuiViewport viewport = ImGui.getMainViewport();
        if (newlyOpened) {
            ImGui.setNextWindowPos(viewport.getCenterX(), viewport.getCenterY(), ImGuiCond.Appearing, 0.5f, 0.5f);
            ImGui.setNextWindowSize(430, 240, ImGuiCond.Appearing);
        }

        ImGui.setNextWindowSizeConstraints(260, 80, 5000, 5000);
        if (ImGui.begin(I18n.get("flashback.replay_info") + "###ReplayInfo", open, ImGuiWindowFlags.NoFocusOnAppearing)) {
            ReplayServer replayServer = Flashback.getReplayServer();
            if (replayServer == null) {
                ImGui.textDisabled(I18n.get("flashback.replay_info.no_replay"));
                ImGui.end();
                return;
            }

            FlashbackMeta metadata = replayServer.getMetadata();

            row(I18n.get("flashback.replay_info.name"), metadata.name);
            if (metadata.worldName != null && !metadata.worldName.isBlank()) {
                row(I18n.get("flashback.replay_info.world"), metadata.worldName);
            }
            if (metadata.versionString != null && !metadata.versionString.isBlank()) {
                row(I18n.get("flashback.replay_info.recorded_on"), metadata.versionString);
            }

            int ticks = replayServer.getTotalReplayTicks();
            row(I18n.get("flashback.replay_info.length"), timeOfTicks(ticks));
            row(I18n.get("flashback.replay_info.ticks"), Integer.toString(ticks));
            row(I18n.get("flashback.replay_info.markers"), Integer.toString(metadata.replayMarkers.size()));

            // The era is what the recording asks the Version Emulator for, so it is what playback is showing.
            String era = metadata.integrationState == null ? null : metadata.integrationState.get(ModIntegrations.VERSION_EMULATOR);
            ImGui.separator();
            if (era == null) {
                ImGui.textWrapped(I18n.get("flashback.replay_info.no_era"));
            } else {
                // Its own line: an era id is long ("historical_experience:beta_1_7_3") and would run off the edge
                // beside a label.
                ImGui.textDisabled(I18n.get("flashback.replay_info.era"));
                ImGui.pushTextWrapPos();
                ImGui.textWrapped(era);
                ImGui.popTextWrapPos();
                if (ModIntegrations.readEmulatorVersion() == null) {
                    // Recorded in a historical world, but no emulator here to present it that way.
                    ImGui.pushTextWrapPos();
                    ImGui.textColored(0xFFFFAA00, I18n.get("flashback.replay_info.era_without_mod"));
                    ImGui.popTextWrapPos();
                }
            }
        }
        ImGui.end();
    }

    /** One label and value on a line, so the values line up and can be read at a glance. */
    private static void row(String label, String value) {
        ImGui.textDisabled(label);
        ImGui.sameLine(0, ImGuiHelper.calcTextWidth(label) + 12);
        ImGui.textUnformatted(value == null ? "" : value);
    }

    /** A tick count as a clock, at the recording's own 20 ticks a second. */
    private static String timeOfTicks(int ticks) {
        if (ticks < 0) {
            return "-";
        }
        int seconds = ticks / 20;
        return String.format("%d:%02d.%d", seconds / 60, seconds % 60, (ticks % 20) / 2);
    }

}
