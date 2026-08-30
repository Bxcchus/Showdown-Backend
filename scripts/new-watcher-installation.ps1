[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_-]{8,64}$')]
    [string]$InstallationName
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envPath = Join-Path $projectRoot 'infra\.env'
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw 'infra/.env est absent.'
}

$clientId = "pinkward-watcher-installation-$InstallationName"
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
if ($index -ge 0) { $lines[$index] = "WATCHER_INSTALLATION_CREDENTIALS=$updated" }
else { $lines.Add("WATCHER_INSTALLATION_CREDENTIALS=$updated") }
[IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))

$outputRoot = Join-Path $projectRoot 'watcher-installations'
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$credentialPath = Join-Path $outputRoot "$clientId.env"
[IO.File]::WriteAllLines($credentialPath, @(
    'SHOWDOWN_WATCHER_ENVIRONMENT=production',
    "SHOWDOWN_WATCHER_CLIENT_ID=$clientId",
    "SHOWDOWN_WATCHER_CLIENT_SECRET=$secret"
), [Text.UTF8Encoding]::new($false))
$acl = New-Object Security.AccessControl.FileSecurity
$acl.SetAccessRuleProtection($true, $false)
foreach ($identity in @([Security.Principal.WindowsIdentity]::GetCurrent().Name,
        'NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $identity, 'FullControl', 'Allow'))
}
Set-Acl -LiteralPath $credentialPath -AclObject $acl

Write-Output "Installation créée : $clientId"
Write-Output "Credential local protégé : $credentialPath"
Write-Output 'Reconstruis/redémarre identity-service pour enregistrer le client.'
