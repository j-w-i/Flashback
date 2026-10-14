# Handoff: continuing the Flashback 1.21.1 port on macOS

## Where things stand (as of 2026-10-15, written on Windows)

- Repo: `https://github.com/j-w-i/Flashback` (remote `origin`). Upstream is `https://github.com/Moulberry/Flashback`.
- `master` = the 26.3 fork (Minecraft 26.3, Java 25). It builds and runs; all headless suites pass.
- **`port/minecraft-1.21.1`** = the back-port to Minecraft **1.21.1** (Fabric, Java 21, Mojang mappings). This is the branch being worked on. The latest commit is a WIP snapshot.
- `audit/26.3` = a small branch off `master` holding Windows tooling (`devtools/checks/run.ps1`) and `docs/AUDIT_PROGRESS.md`. Only needed as reference.

### Verified in game on 1.21.1 (Windows, real client)
- Client launches with Flashback `0.43.6.1-1.21.1` and MCPFabric; no Flashback/mixin errors in the log.
- `/flashback start` records; `/flashback finish` opens the Save Replay dialog; saving produced a valid 4 MB `.zip`.
- The replay appears in the "Select Replay" screen with correct duration/thumbnail.

### NOT yet verified
- Opening/playing the replay, the editor UI, seeking, camera keyframes, timeline editing, export.
  (The last step in progress was clicking "Open Replay" and inspecting playback.)
- Any comparison of port-vs-26.3 behaviour beyond what compiles.
- Nothing from the original audit plan beyond baseline: see `docs/AUDIT_PROGRESS.md` (on `audit/26.3`).

## 1. Get the code on the Mac

```bash
git clone https://github.com/j-w-i/Flashback.git        # or: cd into your existing clone and `git fetch --all`
cd Flashback
git fetch --all
git checkout port/minecraft-1.21.1                       # <- the work branch (NOT master)
git pull
git remote add upstream https://github.com/Moulberry/Flashback.git   # only if missing
git fetch upstream
```

If the Mac clone is currently on `master` with local changes: `git stash` first, then checkout.
Do **not** commit the port onto `master`: `master` must stay the 26.3 build.

Optional reference checkout of the 26.3 fork next to it (lets you diff port vs. master):

```bash
git worktree add ../Flashback-26.3 master
```

## 2. Toolchain

- **Java 21** is what the port targets (`release = 21`). Java 25 also runs the build; the headless
  `devtools/checks/run.sh` hardcodes a macOS JDK 25 path, edit `JAVA_HOME` there if yours differs.
- Gradle wrapper is included (`./gradlew`), Gradle 9.5.
- Build: `./gradlew build` (first run downloads Minecraft + mappings; a few minutes).

## 3. Run the 1.21.1 client with remote control (MCPFabric)

MCPFabric lets the agent drive the real game (movement, chat commands, screenshots, inventory) over
a local HTTP JSON-RPC bridge. Without it the agent cannot test the GUI.

1. Download **MCPFabric 0.5.1 for 1.21.1** (fabric) from https://modrinth.com/mod/mcpfabric
   (file `mcpfabric-0.5.1+1.21.1.jar`, sha512 starts `65628ba3af55bd1b...`). Put it in `run/mods/`.
2. It requires **Fabric Loader >= 0.19.5**, but the port pins 0.16.14. Override it on the command
   line only (do not edit `gradle.properties`):

   ```bash
   ./gradlew runClient -Ploader_version=0.19.5 --args="--quickPlaySingleplayer FlashbackTest"
   ```
3. Create a test world first: copy any 1.21.1 world to `run/saves/FlashbackTest`
   (or launch once without `--args`, create a world named `FlashbackTest`, quit).
4. On first launch MCPFabric writes `run/config/mcpfabric.config.json` (host 127.0.0.1, port 25599,
   random bearer `token`). `run/` is git-ignored; the token never leaves the machine.

### Driving it

`devtools/mcp.ps1` is a **PowerShell** driver (works with `brew install powershell` -> `pwsh`).
Its mouse/keyboard helpers use Win32 and **do not work on macOS**; only the RPC part does.
On the Mac, the agent should call the bridge directly, for example:

```bash
TOKEN=$(python3 -c "import json;print(json.load(open('run/config/mcpfabric.config.json'))['token'])")
curl -s -X POST http://127.0.0.1:25599/rpc -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"player.getState","params":{}}'
```

Useful methods: `session.info`, `player.getState`, `control.setInput` (`forward`,`sprint`...),
`control.look` (`yaw`,`pitch`), `control.jumpOnce`, `chat.send` (a `/`-prefixed message runs as a
command, including client commands such as `/flashback start`), `command.run` (server command with
output), `world.setBlock`, `entities.summon`, `vision.screenshot` (base64 PNG; **only while in a
world**), `info.capabilities`.

Limits found so far, which shape how to test:
- The bridge has **no GUI click or key RPC**. Menus (Save Replay dialog, Select Replay, the editor)
  need real OS input. On macOS use `osascript`/`cliclick` (grant the terminal Accessibility
  permission), and use a screenshot of the window (`screencapture -l <windowid>`) when not in a world.
- Focus loss pauses the game (pause menu). Keep the window focused, or set `pauseOnLostFocus:false`
  in `run/options.txt`.
- Flashback client commands: `/flashback start | finish | end | pause | unpause | config | mark ...`.
  There is no `save`; `finish` opens the Save Replay dialog.

## 4. Headless checks

`devtools/checks/run.sh` is written for macOS (it also needs `build/classes/java/main`, so run
`./gradlew compileJava` first). On Windows the equivalent is `devtools/checks/run.ps1`; the shell
script uses PrismLauncher libs, so on the Mac either have those or point `GSON`/`LIBS` at the Gradle
cache. These suites were written against 26.3; expect some to need adjusting for 1.21.1.

## 5. Suggested prompt to give the agent on the Mac

> I'm continuing a Flashback audit/port. Read `docs/HANDOFF_MAC.md` and `docs/AUDIT_PROGRESS.md` in
> this repo first. I'm on branch `port/minecraft-1.21.1` (the Minecraft **1.21.1** back-port of the
> 26.3 fork on `master`); do not commit the port to `master`. Set up the toolchain as the handoff
> describes (Java 21, `./gradlew build`), install MCPFabric 0.5.1+1.21.1 into `run/mods`, and launch
> with `-Ploader_version=0.19.5`. Then continue from "NOT yet verified": open the saved replay in
> game and drive playback, seeking, the editor/timeline/camera keyframes and export through the
> MCPFabric bridge plus OS-level input, fixing bugs you find, committing small verified milestones
> to `port/minecraft-1.21.1`, and recording findings in `docs/AUDIT_PROGRESS.md`. Compare against
> `master` (26.3) and `upstream/1.21` / `upstream/1.21.11` where behaviour is in doubt, and keep the
> fork-specific features (camera objects, cuts, orbit camera, container GUI recording, weather fixes).

## 6. Things to be aware of

- The port branch's first commit is a large WIP snapshot (~210 files); diff against `master` to see
  what the port changed: `git diff master...port/minecraft-1.21.1 --stat`.
- A full local backup of the Windows working tree existed as a zip and a `refs/backup/...` ref on the
  Windows machine only; it is not pushed and not needed now that the branch is pushed.
- `deps/` gained `imgui-java-*-1.86.11` jars (the port uses imgui 1.86 for 1.21.1); `master` uses
  `imgui-binding-1.90.0.jar`. Both are tracked.
