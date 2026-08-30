[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$environmentFile = Join-Path $root 'infra/.env'
$secretManager = Join-Path $PSScriptRoot 'local-secrets.ps1'

if (-not (Test-Path -LiteralPath $environmentFile)) {
    & $secretManager -Mode Initialize
}
else {
    & $secretManager -Mode Validate
}

Push-Location $root
try {
    docker compose --env-file infra/.env -f infra/compose.yml up --build --detach --wait
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose startup failed with exit code $LASTEXITCODE"
    }
    docker compose --env-file infra/.env -f infra/compose.yml ps
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose status failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
