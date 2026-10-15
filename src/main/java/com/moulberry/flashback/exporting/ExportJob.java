package com.moulberry.flashback.exporting;

import net.minecraft.Util;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.moulberry.flashback.*;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.ExportDoneWindow;
import com.moulberry.flashback.exporting.taskbar.TaskbarManager;
import com.moulberry.flashback.ext.WindowExt;
import com.moulberry.flashback.keyframe.change.KeyframeChangeTickrate;
import com.moulberry.flashback.keyframe.handler.KeyframeHandler;
import com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler;
import com.moulberry.flashback.keyframe.handler.TickrateKeyframeCapture;
import com.moulberry.flashback.sound.FlashbackAudioManager;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.utils.FramebufferUtils;
import com.moulberry.flashback.utils.InputHelper;
import com.moulberry.flashback.utils.WindowSizeTracker;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.flashback.visuals.FlashbackDrawBuffer;
import imgui.moulberry90.flag.ImGuiKey;
import imgui.moulberry90.flag.ImGuiMouseButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openal.SOFTLoopback;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class ExportJob {

    private static final Vector4f EMPTY_CLEAR_COLOUR = new Vector4f(0);

    private final ExportSettings settings;

    private boolean running = false;
    private boolean shouldChangeFramebufferSize = false;
    private long lastRenderMillis;
    private long escapeCancelStartMillis = -1;
    private long renderStartTime;

    private final Random particleRandom;
    private long currentClientTickSeed = 0L;
    private long currentInitialEntityTickSeed = 0L;

    private boolean showingDebug = false;
    private boolean pressedDebugKey = false;
    private long serverTickTimeNanos = 0;
    private long clientTickTimeNanos = 0;
    private long renderTimeNanos = 0;
    private long encodeTimeNanos = 0;
    private boolean patreonLinkClicked = false;

    private int extraDummyFrames = 0;

    public int progressCount = 0;
    public int progressOutOf = 0;

    private double currentTickDouble = 0.0;

    private double audioSamples = 0.0;

    private final AtomicBoolean finishedServerTick = new AtomicBoolean(false);

    private NativeImage firstFrame = null;
    private int writtenFrames = 0;

    public Runnable saveDepthAfterLevel = null;

    public ExportJob(ExportSettings settings) {
        this.settings = settings;
        this.particleRandom = this.settings.resetRng() ? new Random(2000) : null;
    }

    public boolean isRunning() {
        return this.running;
    }

    public void onFinishedServerTick() {
        this.finishedServerTick.set(true);
    }

    public boolean shouldChangeFramebufferSize() {
        return this.running && this.shouldChangeFramebufferSize;
    }

    public int getWidth() {
        int resolutionX = this.settings.resolutionX();

        ExportProjection projection = this.settings.projection();
        if (projection == ExportProjection.CUBE_MAP) {
            resolutionX = (resolutionX + 3)/4;
        } else if (projection == ExportProjection.EQUIRECTANGULAR) {
            // Render the six square cubemap faces used to assemble the equirectangular output.
            resolutionX = Math.max(1, Math.min(this.settings.resolutionX() / 4, this.settings.resolutionY() / 2));
        }

        if (this.settings.ssaa()) {
            resolutionX *= 2;
        }

        return resolutionX;
    }

    public int getHeight() {
        int resolutionY = this.settings.resolutionY();

        ExportProjection projection = this.settings.projection();
        if (projection == ExportProjection.CUBE_MAP) {
            resolutionY = (resolutionY + 2)/3;
        } else if (projection == ExportProjection.EQUIRECTANGULAR) {
            resolutionY = Math.max(1, Math.min(this.settings.resolutionX() / 4, this.settings.resolutionY() / 2));
        }

        if (this.settings.ssaa()) {
            resolutionY *= 2;
        }

        return resolutionY;
    }

    public double getCurrentTickDouble() {
        return this.currentTickDouble;
    }

    public Random getParticleRandom() {
        return this.particleRandom;
    }

    public long getSeedForCurrentClientTick() {
        return this.currentClientTickSeed;
    }

    public long getInitialEntitySeedForCurrentClientTick() {
        this.currentInitialEntityTickSeed += 1;
        return this.currentInitialEntityTickSeed;
    }

    public ExportSettings getSettings() {
        return this.settings;
    }

    private record TempFileInfo(String name, Path file, Path folder) {}

    public void run() {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (this.running || replayServer == null) {
            throw new IllegalStateException("run() called twice");
        }
        this.running = true;
        Minecraft.getInstance().mouseHandler.releaseMouse();
        Minecraft.getInstance().getSoundManager().stop();
        Minecraft.getInstance().getSoundManager().tick(true);

        TaskbarManager.launchTaskbarManager();

        UUID uuid = UUID.randomUUID();

        TempFileInfo tempFileInfo = null;
        if (!this.settings.container().isImageSequence() || this.settings.pngSequenceFormat() == null) {
            String tempFileName = "replay_export_temp/" + uuid + "." + this.settings.container().extension();
            Path exportTempFile = Path.of(tempFileName);
            Path exportTempFolder = exportTempFile.getParent();
            tempFileInfo = new TempFileInfo(tempFileName, exportTempFile, exportTempFolder);
        }
        boolean keepTempFile = false;

        int oldGuiScale = Minecraft.getInstance().options.guiScale().get();

        this.extraDummyFrames = Flashback.getConfig().exporting.exportRenderDummyFrames;

        try {
            if (tempFileInfo != null) {
                Files.createDirectories(tempFileInfo.folder);
            }

            int resolutionX = this.settings.resolutionX();
            int resolutionY = this.settings.resolutionY();

            if (this.settings.projection() == ExportProjection.CUBE_MAP || this.settings.projection() == ExportProjection.EQUIRECTANGULAR) {
                // In 1.21.1 the camera has no panoramic-mode toggle. The projection mixin supplies
                // a square 90-degree frustum, and these are the per-face framebuffer dimensions.
                resolutionX = this.getWidth();
                resolutionY = this.getHeight();
            }

            long exportStartNanos = System.nanoTime();
            try (VideoWriter encoder = createVideoWriter(this.settings, tempFileInfo);
                 SaveableFramebufferQueue downloader = new SaveableFramebufferQueue(resolutionX, resolutionY)) {
                doExport(encoder, downloader);
            }
            logTimingSummary(System.nanoTime() - exportStartNanos);

            Path outputLocation = this.settings.output();
            boolean errorMovingToOutput = false;
            if (tempFileInfo != null) {
                try {
                    Files.move(tempFileInfo.file, this.settings.output(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Flashback.LOGGER.error("Error while moving temp file to export output", e);
                    outputLocation = tempFileInfo.file;
                    errorMovingToOutput = true;
                    keepTempFile = true;
                }
            }

            try {
                long size = 0;
                double duration = this.writtenFrames / this.settings.framerate();

                if (this.settings.container() != VideoContainer.PNG_SEQUENCE && Files.exists(outputLocation) && Files.isRegularFile(outputLocation)) {
                    size = Files.size(outputLocation);
                }

                ExportDoneWindow.addFinishedExportEntry(new ExportDoneWindow.FinishedExportEntry(this.settings, outputLocation, errorMovingToOutput, this.firstFrame, duration, size));
                this.firstFrame = null;
            } catch (IOException ignored) {}
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            this.running = false;
            this.shouldChangeFramebufferSize = false;

            // Reset display size
            Minecraft.getInstance().options.guiScale().set(oldGuiScale);
            ((WindowExt)(Object)Minecraft.getInstance().getWindow()).flashback$updateScaledFramebuffer(true);

            // Refreeze server & client
            replayServer.replayPaused = true;
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                level.tickRateManager().setFrozen(true);
            }

            Minecraft.getInstance().getSoundManager().stop();
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, 1.0f));
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0f));

            if (this.firstFrame != null) {
                this.firstFrame.close();
                this.firstFrame = null;
            }

            if (tempFileInfo != null) {
                if (!keepTempFile) {
                    try {
                        Files.deleteIfExists(tempFileInfo.file);
                    } catch (IOException ignored) {}
                }

                try {
                    Files.deleteIfExists(tempFileInfo.folder);
                } catch (IOException ignored) {}
            }
        }
    }

    private static VideoWriter createVideoWriter(ExportSettings settings, TempFileInfo tempFileInfo) {
        String filename;
        if (tempFileInfo != null) {
            filename = tempFileInfo.name;
        } else if (settings.pngSequenceFormat() != null) {
            String prefix;
            Path folder;

            if (Files.isDirectory(settings.output())) {
                prefix = "";
                folder = settings.output();
            } else {
                prefix = settings.output().getFileName().toString() + "-";
                folder = settings.output().getParent();
            }

            String formatter = prefix + settings.pngSequenceFormat();

            // Add extension if missing
            String extension = "." + settings.container().extension();
            if (!formatter.endsWith(extension)) {
                formatter = formatter + extension;
            }

            filename = folder.resolve(formatter).toString();
        } else {
            throw new IllegalStateException("settings.pngSequenceFormat() must not be null");
        }
        return new AsyncFFmpegVideoWriter(settings, filename);
    }

    private void doExport(VideoWriter videoWriter, SaveableFramebufferQueue downloader) {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        Random random = new Random(1000);
        Random mathRandom = this.settings.resetRng() ? Utils.getInternalMathRandom() : null;

        this.setup(replayServer);
        this.updateRandoms(random, mathRandom);

        shouldChangeFramebufferSize = true;
        // Double gui scale if using SSAA which doubles resolution
        if (this.settings.ssaa()) {
            mc.options.guiScale().set(mc.options.guiScale().get() * 2);
        }
        ((WindowExt)(Object)Minecraft.getInstance().getWindow()).flashback$updateScaledFramebuffer(true);

        List<TickInfo> ticks = calculateTicks(this.settings.editorState(), this.settings.startTick(), this.settings.endTick(), this.settings.framerate());
        ticks = applyCuts(this.settings.editorState(), this.settings.startTick(), ticks);

        int clientTickCount = 0;

        this.renderStartTime = System.currentTimeMillis();

        double lastClientTickDouble = 0;

        for (int tickIndex = 0; tickIndex < ticks.size(); tickIndex++) {
            TickInfo tickInfo = ticks.get(tickIndex);
            boolean frozen = tickInfo.frozen;
            this.currentTickDouble = tickInfo.serverTick;
            int targetServerTick = this.settings.startTick() + (int) currentTickDouble;

            float deltaTicksFloat = (float)(tickInfo.clientTick - lastClientTickDouble);
            lastClientTickDouble = tickInfo.clientTick;
            double partialClientTick = tickInfo.clientTick - (int) tickInfo.clientTick;

            // A frame whose predecessor is more than a tick away begins right after a removed stretch,
            // and one whose successor is more than a tick away ends right before one. Those are the two
            // frames where the exported picture and sound are a join rather than continuous footage.
            boolean afterCut = tickIndex > 0 && tickInfo.serverTick - ticks.get(tickIndex - 1).serverTick > 1.0;
            boolean beforeCut = tickIndex + 1 < ticks.size() && ticks.get(tickIndex + 1).serverTick - tickInfo.serverTick > 1.0;

            // Wait until server is on correct replay tick
            long start = System.nanoTime();
            this.setServerTickAndWait(replayServer, targetServerTick, false);
            serverTickTimeNanos += System.nanoTime() - start;

            // Tick client
            while (clientTickCount < (int) tickInfo.clientTick) {
                start = System.nanoTime();
                this.updateRandoms(random, mathRandom);
                this.runClientTick(frozen);
                clientTickTimeNanos += System.nanoTime() - start;

                clientTickCount += 1;
            }

            this.updateClientFreeze(frozen);

            DeltaTracker.Timer timer = mc.timer;
            timer.updateFrozenState(frozen);
            timer.updatePauseState(false);
            timer.deltaTicks = deltaTicksFloat;
            timer.realtimeDeltaTicks = deltaTicksFloat;
            timer.deltaTickResidual = (float) partialClientTick;
            timer.pausedDeltaTickResidual = (float) partialClientTick;

            AccurateEntityPositionHandler.apply(mc.level, timer);

            // Apply keyframes
            if (frozen) {
                FlashbackAudioManager.pauseAll();
            } else {
                FlashbackAudioManager.startHandling();
            }
            try {
                KeyframeHandler keyframeHandler = new MinecraftKeyframeHandler(mc);
                this.settings.editorState().applyKeyframes(keyframeHandler, (float)(this.settings.startTick() + currentTickDouble));
            } finally {
                if (!frozen) {
                    FlashbackAudioManager.finishHandling();
                }
            }

            long pauseScreenStart = System.currentTimeMillis();
            int additionalDummyFrames = this.extraDummyFrames;
            if (tickIndex == 0) {
                additionalDummyFrames = Math.max(this.extraDummyFrames, 60);
            }
            while (mc.getOverlay() != null || mc.screen != null || additionalDummyFrames > 0) {
                boolean overlayOrScreen = mc.getOverlay() != null || mc.screen != null;
                if (overlayOrScreen) {
                    this.runClientTick(frozen);
                }
                if (additionalDummyFrames > 0) {
                    additionalDummyFrames -= 1;
                }

                RenderTarget renderTarget = mc.mainRenderTarget;
                render(renderTarget, mc.timer);

                if (overlayOrScreen) {
                    finishFrame(renderTarget, new ArrayList<>(List.of("Waiting for overlay to disappear")), true, false);
                } else if (tickIndex == 0) {
                    finishFrame(renderTarget, new ArrayList<>(List.of("Warming up... " + Math.max(0, 60 - additionalDummyFrames) + "/60")), true, false);
                } else {
                    finishHiddenFrame();
                }

                if (overlayOrScreen) {
                    LockSupport.parkNanos("waiting for pause overlay to disappear", 50_000_000L);

                    // Force remove screens/overlays after 5s/15s respectively
                    long currentTime = System.currentTimeMillis();
                    if (pauseScreenStart > currentTime) {
                        pauseScreenStart = currentTime;
                    }
                    if (currentTime - pauseScreenStart > 5000) {
                        mc.setScreen(null);
                    }
                    if (currentTime - pauseScreenStart > 15000) {
                        mc.setOverlay(null);
                    }
                }

                this.updateClientFreeze(frozen);

                timer.updateFrozenState(frozen);
                timer.updatePauseState(false);
                timer.deltaTicks = deltaTicksFloat;
                timer.realtimeDeltaTicks = deltaTicksFloat;
                timer.deltaTickResidual = (float) partialClientTick;
                timer.pausedDeltaTickResidual = (float) partialClientTick;
            }

            RenderTarget renderTarget = Minecraft.getInstance().mainRenderTarget;

            ExportProjection projection = this.settings.projection();

            if (projection == ExportProjection.CUBE_MAP || projection == ExportProjection.EQUIRECTANGULAR) {
                var player = mc.player;

                for (int i = 0; i < 6; i++) {
                    if (i < 4) {
                        player.setYRot(90.0f * i - 90.0f);
                        player.setXRot(0.0f);
                    } else if (i == 4) {
                        player.setYRot(0.0f);
                        player.setXRot(-90.0f);
                    } else if (i == 5) {
                        player.setYRot(0.0f);
                        player.setXRot(90.0f);
                    }
                    player.xRotO = player.getXRot();
                    player.yRotO = player.getYRot();

                    SaveableFramebuffer saveable = downloader.take();
                    if (this.settings.depthMap()) {
                        this.saveDepthAfterLevel = () -> {
                            downloader.startDepthDownload(renderTarget, saveable);
                        };
                    }

                    // Perform rendering
                    PerfectFrames.waitUntilFrameReady();
                    start = System.nanoTime();
                    renderPanoramicFace(renderTarget, timer);
                    finishHiddenFrame();
                    renderTimeNanos += System.nanoTime() - start;

                    // Capture audio if necessary
                    FloatBuffer audioBuffer = null;
                    if (this.settings.recordAudio() && i == 0) {
                        long device = Minecraft.getInstance().getSoundManager().soundEngine.library.currentDevice;

                        audioSamples += 48000 / this.settings.framerate();
                        int renderSamples = (int) audioSamples;
                        audioSamples -= renderSamples;

                        int channels = this.settings.stereoAudio() ? 2 : 1;

                        audioBuffer = ByteBuffer.allocateDirect(renderSamples * 4 * channels).order(ByteOrder.nativeOrder()).asFloatBuffer();
                        SOFTLoopback.alcRenderSamplesSOFT(device, audioBuffer, renderSamples);
                        applyCutFade(audioBuffer, channels, afterCut, beforeCut);
                    }
                    saveable.audioBuffer = audioBuffer;

                    if (this.settings.depthMap()) {
                        this.tryDepthDownload();
                    } else {
                        downloader.startDownload(renderTarget, saveable, this.settings.ssaa());
                    }
                }
            } else {
                SaveableFramebuffer saveable = downloader.take();
                if (this.settings.depthMap()) {
                    this.saveDepthAfterLevel = () -> {
                        downloader.startDepthDownload(renderTarget, saveable);
                    };
                }

                // Perform rendering
                PerfectFrames.waitUntilFrameReady();
                start = System.nanoTime();
                render(renderTarget, timer);
                finishHiddenFrame();
                renderTimeNanos += System.nanoTime() - start;

                // Capture audio if necessary
                FloatBuffer audioBuffer = null;
                if (this.settings.recordAudio()) {
                    long device = Minecraft.getInstance().getSoundManager().soundEngine.library.currentDevice;

                    audioSamples += 48000 / this.settings.framerate();
                    int renderSamples = (int) audioSamples;
                    audioSamples -= renderSamples;

                    int channels = this.settings.stereoAudio() ? 2 : 1;

                    audioBuffer = ByteBuffer.allocateDirect(renderSamples * 4 * channels).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    SOFTLoopback.alcRenderSamplesSOFT(device, audioBuffer, renderSamples);
                    applyCutFade(audioBuffer, channels, afterCut, beforeCut);
                }
                saveable.audioBuffer = audioBuffer;

                if (this.settings.depthMap()) {
                    this.tryDepthDownload();
                } else {
                    downloader.startDownload(renderTarget, saveable, this.settings.ssaa());
                }
            }

            submitDownloadedFrames(videoWriter, downloader, false);

            this.shouldChangeFramebufferSize = false;
            boolean cancel = finishFrameNormal(renderTarget, tickIndex, ticks.size());
            this.shouldChangeFramebufferSize = true;

            if (cancel) {
                ExportJobQueue.drainingQueue = false;
                break;
            }
        }

        submitDownloadedFrames(videoWriter, downloader, true);

        long finishStart = System.currentTimeMillis();
        videoWriter.finish(info -> {
            long time = System.currentTimeMillis() - finishStart;
            String progress = "Finalizing video (" + info + ")... " + time/1000 + "s";
            finishFrameFinal(Minecraft.getInstance().mainRenderTarget, progress, false);
        });
    }

    /** Where the time of a finished export went, so slow exports can be diagnosed and speed-ups measured. */
    private void logTimingSummary(long totalNanos) {
        int frames = Math.max(1, this.progressCount);
        Flashback.LOGGER.info("Export timing: total {} ms for {} frames ({} ms/frame) - server ticks {} ms, client ticks {} ms, "
                + "render {} ms, encode submit {} ms", totalNanos / 1_000_000, this.progressCount,
            String.format("%.1f", totalNanos / 1_000_000.0 / frames), serverTickTimeNanos / 1_000_000,
            clientTickTimeNanos / 1_000_000, renderTimeNanos / 1_000_000, encodeTimeNanos / 1_000_000);
    }

    public void tryDepthDownload() {
        if (this.saveDepthAfterLevel != null) {
            this.saveDepthAfterLevel.run();
            this.saveDepthAfterLevel = null;
        }
    }

    private void renderPanoramicFace(RenderTarget renderTarget, DeltaTracker.Timer timer) {
        Minecraft minecraft = Minecraft.getInstance();
        EditorState editorState = EditorStateManager.getCurrent();
        int previousFov = minecraft.options.fov().get();
        boolean previousOverrideFov = editorState != null && editorState.replayVisuals.overrideFov;
        float previousOverrideFovAmount = editorState != null ? editorState.replayVisuals.overrideFovAmount : 0.0F;

        try {
            // Cube-map faces must use a 90-degree field of view to cover the full sphere without gaps.
            // 1.21.1 has no Camera panoramic-mode switch, so force the equivalent FOV for each face.
            minecraft.options.fov().set(90);
            if (editorState != null) {
                editorState.replayVisuals.overrideFov = true;
                editorState.replayVisuals.overrideFovAmount = 90.0F;
            }
            render(renderTarget, timer);
        } finally {
            minecraft.options.fov().set(previousFov);
            if (editorState != null) {
                editorState.replayVisuals.overrideFov = previousOverrideFov;
                editorState.replayVisuals.overrideFovAmount = previousOverrideFovAmount;
            }
        }
    }

    private static void render(RenderTarget renderTarget, DeltaTracker.Timer timer) {
        Minecraft minecraft = Minecraft.getInstance();

        RenderSystem.replayQueue();
        FramebufferUtils.clear(renderTarget, EMPTY_CLEAR_COLOUR);
        // clear() leaves the default framebuffer bound. Vanilla binds the main target immediately before
        // GameRenderer.render (Minecraft.runTick); without it, whether the frame is drawn into the export target
        // depends on whatever state the previous step left behind, and every frame after a client tick came out
        // black (about one frame in five).
        renderTarget.bindWrite(true);
        minecraft.gameRenderer.render(timer, true);
    }

    private void updateRandoms(Random random, Random mathRandom) {
        if (!this.settings.resetRng()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        long connectionSeed = random.nextLong();
        long levelSeed = random.nextLong();
        long entitySeed = random.nextLong();
        long mathSeed = random.nextLong();
        long particleSeed = random.nextLong();
        this.currentClientTickSeed = random.nextLong();
        this.currentInitialEntityTickSeed = random.nextLong();

        if (minecraft.getConnection() != null) {
            minecraft.getConnection().random.setSeed(connectionSeed);
        }
        if (minecraft.level != null) {
            minecraft.level.random.setSeed(levelSeed);

            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity == null) {
                    continue;
                }

                entity.getRandom().setSeed(entitySeed ^ entity.getUUID().getMostSignificantBits());
            }
        }
        if (mathRandom != null) {
            mathRandom.setSeed(mathSeed);
        }
        this.particleRandom.setSeed(particleSeed);
    }

    private void setup(ReplayServer replayServer) {
        Minecraft minecraft = Minecraft.getInstance();

        replayServer.setDesiredTickRate(20.0f, true);

        if (replayServer.getReplayTick() != this.settings.startTick()) {
            int currentTick = Math.max(0, this.settings.startTick() - 40);

            // Ensure replay server is paused at currentTick
            this.setServerTickAndWait(replayServer, currentTick, true);
            this.runClientTick(false);

            // Clear particles
            minecraft.particleEngine.clearParticles();

            // Reset all walk animations & tick counts
            if (minecraft.level != null) {
                for (Entity entity : minecraft.level.entitiesForRendering()) {
                    if (entity instanceof LivingEntity livingEntity) {
                        livingEntity.walkAnimation.setSpeed(0.0F);
                        livingEntity.walkAnimation.update(0.0F, 0.0F);
                    }
                    entity.tickCount = 0;
                }
            }

            // Advance until tick is at start
            while (currentTick < this.settings.startTick()) {
                currentTick += 1;
                this.setServerTickAndWait(replayServer, currentTick, true);
                this.runClientTick(false);
            }

            // Apply initial position and keyframes at start tick
            LocalPlayer player = minecraft.player;
            if (player != null) {
                Vec3 position = this.settings.initialCameraPosition();
                player.moveTo(position.x, position.y, position.z, this.settings.initialCameraYaw(), this.settings.initialCameraPitch());
                player.setDeltaMovement(Vec3.ZERO);
            }
            this.settings.editorState().applyKeyframes(new MinecraftKeyframeHandler(Minecraft.getInstance()), this.settings.startTick());
            this.runClientTick(false);

            // Ensured replay server is paused at startTick
            this.setServerTickAndWait(replayServer, this.settings.startTick(), true);
            this.runClientTick(false);
        }

        // Remove screen
        minecraft.setScreen(null);
    }

    private void setServerTickAndWait(ReplayServer replayServer, int targetTick, boolean force) {
        if (force || replayServer.getReplayTick() != targetTick) {
            this.finishedServerTick.set(false);

            replayServer.jumpToTick = targetTick;
            replayServer.replayPaused = true;
            replayServer.sendFinishedServerTick.set(true);

            while (!this.finishedServerTick.compareAndExchange(true, false)) {
                LockSupport.parkNanos("waiting for server thread", 100000L);
            }
        }
    }

    private void runClientTick(boolean frozen) {
        this.updateClientFreeze(frozen);

        Minecraft minecraft = Minecraft.getInstance();

        while (minecraft.pollTask()) {}
        this.updateClientFreeze(frozen);
        if (!frozen) {
            minecraft.getTextureManager().tick();
        }
        minecraft.tick();
        this.updateSoundSource(minecraft);

        this.updateClientFreeze(frozen);
    }

    private void updateSoundSource(Minecraft minecraft) {
        EditorState editorState = this.settings.editorState();
        if (editorState != null) {
            Camera audioCamera = editorState.getAudioCamera();
            if (audioCamera != null) {
                minecraft.getSoundManager().updateSource(audioCamera);
                return;
            }
        }

        minecraft.getSoundManager().updateSource(Minecraft.getInstance().gameRenderer.getMainCamera());
    }

    private void updateClientFreeze(boolean frozen) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.tickRateManager().setFrozen(frozen);
        }
    }

    private void submitDownloadedFrames(VideoWriter videoWriter, SaveableFramebufferQueue downloader, boolean drain) {
        long start = System.currentTimeMillis();
        boolean firstDrainFrame = true;
        while (true) {
            if (drain) {
                long time = System.currentTimeMillis() - start;
                String progress = "Capturing " + downloader.pendingCount() + " output frames... " + time/1000 + "s";
                finishFrameFinal(Minecraft.getInstance().mainRenderTarget, progress, firstDrainFrame);
                firstDrainFrame = false;
            } else {
                RenderSystem.replayQueue();
            }

            if (this.settings.projection() == ExportProjection.CUBE_MAP || this.settings.projection() == ExportProjection.EQUIRECTANGULAR) {
                var frames = downloader.finishDownloadMultiple(6);

                if (frames == null) {
                    if (drain && !downloader.isEmpty()) {
                        LockSupport.parkNanos("waiting for frame to download", 100000L);
                        continue;
                    } else {
                        break;
                    }
                }

                if (!this.settings.transparent()) {
                    for (ImageFrame frame : frames) {
                        frame.makeOpaque();
                    }
                }

                int resolutionX = this.settings.resolutionX();
                int resolutionY = this.settings.resolutionY();

                ImageFrame target = new ImageFrame(resolutionX, resolutionY, frames[0].format, true);
                target.audioBuffer = frames[0].audioBuffer;

                if (this.settings.projection() == ExportProjection.CUBE_MAP) {
                    for (int i = 0; i < frames.length; i++) {
                        int positionX;
                        int positionY;

                        if (i < 4) {
                            positionX = resolutionX * i / 4;
                            positionY = resolutionY / 3;
                        } else if (i == 4) {
                            positionX = resolutionX / 4;
                            positionY = 0;
                        } else if (i == 5) {
                            positionX = resolutionX / 4;
                            positionY = resolutionY * 2 / 3;
                        } else {
                            break;
                        }

                        ImageFrame image = frames[i];
                        int sizeX = Math.min(image.width, target.width - positionX);
                        int sizeY = Math.min(image.height, target.height - positionY);
                        image.copyRect(target, 0, 0, positionX, positionY, sizeX, sizeY);
                    }
                } else {
                    for (int y = 0; y < resolutionY; y++) {
                        for (int x = 0; x < resolutionX; x++) {
                            double yaw = (double) x / resolutionX * 2.0 * Math.PI;
                            double pitch = (double) y / resolutionY * Math.PI - Math.PI/2.0;

                            // Sphere xyz
                            double sx = -Math.sin(yaw) * Math.cos(pitch);
                            double sy = Math.sin(pitch);
                            double sz = -Math.cos(yaw) * Math.cos(pitch);

                            // Cube xyz
                            double a = Math.max(Math.abs(sx), Math.max(Math.abs(sy), Math.abs(sz)));
                            double cx = sx / a;
                            double cy = sy / a;
                            double cz = sz / a;

                            if (cy == -1.0) {
                                ImageFrame image = frames[4];
                                int imageX = (int) Math.round((cx+1)/2 * (image.width-1));
                                int imageY = (int) Math.round((cz+1)/2 * (image.height-1));

                                image.copyPixel(target, imageX, imageY, x, y);
                            } else if (cy == 1.0) {
                                ImageFrame image = frames[5];
                                int imageX = (int) Math.round((cx+1)/2 * (image.width-1));
                                int imageY = image.height-1 - (int) Math.round((cz+1)/2 * (image.height-1));

                                image.copyPixel(target, imageX, imageY, x, y);
                            } else if (cz == -1.0) {
                                ImageFrame image = frames[3];
                                int imageX = image.width-1 - (int) Math.round((cx+1)/2 * (image.width-1));
                                int imageY = (int) Math.round((cy+1)/2 * (image.height-1));

                                image.copyPixel(target, imageX, imageY, x, y);
                            } else if (cz == 1.0) {
                                ImageFrame image = frames[1];
                                int imageX = (int) Math.round((cx+1)/2 * (image.width-1));
                                int imageY = (int) Math.round((cy+1)/2 * (image.height-1));

                                image.copyPixel(target, imageX, imageY, x, y);
                            } else if (cx == 1.0) {
                                ImageFrame image = frames[2];
                                int imageX = image.width-1 - (int) Math.round((cz+1)/2 * (image.width-1));
                                int imageY = (int) Math.round((cy+1)/2 * (image.height-1));

                                image.copyPixel(target, imageX, imageY, x, y);
                            } else if (cx == -1.0) {
                                ImageFrame image = frames[0];
                                int imageX = (int) Math.round((cz+1)/2 * (image.width-1));
                                int imageY = (int) Math.round((cy+1)/2 * (image.height-1));

                                image.copyPixel(target, imageX, imageY, x, y);
                            }
                        }
                    }
                }

                for (ImageFrame frame : frames) {
                    frame.close();
                }

                if (this.firstFrame == null) {
                    this.firstFrame = target.toOpaqueRgbaU8NativeImage();
                }
                this.writtenFrames += 1;

                long encodeStart = System.nanoTime();
                videoWriter.encode(target);
                encodeTimeNanos += System.nanoTime() - encodeStart;
            } else {
                ImageFrame frame = downloader.finishDownload();

                if (frame == null) {
                    if (drain && !downloader.isEmpty()) {
                        LockSupport.parkNanos("waiting for frame to download", 100000L);
                        continue;
                    } else {
                        break;
                    }
                }

                if (!this.settings.transparent()) {
                    frame.makeOpaque();
                }

                if (this.firstFrame == null) {
                    this.firstFrame = frame.toOpaqueRgbaU8NativeImage();
                }
                this.writtenFrames += 1;

                long encodeStart = System.nanoTime();
                videoWriter.encode(frame);
                encodeTimeNanos += System.nanoTime() - encodeStart;
            }
        }
    }

    private static RenderTarget displayTarget = null;

    private boolean finishFrameNormal(RenderTarget framebuffer, int currentFrame, int totalFrames) {
        this.progressCount = currentFrame;
        this.progressOutOf = totalFrames;

        if (currentFrame == totalFrames) {
            this.finishFrameFinal(framebuffer, "Saving...", true);
            return false;
        }

        List<String> lines = new ArrayList<>();

        lines.add("Exported Frames: " + currentFrame + "/" + totalFrames);

        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - this.renderStartTime;
        lines.add("Time elapsed: " + formatTime(elapsed));

        if (currentFrame >= this.settings.framerate()) {
            long estimatedRemaining = (currentTime - this.renderStartTime) * (totalFrames - currentFrame) / currentFrame;
            lines.add("Estimated time remaining: " + formatTime(estimatedRemaining));
        } else {
            lines.add("Estimated time remaining: ~");
        }

        return this.finishFrame(framebuffer, lines, false, true);
    }

    private void finishFrameFinal(RenderTarget framebuffer, String customProgress, boolean forceShow) {
        List<String> lines = new ArrayList<>();
        lines.add(customProgress);
        this.finishFrame(framebuffer, lines, forceShow, false);
    }

    private static void finishHiddenFrame() {
        RenderSystem.replayQueue();
        org.lwjgl.glfw.GLFW.glfwPollEvents();
        org.lwjgl.opengl.GL11C.glFlush();
    }

    private boolean finishFrame(RenderTarget framebuffer, List<String> lines, boolean forceShow, boolean showCancel) {
        RenderSystem.replayQueue();
        org.lwjgl.glfw.GLFW.glfwPollEvents();

        long currentTime = System.currentTimeMillis();
        if (currentTime - this.lastRenderMillis > 1000/60 || forceShow) {
            this.lastRenderMillis = currentTime;
        } else {
            org.lwjgl.opengl.GL11C.glFlush();
            return false;
        }

        boolean cancel = false;

        Window window = Minecraft.getInstance().getWindow();

        Font font = Minecraft.getInstance().font;

        int windowFramebufferWidth = WindowSizeTracker.getWidth(window);
        int windowFramebufferHeight = WindowSizeTracker.getHeight(window);

        displayTarget = FramebufferUtils.resizeOrCreateFramebuffer(displayTarget, windowFramebufferWidth, windowFramebufferHeight, true);
        FramebufferUtils.clear(displayTarget, FramebufferUtils.BLACK_CLEAR_COLOUR);

        // Copy framebuffer to display
        float aspectWidth = framebuffer.width / (float) windowFramebufferWidth;
        float aspectHeight = framebuffer.height / (float) windowFramebufferHeight;
        float framebufferX1, framebufferY1, framebufferX2, framebufferY2;
        if (aspectWidth > aspectHeight) {
            framebufferX1 = 0;
            framebufferX2 = 1;
            framebufferY1 = 0.5f - aspectHeight/aspectWidth/2;
            framebufferY2 = 0.5f + aspectHeight/aspectWidth/2;
        } else {
            framebufferY1 = 0;
            framebufferY2 = 1;
            framebufferX1 = 0.5f - aspectWidth/aspectHeight/2;
            framebufferX2 = 0.5f + aspectWidth/aspectHeight/2;
        }
        FramebufferUtils.blitTo(framebuffer, displayTarget, framebufferX1, framebufferY1, framebufferX2, framebufferY2);

        float guiScale = Math.min(windowFramebufferWidth / 420, windowFramebufferHeight / 240);
        int scaledWidth = (int) Math.ceil(windowFramebufferWidth / guiScale);
        int scaledHeight = (int) Math.ceil(windowFramebufferHeight / guiScale);

        if (this.settings.name() != null) {
            lines.add(0, this.settings.name());
            lines.add(1, "");
        }

        lines.add("");

        boolean debugPressed = InputHelper.isKeyDownRaw(ImGuiKey.F3);
        if (pressedDebugKey != debugPressed) {
            pressedDebugKey = debugPressed;
            if (pressedDebugKey) {
                showingDebug = !showingDebug;
            }
        }

        if (showingDebug) {
            lines.add("ST: " + serverTickTimeNanos/1000000 + ", CT: " + clientTickTimeNanos/1000000);
            lines.add("RT: " + renderTimeNanos/1000000 + ", ET: " + encodeTimeNanos/1000000);
        } else {
            lines.add("Press [F3] to show debug info");
        }

        lines.add("");

        if (showCancel) {
            if (InputHelper.isKeyDownRaw(ImGuiKey.Escape)) {
                long current = System.currentTimeMillis();
                if (this.escapeCancelStartMillis <= 0 || current < this.escapeCancelStartMillis) {
                    this.escapeCancelStartMillis = current;
                }
                if (current - this.escapeCancelStartMillis > 3000) {
                    cancel = true;
                    lines.add("Saving...");
                } else {
                    long remainingSeconds = 3 - (current - this.escapeCancelStartMillis) / 1000;
                    lines.add("Hold [ESC] to cancel (" + remainingSeconds + "s)");
                }
            } else {
                lines.add("Hold [ESC] to cancel");
                this.escapeCancelStartMillis = -1;
            }
        }

        int x = scaledWidth / 2;
        int y = scaledHeight / 2 - font.lineHeight * (lines.size() + 1) / 2;
        double mouseX = ReplayUI.imguiWindower.getRawMouseX() / window.getScreenWidth() * scaledWidth;
        double mouseY = ReplayUI.imguiWindower.getRawMouseY() / window.getScreenHeight() * scaledHeight;
        String patreon = "https://www.patreon.com/flashbackmod";
        int patreonWidth = font.width(patreon);
        boolean hoverPatreon = mouseX > x - patreonWidth / 2.0f && mouseX < x + patreonWidth / 2.0f
            && mouseY > y + font.lineHeight * lines.size() && mouseY < y + font.lineHeight * (lines.size() + 1);

        displayTarget.bindWrite(true);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0f, scaledWidth, scaledHeight, 0.0f, 1000.0f, 21000.0f),
            com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4f textMatrix = new Matrix4f().translate(0.0f, 0.0f, -11000.0f);
        var bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (String line : lines) {
            if (line.isEmpty()) {
                y += font.lineHeight / 2 + 1;
            } else {
                font.drawInBatch(line, x - font.width(line) / 2.0f, y, -1, true, textMatrix, bufferSource,
                    Font.DisplayMode.NORMAL, 0, 0xF000F0);
                y += font.lineHeight;
            }
        }
        y += font.lineHeight / 2 + 1;
        if (hoverPatreon && InputHelper.isMouseDownRaw(ImGuiMouseButton.Left)) {
            if (!this.patreonLinkClicked) {
                this.patreonLinkClicked = true;
                try {
                    Util.getPlatform().openUri(new URI(patreon));
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            }
        } else {
            this.patreonLinkClicked = false;
        }
        font.drawInBatch(hoverPatreon ? Component.literal(patreon).withStyle(ChatFormatting.UNDERLINE) : Component.literal(patreon),
            x - patreonWidth / 2.0f, y, -1, true, textMatrix, bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);
        bufferSource.endBatch();
        RenderSystem.enableDepthTest();
        displayTarget.unbindWrite();

        if (GLFW.glfwGetWindowAttrib(window.getWindow(), GLFW.GLFW_ICONIFIED) == GLFW.GLFW_FALSE) {
            displayTarget.blitToScreen(windowFramebufferWidth, windowFramebufferHeight, false);
            window.updateDisplay();
        } else {
            org.lwjgl.opengl.GL11C.glFlush();
        }
        return cancel;
    }

    private String formatTime(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / 1000 / 60) % 60;
        long hours = millis / 1000 / 60 / 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        } else if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        } else {
            return seconds + "s";
        }
    }

    private record TickInfo(double serverTick, double clientTick, boolean frozen) {}

    /**
     * Rounds the first and last moments of the audio either side of a cut.
     *
     * <p>A cut puts two pieces of sound next to each other that were never neighbours, and stepping
     * from one sample to an unrelated one is a click. A couple of milliseconds of ramp is shorter than
     * anything anyone hears and takes the click away, which is what cutting a clip in an editor does.
     */
    private static void applyCutFade(FloatBuffer audio, int channels, boolean fadeIn, boolean fadeOut) {
        if (audio == null || channels <= 0 || (!fadeIn && !fadeOut)) {
            return;
        }

        int frames = audio.limit() / channels;
        int ramp = Math.min(frames, 96);
        for (int i = 0; i < ramp; i++) {
            float amount = (i + 1) / (float) ramp;
            if (fadeIn) {
                for (int channel = 0; channel < channels; channel++) {
                    int index = i * channels + channel;
                    audio.put(index, audio.get(index) * amount);
                }
            }
            if (fadeOut) {
                int frame = frames - 1 - i;
                for (int channel = 0; channel < channels; channel++) {
                    int index = frame * channels + channel;
                    audio.put(index, audio.get(index) * amount);
                }
            }
        }
    }

    /**
     * Drops the frames inside cut stretches and slides the client timeline up behind them.
     *
     * <p>A cut removes replay ticks from the edit, so those frames are never rendered and the frames
     * after them move up by the length of the cut. The server tick of each surviving frame is left
     * alone: it still names the replay tick the footage comes from, which is what the keyframe tracks
     * and the replay itself are written against. Only the client tick - the timeline the exported
     * video runs on - is compressed, and because the client timeline stays continuous across a cut the
     * join is a hard cut rather than a stretch of interpolated movement.
     */
    private static List<TickInfo> applyCuts(EditorState editorState, int startTick, List<TickInfo> ticks) {
        if (editorState.normalisedCuts().isEmpty()) {
            return ticks;
        }

        List<TickInfo> kept = new ArrayList<>(ticks.size());
        for (TickInfo tick : ticks) {
            if (editorState.isCut((int) Math.floor(startTick + tick.serverTick))) {
                continue;
            }
            double clientTick = editorState.keptTick(startTick + tick.clientTick) - startTick;
            kept.add(new TickInfo(tick.serverTick, clientTick, tick.frozen));
        }

        if (kept.isEmpty()) {
            // Every tick in the exported range was cut. Exporting nothing at all would be worse than
            // exporting the one frame the range still starts on.
            kept.add(ticks.get(0));
        }
        return kept;
    }

    /**
     * How many video frames an export of this range would contain: the speed, freeze and timelapse tracks and the
     * cuts all change it, so the answer is the one the exporter itself would work out. Used by the export window to
     * show the length of the result before anything is rendered.
     */
    public static int countExportFrames(EditorState editorState, int startTick, int endTick, double fps) {
        List<TickInfo> ticks = calculateTicks(editorState, startTick, endTick, fps);
        ticks = applyCuts(editorState, startTick, ticks);
        return ticks.size();
    }

    private static List<TickInfo> calculateTicks(EditorState editorState, int startTick, int endTick, double fps) {
        List<TickInfo> ticks = new ArrayList<>();

        ticks.add(new TickInfo(0, 0, false));

        BigDecimal lastResidual = BigDecimal.ZERO;
        BigDecimal residual = BigDecimal.ZERO;
        BigDecimal fpsReciprocal = BigDecimal.ONE.divide(BigDecimal.valueOf(fps), MathContext.DECIMAL128);
        int currentTick = 0;

        TickrateKeyframeCapture capture = new TickrateKeyframeCapture();

        int count = endTick - startTick;
        int startFrozen = -1;
        while (currentTick <= count) {
            BigDecimal remainingFrame = BigDecimal.ONE;

            while (remainingFrame.compareTo(BigDecimal.ZERO) > 0) {
                capture.tickrate = 20.0f;
                capture.frozen = false;
                editorState.applyKeyframes(capture, startTick + currentTick + residual.floatValue());

                capture.tickrate = Math.max(KeyframeChangeTickrate.MIN_TICKRATE, capture.tickrate);

                BigDecimal ticksThisFrame = BigDecimal.valueOf(capture.tickrate).multiply(fpsReciprocal).multiply(remainingFrame);
                if (ticksThisFrame.compareTo(BigDecimal.ONE) > 0) {
                    residual = residual.add(BigDecimal.ONE);
                    // remainingFrame *= 1 - 1/ticksThisFrame
                    BigDecimal lastRemainingFrame = remainingFrame;
                    remainingFrame = remainingFrame.multiply(BigDecimal.ONE.subtract(BigDecimal.ONE.divide(ticksThisFrame, MathContext.DECIMAL128))).round(MathContext.DECIMAL128);
                    if (remainingFrame.compareTo(lastRemainingFrame) >= 0) {
                        throw new IllegalStateException("remainingFrame >= lastRemainingFrame");
                    }
                } else {
                    residual = residual.add(ticksThisFrame);
                    break;
                }
            }

            int roundedResidual = residual.intValue();
            residual = residual.subtract(BigDecimal.valueOf(roundedResidual));
            currentTick += roundedResidual;

            if (currentTick >= count) {
                break;
            }

            double currentTickDouble = currentTick + residual.doubleValue();
            if (roundedResidual > 0 && currentTickDouble % 1.0 == 0.0) {
                residual = BigDecimal.ZERO;
            }

            // Sanity checks to make sure we don't get into an infinite loop
            if (roundedResidual < 0) {
                throw new IllegalStateException("roundedResidual < 0");
            } else if (roundedResidual == 0 && residual.compareTo(lastResidual) <= 0) {
                throw new IllegalStateException("roundedResidual == 0 && residual <= lastResidual)");
            }
            lastResidual = residual;

            if (!capture.frozen) {
                startFrozen = -1;
                ticks.add(new TickInfo(currentTickDouble, currentTickDouble, false));
            } else {
                if (startFrozen < 0) {
                    startFrozen = currentTick;
                }

                if (capture.frozenDelay == 0) {
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen), true));
                    continue;
                }

                double deltaFromStart = currentTickDouble - startFrozen;
                double freezeClientTicks = capture.frozenDelay <= 5 ? 0.999 : 1.999;
                double freezeDerivative = capture.frozenDelay <= 5 ? 1.0 : 0.5;
                if (deltaFromStart > capture.frozenDelay) {
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen + freezeClientTicks), true));
                } else if (capture.frozenDelay == 1) {
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen + freezeClientTicks), false));
                } else {
                    double freezePowerBase = FreezeSlowdownFormula.getFreezePowerBase(capture.frozenDelay, freezeDerivative);
                    double clientTicks = freezeClientTicks * FreezeSlowdownFormula.calculateFreezeClientTick(deltaFromStart, capture.frozenDelay, freezePowerBase);
                    ticks.add(new TickInfo(currentTickDouble, Math.min(currentTickDouble, startFrozen + clientTicks), false));
                }
            }
        }

        return ticks;
    }


}
