[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^pinkward-watcher-installation-[A-Za-z0-9_-]{8,64}$')]
    [string]$ClientId
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envPath = Join-Path $projectRoot 'infra\.env'
if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw 'infra/.env est absent.'
}
$lines = [Collections.Generic.List[string]](Get-Content -LiteralPath $envPath)
$found = $false
for ($position = 0; $position -lt $lines.Count; $position++) {
    if ($lines[$position] -notmatch '^WATCHER_INSTALLATION_CREDENTIALS=(.*)$') { continue }
    $remaining = $Matches[1].Split(',', [StringSplitOptions]::RemoveEmptyEntries) |
        Where-Object { $_.Split('=', 2)[0] -ne $ClientId }
    $found = $remaining.Count -lt $Matches[1].Split(',', [StringSplitOptions]::RemoveEmptyEntries).Count
    $lines[$position] = 'WATCHER_INSTALLATION_CREDENTIALS=' + ($remaining -join ',')
    break
}
if (-not $found) { throw "Credential inconnu : $ClientId" }
[IO.File]::WriteAllLines($envPath, $lines, [Text.UTF8Encoding]::new($false))
$credentialPath = Join-Path $projectRoot "watcher-installations\$ClientId.env"
if (Test-Path -LiteralPath $credentialPath -PathType Leaf) {
    Remove-Item -LiteralPath $credentialPath -Force
}
Write-Output "Installation révoquée dans la configuration : $ClientId"
Write-Output 'Redémarre identity-service. Les JWT déjà émis expirent au plus tard après deux minutes.'
