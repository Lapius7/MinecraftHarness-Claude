$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
. "$root\env.ps1"

$serverDir = $PSScriptRoot
$paperJar = Join-Path $serverDir "paper-1.21.4-232.jar"
$paperUrl = "https://fill-data.papermc.io/v1/objects/5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc/paper-1.21.4-232.jar"

if (-not (Test-Path $paperJar)) {
    Write-Host "Downloading PaperMC 1.21.4 build #232..."
    curl.exe -sSL -A "MinecraftHarness/1.0" $paperUrl -o $paperJar
}

Set-Content -Path (Join-Path $serverDir "eula.txt") -Value "eula=true" -Encoding ASCII

$propsPath = Join-Path $serverDir "server.properties"
if (-not (Test-Path $propsPath)) {
    $props = @"
enable-command-block=true
spawn-protection=0
gamemode=creative
force-gamemode=false
difficulty=normal
online-mode=true
view-distance=12
simulation-distance=10
max-tick-time=-1
allow-flight=true
level-type=minecraft:flat
motd=MinecraftHarness - OpenClaw x Cerebras
"@
    Set-Content -Path $propsPath -Value $props -Encoding ASCII
}

$pluginsDir = Join-Path $serverDir "plugins"
if (-not (Test-Path $pluginsDir)) {
    New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
}

$builtJar = Join-Path $root "plugin\build\libs\MinecraftHarness-0.1.0.jar"
if (Test-Path $builtJar) {
    Copy-Item -Path $builtJar -Destination (Join-Path $pluginsDir "MinecraftHarness-0.1.0.jar") -Force
    Write-Host "Copied MinecraftHarness-0.1.0.jar to server/plugins/"
} else {
    Write-Warning "Built plugin JAR not found at $builtJar. Build plugin/ first."
}
