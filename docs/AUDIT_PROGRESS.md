# Flashback 1.21.1 port - verification record (macOS, Apple Silicon)

Branch `port/minecraft-1.21.1`. Environment: macOS arm64, JDK 25 runs the build (target 21), MCPFabric 0.5.1+1.21.1,
`-Ploader_version=0.19.5`. (Windows 26.3 audit notes live on `audit/26.3`.)

## Verified
- `./gradlew build`: SUCCESS (3m48s cold).
- Record (`/flashback start` / `finish`), Save Replay dialog, replay listing with thumbnail + duration.
- Open replay -> editor (ImGui 1.86) renders: menu bar, visuals panel, timeline, player list.
- Playback play/pause, skip-to-start, click-seek on the ruler; terrain, entities, sky, recorded player + nametag render.
- Timeline: Add Element menu (New Camera / Orbit / Spectate / Shake / FOV / Speed / Timelapse / Time of day / Weather / Freeze /
  Audio), camera creation, camera-cut track, keyframe insertion.
- Camera keyframes interpolate: two keyframes (-19.5,-55,-7.5 yaw 0) and (30.5,-55,30.5 yaw 90 pitch 20); mid-timeline
  pose was (5.79,-55,11.72 yaw 45.5 pitch 10.1) - a linear blend, restored exactly at the keyframes.
- Export (h264_videotoolbox): 1920x1080@60, 370 ticks -> 18.5 s, 1110 frames, valid MP4 per ffprobe.
- Native file dialog (macOS NSSavePanel) opens from Save Replay.

## Bugs fixed
1. `utils/AsyncFileDialogs` used Swing `JFileChooser`, which throws `HeadlessException` on macOS (AWT is headless under
   Minecraft). Every file dialog (export output, load replay from file, save replay path, audio) was dead. It now delegates to the
   NFD implementation in `exporting/AsyncFileDialogs`, which already runs dialogs on the main thread on macOS.

## Tooling added
- `command/FlashbackDevCommands` (dev-environment only) so the bridge can drive GUI-only flows without OS input; see
  `devtools/mac/README.md`. `devtools/mac/input.swift` + `rpc.sh` for the remaining real-input cases.

## Observations / not yet checked
- Export frame at t=9s (no camera keyframes, default viewer pose) showed the camera inside the recorded player's head, and the
  vanilla "Move with W,A,S,D" tutorial toast is baked into the exported frame (noGui=false). Compare with 26.3 / upstream before
  calling these bugs; neither was investigated.
- Not yet exercised: cuts, orbit/spectate cameras, speed/timelapse/weather/time-of-day tracks, undo/redo, audio export,
  PNG/EXR/ProRes containers, export queue, container-GUI recording, weather rendering, `devtools/checks` suites.
- Test artifact, not a bug: `control.setInput {}` leaves keys held; the viewer flew ~1800 blocks until released explicitly.
- The only local world was 1.21.11; the test world `run/saves/FlashbackTest` was generated with the vanilla 1.21.1 server jar.


## Session 2 - Version Emulator integration (REQUIRED goal item) and replay fixes

Emulator = `j-w-i/minecraft-version-emulator` (cloned to `~/minecraft-version-emulator`, Fabric 1.21.1, mod id `hex1211`).
It gates the HUD via `HudHelper.hideHungerBar()/hideExperienceBar()` which read the global `HistoricalActiveSession`, set by its
server join hook. Local build needs the JDK-21 toolchain pin dropped (uncommitted local edit; JDK 25 + release 21).
Test world: `run/saves/EraBeta` (Beta 1.7.3, `level-type=historical_experience:beta_1_7_3`); mods `historical-experience` +
`moderner-beta 5.0.0-alpha.4` in `run/mods` (git-ignored).

### Problem found
Replays are played by Flashback's own `ReplayServer`, so the emulator's join hook never established the era: **a replay of a Beta
1.7.3 world opened from a modern world drew the modern HUD** (hunger + XP), and exports baked that in.

### Fix
- `FlashbackMeta.integrationState` (new `integrationState` JSON object) stores per-recording state of other mods;
  `compat/ModIntegrations` captures the emulator's version at record time (reflection only - no compile/runtime dependency) and
  re-applies it every client tick for the whole replay (`applyForReplay`), clearing it on exit (`clearAfterReplay`).
- Flashback's `MixinHud.isExperienceBarVisible` overrode the emulator's own XP gate (it forces survival -> visible), so it now also
  asks `HudHelper.hideExperienceBar()`.
- Verified: record Beta 1.7.3 world (with and without iron armour) -> reopen from a *modern* world -> spectate: hearts only, no
  hunger, no XP bar, armour relocated to the right of the hearts exactly as in the live game; MP4 export of the same shows the same
  HUD (frame inspected with ffmpeg). Old recordings without `integrationState` keep modern HUD (no era is recorded in them).

### Other fixes this session
- Vanilla tutorial toast ("Move with WASD") leaked into replay playback and exported frames: `MixinToastManager` drops
  `TutorialToast` while a replay is open.
- Free camera started exactly at the recorded player's position (inside their head): now starts 3.5 blocks behind / 1 above.
- Dev commands added: `spectate`, `unspectate`, `armor <material>`; `status` now reports camera entity and the emulator era.

### Still to do
Other eras (Alpha/Infdev/Release 1.8.x), relocated elements for other versions, hunger-less eras on multiplayer/LAN, cuts, orbit
camera, undo/redo, audio, other containers, export queue, creator-facing UI polish, GUI capture cleanup, render optimisation.


### Headless check suites on 1.21.1 (`devtools/checks/run.sh`) - 19/19 pass
Runner fixes (it was written for 26.3 and picked wrong jars): game jar chosen from `gradle.properties`' `minecraft_version`;
foreign game jars (26.3 loom cache, PrismLauncher `com/mojang/minecraft/*`) and duplicate/signed Fabric API jars from `modules-2`
removed from the classpath; DFU/brigadier pinned to the 1.21.1 versions; `JAVA_HOME` no longer hard-coded.
Suite adaptations (each is a SKIP or an alternative assertion, never a weakened 1.21.1 check): `Timer.advanceTime(long,boolean)`;
time-sync override is `synchronizeTime(ServerLevel)`; the AFK `FramerateLimitTracker`, the render-state extractor stage and the
remote-player hand state do not exist on 1.21.1; weather animation clock substitution is 26.x-only (1.21.1 animates from
`LevelRenderer.ticks`) - weather needs a playtest, not source inspection; overlay uses `Screen.renderWithTooltip`.
GUI capture cleanup: reflective hovered-slot search -> typed `AbstractContainerScreenAccessor`; removed `GuiPlayback.shouldShow()`
(a pure alias of `enabled()`), duplicated/orphaned javadoc blocks. Container record -> replay playback re-verified in game.

### Export performance baseline (instrumentation added, no render changes yet)
`ExportJob` now logs `Export timing: total ... - server ticks, client ticks, render, encode submit` after every export.
Baseline on Apple Silicon, 1080p60, h264_videotoolbox, flat/Beta test scenes (311 frames): 2.1-2.5 s total = 6.8-8.1 ms/frame
(render ~0.45-0.48 s, server ticks ~0.07 s, client ticks ~0.1 s, encode submit ~0.12-0.13 s; the remainder is the fixed 60-frame
warm-up and encoder finalisation). These scenes are too light to expose a render bottleneck, so no render-path changes were made on
speculation. To optimise meaningfully, record a heavy scene (many chunks/entities/particles, 4K, SSAA) and compare this log line
before/after any change.

### Timeline engine verified through export (dev commands `cut`, `uncut`, `track`)
All checked by exporting 1080p60 clips and measuring with ffprobe / inspecting frames:
- Cuts: a 50-100 cut on a 104-tick range shortens the export 5.2 s/312 frames -> 2.7 s/162 frames (exactly the 50 ticks removed);
  live playback from tick 45 jumps to 107 (steps over the cut).
- Speed track: 2x -> 2.5 s, 0.5x -> 10.0 s, 1x -> 5.0 s for the same 100-tick range.
- Time-of-day track (6000 -> 18000) darkens from day to night across the clip; weather track `raining` draws rain with an overcast sky.
- Test-tool lesson: an export with unlimited bitrate (bitrate 0) is ~36 MB/s of video; use ~20 Mbps for scripted exports.
Not yet exercised by script: orbit/spectate camera paths, undo/redo through the UI history, audio, other containers, export queue.

### BUG FIXED: ~1 in 5 exported frames was black (all containers, every export)
Symptom (found while measuring exported frames, not visible in live playback): in a plain 1080p60 export, 21 % of frames were
fully black except for the player/nametag, at a pattern of every 4th-6th frame (60 fps video from 20 tps ticks).
Diagnosis by elimination with per-frame probes: world state (time, sky, sections, layers drawn) identical on black and good frames;
`glReadPixels` on the export target immediately after `GameRenderer.render` was already 0,0,0 on black frames, while the terrain
layers and sky were being issued. => the frame was being drawn into a different framebuffer than the export target.
Cause: `ExportJob.render` called `FramebufferUtils.clear(target)`, which restores the previous bindings (the default framebuffer),
then `GameRenderer.render`, which assumes the main target is bound (vanilla binds it in `Minecraft.runTick` first). Frames that
followed a client tick drew into the wrong target.
Fix: `renderTarget.bindWrite(true)` before `gameRenderer.render` in `ExportJob.render`.
Verified: 0 dark frames of 738 (mp4, mkv, mov, webm, avi) and 0 of 1230 (gif), vs 21 % / 49 % / 36 % (mp4/webm/mkv) before.
Containers all produce valid files: mp4 mkv mov webm avi gif webp + PNG sequence (via `/flashback dev exportas`).
Earlier export checks in this file measured length/HUD, so they were unaffected, but any export made before this commit has flicker.

### Export queue and audio verified
- Queue: three jobs queued with `/flashback dev set queue true` + `exportas`, drained with `/flashback dev runqueue`; all three finished in
  order, 768 frames each, 0 dark frames. (The queue window UI itself still needs a real-click pass.)
- Audio: recorded a replay with four `playsound` note-block hits (recording saved without the dialog via `set quicksave true`),
  exported with audio on: AAC, 48 kHz stereo, 7.2 s = video length, bursts in the envelope where the notes play.
- Dev switches: `/flashback dev set audio|queue|quicksave <bool>`, `runqueue`. Dev export uses 20 Mbps (not unlimited) unless configured.

### Orbit camera verified (`/flashback dev orbit <fromYaw> <toYaw>`)
Orbit camera around the recorded player, yaw 0 -> 360 over the clip, cut to at tick 0. Live playback pose: yaw 1.9 / 77.4 / 218.5 / 335.6 at
ticks 17 / 111 / 212 / 311, position circling the player at radius ~4. 1107-frame 1080p export shows the player from the front, side and
back, 0 dark frames. Note: a paused `seek` alone does not re-evaluate camera keyframes (the reading stayed at the last value until playback
stepped a tick) - worth a look as a UI nicety: scrubbing while paused should update the camera.
