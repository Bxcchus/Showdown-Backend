[CmdletBinding(DefaultParameterSetName = 'S3')]
param(
    [Parameter(Mandatory)][ValidateSet('matchmaking', 'matches', 'players', 'identity')][string]$Database,
    [Parameter(Mandatory, ParameterSetName = 'Filesystem')][string]$EncryptedBackup,
    [Parameter(Mandatory, ParameterSetName = 'S3')][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$S3Bucket,
    [Parameter(Mandatory, ParameterSetName = 'S3')][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9/_.-]{0,255}\.tar\.age$')][string]$S3ObjectKey,
    [Parameter(Mandatory, ParameterSetName = 'S3')][ValidatePattern('^https://')][string]$S3EndpointUrl,
    [Parameter(ParameterSetName = 'S3')][string]$AwsProfile,
    [Parameter(Mandatory)][string]$AgeIdentityFile,
    [string]$EnvironmentFile,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-StrictMode -Version Latest
if (-not $Force) { throw 'Restore replaces production data. Re-run with -Force after verifying the target.' }
$age = Get-Command age -ErrorAction SilentlyContinue
if (-not $age) { throw 'age is required to decrypt production backups.' }
$aws = if ($PSCmdlet.ParameterSetName -eq 'S3') { Get-Command aws -ErrorAction SilentlyContinue } else { $null }
if ($PSCmdlet.ParameterSetName -eq 'S3' -and -not $aws) {
    throw 'AWS CLI is required to restore an OVHcloud S3 backup.'
}
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backupRoot = Join-Path $root 'backups'
$productionEnvironment = if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
    Join-Path $root 'infra/production.env'
} elseif ([IO.Path]::IsPathRooted($EnvironmentFile)) {
    [IO.Path]::GetFullPath($EnvironmentFile)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $EnvironmentFile))
}
$composeFiles = @(
    (Join-Path $root 'infra/compose.yml'),
    (Join-Path $root 'infra/compose.production.yml')
)
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('showdown-production-restore-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null

try {
    if ($PSCmdlet.ParameterSetName -eq 'S3') {
        $endpoint = [Uri]$S3EndpointUrl
        if (-not $endpoint.IsAbsoluteUri -or $endpoint.Scheme -ne 'https' -or
            $endpoint.AbsolutePath -ne '/' -or $endpoint.Query -or $endpoint.Fragment) {
            throw 'S3EndpointUrl must be an HTTPS origin without a path, query or fragment.'
        }
        $awsArguments = @()
        if (-not [string]::IsNullOrWhiteSpace($AwsProfile)) { $awsArguments += @('--profile', $AwsProfile) }
        $awsArguments += @('--endpoint-url', $S3EndpointUrl)
        $encrypted = Join-Path $temporaryRoot ([IO.Path]::GetFileName($S3ObjectKey))
        $checksumFile = "$encrypted.sha256"
        & $aws.Source @awsArguments s3 cp "s3://$S3Bucket/$S3ObjectKey" $encrypted --only-show-errors
        & $aws.Source @awsArguments s3 cp "s3://$S3Bucket/$S3ObjectKey.sha256" $checksumFile --only-show-errors
    } else {
        $encrypted = (Resolve-Path -LiteralPath $EncryptedBackup).Path
        $checksumFile = "$encrypted.sha256"
    }
    if (-not (Test-Path -LiteralPath $checksumFile -PathType Leaf)) {
        throw 'The encrypted backup checksum file is missing.'
    }
    $expectedChecksum = ((Get-Content -LiteralPath $checksumFile -Raw) -split '\s+')[0].ToLowerInvariant()
    if ($expectedChecksum -notmatch '^[a-f0-9]{64}$') { throw 'The backup checksum file is invalid.' }
    $actualChecksum = (Get-FileHash -LiteralPath $encrypted -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualChecksum -ne $expectedChecksum) { throw 'Encrypted backup checksum mismatch.' }

    $archive = Join-Path $temporaryRoot 'backup.tar'
    & $age.Source -d -i (Resolve-Path -LiteralPath $AgeIdentityFile).Path -o $archive $encrypted
    if ($LASTEXITCODE -ne 0) { throw 'Backup decryption failed.' }
    $archiveEntries = @(& tar -tf $archive)
    if ($LASTEXITCODE -ne 0 -or $archiveEntries.Count -ne 5) {
        throw 'The decrypted archive does not contain the expected five files.'
    }
    foreach ($entry in $archiveEntries) {
        if ($entry -notmatch '^(matchmaking|matches|players|identity)-[0-9]{8}T[0-9]{6}Z\.dump$|^manifest-[0-9]{8}T[0-9]{6}Z\.json$') {
            throw "Unsafe or unexpected archive entry: $entry"
        }
    }
    & tar -xf $archive -C $temporaryRoot
    if ($LASTEXITCODE -ne 0) { throw 'Backup extraction failed.' }
    $manifests = @(Get-ChildItem -LiteralPath $temporaryRoot -Filter 'manifest-*.json')
    if ($manifests.Count -ne 1) { throw 'The encrypted bundle must contain exactly one manifest.' }
    $manifest = Get-Content -LiteralPath $manifests[0].FullName -Raw | ConvertFrom-Json
    if ($manifest.verified -ne $true -or @($manifest.artifacts).Count -ne 4) {
        throw 'The backup manifest is incomplete or unverified.'
    }
    foreach ($artifact in @($manifest.artifacts)) {
        $artifactPath = Join-Path $temporaryRoot ([IO.Path]::GetFileName([string]$artifact.file))
        if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
            throw "Backup artifact is missing: $($artifact.file)"
        }
        $artifactChecksum = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($artifactChecksum -ne ([string]$artifact.sha256).ToLowerInvariant()) {
            throw "Backup artifact checksum mismatch: $($artifact.file)"
        }
    }
    $prefix = @{ matchmaking='matchmaking'; matches='matches'; players='players'; identity='identity' }[$Database]
    $dump = Get-ChildItem -LiteralPath $temporaryRoot -Filter "$prefix-*.dump" | Select-Object -First 1
    if (-not $dump) { throw "The encrypted bundle does not contain $Database." }
    $staged = Join-Path $backupRoot ('restore-' + [Guid]::NewGuid().ToString('N') + '.dump')
    Copy-Item -LiteralPath $dump.FullName -Destination $staged
    try {
        & (Join-Path $PSScriptRoot 'restore-postgres.ps1') -Database $Database -BackupFile $staged `
            -EnvironmentFile $productionEnvironment -ComposeFiles $composeFiles -Force
    }
    finally {
        if (Test-Path -LiteralPath $staged) { Remove-Item -LiteralPath $staged -Force }
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
