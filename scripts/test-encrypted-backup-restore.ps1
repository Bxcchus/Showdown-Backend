[CmdletBinding()]
param(
    [string]$AgeDirectory,
    [switch]$KeepArtifacts
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$composeFile = Join-Path $root 'infra/compose.yml'
$templateFile = Join-Path $root 'infra/.env.example'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$sourceProject = "showdown-backup-source-$runId"
$targetProject = "showdown-backup-target-$runId"
$environmentFile = Join-Path ([IO.Path]::GetTempPath()) "showdown-backup-$runId.env"
$testRoot = Join-Path ([IO.Path]::GetTempPath()) "showdown-backup-test-$runId"
$offsiteDirectory = Join-Path $testRoot 'offsite-storage'
$identityFile = Join-Path $testRoot 'age-identity.txt'
$backupRoot = Join-Path $root 'backups'
$beforePlaintext = @(
    Get-ChildItem -LiteralPath $backupRoot -File -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName
)

if (-not [string]::IsNullOrWhiteSpace($AgeDirectory)) {
    $resolvedAgeDirectory = [IO.Path]::GetFullPath($AgeDirectory)
    $env:PATH = "$resolvedAgeDirectory$([IO.Path]::PathSeparator)$env:PATH"
} elseif (-not (Get-Command age -ErrorAction SilentlyContinue)) {
    $localAge = Join-Path $env:LOCALAPPDATA 'Pinkward/tools/age-1.3.2/age'
    if (Test-Path -LiteralPath (Join-Path $localAge 'age.exe')) {
        $env:PATH = "$localAge$([IO.Path]::PathSeparator)$env:PATH"
    }
}

$age = Get-Command age -ErrorAction SilentlyContinue
$ageKeygen = Get-Command age-keygen -ErrorAction SilentlyContinue
if (-not $age -or -not $ageKeygen) {
    throw 'age and age-keygen are required. Pass -AgeDirectory or install age.'
}

$databases = @(
    @{ Name='matchmaking'; Service='postgres'; Database='pinkward_matchmaking'; User='matchmaking_service' },
    @{ Name='matches'; Service='match-postgres'; Database='pinkward_matches'; User='match_service' },
    @{ Name='players'; Service='player-postgres'; Database='pinkward_players'; User='player_service' },
    @{ Name='identity'; Service='identity-postgres'; Database='pinkward_identity'; User='identity_service' }
)

function Invoke-Compose {
    param(
        [Parameter(Mandatory)][string]$ProjectName,
        [Parameter(ValueFromRemainingArguments)][string[]]$Arguments
    )
    & docker compose --project-name $ProjectName --env-file $environmentFile -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed for $ProjectName`: $($Arguments -join ' ')" }
}

function Start-Databases {
    param([Parameter(Mandatory)][string]$ProjectName)
    Invoke-Compose -ProjectName $ProjectName up --detach --wait --wait-timeout 180 `
        postgres match-postgres player-postgres identity-postgres
}

function Invoke-ProbeSql {
    param(
        [Parameter(Mandatory)][string]$ProjectName,
        [Parameter(Mandatory)][hashtable]$Database,
        [Parameter(Mandatory)][string]$Sql
    )
    & docker compose --project-name $ProjectName --env-file $environmentFile -f $composeFile `
        exec -T $Database.Service psql -v ON_ERROR_STOP=1 -At `
        -U $Database.User -d $Database.Database -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "SQL probe failed for $($Database.Name) in $ProjectName" }
}

New-Item -ItemType Directory -Path $offsiteDirectory -Force | Out-Null
try {
    & (Join-Path $PSScriptRoot 'local-secrets.ps1') -Mode Initialize `
        -EnvironmentFile $environmentFile -TemplateFile $templateFile | Out-Null
    & $ageKeygen.Source -o $identityFile 2>$null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $identityFile)) {
        throw 'Unable to create the temporary age identity.'
    }
    $recipient = (& $ageKeygen.Source -y $identityFile).Trim()
    if ($LASTEXITCODE -ne 0 -or $recipient -notmatch '^age1') { throw 'Invalid age recipient.' }

    $expiredBackup = Join-Path $offsiteDirectory 'showdown-backup-20000101T000000Z.tar.age'
    $expiredChecksum = "$expiredBackup.sha256"
    [IO.File]::WriteAllText($expiredBackup, 'expired test artifact')
    [IO.File]::WriteAllText($expiredChecksum, ('0' * 64) + '  expired')
    (Get-Item -LiteralPath $expiredBackup).LastWriteTimeUtc = [datetime]'2000-01-01T00:00:00Z'
    (Get-Item -LiteralPath $expiredChecksum).LastWriteTimeUtc = [datetime]'2000-01-01T00:00:00Z'

    Start-Databases -ProjectName $sourceProject
    foreach ($database in $databases) {
        $marker = "$runId-$($database.Name)"
        Invoke-ProbeSql -ProjectName $sourceProject -Database $database `
            -Sql "CREATE TABLE backup_probe (marker text PRIMARY KEY); INSERT INTO backup_probe(marker) VALUES ('$marker');"
    }

    & (Join-Path $PSScriptRoot 'backup-production.ps1') `
        -AgeRecipient $recipient `
        -OffsiteDirectory $offsiteDirectory `
        -ConfirmOffHostDestination `
        -EnvironmentFile $environmentFile `
        -ComposeFiles $composeFile `
        -ProjectName $sourceProject `
        -RetentionDays 1

    $encryptedBackups = @(Get-ChildItem -LiteralPath $offsiteDirectory -Filter 'showdown-backup-*.tar.age')
    if ($encryptedBackups.Count -ne 1) { throw 'Exactly one encrypted backup was expected after retention.' }
    $encryptedBackup = $encryptedBackups[0].FullName
    if (-not (Test-Path -LiteralPath "$encryptedBackup.sha256" -PathType Leaf)) {
        throw 'Encrypted backup checksum is missing.'
    }
    if ((Test-Path -LiteralPath $expiredBackup) -or (Test-Path -LiteralPath $expiredChecksum)) {
        throw 'Expired offsite backup artifacts were not removed.'
    }
    $plaintext = @(Get-ChildItem -LiteralPath $backupRoot -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notin $beforePlaintext })
    if ($plaintext.Count -ne 0) { throw 'Plaintext production backup artifacts were retained.' }

    Invoke-Compose -ProjectName $sourceProject down --volumes --remove-orphans
    Start-Databases -ProjectName $targetProject
    foreach ($database in $databases) {
        & (Join-Path $PSScriptRoot 'restore-production.ps1') `
            -Database $database.Name `
            -EncryptedBackup $encryptedBackup `
            -AgeIdentityFile $identityFile `
            -EnvironmentFile $environmentFile `
            -ComposeFiles $composeFile `
            -ProjectName $targetProject `
            -Force
        $expected = "$runId-$($database.Name)"
        $actual = (Invoke-ProbeSql -ProjectName $targetProject -Database $database `
            -Sql 'SELECT marker FROM backup_probe;').Trim()
        if ($actual -ne $expected) {
            throw "Restored marker mismatch for $($database.Name): expected $expected, got $actual"
        }
    }

    [pscustomobject]@{
        encryption = 'VERIFIED'
        checksum = 'VERIFIED'
        retention = 'VERIFIED'
        isolatedRestore = 'VERIFIED'
        databases = 4
    } | Format-List
}
finally {
    foreach ($project in @($sourceProject, $targetProject)) {
        try { Invoke-Compose -ProjectName $project down --volumes --remove-orphans } catch {}
    }
    if (-not $KeepArtifacts) {
        if (Test-Path -LiteralPath $environmentFile) { Remove-Item -LiteralPath $environmentFile -Force }
        if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
    } else {
        Write-Host "Encrypted test artifacts retained at $testRoot"
    }
}
