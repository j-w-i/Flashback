# Calls the MCPFabric bridge (dev-only driver for runtime testing). Usage: . devtools/mcp.ps1; Rpc 'info.capabilities' @{}
function Rpc([string]$method, $params = @{}) {
    $cfg = Get-Content (Join-Path $PSScriptRoot '../run/config/mcpfabric.config.json') -Raw | ConvertFrom-Json
    $body = @{ jsonrpc = '2.0'; id = 1; method = $method; params = $params } | ConvertTo-Json -Depth 10 -Compress
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:$($cfg.port)/rpc" -Method Post -ContentType 'application/json' -Headers @{ Authorization = "Bearer $($cfg.token)" } -Body $body -TimeoutSec 20
    if ($r.error) { throw ($r.error | ConvertTo-Json -Compress) }
    $r.result
}
