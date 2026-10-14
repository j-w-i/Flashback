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
