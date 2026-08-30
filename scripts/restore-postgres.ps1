[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('matchmaking', 'matches', 'players', 'identity')][string]$Database,
    [Parameter(Mandatory)][string]$BackupFile,
    [string]$EnvironmentFile,
    [string[]]$ComposeFiles,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
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
$envFile = if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
    Join-Path $projectRoot 'infra/.env'
} elseif ([IO.Path]::IsPathRooted($EnvironmentFile)) {
    [IO.Path]::GetFullPath($EnvironmentFile)
} else { [IO.Path]::GetFullPath((Join-Path $projectRoot $EnvironmentFile)) }
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Environment file is missing: $envFile"
}
$resolvedComposeFiles = if ($null -eq $ComposeFiles -or $ComposeFiles.Count -eq 0) {
    @((Join-Path $projectRoot 'infra/compose.yml'))
} else {
    @($ComposeFiles | ForEach-Object {
        if ([IO.Path]::IsPathRooted($_)) { [IO.Path]::GetFullPath($_) }
        else { [IO.Path]::GetFullPath((Join-Path $projectRoot $_)) }
    })
}
foreach ($composeFile in $resolvedComposeFiles) {
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw "Compose file is missing: $composeFile"
    }
}
$composeArguments = @('compose', '--env-file', $envFile)
foreach ($composeFile in $resolvedComposeFiles) { $composeArguments += @('-f', $composeFile) }
& docker @composeArguments exec -T $target.Service pg_restore -U $target.User `
    -d $target.Database --clean --if-exists --no-owner --exit-on-error "/backups/$([IO.Path]::GetFileName($source))"
if ($LASTEXITCODE -ne 0) { throw "Restore failed for $($target.Database)" }
Write-Output "Restore completed for $($target.Database). Run .\scripts\smoke-test.ps1 now."
