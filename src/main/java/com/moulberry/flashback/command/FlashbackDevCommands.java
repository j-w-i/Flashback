package com.moulberry.flashback.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.screen.SelectReplayScreen;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Test-automation commands, only registered when running in a development environment (never in release builds).
 * They let an external driver (e.g. the MCPFabric bridge via chat.send) exercise GUI-only flows without OS input.
 *
 * Every command writes a JSON status document to {@code <gameDir>/flashback_dev_status.json} and logs it.
 */
public final class FlashbackDevCommands {

    private FlashbackDevCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }

        var dev = ClientCommandManager.literal("dev");
        dev.then(ClientCommandManager.literal("status").executes(ctx -> reply(ctx.getSource(), status())));
        dev.then(ClientCommandManager.literal("open")
            .then(ClientCommandManager.argument("file", StringArgumentType.greedyString()).executes(ctx -> {
                String file = StringArgumentType.getString(ctx, "file");
                Path path = Path.of(file);
                if (!path.isAbsolute()) {
                    path = Flashback.getReplayFolder().resolve(file);
                }
                if (!Files.exists(path)) {
                    return reply(ctx.getSource(), error("no such replay: " + path));
                }
                Path finalPath = path;
                Minecraft.getInstance().execute(() -> Flashback.openReplayWorld(finalPath));
                return reply(ctx.getSource(), ok("opening " + path));
            })));
        dev.then(ClientCommandManager.literal("exit").executes(ctx -> {
            Minecraft.getInstance().execute(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.level != null) {
                    minecraft.level.disconnect();
                }
                minecraft.disconnect();
                minecraft.setScreen(new SelectReplayScreen(new TitleScreen()));
            });
            return reply(ctx.getSource(), ok("exiting replay"));
        }));
        dev.then(ClientCommandManager.literal("seek")
            .then(ClientCommandManager.argument("tick", IntegerArgumentType.integer(0)).executes(ctx -> {
                ReplayServer server = Flashback.getReplayServer();
                if (server == null) return reply(ctx.getSource(), error("not in a replay"));
                server.goToReplayTick(IntegerArgumentType.getInteger(ctx, "tick"));
                return reply(ctx.getSource(), ok("seeking"));
            })));
        dev.then(ClientCommandManager.literal("spectate").executes(ctx -> {
            ReplayServer server = Flashback.getReplayServer();
            if (server == null) return reply(ctx.getSource(), error("not in a replay"));
            net.minecraft.world.entity.player.Player recorded = server.recordedPlayer();
            if (recorded == null) return reply(ctx.getSource(), error("recorded player not present at this tick"));
            new com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler(Minecraft.getInstance()).applySpectate(recorded.getUUID());
            return reply(ctx.getSource(), ok("spectating " + recorded.getName().getString()));
        }));
        dev.then(ClientCommandManager.literal("unspectate").executes(ctx -> {
            ReplayServer server = Flashback.getReplayServer();
            if (server == null) return reply(ctx.getSource(), error("not in a replay"));
            new com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler(Minecraft.getInstance()).applySpectate(null);
            return reply(ctx.getSource(), ok("stopped spectating"));
        }));
        dev.then(ClientCommandManager.literal("play").executes(ctx -> setPaused(ctx.getSource(), false)));
        dev.then(ClientCommandManager.literal("pause").executes(ctx -> setPaused(ctx.getSource(), true)));
        dev.then(ClientCommandManager.literal("export")
            .then(ClientCommandManager.argument("noGui", BoolArgumentType.bool())
                .then(ClientCommandManager.argument("output", StringArgumentType.greedyString()).executes(ctx -> {
                    try {
                        return startExport(ctx.getSource(), BoolArgumentType.getBool(ctx, "noGui"),
                            StringArgumentType.getString(ctx, "output"));
                    } catch (Throwable t) {
                        Flashback.LOGGER.error("dev export failed", t);
                        return reply(ctx.getSource(), error(String.valueOf(t)));
                    }
                }))));

        var flashback = ClientCommandManager.literal("flashback").then(dev);
        dispatcher.register(flashback);
    }

    private static int setPaused(FabricClientCommandSource source, boolean paused) {
        ReplayServer server = Flashback.getReplayServer();
        if (server == null) return reply(source, error("not in a replay"));
        server.replayPaused = paused;
        return reply(source, ok(paused ? "paused" : "playing"));
    }

    private static int startExport(FabricClientCommandSource source, boolean noGui, String outputStr) {
        EditorState editorState = EditorStateManager.getCurrent();
        ReplayServer replayServer = Flashback.getReplayServer();
        LocalPlayer player = Minecraft.getInstance().player;
        if (editorState == null || replayServer == null || player == null) {
            return reply(source, error("not in a replay editor"));
        }
        if (Flashback.isExporting()) {
            return reply(source, error("an export is already running"));
        }

        FlashbackConfigV1 config = Flashback.getConfig();
        var export = config.internalExport;

        var range = editorState.getFirstAndLastTicksInTracks();
        int total = replayServer.getTotalReplayTicks();
        int start = Math.max(0, range.start());
        int end = range.end() < 0 || range.end() == start ? total : range.end();
        start = Math.min(start, total);
        end = Math.min(Math.max(start, end), total);

        if (export.container == null) {
            export.container = com.moulberry.flashback.combo_options.VideoContainer.MP4;
        }
        VideoCodec codec = export.videoCodec;
        VideoCodec[] codecs = export.container.getSupportedVideoCodecs(false);
        if (codec == null || !Arrays.asList(codecs).contains(codec)) {
            codec = codecs[0];
        }
        String[] encoders = codec.getEncoders();
        String encoder = encoders == null || encoders.length == 0 ? null : encoders[0];
        if (encoders != null && export.selectedVideoEncoder != null && Arrays.asList(encoders).contains(export.selectedVideoEncoder)) {
            encoder = export.selectedVideoEncoder;
        }
        AudioCodec audio = null;

        Path output = Path.of(outputStr);
        if (!output.isAbsolute()) {
            output = FabricLoader.getInstance().getGameDir().resolve(outputStr);
        }
        if (output.getParent() != null) {
            try {
                Files.createDirectories(output.getParent());
            } catch (Exception e) {
                return reply(source, error("cannot create output folder: " + e));
            }
        }

        ExportSettings settings = new ExportSettings(null, editorState.copy(),
            player.position(), player.getYRot(), player.getXRot(),
            export.resolution[0] - export.resolution[0] % 2, export.resolution[1] - export.resolution[1] % 2, start, end,
            export.projection, export.orthographicZoom[0],
            Math.max(1, export.framerate[0]), export.resetRng, export.depthMap,
            export.container, codec, encoder, 0,
            codec.hasProResProfile() ? export.proResProfile : null,
            false, export.ssaa, noGui,
            export.stereoAudio, audio,
            output, null);

        Flashback.EXPORT_JOB = new ExportJob(settings);
        JsonObject o = ok("export started");
        o.addProperty("output", output.toString());
        o.addProperty("startTick", start);
        o.addProperty("endTick", end);
        return reply(source, o);
    }

    private static JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        Minecraft mc = Minecraft.getInstance();
        o.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getSimpleName());
        o.addProperty("inReplay", Flashback.isInReplay());
        ReplayServer server = Flashback.getReplayServer();
        if (server != null) {
            o.addProperty("replayTick", server.getReplayTick());
            o.addProperty("totalTicks", server.getTotalReplayTicks());
            o.addProperty("paused", server.replayPaused);
            o.addProperty("fastForwarding", server.fastForwarding);
        }
        EditorState editorState = EditorStateManager.getCurrent();
        o.addProperty("hasEditorState", editorState != null);
        ExportJob job = Flashback.EXPORT_JOB;
        o.addProperty("exporting", Flashback.isExporting());
        if (job != null) {
            o.addProperty("exportProgress", job.progressCount);
            o.addProperty("exportOutOf", job.progressOutOf);
        }
        if (mc.player != null) {
            o.addProperty("x", mc.player.getX());
            o.addProperty("y", mc.player.getY());
            o.addProperty("z", mc.player.getZ());
            o.addProperty("yaw", mc.player.getYRot());
            o.addProperty("pitch", mc.player.getXRot());
        }
        o.addProperty("recording", Flashback.RECORDER != null);
        if (mc.getCameraEntity() != null) {
            o.addProperty("cameraEntity", mc.getCameraEntity().getName().getString());
            o.addProperty("cameraIsViewer", mc.getCameraEntity() == mc.player);
        }
        o.addProperty("showingThroughPlayer", com.moulberry.flashback.gui.GuiDisplay.showingThroughAPlayer());
        o.addProperty("historicalVersion", com.moulberry.flashback.compat.ModIntegrations.readEmulatorVersion());
        return o;
    }

    private static JsonObject ok(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("message", message);
        return o;
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    private static int reply(FabricClientCommandSource source, JsonObject json) {
        String text = FlashbackGson.COMPRESSED.toJson(json);
        Flashback.LOGGER.info("[dev] {}", text);
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve("flashback_dev_status.json"), text);
        } catch (Exception ignored) {
        }
        source.sendFeedback(Component.literal(text));
        return json.get("ok").getAsBoolean() ? 1 : 0;
    }
}
