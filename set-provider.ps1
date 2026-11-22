[CmdletBinding(DefaultParameterSetName='Select')]
param(
    [Parameter(Mandatory, Position=0, ParameterSetName='Select')][string]$Provider,
    [Parameter(ParameterSetName='Select')][string]$Model,
    [Parameter(ParameterSetName='Select')][string]$BaseUrl,
    [Parameter(ParameterSetName='Select')][string]$ApiKeyEnv,
    [Parameter(Mandatory, ParameterSetName='List')][switch]$List
)
if ($List) {
    & node "$PSScriptRoot/bridge/provider-config.mjs" --list
} else {
    & node "$PSScriptRoot/bridge/provider-config.mjs" $Provider "$Model" "$BaseUrl" "$ApiKeyEnv"
}
if ($LASTEXITCODE) { throw "Provider configuration failed" }
