$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. "$root\env.ps1"

$serverDir = $PSScriptRoot
$paperJar = Join-Path $serverDir "paper-1.21.4-232.jar"
if (-not (Test-Path $paperJar)) {
    & "$PSScriptRoot\setup-server.ps1"
}

$builtJar = Join-Path $root "plugin\build\libs\MinecraftHarness-0.1.0.jar"
$destJar = Join-Path $serverDir "plugins\MinecraftHarness-0.1.0.jar"
if (Test-Path $builtJar) {
    Copy-Item -Path $builtJar -Destination $destJar -Force
}

Set-Location $serverDir
& java "-Djava.io.tmpdir=$root\.tools\tmp" -Xms2G -Xmx4G -jar $paperJar nogui
