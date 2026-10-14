# Calls the MCPFabric bridge (dev-only driver for runtime testing). Usage: . devtools/mcp.ps1; Rpc 'info.capabilities' @{}
function Rpc([string]$method, $params = @{}) {
    $cfg = Get-Content (Join-Path $PSScriptRoot '../run/config/mcpfabric.config.json') -Raw | ConvertFrom-Json
    $body = @{ jsonrpc = '2.0'; id = 1; method = $method; params = $params } | ConvertTo-Json -Depth 10 -Compress
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:$($cfg.port)/rpc" -Method Post -ContentType 'application/json' -Headers @{ Authorization = "Bearer $($cfg.token)" } -Body $body -TimeoutSec 20
    if ($r.error) { throw ($r.error | ConvertTo-Json -Compress) }
    $r.result
}

function Shot([string]$path = "$PSScriptRoot/../run/shot.png") {
    try { $r = Rpc 'vision.screenshot'; [IO.File]::WriteAllBytes($path, [Convert]::FromBase64String($r.base64)); return $path } catch { }
    # not in a world: grab the window itself
    & pwsh -NoProfile -File "$PSScriptRoot/capture-client.ps1" -OutputPath ([IO.Path]::GetFullPath((Join-Path (Get-Location) $path)))
}

# --- real OS input into the game window (the bridge has no GUI click RPC) ---
if (-not ('Mc' -as [type])) { Add-Type @"
using System; using System.Runtime.InteropServices;
public static class Mc {
 [StructLayout(LayoutKind.Sequential)] public struct Pt { public int X, Y; }
 [StructLayout(LayoutKind.Sequential)] public struct Rc { public int L, T, R, B; }
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out Rc r);
 [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref Pt p);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 public static void Move(IntPtr h, double fx, double fy) { SetProcessDPIAware(); SetForegroundWindow(h); Rc r; GetClientRect(h, out r); Pt p = new Pt{X=(int)(r.R*fx), Y=(int)(r.B*fy)}; ClientToScreen(h, ref p); SetCursorPos(p.X, p.Y); }
 public static void Click(IntPtr h, double fx, double fy, bool right) { Move(h,fx,fy); System.Threading.Thread.Sleep(150); mouse_event(right?8u:2u,0,0,0,UIntPtr.Zero); System.Threading.Thread.Sleep(60); mouse_event(right?16u:4u,0,0,0,UIntPtr.Zero); }
 public static void Drag(IntPtr h, double fx, double fy, double tx, double ty) { Move(h,fx,fy); System.Threading.Thread.Sleep(100); mouse_event(2,0,0,0,UIntPtr.Zero); for(int i=1;i<=10;i++){ Move(h, fx+(tx-fx)*i/10, fy+(ty-fy)*i/10); System.Threading.Thread.Sleep(30);} mouse_event(4,0,0,0,UIntPtr.Zero); }
}
"@ }
Add-Type -AssemblyName System.Windows.Forms
function McWin { Get-Process java | ? { $_.MainWindowTitle -like 'Minecraft*' -and $_.MainWindowTitle -match '1\.21\.1' } | select -first 1 }
# Click at a fraction (0..1) of the client area. Fractions come straight from a Shot (854x480 => px/854, px/480).
function Click([double]$fx, [double]$fy, [switch]$Right) { [Mc]::Click((McWin).MainWindowHandle, $fx, $fy, [bool]$Right) }
function Drag([double]$fx, [double]$fy, [double]$tx, [double]$ty) { [Mc]::Drag((McWin).MainWindowHandle, $fx, $fy, $tx, $ty) }
function Key([string]$sendKeys) { [Mc]::SetForegroundWindow((McWin).MainWindowHandle) | Out-Null; Start-Sleep -Milliseconds 200; [System.Windows.Forms.SendKeys]::SendWait($sendKeys) }

