$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. "$root\env.ps1"

$serverDir = $PSScriptRoot
$paperJar = Join-Path $serverDir "paper-1.21.4-232.jar"
if (-not (Test-Path $paperJar)) {
    & "$PSScriptRoot\setup-server.ps1"
}

$pluginsDir = Join-Path $serverDir "plugins"
$builtJar = Get-ChildItem -Path (Join-Path $root "plugin\build\libs") -Filter "MinecraftHarness-*.jar" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($builtJar) {
    New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
    Get-ChildItem -Path $pluginsDir -Filter "MinecraftHarness-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne $builtJar.Name } | Remove-Item -Force
    Copy-Item -Path $builtJar.FullName -Destination (Join-Path $pluginsDir $builtJar.Name) -Force
}

Set-Location $serverDir
& java "-Djava.io.tmpdir=$root\.tools\tmp" -Xms2G -Xmx4G -jar $paperJar nogui
