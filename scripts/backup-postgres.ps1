[CmdletBinding()]
param(
    [ValidateRange(1, 365)][int]$RetentionDays = 14
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backupRoot = Join-Path $projectRoot 'backups'
$composeFile = Join-Path $projectRoot 'infra/compose.yml'
$envFile = Join-Path $projectRoot 'infra/.env'
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$resolvedBackupRoot = (Resolve-Path -LiteralPath $backupRoot).Path
if (-not $resolvedBackupRoot.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup directory escaped the project root.'
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$databases = @(
    @{ Service = 'postgres'; Database = 'pinkward_matchmaking'; User = 'matchmaking_service'; Prefix = 'matchmaking' },
    @{ Service = 'match-postgres'; Database = 'pinkward_matches'; User = 'match_service'; Prefix = 'matches' },
    @{ Service = 'player-postgres'; Database = 'pinkward_players'; User = 'player_service'; Prefix = 'players' },
    @{ Service = 'identity-postgres'; Database = 'pinkward_identity'; User = 'identity_service'; Prefix = 'identity' }
)

$artifacts = foreach ($database in $databases) {
    $fileName = "$($database.Prefix)-$stamp.dump"
    & docker compose --env-file $envFile -f $composeFile exec -T $database.Service `
        pg_dump -U $database.User -d $database.Database -Fc -Z 9 -f "/backups/$fileName"
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed for $($database.Database)" }
    & docker compose --env-file $envFile -f $composeFile exec -T $database.Service `
        pg_restore --list "/backups/$fileName" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Backup verification failed for $fileName" }
    $hostPath = Join-Path $resolvedBackupRoot $fileName
    $file = Get-Item -LiteralPath $hostPath
    [pscustomobject]@{
        database = $database.Database
        file = $file.Name
        bytes = $file.Length
        sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$manifestPath = Join-Path $resolvedBackupRoot "manifest-$stamp.json"
[pscustomobject]@{
    createdAt = (Get-Date).ToUniversalTime().ToString('o')
    format = 'PostgreSQL custom archive'
    verified = $true
    artifacts = @($artifacts)
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8

$cutoff = (Get-Date).ToUniversalTime().AddDays(-$RetentionDays)
Get-ChildItem -LiteralPath $resolvedBackupRoot -File |
    Where-Object { $_.LastWriteTimeUtc -lt $cutoff -and $_.Name -match '^(matchmaking|matches|players|identity|manifest)-' } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

Write-Output "Backup verified: $manifestPath"
