$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
. "$root\env.ps1"

$port = if ($env:MH_OPENCLAW_GATEWAY_PORT) { [int]$env:MH_OPENCLAW_GATEWAY_PORT } else { 18789 }
$existing = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "OpenClaw Gateway is already listening on 127.0.0.1:$port (PID $($existing[0].OwningProcess))."
    exit 0
}

$entry = Join-Path $root ".tools\openclaw-pkg\node_modules\openclaw\openclaw.mjs"
if (-not (Test-Path $entry)) {
    throw "OpenClaw entry not found: $entry"
}

Write-Host "Pre-starting persistent OpenClaw Gateway on 127.0.0.1:$port..."
& node $entry gateway run --port $port --bind loopback
