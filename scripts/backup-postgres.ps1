[CmdletBinding()]
param(
    [ValidateRange(1, 365)][int]$RetentionDays = 14,
    [string]$EnvironmentFile,
    [string[]]$ComposeFiles
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backupRoot = Join-Path $projectRoot 'backups'
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
    & docker @composeArguments exec -T $database.Service pg_dump -U $database.User `
        -d $database.Database -Fc -Z 9 -f "/backups/$fileName"
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed for $($database.Database)" }
    & docker @composeArguments exec -T $database.Service pg_restore --list "/backups/$fileName" | Out-Null
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
