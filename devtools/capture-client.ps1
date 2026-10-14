param([string]$OutputPath = 'run/client-test.png')
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ClientCapture {
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out Rect r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
'@
[ClientCapture]::SetProcessDPIAware() | Out-Null
$client = Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (!$client) { throw 'No Minecraft client window found' }
[ClientCapture]::SetForegroundWindow($client.MainWindowHandle) | Out-Null
$rect = New-Object ClientCapture+Rect
[ClientCapture]::GetWindowRect($client.MainWindowHandle, [ref]$rect) | Out-Null
$bitmap = New-Object System.Drawing.Bitmap ($rect.Right-$rect.Left),($rect.Bottom-$rect.Top)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
 $dc = $graphics.GetHdc()
 try { [ClientCapture]::PrintWindow($client.MainWindowHandle, $dc, 2) | Out-Null }
 finally { $graphics.ReleaseHdc($dc) }
 $bitmap.Save($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath), [System.Drawing.Imaging.ImageFormat]::Png)
} finally { $graphics.Dispose(); $bitmap.Dispose() }
Write-Output $OutputPath
