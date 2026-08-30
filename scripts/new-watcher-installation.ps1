[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_-]{8,64}$')]
    [string]$InstallationName,
    [string]$EnvironmentFile,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envPath = if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
    Join-Path $projectRoot 'infra\.env'
} elseif ([IO.Path]::IsPathRooted($EnvironmentFile)) {
    [IO.Path]::GetFullPath($EnvironmentFile)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $EnvironmentFile))
}
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw "Environment file is missing: $envPath"
}
$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $projectRoot 'watcher-installations'
} elseif ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
}
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null

$clientId = "pinkward-watcher-installation-$InstallationName"
$credentialPath = Join-Path $outputRoot "$clientId.env"
if (Test-Path -LiteralPath $credentialPath) {
    throw "Credential file already exists: $credentialPath"
}
$bytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$secret = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$lines = [Collections.Generic.List[string]](Get-Content -LiteralPath $envPath)
$index = -1
$current = ''
for ($position = 0; $position -lt $lines.Count; $position++) {
    if ($lines[$position] -match '^WATCHER_INSTALLATION_CREDENTIALS=(.*)$') {
        $index = $position
        $current = $Matches[1].Trim()
        break
    }
}
if ($current.Split(',', [StringSplitOptions]::RemoveEmptyEntries) |
        Where-Object { $_.Split('=', 2)[0] -eq $clientId }) {
    throw "Une installation $clientId existe déjà. Révoque-la avant de la recréer."
}
$credential = "$clientId=$secret"
$updated = if ([string]::IsNullOrWhiteSpace($current)) { $credential } else { "$current,$credential" }
[IO.File]::WriteAllLines($credentialPath, @(
    'SHOWDOWN_WATCHER_ENVIRONMENT=production',
    "SHOWDOWN_WATCHER_CLIENT_ID=$clientId",
    "SHOWDOWN_WATCHER_CLIENT_SECRET=$secret"
), [Text.UTF8Encoding]::new($false))
if ($IsWindows) {
    $acl = Get-Acl -LiteralPath $credentialPath
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) { $acl.RemoveAccessRuleSpecific($rule) }
    foreach ($identity in @(
            [Security.Principal.WindowsIdentity]::GetCurrent().User,
            [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
            [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
                $identity, 'FullControl', 'Allow'))
    }
    Set-Acl -LiteralPath $credentialPath -AclObject $acl
} else {
    & chmod 600 $credentialPath
}
if ($index -ge 0) { $lines[$index] = "WATCHER_INSTALLATION_CREDENTIALS=$updated" }
else { $lines.Add("WATCHER_INSTALLATION_CREDENTIALS=$updated") }
[IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))

Write-Output "Installation créée : $clientId"
Write-Output "Credential local protégé : $credentialPath"
Write-Output 'Reconstruis/redémarre identity-service pour enregistrer le client.'
