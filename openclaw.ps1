. "$PSScriptRoot\env.ps1"
$openclawCmd = Join-Path $PSScriptRoot ".tools\openclaw-pkg\node_modules\.bin\openclaw.cmd"
if (-not (Test-Path $openclawCmd)) {
    Write-Error "openclaw.cmd not found at $openclawCmd"
    exit 1
}
& $openclawCmd @args
exit $LASTEXITCODE
