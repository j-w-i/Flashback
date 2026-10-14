# Flashback — 26.3 audit progress record

Working branch: `audit/26.3` (git worktree at `C:\Users\jwalr\Flashback-26.3`, branched from fork `master` = `076b96b`).
The original checkout `C:\Users\jwalr\Flashback` is on `port/minecraft-1.21.1` with ~210 uncommitted files
(a 1.21.1 back-port). It is **not touched** by this audit.

## Baseline facts (verified)
- Fork `master` targets Minecraft **26.3**, Fabric loader 0.19.5, Fabric API 0.161.0+26.3, Java 25, loom `net.fabricmc.fabric-loom` (no mappings; 26.x is unobfuscated).
- Upstream `Moulberry/Flashback` `master` (`bf046b76`, 0.43.6, 26.3) **is the merge-base** of the fork: the fork contains every upstream commit; nothing upstream is missing. (`git rev-list master..upstream/master` = 0.)
- Fork-specific delta vs upstream: 53 commits, ~23k added lines (camera objects/per-property tracks, cuts, timeline rewrite, container/GUI recording, weather rendering fixes, orbit camera, accurate-position timeline, ProRes/export tweaks reverted, devtools).
- `gradlew build` on 26.3: SUCCESS (baseline, before any change).
- Headless suites (`devtools/checks`): all pass on Windows via new `devtools/checks/run.ps1` (the original `run.sh` is macOS-only). 
- Runtime: `gradlew runClient` launches Minecraft 26.3 with Flashback 0.43.6.1; title screen renders; no Flashback/mixin errors in log (Realms/auth errors are dev-env noise).

## Subsystem audit status
(see sections below, filled in as work proceeds)
