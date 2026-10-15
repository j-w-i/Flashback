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
