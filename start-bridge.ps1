$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
. "$root\env.ps1"

$bridgeDir = Join-Path $root "bridge"
if (-not (Test-Path (Join-Path $bridgeDir "node_modules"))) {
    Write-Host "Installing Bridge dependencies..."
    Push-Location $bridgeDir
    npm install
    Pop-Location
}
if (-not (Test-Path (Join-Path $bridgeDir "dist\server.js"))) {
    Write-Host "Building MinecraftHarness bridge..."
    Push-Location $bridgeDir
    npm run build
    Pop-Location
}

Write-Host "Starting MinecraftHarness Bridge..."
& node (Join-Path $bridgeDir "dist\server.js")
