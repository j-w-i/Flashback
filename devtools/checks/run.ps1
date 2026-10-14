# Windows runner for the headless suites (mirror of run.sh). Usage: pwsh devtools/checks/run.ps1 [-NoBuild]
# Requires `gradlew compileJava` to have produced build/classes/java/main.
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
if (-not $NoBuild) { & ./gradlew.bat compileJava --console=plain -q 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'compileJava failed' } }
if (-not $NoBuild) { & ./gradlew.bat writeCheckClasspath -q --console=plain 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'writeCheckClasspath failed' } }
$mc = (Get-ChildItem '.gradle/loom-cache/minecraftMaven/net/minecraft' -Recurse -Filter 'minecraft-merged-*.jar' | Where-Object { $_.Name -notmatch 'backup' } | Select-Object -First 1).FullName
$libs = (Get-Content build/check-classpath.txt -Raw).Trim()
$gson = ''; $slf4j = ''
$out = 'devtools/checks/out'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory "$out/stubs", "$out/classes" -Force | Out-Null
(Get-ChildItem devtools/checks/stubs -Recurse -Filter *.java).FullName | Set-Content "$out/stub-sources.txt"
(Get-ChildItem devtools/checks/src -Recurse -Filter *.java).FullName | Set-Content "$out/sources.txt"
$cp = "build/classes/java/main;$mc"
Set-Content "$out/stub.args" ("-cp `"" + ("$cp;$libs").Replace("\","/") + "`"")
& javac -nowarn "@$out/stub.args" -d "$out/stubs" "@$out/stub-sources.txt"; if ($LASTEXITCODE) { throw 'stub compile failed' }
Set-Content "$out/javac.args" ("-cp `"" + ("$out/stubs;$cp;$libs").Replace("\","/") + "`"")
& javac -nowarn "@$out/javac.args" -d "$out/classes" "@$out/sources.txt"; if ($LASTEXITCODE) { throw 'test compile failed' }
$run = "$out/stubs;$out/classes;build/classes/java/main;$mc;$libs"
$failures = 0
Set-Content "$out/run.args" ("-cp `"" + $run.Replace("\","/") + "`"")
function Run-Check([string]$name, [string[]]$jargs) {
    $o = & java "@$out/run.args" @jargs 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    '{0,-46} {1}' -f ($name.Split('.')[-1]), ($o | Select-Object -Last 1)
    if ($code -ne 0) { $script:failures++; $o | Where-Object { $_ -match '^(FAIL|FAILURES|Exception|.*Error)' } | Select-Object -First 8 }
}
foreach ($s in 'state.MigrationTest','state.EvaluationTest','state.CameraObjectTest','state.CameraInspectorRegressionTest','state.CutTest','state.LoadCompatTest',
    'editor.ui.timeline.LayoutTest','keyframe.OrbitTest','keybind.ScrollBindingsTest','gui.GuiLogTest','gui.ContainerWireTest','playback.PlaybackTimingTest',
    'playback.AccuratePositionTimelineTest','playback.WeatherColumnOrientationTest','playback.WeatherTextureCoordinatesTest') {
    Run-Check $s @("com.moulberry.flashback.$s")
}
Run-Check 'ImGuiPairingCheck' @('com.moulberry.flashback.ImGuiPairingCheck','src/main/java/com/moulberry/flashback/editor/ui/windows/TimelineWindow.java','src/main/java/com/moulberry/flashback/editor/ui/windows/CameraInspectorWindow.java')
Run-Check 'ContainerGuiCheck' @('com.moulberry.flashback.gui.ContainerGuiCheck','src/main/java')
Run-Check 'MixinTargetCheck' @('com.moulberry.flashback.MixinTargetCheck','src/main/java')
$inv = 'build/replay-inventory-check'
New-Item -ItemType Directory $inv -Force | Out-Null
& javac -d $inv src/main/java/com/moulberry/flashback/gui/InventoryMenuSlots.java devtools/checks/ReplayInventorySlotsTest.java
Run-Check 'ReplayInventorySlotsTest' @('-cp', $inv, 'ReplayInventorySlotsTest')
if ($failures) { Write-Output "$failures suite(s) failed"; exit 1 }



