[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('matchmaking', 'matches', 'players', 'identity')][string]$Database,
    [Parameter(Mandatory)][string]$EncryptedBackup,
    [Parameter(Mandatory)][string]$AgeIdentityFile,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $Force) { throw 'Restore replaces data. Re-run with -Force after verifying the target.' }
$age = Get-Command age -ErrorAction SilentlyContinue
if (-not $age) { throw 'age is required to decrypt production backups.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backupRoot = Join-Path $root 'backups'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('showdown-restore-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
try {
    $archive = Join-Path $temporaryRoot 'backup.tar'
    & $age.Source -d -i (Resolve-Path -LiteralPath $AgeIdentityFile).Path -o $archive `
        (Resolve-Path -LiteralPath $EncryptedBackup).Path
    if ($LASTEXITCODE -ne 0) { throw 'Backup decryption failed.' }
    & tar -xf $archive -C $temporaryRoot
    if ($LASTEXITCODE -ne 0) { throw 'Backup extraction failed.' }
    $prefix = @{ matchmaking='matchmaking'; matches='matches'; players='players'; identity='identity' }[$Database]
    $dump = Get-ChildItem -LiteralPath $temporaryRoot -Filter "$prefix-*.dump" | Select-Object -First 1
    if (-not $dump) { throw "The encrypted bundle does not contain $Database." }
    $staged = Join-Path $backupRoot ('restore-' + [Guid]::NewGuid().ToString('N') + '.dump')
    Copy-Item -LiteralPath $dump.FullName -Destination $staged
    try {
        & (Join-Path $PSScriptRoot 'restore-postgres.ps1') -Database $Database -BackupFile $staged -Force
    }
    finally {
        if (Test-Path -LiteralPath $staged) { Remove-Item -LiteralPath $staged -Force }
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) { Remove-Item -LiteralPath $temporaryRoot -Recurse -Force }
}
