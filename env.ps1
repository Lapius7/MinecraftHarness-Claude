$scriptRoot = $PSScriptRoot
if (-not $scriptRoot) {
    $scriptRoot = (Get-Location).Path
}

$dirs = @(
    "$scriptRoot\.tools\tmp",
    "$scriptRoot\.tools\npm-cache",
    "$scriptRoot\.tools\gradle-home",
    "$scriptRoot\.tools\pnpm-store",
    "$scriptRoot\.tools\openclaw-pkg",
    "$scriptRoot\.openclaw-home\.openclaw"
)

foreach ($d in $dirs) {
    if (-not (Test-Path $d)) {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
}

$env:TEMP = "$scriptRoot\.tools\tmp"
$env:TMP = "$scriptRoot\.tools\tmp"
$env:NPM_CONFIG_CACHE = "$scriptRoot\.tools\npm-cache"
$env:GRADLE_USER_HOME = "$scriptRoot\.tools\gradle-home"
$env:OPENCLAW_HOME = "$scriptRoot\.openclaw-home"
$env:OPENCLAW_STATE_DIR = "$scriptRoot\.openclaw-home\.openclaw"
$env:OPENCLAW_CONFIG_PATH = "$scriptRoot\.openclaw-home\.openclaw\openclaw.json"

# Auto-bootstrap openclaw.json from config/openclaw.example.json on fresh clone
$exampleConfig = Join-Path $scriptRoot "config\openclaw.example.json"
if ((-not (Test-Path -LiteralPath $env:OPENCLAW_CONFIG_PATH)) -and (Test-Path -LiteralPath $exampleConfig)) {
    $wsPath = (Join-Path $scriptRoot "bridge\openclaw-workspace").Replace("\", "\\")
    $rawCfg = (Get-Content -Raw -LiteralPath $exampleConfig).Replace("__WORKSPACE_DIR__", $wsPath)
    Set-Content -LiteralPath $env:OPENCLAW_CONFIG_PATH -Value $rawCfg -Encoding UTF8
}

if (!$env:CMD_API_KEY -and (Test-Path -LiteralPath $env:OPENCLAW_CONFIG_PATH)) {
    $mhProviderConfig = Get-Content -Raw -LiteralPath $env:OPENCLAW_CONFIG_PATH | ConvertFrom-Json
    if ($mhProviderConfig.models.providers.commandcode) {
        $mhCommandCodeAuth = Join-Path $env:USERPROFILE ".commandcode/auth.json"
        if (Test-Path -LiteralPath $mhCommandCodeAuth) {
            $env:CMD_API_KEY = (Get-Content -Raw -LiteralPath $mhCommandCodeAuth | ConvertFrom-Json).apiKey
        }
    }
}
