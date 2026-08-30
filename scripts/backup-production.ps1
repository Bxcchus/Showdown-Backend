[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AgeRecipient,
    [Parameter(Mandatory)][string]$OffsiteDirectory,
    [ValidateRange(1, 365)][int]$RetentionDays = 14
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$age = Get-Command age -ErrorAction SilentlyContinue
if (-not $age) { throw 'age is required to create encrypted production backups.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backupRoot = Join-Path $root 'backups'
$offsite = [IO.Path]::GetFullPath($OffsiteDirectory)
if ($offsite.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OffsiteDirectory must be outside the project checkout.'
}
[IO.Directory]::CreateDirectory($offsite) | Out-Null

& (Join-Path $PSScriptRoot 'backup-postgres.ps1') -RetentionDays $RetentionDays
if ($LASTEXITCODE -ne 0) { throw 'Plaintext database backup failed.' }
$manifest = Get-ChildItem -LiteralPath $backupRoot -Filter 'manifest-*.json' |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not $manifest) { throw 'Backup manifest was not created.' }
$stamp = $manifest.BaseName.Substring('manifest-'.Length)
$files = @($manifest.FullName) + @(Get-ChildItem -LiteralPath $backupRoot -Filter "*-$stamp.dump" |
    Select-Object -ExpandProperty FullName)
if ($files.Count -ne 5) { throw 'Expected four database archives and one manifest.' }

$temporaryArchive = Join-Path ([IO.Path]::GetTempPath()) "showdown-backup-$stamp.tar"
$encrypted = Join-Path $offsite "showdown-backup-$stamp.tar.age"
try {
    & tar -cf $temporaryArchive -C $backupRoot ($files | ForEach-Object { [IO.Path]::GetFileName($_) })
    if ($LASTEXITCODE -ne 0) { throw 'Unable to build the backup bundle.' }
    & $age.Source -r $AgeRecipient -o $encrypted $temporaryArchive
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $encrypted)) { throw 'Backup encryption failed.' }
    $checksum = (Get-FileHash -LiteralPath $encrypted -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$encrypted.sha256" -Value "$checksum  $([IO.Path]::GetFileName($encrypted))" -Encoding ascii
}
finally {
    if (Test-Path -LiteralPath $temporaryArchive) { Remove-Item -LiteralPath $temporaryArchive -Force }
}

$cutoff = (Get-Date).ToUniversalTime().AddDays(-$RetentionDays)
Get-ChildItem -LiteralPath $offsite -File | Where-Object {
    $_.LastWriteTimeUtc -lt $cutoff -and $_.Name -match '^showdown-backup-.*\.(tar\.age|age\.sha256)$'
} | Remove-Item -Force
Write-Host "Encrypted offsite backup created: $encrypted"
