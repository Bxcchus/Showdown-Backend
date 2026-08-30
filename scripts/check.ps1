[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Resolve-Path (Join-Path $PSScriptRoot '..')

Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'test-local-security.ps1')

    mvn --batch-mode --no-transfer-progress verify
    if ($LASTEXITCODE -ne 0) {
        throw "Maven verification failed with exit code $LASTEXITCODE"
    }

    docker compose --env-file infra/.env.example -f infra/compose.yml config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose validation failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
