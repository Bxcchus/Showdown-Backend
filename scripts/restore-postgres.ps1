[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('matchmaking', 'matches', 'players', 'identity')][string]$Database,
    [Parameter(Mandatory)][string]$BackupFile,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
if (-not $Force) { throw 'Restore replaces the selected database. Re-run with -Force after verifying the target.' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backupRoot = (Resolve-Path (Join-Path $projectRoot 'backups')).Path
$source = (Resolve-Path -LiteralPath $BackupFile).Path
if (-not $source.StartsWith($backupRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The archive must be inside the project backups directory.'
}
$targets = @{
    matchmaking = @{ Service = 'postgres'; Database = 'pinkward_matchmaking'; User = 'matchmaking_service' }
    matches = @{ Service = 'match-postgres'; Database = 'pinkward_matches'; User = 'match_service' }
    players = @{ Service = 'player-postgres'; Database = 'pinkward_players'; User = 'player_service' }
    identity = @{ Service = 'identity-postgres'; Database = 'pinkward_identity'; User = 'identity_service' }
}
$target = $targets[$Database]
$composeFile = Join-Path $projectRoot 'infra/compose.yml'
$envFile = Join-Path $projectRoot 'infra/.env'
& docker compose --env-file $envFile -f $composeFile exec -T $target.Service `
    pg_restore -U $target.User -d $target.Database --clean --if-exists --no-owner --exit-on-error "/backups/$([IO.Path]::GetFileName($source))"
if ($LASTEXITCODE -ne 0) { throw "Restore failed for $($target.Database)" }
Write-Output "Restore completed for $($target.Database). Run .\scripts\smoke-test.ps1 now."
