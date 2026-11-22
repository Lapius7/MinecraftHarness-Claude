param(
    [switch]$Build,      # Force full rebuild of PaperMC plugin + Bridge TypeScript
    [switch]$SkipBuild,  # Skip source timestamp check (still copies built plugin JAR)
    [switch]$StopOnly,   # Stop PaperMC, OpenClaw Gateway, and Bridge without restarting
    [switch]$NoWait,     # Launch windows and exit immediately without polling readiness
    [switch]$Direct      # Run Bridge in standalone Direct Mode without OpenClaw Gateway
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not $root) {
    $root = (Get-Location).Path
}
. "$root\env.ps1"

$openclawEntry = Join-Path $root ".tools\openclaw-pkg\node_modules\openclaw\openclaw.mjs"
$useGateway = (-not $Direct) -and ($env:MH_AGENT_MODE -ne "direct")
if ($useGateway -and -not (Test-Path $openclawEntry)) {
    Write-Host "  OpenClaw runtime not found at .tools\openclaw-pkg — falling back to Direct Mode." -ForegroundColor Yellow
    $useGateway = $false
}

$mcPort    = 25565
$wsPort    = 25580
$rpcPort   = 25581
$proxyPort = 25582
$gwPort    = if ($env:MH_OPENCLAW_GATEWAY_PORT) { [int]$env:MH_OPENCLAW_GATEWAY_PORT } else { 18789 }

function Test-LocalPortOpen([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return ($null -ne $conn)
}

function Get-PortOwnerPids([int[]]$Ports) {
    $conns = Get-NetTCPConnection -LocalPort $Ports -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) { return @() }
    return @($conns | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 4 })
}

function Stop-ProcessTreeForce([int]$TargetPid, [string]$Label) {
    if (-not (Get-Process -Id $TargetPid -ErrorAction SilentlyContinue)) { return }
    Write-Host "  Stopping $Label (PID $TargetPid)..." -ForegroundColor Yellow
    & taskkill.exe /T /F /PID $TargetPid 2>$null | Out-Null
}

Write-Host "=== [1/4] Stopping running MinecraftHarness services ===" -ForegroundColor Cyan

# 1a. Try graceful PaperMC save & shutdown via Bridge /admin/shutdown-mc first
$paperPids = [System.Collections.Generic.HashSet[int]]::new()
foreach ($id in (Get-PortOwnerPids @($mcPort, $wsPort))) { [void]$paperPids.Add($id) }
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and $_.CommandLine.IndexOf($root, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.CommandLine -match "paper-1\.21\.4") {
        [void]$paperPids.Add([int]$_.ProcessId)
    }
}

if ($paperPids.Count -gt 0 -and (Test-LocalPortOpen $rpcPort)) {
    try {
        Write-Host "  Requesting graceful PaperMC save & shutdown via Bridge..." -ForegroundColor Yellow
        Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$rpcPort/admin/shutdown-mc" -TimeoutSec 4 | Out-Null
    } catch {
        # Older Bridge/Plugin may not expose /admin/shutdown-mc yet; fallback below handles it
    }
}

# Wait up to 12s for PaperMC to exit gracefully; otherwise try non-forced taskkill then forced taskkill
if ($paperPids.Count -gt 0) {
    foreach ($id in @($paperPids)) {
        if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
            & taskkill.exe /PID $id 2>$null | Out-Null
        }
    }
    $swPaper = [Diagnostics.Stopwatch]::StartNew()
    while ($swPaper.Elapsed.TotalSeconds -lt 12) {
        $alive = @($paperPids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
        if ($alive.Count -eq 0) { break }
        Start-Sleep -Milliseconds 400
    }
    foreach ($id in @($paperPids)) {
        Stop-ProcessTreeForce -TargetPid $id -Label "PaperMC"
    }
}

# 1b. Stop Bridge (:25581 / :25582)
$bridgePids = [System.Collections.Generic.HashSet[int]]::new()
foreach ($id in (Get-PortOwnerPids @($rpcPort, $proxyPort))) { [void]$bridgePids.Add($id) }
Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and $_.CommandLine.IndexOf($root, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.CommandLine -match "bridge[\\/]dist[\\/]server\.js") {
        [void]$bridgePids.Add([int]$_.ProcessId)
    }
}
foreach ($id in @($bridgePids)) {
    Stop-ProcessTreeForce -TargetPid $id -Label "Bridge"
}

# 1c. Stop OpenClaw Gateway (:18789)
$gwPids = [System.Collections.Generic.HashSet[int]]::new()
foreach ($id in (Get-PortOwnerPids @($gwPort))) { [void]$gwPids.Add($id) }
Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.CommandLine -and $_.CommandLine.IndexOf($root, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.CommandLine -match "openclaw\.mjs.*gateway") {
        [void]$gwPids.Add([int]$_.ProcessId)
    }
}
foreach ($id in @($gwPids)) {
    Stop-ProcessTreeForce -TargetPid $id -Label "OpenClaw Gateway"
}

# Wait until all 5 ports are free
$allPorts = @($mcPort, $wsPort, $rpcPort, $proxyPort, $gwPort)
$swPorts = [Diagnostics.Stopwatch]::StartNew()
while ($swPorts.Elapsed.TotalSeconds -lt 15) {
    $stillBusy = Get-PortOwnerPids $allPorts
    if ($stillBusy.Count -eq 0) { break }
    Start-Sleep -Milliseconds 300
}
Write-Host "  All previous MinecraftHarness processes stopped cleanly." -ForegroundColor Green

if ($StopOnly) {
    Write-Host "StopOnly complete." -ForegroundColor Green
    exit 0
}

Write-Host "=== [2/4] Checking & reloading builds ===" -ForegroundColor Cyan

$pluginDir = Join-Path $root "plugin"
$gradleText = Get-Content -Raw (Join-Path $pluginDir "build.gradle.kts")
$pluginVer = if ($gradleText -match 'version\s*=\s*"([^"]+)"') { $Matches[1] } else { "0.1.1" }
$jarName   = "MinecraftHarness-$pluginVer.jar"
$builtJar  = Join-Path $pluginDir "build\libs\$jarName"
$pluginsDir = Join-Path $root "server\plugins"
$destJar   = Join-Path $pluginsDir $jarName

$needPluginBuild = $Build -or (-not (Test-Path $builtJar))
if (-not $needPluginBuild -and -not $SkipBuild) {
    $jarTime = (Get-Item $builtJar).LastWriteTimeUtc
    $newerJava = Get-ChildItem -Path (Join-Path $pluginDir "src") -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -gt $jarTime } | Select-Object -First 1
    if ($newerJava) { $needPluginBuild = $true }
}

if ($needPluginBuild) {
    Write-Host "  Building PaperMC plugin (Gradle)..." -ForegroundColor Yellow
    Push-Location $pluginDir
    try {
        & .\gradlew.bat build --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Plugin Gradle build failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "  PaperMC plugin JAR is up-to-date." -ForegroundColor Gray
}

New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
Get-ChildItem -Path $pluginsDir -Filter "MinecraftHarness-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $jarName } | Remove-Item -Force
Copy-Item -Force -Path $builtJar -Destination $destJar
Write-Host "  Synced $jarName -> server\plugins\" -ForegroundColor Green

$bridgeDir    = Join-Path $root "bridge"
if (-not (Test-Path (Join-Path $bridgeDir "node_modules"))) {
    Write-Host "  Installing Bridge npm dependencies..." -ForegroundColor Yellow
    Push-Location $bridgeDir
    try {
        & npm install
        if ($LASTEXITCODE -ne 0) { throw "Bridge npm install failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}
$bridgeServer = Join-Path $bridgeDir "dist\server.js"
$needBridgeBuild = $Build -or (-not (Test-Path $bridgeServer))
if (-not $needBridgeBuild -and -not $SkipBuild) {
    $distTime = (Get-Item $bridgeServer).LastWriteTimeUtc
    $newerTs = Get-ChildItem -Path (Join-Path $bridgeDir "src") -Recurse -Filter "*.ts" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -gt $distTime } | Select-Object -First 1
    if ($newerTs) { $needBridgeBuild = $true }
}

if ($needBridgeBuild) {
    Write-Host "  Building Bridge TypeScript..." -ForegroundColor Yellow
    Push-Location $bridgeDir
    try {
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw "Bridge npm build failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "  Bridge dist\server.js is up-to-date." -ForegroundColor Gray
}

if ($useGateway) {
    Write-Host "=== [3/4] Launching PaperMC + OpenClaw Gateway + Bridge in parallel ===" -ForegroundColor Cyan
} else {
    Write-Host "=== [3/4] Launching PaperMC + Bridge (Direct Mode) in parallel ===" -ForegroundColor Cyan
}

$psExe = (Get-Command powershell.exe -ErrorAction SilentlyContinue).Source
if (-not $psExe) { $psExe = "powershell" }

# Window 1: PaperMC 1.21.4
$paperCmd = "`$Host.UI.RawUI.WindowTitle = 'MinecraftHarness - PaperMC (:25565)'; Set-Location '$root'; & '$root\server\start-server.ps1'"
Start-Process -FilePath $psExe -WorkingDirectory $root -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $paperCmd) | Out-Null
Write-Host "  -> Spawned Window 1: PaperMC Server (:25565 / WS :25580)" -ForegroundColor Green

# Optional Window 2: Persistent OpenClaw Gateway (only when MH_AGENT_MODE=openclaw or -WithGateway)
if ($useGateway) {
    $gwCmd = "`$Host.UI.RawUI.WindowTitle = 'MinecraftHarness - OpenClaw Gateway (:$gwPort)'; Set-Location '$root'; & '$root\start-gateway.ps1'"
    Start-Process -FilePath $psExe -WorkingDirectory $root -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $gwCmd) | Out-Null
    Write-Host "  -> Spawned Window 2: OpenClaw Gateway (:$gwPort)" -ForegroundColor Green
}

# Bridge & Telemetry Dashboard
$modeEnv = if ($useGateway) { "`$env:MH_AGENT_MODE = 'openclaw';" } else { "`$env:MH_AGENT_MODE = 'direct';" }
$bridgeCmd = "`$Host.UI.RawUI.WindowTitle = 'MinecraftHarness - Bridge & Telemetry (:$rpcPort)'; $modeEnv `$env:MH_NO_AUTO_GATEWAY = '1'; Set-Location '$root'; & '$root\start-bridge.ps1'"
Start-Process -FilePath $psExe -WorkingDirectory $root -ArgumentList @("-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $bridgeCmd) | Out-Null
Write-Host "  -> Spawned Bridge & Telemetry Dashboard (http://127.0.0.1:$rpcPort/)" -ForegroundColor Green

if ($NoWait) {
    Write-Host "Launched services (-NoWait)." -ForegroundColor Cyan
    exit 0
}

Write-Host "=== [4/4] Waiting for services to become ready ===" -ForegroundColor Cyan
$readyBridge = $false
$readyGw     = -not $useGateway
$readyPaper  = $false
$readyWarm   = -not $useGateway
$swReady = [Diagnostics.Stopwatch]::StartNew()

while ($swReady.Elapsed.TotalSeconds -lt 150) {
    if (-not $readyBridge -and (Test-LocalPortOpen $rpcPort)) {
        $readyBridge = $true
        Write-Host ("  [OK +{0:N1}s] Bridge & Dashboard ready -> http://127.0.0.1:{1}/" -f $swReady.Elapsed.TotalSeconds, $rpcPort) -ForegroundColor Green
    }
    if (-not $readyGw -and (Test-LocalPortOpen $gwPort)) {
        $readyGw = $true
        Write-Host ("  [OK +{0:N1}s] OpenClaw Gateway listening -> http://127.0.0.1:{1}/ (pre-warming runtime...)" -f $swReady.Elapsed.TotalSeconds, $gwPort) -ForegroundColor Green
    }
    if ($readyBridge -and $readyGw -and -not $readyWarm) {
        try {
            $h = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$rpcPort/health" -TimeoutSec 2
            if ($h.gatewayWarm -eq $true) {
                $readyWarm = $true
                Write-Host ("  [OK +{0:N1}s] OpenClaw Agent & HTTP MCP pre-warmed (0ms cold-start!)" -f $swReady.Elapsed.TotalSeconds) -ForegroundColor Green
            }
        } catch {}
    }
    if (-not $readyPaper -and (Test-LocalPortOpen $wsPort) -and (Test-LocalPortOpen $mcPort)) {
        $readyPaper = $true
        Write-Host ("  [OK +{0:N1}s] PaperMC 1.21.4 ready     -> localhost:{1} (WS :{2})" -f $swReady.Elapsed.TotalSeconds, $mcPort, $wsPort) -ForegroundColor Green
    }
    if ($readyBridge -and $readyGw -and $readyWarm -and $readyPaper) {
        break
    }
    Start-Sleep -Milliseconds 500
}

Write-Host ""
if ($readyBridge -and $readyGw -and $readyPaper) {
    Write-Host "MinecraftHarness is LIVE!" -ForegroundColor Cyan
    Write-Host "  - Minecraft Server : localhost:$mcPort"
    Write-Host "  - Telemetry Web UI : http://127.0.0.1:$rpcPort/"
    if ($useGateway) {
        Write-Host "  - OpenClaw Gateway : http://127.0.0.1:$gwPort/"
    } else {
        Write-Host "  - Agent Runtime    : Direct Mode (ultra-low latency, no Gateway needed)"
    }
} else {
    Write-Host "Warning: Timed out waiting for one or more ports (Bridge=$readyBridge, Gateway=$readyGw, PaperMC=$readyPaper). Check the spawned windows for logs." -ForegroundColor Yellow
}
