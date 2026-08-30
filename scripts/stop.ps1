[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')

Push-Location $root
try {
    docker compose --env-file infra/.env -f infra/compose.yml down
}
finally {
    Pop-Location
}
