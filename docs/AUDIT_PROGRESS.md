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
back, 0 dark frames. (A paused dev `seek` initially did not re-evaluate camera keyframes because the dev command did not set `forceApplyKeyframes` like the timeline UI does; fixed in the dev command, not a product bug.)

### Spectate camera cut verified end to end (`/flashback dev spectatecam`)
Replay of the armoured Beta 1.7.3 session, opened from a modern world, spectate camera + cut at tick 0, exported 1080p60 (432 frames, 0 dark
frames): first-person hand, hearts only, armour relocated to the right of the hearts, no hunger bar, no XP bar, no tutorial toast.

### Second era verified: Release 1.6.4 (hunger + XP) - the opposite case to Beta 1.7.3
World `EraRel` (`level-type=historical_experience:release_1_6_4`, rules: hunger=true xp=true). Recorded armoured session -> metadata
`version_emulator: release_1_6_4` -> replayed from a modern world, spectate camera, 1080p export (525 frames, 0 dark): hearts + hunger
drumsticks + XP bar all drawn, armour in the modern position above the hearts. Together with Beta 1.7.3 (hearts only, armour relocated
right) this confirms the era is captured in both directions. The emulator defines 32 versions; hunger/xp are off for Alpha, Infdev, Beta
1.0-1.7.3 and on for Beta 1.8+ and Releases 1.0+ - the others were not individually recorded but use the same stored-version path.

### Beta 1.7.3 long session with a container (answers "does Beta record properly")
Recorded 1799 ticks (~90 s) in `EraBeta` including opening a chest: metadata `version_emulator: beta_1_7_3`, zip intact. Replayed from a
modern world and scanned 12 points across the clip: the Chest + Inventory screen is drawn at tick ~1150 (and the chest block is visible
before/after), and the Beta HUD (hearts only, armour relocated) stays correct throughout. Not covered: clicking items inside the chest,
furnaces/crafting tables, or Beta containers whose layout differs from the modern screens.

### Real-UI verification (screen unlocked again)
- "Watch as recorded player" / "Back to free camera" menu toggle: clicked both directions in the live editor; camera entity and the HUD gating
  (`showingThroughPlayer`) flip each time, label updates.
- Undo/redo: three camera keyframes inserted through the timeline UI; Cmd+Z removed one per press (4 -> 3 -> 2 keyframes counting the
  camera-switch key), Cmd+Y restored one. On macOS the app's "Ctrl" bindings are Command (by design, `replaceCtrlWithCommand`). Plain `P` (pause) works.
- Tool lesson: GLFW ignores modifier flags on synthetic key events; hold the real modifier (`devtools/mac/input chord`).

### Weather rendering verified in export (all four modes)
Weather track `raining` / `snowing` / `thundering` / `clear` on a clear-day replay: rain streaks, snow flakes, darkened sky + rain for thunder, and
a clean blue sky for clear. 0 dark frames in all four. Rain pixels change between adjacent frames (the animation advances; clear sky differs by
exactly 0), so weather animates through the export. This covers weather *rendering* on 1.21.1; it replaces the 26.x-only source-inspection
assertions that the check suite now skips.

### Timelapse track verified (`/flashback dev track timelapse <replayTick> <outputTicks>`)
A timelapse keyframe pair maps a source range onto an output duration: tickrate = (Δ source ticks / Δ output ticks) x 20. 100-tick range exported 1080p60:
- 0->0, 100->40 : 2.0 s (120 frames), 5x faster. Matches 100 / 40 x 20 = 50 tps.
- 0->0, 100->200: 10.0 s (599 frames), 0.5x. 
- 0->0, 50->200 (50-tick range): 10.0 s. 
- Invalid pair (right output time <= left): the track is ignored and playback stays at 1x (5.0 s), with the on-screen warning. No crash.
- No-track baseline for the same replay: 175 ticks = 8.75 s. Exact in all cases.

### Export performance at 4K: the bottleneck is the hardware encoder, not rendering
New dev command `/flashback dev resolution <w> <h> <ssaa>`, and `AsyncFFmpegVideoWriter` now logs the time spent on its two worker threads.
599-frame export of the same scene (h264_videotoolbox, Apple Silicon):
| | total | render | sws_scale thread | encode thread (`recordImage`) |
|---|---|---|---|---|
| 1080p | 4.4 ms/frame | 6.8 s / 5396 f | - | - |
| 4K | 17.3 ms/frame (10.35 s) | 1.1 s | 5.1-6.0 s | 9.2 s |
| 4K + SSAA | 17.3 ms/frame (10.38 s) | 1.35 s | 6.7 s | 9.2 s |
The three stages run on separate threads and overlap, so wall time equals the slowest one: the encoder at ~15 ms/frame for 4K. Rendering is ~1.8 ms/frame
and SSAA costs ~0.4 ms more. Changing the swscale flags (bilinear, fast-bilinear, no accurate-rounding) did not move the total, which confirms it.
Conclusion: there is nothing to gain by optimising the render path on this machine; 4K speed is set by VideoToolbox. A different encoder (libx264
`ultrafast`, for example) is the only lever. The 1080p run of the 5396-frame replay was 23.6 s, 4.4 ms/frame, which is real-time x 11 at 60 fps.

### All 32 emulator eras checked through the replay-playback path (`/flashback dev eras`)
The command applies each version via `ModIntegrations.applyForReplay` (the same call playback makes from recorded metadata), reads it back, and asks
the emulator's `HudHelper.hideHungerBar/hideExperienceBar`, then restores the previous session. Result: 32/32 applied and read back exactly.
No hunger/XP: alpha, alpha_1_0_x, alpha_1_1_0, alpha_1_2_0, infdev_420/611, beta_1_0 to beta_1_6, beta_1_7_3. Hunger + XP: beta_1_8_1, beta_1_9_pre_1-6,
release_1_0_0, 1_1, 1_2_5, 1_3, 1_4, 1_5, 1_6_4. This replaces the earlier "assumed" status. Still only Beta 1.7.3 and Release 1.6.4 were recorded and
rendered end to end; the others are covered by the state/gate check, not by pixels.
Emulator build: its `build.gradle` has a local, uncommitted edit that removes the `java.toolchain` JDK 21 pin (no JDK 21 here; `options.release = 21`
already pins the bytecode). Recommend keeping it local: committing it would change that repo's build rules for a problem specific to this machine.
Environment note: the shell had a stale `JAVA_HOME`; use /Library/Java/JavaVirtualMachines/jdk-25.0.2.jdk/Contents/Home.

### Crafting table and furnace recorded and replayed (real clicks through `container.click`)
Recorded 3407 ticks in `FlashbackTest` (creative, items via `command.run give`), opening a crafting table and a furnace, clicking slots with the bridge
(`container.open/click/close`; slot indices: crafting 0 result, 1-9 grid, 37+ hotbar; furnace 0 input, 1 fuel, 2 output). Bridge lesson: `chat.send` does not
reach the server command dispatcher; use `command.run` for `give/tp/setblock/time`.
Played back through the recorded player (spectate): the Crafting and Furnace screens are drawn, and `container.state` in the replay reports per tick:
- crafting: grid filled by client prediction only (3 iron ingots + 2 sticks) is present in the replay, the result slot shows `iron_pickaxe`, then empties
  when the result is taken; after planks (log -> 4 planks, shift-click) the inventory shows them.
- furnace: coal in the fuel slot, `raw_iron` in the input (counts 8 -> 7 -> 6 as it smelts), `iron_ingot` in the output once smelted, flames and progress arrow drawn.
- seeking into the middle of either container's life (700 -> 1000, 3300 -> 1000, 700 -> 2700) lands with the right container open and its contents intact.
Not covered: chest with items being moved, villager trade, enchanting/anvil/brewing, hopper/dispenser layouts, and Beta containers whose layout differs from modern.
Test-tool lesson: the screenshot size from `vision.screenshot` varies (1708x960 vs 732x412), so a pixel-colour panel detector gave false negatives; trust `container.state`.

### Emulator update (8b0f30a) installed; replay now gets era textures and GUI rules; Mod Menu 11.0.5 installed
- `run/mods` now has the freshly built emulator jar and Mod Menu 11.0.5 (the version the emulator pins). Texture auto-download works live: pinned packs fetched from
  Modrinth, stack `vanilla+programmer_art+continuation+golden_days_base` applied in a Beta 1.7.3 world.
- BUG FIXED: a replay applied only the emulator's session value, so the texture plan resolved to "none" (`-> colormaps:none`) and the recipe-book button
  showed on replayed screens. `ModIntegrations.writeEmulatorVersion` now calls `HistoricalClientRules.applySyncedVersion/clearSyncedVersion` (falls back to the
  session setter on older emulator builds). After it: replay log shows `Historical textures active: stack=...golden_days_base`, no recipe button.
- Beta crafting table and chest replay with the Beta panel style; the survival inventory replays too (armour, hotbar, bread visible).
- FIXED: the replay survival inventory's character preview was a black box; new `MixinInventoryScreenPreview` draws the recorded player there (armoured model verified in replay). All `devtools/checks` suites still pass.
- Hotbar toggle (`recordHotbar`) vs GUI recording: independent data paths (see below); two scripted sessions (selection/give/clear/drop, and inventory-menu moves)
  replayed with a HUD hotbar identical to the live one.

### Chest item moves and villager trade replay (modern world, `FlashbackTest`)
Recorded 1324 ticks: chest with diamond / golden apple / iron sword (moves, shift-click, partial stack), then a Librarian trade (2 offers) with a real-mouse
click selecting the diamond offer and shift-click buying it. Replayed through the recorded player: the chest screen (items in their moved slots) and the
merchant screen (both offer rows, "Librarian - Apprentice" title, trade slots, inventory) are drawn. Lesson: `container.state` sampled right after a seek can
report "closed" while the frame already shows the container; judge by screenshot, with 2+ s settle.
Bridge note: trade selection is a screen button, not a slot click - use a real click (`devtools/mac/input click X Y`, screen points).

### Editor UI: the export window now shows what the export will be
New line above Start Export: `Result: 60.0 s, 3597 frames, about 150 MB` (length, frame count, estimated size at the chosen bitrate; size is hidden for codecs
without rate control and for "maximum bitrate"). The count comes from the exporter's own tick planner (`ExportJob.countExportFrames`), so speed/freeze/timelapse
tracks and cuts are included, and it is recomputed only when the editor's modCount, range or framerate changes. Verified: the 1.5 min replay shows 1:30.0 / 5397
frames / 225 MB; after `cut 100 700` it shows 60.0 s / 3597 frames; the real export of that setup is 3597 frames, 59.95 s (ffprobe). Range resolution moved into
one helper shared by the summary and the export button so the two cannot disagree.
Dev command: `/flashback dev exportwindow` opens the window. Screenshot tip: `vision.screenshot` captures only the game framebuffer; use `screencapture -x` to see ImGui.

### Era texture switching in replays (one era per replay - confirmed by the emulator's author)
An era is a property of the world, so a replay can only be of the era it was recorded in: record in that era's world, exit (the replay saves), then open the replay
from any world. Ad-hoc switching of the era inside one open replay is not meaningful and was removed.
- Beta 1.7.3 (`EraBeta`): replay applies `vanilla+programmer_art+continuation+golden_days_base`, Beta-style crafting table / chest / survival inventory (with the
  recorded player's armoured model in the preview), toasts in the Beta grey.
- Release 1.6.4 (`EraRel`): live stack `vanilla+programmer_art+continuation`; replay opened from a modern world logs `Applied recorded version-emulator version
  ...release_1_6_4` then `Historical textures active: stack=vanilla+programmer_art+continuation|...`, and the frames show the Release HUD (hearts, armour above,
  hunger, XP bar) and the 1.6.4 crafting panel.
- Not yet recorded: Alpha, Infdev, and Release 1.0-1.5 worlds (they need their own `level-type=historical_experience:<id>` world each).
- Setup lesson: respawn/daylight - a survival test in a dark Release spawn got the player killed by mobs; set `difficulty peaceful` and `time set day` first.

### Release 1.0.0 and Alpha 1.0.17 recorded and replayed (world upgraded in place with `/hex upgrade`, one era per replay)
Flow used (matches how the emulator works): in the test world run `/hex upgrade <version>`, exit (the staged generator is written on server stop), relaunch the
world (now that era), record the standard session (crafting table, chest, survival inventory), exit, open the replay from a modern world (`FlashbackTest`).
- Release 1.0.0: replay log `Applied recorded version-emulator version ...release_1_0_0` then stack `vanilla+programmer_art+continuation+golden_days_base`; hearts +
  armour row + hunger drumsticks, crafting panel in the old style, chest/crafting-table textures of the era.
- Alpha 1.0.17: stack adds `golden_days_alpha`; the replay shows the "Minecraft Alpha v1.0.17" corner label, hearts only with the Alpha-style armour row to the right, no
  hunger bar, no XP bar, Alpha chest/crafting table, Alpha crafting screen. A 1080p60 export of it (1521 frames, 1920x1080) keeps all of that and has 0 dark frames of
  305 sampled.
- Eras with recorded + replayed evidence now: Beta 1.7.3, Release 1.0.0, Release 1.6.4, Alpha 1.0.17.

### Hotbar now dims with the background when a recorded container is open
Reported: in a replay the hotbar stayed bright while the world behind a container screen was darkened. Cause: the replayed screen was drawn in the HUD pass without
clearing depth, so the hotbar's item sprites (drawn at a higher z) sat in front of the screen's dimming. A real screen is drawn after the HUD with depth cleared. Fix
in `GuiDisplay.render`: flush the HUD batch and clear depth before `renderWithTooltip`. Verified live (hotbar, hearts, armour, food and XP bar all dimmed) and in a
1080p export frame with the crafting panel open.
Also verified one-era-per-replay on three more versions (record in an upgraded world, replay from a modern one): Infdev 420 ("Minecraft Infdev" label, Alpha stack),
Beta 1.3 (Beta stack), Release 1.5 (Release stack). Eras with recorded evidence: Alpha 1.0.17, Infdev 420, Beta 1.3, Beta 1.7.3, Release 1.0.0, 1.5, 1.6.4.

### Editor UI: one-click export presets
The export window opens with a "Quick Presets" row: 1080p60 (MP4, 20 Mbps), 4K60 (60 Mbps), Vertical 1080x1920 (16 Mbps, Shorts/Reels/TikTok), Quick Draft
720p30 (8 Mbps). Each sets resolution, frame rate, container and bitrate together (codec/encoder back to the container default, "maximum bitrate" off) and leaves
the range, audio and projection alone. Each has a tooltip stating exactly what it sets. Verified with real mouse clicks in the live editor: 4K60 -> 3840x2160 / 60 / 60m,
Vertical -> 1080x1920 / 60 / 16m. The result summary below updates with the new numbers. Beta 1.3 and Release 1.5 replay frames reviewed: Beta label + Beta stack
with armour row; Release HUD with hunger and XP.

### Render optimisation, round 2: a heavy entity scene (130 armoured villagers + 130 armoured stands, 1385 frames, h264_videotoolbox)
| | total | render | sws_scale thread | encode thread |
|---|---|---|---|---|
| 1080p60 | 11.0 ms/frame (15.3 s) | 10.7 s | 3.2 s | 0.3 s |
| 4K60 | 16.4 ms/frame (22.8 s) | 11.1 s | 14.1 s | 20.7 s |
| 4K60 + SSAA | 16.5 ms/frame | 11.4 s | 14.5 s | 19.3 s |
Compared with the light scene (1080p 4.4 ms/frame, render 1.3 ms/frame) the heavy scene is **render-bound at 1080p** (render ~7.7 ms/frame) and flips to
encoder-bound at 4K, where the encoder (~15 ms/frame) again hides the render cost, and SSAA costs nothing extra. So 4K wall time is set by VideoToolbox, 1080p
wall time by rendering. Sampling the render thread's stack during a 1080p export (60 `jstack` samples): 36 in entity rendering (`ModelPart.compile` /
`LivingEntityRenderer`, from vanilla's per-bone model compile) and 20 in Flashback's own export code (readback/blit), 1 in terrain. The entity cost is vanilla's per-entity model
submission and scales with the number of entities in view; Flashback adds nothing to it. No Flashback-side change was made, because the measurable cost is in vanilla's
entity renderers; the practical lever for creators is the Render Filter (hide entities) and fewer visible entities in the shot.
Dev tooling: `/flashback dev resolution <w> <h> <ssaa>`; per-phase timing is in the `Export timing` and `Writer timing` log lines.

### Export presets verified end to end
Real mouse click on Quick Draft in the export window (after closing the leftover "Export has successfully finished" popup, which swallows clicks while open): fields
become 1280x720 / 30 / 8m; the following export is 1280x720, 30 fps, 694 frames, 23.1 s, ~10 MB (vs 32 MB for the 1080p60 export of the same range).
Test-tool lesson: `devtools/mac/input` takes screen *points*, not screenshot pixels (the Retina screenshot is 1.7x larger), and the game window must be frontmost.

### BUG FIXED: the first-person hand and held item missing in spectate playback
Reported: "a lot of the time the hands don't render, nor the items" when watching as the recorded player. Reproduced with the dev status field `spectatedAttackScale`:
a replayed player is created fresh, so `Player.getAttackStrengthScale` starts near 0 (0.08 at tick 0) and only recovers while replay ticks run. The spectate hand
logic (`MixinItemInHandRenderer.tick`) raised the hand toward `attackStrength^3`, so whenever the replay was paused or just seeked near the start (every freshly
opened replay, every scrub to the beginning) the hand sat at height ~0 - arm and item hidden - until playback ran for a second or so; that is "a lot of the time".
A second contributor: after a seek the hand animated from wherever it was instead of showing the item the player holds at the new time.
Fix: the hand height now ignores the attack cooldown (the recording cannot reproduce it anyway) and follows only whether the held item changed, so a swap still
lowers and raises; any jump in replay time (backwards, or more than 3 ticks) snaps the hand to the player's current items. Verified with the same dev status
field and screenshots: tick 0, 1, 2, 3 paused after seeks all show the sword; random rapid scrubs show the correct item for the slot; a mining recording plays with
the pickaxe visible and swinging while breaking stone, and the swap to the sword at the end appears. New dev status fields: `spectatedAttackScale`, `spectatedMainHand`.
Build environment settled: JDK 21 (Temurin) installed under `~/Library/Java/JavaVirtualMachines` and registered in `~/.gradle/gradle.properties`, so the emulator's
`java.toolchain` pin builds unmodified and its working tree is clean (no local edits left). Run gradle with `sh ./gradlew` (the emulator's wrapper is not executable).

### Hand fix confirmed through the real timeline UI
Real mouse clicks on the editor's skip-to-start button and on the ruler at five positions (ticks 28, 50, 114, 167, 247 of a mining recording) each landed on the
clicked tick and showed the pickaxe in the recorded player's hand, with the attack-cooldown field reading 1.0 (the first-click value at tick 0 was the case that used to hide the hand).
The earlier "ruler clicks do nothing" observation was mis-aimed clicks (the screen-point to screenshot-pixel factor on this Mac is 1.728), not an editor fault.
Coordinates that work on this display for `devtools/mac/input` (screen points): window origin (437, 290); skip-to-start button ~(478, 590); ruler y ~596 with 00:00 at x ~706.

### BUGS FIXED: containers vanished when scrubbing forwards, and moves into a container were not in the recording
Found by recording all 13 remaining container types (enchanting table, anvil, brewing stand, hopper, dispenser, shulker box, smithing table, loom, stonecutter, cartography
table, grindstone, blast furnace, barrel): live all 13 opened, and the replay drew all 13 with their own screens, titles and contents (e.g. dispenser items, shulker diamonds,
barrel emerald, hopper golden apple) - when jumped into directly. Then an enchanting session with real clicks exposed two separate faults:
1. **Forward jumps dropped an open container.** Any jump over more than one tick tore the interface down (`GuiDisplayForwarder.reset`) because the packet that closes it
   may be skipped - but the packet that *opened* it is skipped as well when it lies before the start of the jump, so a container open both before and after the jump was gone
   (scrub 150 -> 300 inside a 220-tick window: panel gone; 300 -> 150 backwards: fine). Fix: the forwarder now keeps a model of the open container (open payload, contents,
   cursor, merchant offers), updated from everything that reaches the viewer; a forward jump re-sends that model after the reset, a backward jump clears it as before (the
   replay re-opens it by playing the recording again). Verified: 13/13 containers shown when jumping straight into each, and the enchanting container is on screen at
   ticks 150, 300, 310, 500, 1000 and 1200 of a 1409-tick clip and gone before it opens and after it closes.
2. **Items moved into a container by the player were never recorded.** A click sends the server a hash of what the client now believes and the server files it as known, so
   it is never sent back - for every slot of every container, not just the player's own. Recording only the player's own slots (an earlier decision, made to stop a stale client
   value overwriting a server answer) left the sword and lapis out of the enchanting table: the replay showed an empty table all through the session. The cause of the
   earlier chest problem was ordering (the mirror takes the server's value before the client applies it), not which slots were mirrored, so every slot is mirrored again and a slot the
   server has just described is skipped for 4 ticks. Verified with a fresh enchanting recording (sword and lapis appear in the table, lapis 10 -> 8 after enchanting, the sword
   leaves the hotbar) and a chest recording with six different moves (single stack, shift-click, right-click split, hotbar -> chest) whose replay shows each move at the
   right moment and the same final layout as the live chest (cobblestone slot 5, diamonds 12, emeralds 20, sword and golden apples in the player inventory).
`devtools/checks` ContainerGuiCheck updated to the new rule and a check that the forward-jump restore exists; all suites pass.
Test-tool lesson: a panel-colour detector on 4-pixel steps is reliable for "a container is on screen"; the earlier false negatives were this seek bug, not the detector.
