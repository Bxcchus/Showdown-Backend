[CmdletBinding()]
param(
    [switch]$Simulation,
    [string]$RiotId = 'Claude Code#JAVA',
    [switch]$Production,
    [string]$InstallationClientId,
    [string]$InstallationClientSecret,
    [string]$WebOrigins
)

$ErrorActionPreference = 'Stop'
$showdownDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$watcherDirectory = Join-Path $showdownDirectory 'watcher'
$dotenvPath = Join-Path $showdownDirectory 'infra\.env'

function Get-DotEnvValue {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match "^\s*$([Regex]::Escape($Name))\s*=\s*(.*)\s*$") {
            $value = $Matches[1].Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                return $value.Substring(1, $value.Length - 2)
            }
            return $value
        }
    }
    return $null
}

$managedEnvironment = @(
    'CARGO_TARGET_DIR',
    'SHOWDOWN_SERVER_BASE_URL',
    'SHOWDOWN_WATCHER_CLIENT_ID',
    'SHOWDOWN_WATCHER_CLIENT_SECRET',
    'SHOWDOWN_WATCHER_SIMULATE',
    'SHOWDOWN_RIOT_ID',
    'SHOWDOWN_RIOT_PUUID',
    'SHOWDOWN_WATCHER_ENVIRONMENT',
    'SHOWDOWN_WEB_ORIGINS',
    'SHOWDOWN_WATCHER_ADDRESS'
)
$previousEnvironment = @{}
$locationPushed = $false
foreach ($name in $managedEnvironment) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $health = Invoke-RestMethod 'http://127.0.0.1:43991/health' -TimeoutSec 1
    if ($health.component -eq 'showdown-watcher') {
        Write-Host 'Le watcher Showdown est déjà lancé sur 127.0.0.1:43991.' -ForegroundColor Yellow
        return
    }
} catch {
    # Aucun watcher HTTP détecté ; le contrôle du port ci-dessous tranche le cas restant.
}

$portProbe = [Net.Sockets.TcpClient]::new()
try {
    $portProbe.Connect('127.0.0.1', 43991)
    if ($portProbe.Connected) {
        throw 'Le port 43991 est utilisé par une autre application. Ferme-la ou configure un autre port.'
    }
} catch [Net.Sockets.SocketException] {
    # Port libre : le watcher peut démarrer.
} finally {
    $portProbe.Dispose()
}

try {
    $env:CARGO_TARGET_DIR = Join-Path $watcherDirectory 'target'
    if ([string]::IsNullOrWhiteSpace($env:SHOWDOWN_SERVER_BASE_URL)) {
        $env:SHOWDOWN_SERVER_BASE_URL = Get-DotEnvValue -Path $dotenvPath -Name 'JWT_ISSUER'
        if ([string]::IsNullOrWhiteSpace($env:SHOWDOWN_SERVER_BASE_URL)) {
            $env:SHOWDOWN_SERVER_BASE_URL = 'http://localhost:8088'
        }
    }
    $env:SHOWDOWN_WATCHER_ADDRESS = '127.0.0.1:43991'
    if ($Production) {
        if ([string]::IsNullOrWhiteSpace($InstallationClientId) -or
            [string]::IsNullOrWhiteSpace($InstallationClientSecret) -or
            [string]::IsNullOrWhiteSpace($WebOrigins)) {
            throw 'Production exige InstallationClientId, InstallationClientSecret et WebOrigins.'
        }
        $env:SHOWDOWN_WATCHER_ENVIRONMENT = 'production'
        $env:SHOWDOWN_WEB_ORIGINS = $WebOrigins
        $env:SHOWDOWN_WATCHER_CLIENT_ID = $InstallationClientId
        $env:SHOWDOWN_WATCHER_CLIENT_SECRET = $InstallationClientSecret
    } else {
        $env:SHOWDOWN_WATCHER_ENVIRONMENT = 'development'
        if ([string]::IsNullOrWhiteSpace($env:SHOWDOWN_WATCHER_CLIENT_ID)) {
            $env:SHOWDOWN_WATCHER_CLIENT_ID = 'pinkward-watcher'
        }
        if ([string]::IsNullOrWhiteSpace($env:SHOWDOWN_WATCHER_CLIENT_SECRET)) {
            $env:SHOWDOWN_WATCHER_CLIENT_SECRET = Get-DotEnvValue -Path $dotenvPath -Name 'WATCHER_CLIENT_SECRET'
        }
        if ([string]::IsNullOrWhiteSpace($env:SHOWDOWN_WATCHER_CLIENT_SECRET)) {
            Write-Warning 'WATCHER_CLIENT_SECRET est absent : les duels et tests bot seront indisponibles.'
        }
    }
    if ($Simulation) {
        $env:SHOWDOWN_WATCHER_SIMULATE = 'true'
        $env:SHOWDOWN_RIOT_ID = $RiotId
        $env:SHOWDOWN_RIOT_PUUID = 'showdown-simulated-puuid'
    }
    Push-Location $watcherDirectory
    $locationPushed = $true
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw 'La compilation release du Watcher a échoué.' }
    & (Join-Path $watcherDirectory 'target\release\showdown-watcher.exe')
} finally {
    if ($locationPushed) {
        Pop-Location
    }
    foreach ($name in $managedEnvironment) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
