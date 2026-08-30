[CmdletBinding(DefaultParameterSetName = 'S3')]
param(
    [Parameter(Mandatory)][string]$AgeRecipient,
    [Parameter(Mandatory, ParameterSetName = 'Filesystem')][string]$OffsiteDirectory,
    [Parameter(Mandatory, ParameterSetName = 'Filesystem')][switch]$ConfirmOffHostDestination,
    [Parameter(Mandatory, ParameterSetName = 'S3')][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')][string]$S3Bucket,
    [Parameter(Mandatory, ParameterSetName = 'S3')][ValidatePattern('^https://')][string]$S3EndpointUrl,
    [Parameter(ParameterSetName = 'S3')][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9/_.-]{0,127}$')][string]$S3Prefix = 'showdown-production',
    [Parameter(ParameterSetName = 'S3')][string]$AwsProfile,
    [string]$EnvironmentFile,
    [string[]]$ComposeFiles,
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{2,62}$')][string]$ProjectName,
    [ValidateRange(1, 365)][int]$RetentionDays = 30
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-StrictMode -Version Latest
$age = Get-Command age -ErrorAction SilentlyContinue
if (-not $age) { throw 'age is required to create encrypted production backups.' }
$aws = if ($PSCmdlet.ParameterSetName -eq 'S3') { Get-Command aws -ErrorAction SilentlyContinue } else { $null }
if ($PSCmdlet.ParameterSetName -eq 'S3' -and -not $aws) {
    throw 'AWS CLI is required for an OVHcloud S3 backup.'
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
$resolvedComposeFiles = if ($null -eq $ComposeFiles -or $ComposeFiles.Count -eq 0) {
    @((Join-Path $root 'infra/compose.yml'), (Join-Path $root 'infra/compose.production.yml'))
} else {
    @($ComposeFiles)
}
$before = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
if (Test-Path -LiteralPath $backupRoot) {
    Get-ChildItem -LiteralPath $backupRoot -File | ForEach-Object { [void]$before.Add($_.FullName) }
}
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('showdown-production-backup-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
$published = $false

try {
    $backupParameters = @{
        RetentionDays = $RetentionDays
        EnvironmentFile = $productionEnvironment
        ComposeFiles = $resolvedComposeFiles
    }
    if (-not [string]::IsNullOrWhiteSpace($ProjectName)) { $backupParameters.ProjectName = $ProjectName }
    & (Join-Path $PSScriptRoot 'backup-postgres.ps1') @backupParameters
    if ($LASTEXITCODE -ne 0) { throw 'Plaintext database backup failed.' }
    $manifest = Get-ChildItem -LiteralPath $backupRoot -Filter 'manifest-*.json' |
        Where-Object { -not $before.Contains($_.FullName) } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $manifest) { throw 'Backup manifest was not created.' }
    $stamp = $manifest.BaseName.Substring('manifest-'.Length)
    $files = @($manifest.FullName) + @(Get-ChildItem -LiteralPath $backupRoot -Filter "*-$stamp.dump" |
        Select-Object -ExpandProperty FullName)
    if ($files.Count -ne 5) { throw 'Expected four database archives and one manifest.' }

    $archive = Join-Path $temporaryRoot "showdown-backup-$stamp.tar"
    $encrypted = "$archive.age"
    $checksumFile = "$encrypted.sha256"
    & tar -cf $archive -C $backupRoot ($files | ForEach-Object { [IO.Path]::GetFileName($_) })
    if ($LASTEXITCODE -ne 0) { throw 'Unable to build the backup bundle.' }
    & $age.Source -r $AgeRecipient -o $encrypted $archive
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $encrypted)) {
        throw 'Backup encryption failed.'
    }
    $checksum = (Get-FileHash -LiteralPath $encrypted -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($checksumFile,
        "$checksum  $([IO.Path]::GetFileName($encrypted))`n", [Text.Encoding]::ASCII)

    if ($PSCmdlet.ParameterSetName -eq 'Filesystem') {
        if (-not $ConfirmOffHostDestination) { throw 'ConfirmOffHostDestination is required.' }
        $offsite = [IO.Path]::GetFullPath($OffsiteDirectory)
        if ($offsite.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'OffsiteDirectory must be outside the project checkout.'
        }
        [IO.Directory]::CreateDirectory($offsite) | Out-Null
        $destination = Join-Path $offsite ([IO.Path]::GetFileName($encrypted))
        $checksumDestination = "$destination.sha256"
        Copy-Item -LiteralPath $encrypted -Destination $destination -ErrorAction Stop
        Copy-Item -LiteralPath $checksumFile -Destination $checksumDestination -ErrorAction Stop
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum) {
            throw 'Off-host backup checksum verification failed.'
        }
        $published = $true
        $cutoff = (Get-Date).ToUniversalTime().AddDays(-$RetentionDays)
        Get-ChildItem -LiteralPath $offsite -File | Where-Object {
            $_.LastWriteTimeUtc -lt $cutoff -and $_.Name -match '^showdown-backup-.*\.(tar\.age|age\.sha256)$'
        } | Remove-Item -Force
        Write-Output "Encrypted off-host backup created and verified: $destination"
    } else {
        $endpoint = [Uri]$S3EndpointUrl
        if (-not $endpoint.IsAbsoluteUri -or $endpoint.Scheme -ne 'https' -or
            $endpoint.AbsolutePath -ne '/' -or $endpoint.Query -or $endpoint.Fragment) {
            throw 'S3EndpointUrl must be an HTTPS origin without a path, query or fragment.'
        }
        $awsArguments = @()
        if (-not [string]::IsNullOrWhiteSpace($AwsProfile)) { $awsArguments += @('--profile', $AwsProfile) }
        $awsArguments += @('--endpoint-url', $S3EndpointUrl)
        $prefix = $S3Prefix.Trim('/')
        $objectKey = "$prefix/$([IO.Path]::GetFileName($encrypted))"
        $checksumKey = "$objectKey.sha256"
        & $aws.Source @awsArguments s3 cp $encrypted "s3://$S3Bucket/$objectKey" --only-show-errors
        & $aws.Source @awsArguments s3 cp $checksumFile "s3://$S3Bucket/$checksumKey" --only-show-errors
        & $aws.Source @awsArguments s3api head-object --bucket $S3Bucket --key $objectKey | Out-Null
        & $aws.Source @awsArguments s3api head-object --bucket $S3Bucket --key $checksumKey | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Uploaded S3 backup objects could not be verified.' }
        $published = $true
        Write-Output "Encrypted OVHcloud S3 backup uploaded and verified: s3://$S3Bucket/$objectKey"
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $backupRoot) {
        Get-ChildItem -LiteralPath $backupRoot -File | Where-Object {
            ($published -or -not $before.Contains($_.FullName)) -and
            $_.Name -match '^(matchmaking|matches|players|identity|manifest)-'
        } | Remove-Item -Force
    }
}
