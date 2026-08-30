[CmdletBinding()]
param([string]$ImagePrefix = 'pinkward-showdown-')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$services = @('identity-service', 'api-gateway', 'player-service', 'matchmaking-service', 'match-service', 'web-app')
$images = foreach ($service in $services) {
    if ($ImagePrefix.EndsWith('/')) { "$ImagePrefix$service`:ci" } else { "$ImagePrefix$service`:latest" }
}
$images += if ($ImagePrefix.EndsWith('/')) { "${ImagePrefix}caddy:ci" } else { 'pinkward-showdown-caddy:latest' }

foreach ($image in $images) {
    $user = (& docker image inspect --format '{{.Config.User}}' $image).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Missing image $image" }
    if ([string]::IsNullOrWhiteSpace($user) -or $user -in @('0', 'root')) {
        throw "$image runs as root"
    }
    & docker run --rm --entrypoint /bin/sh $image -ec @'
matches=$(find /app /srv -type f 2>/dev/null | grep -E '/\.env(\.|$)|\.(dump|pdb|bak)$' || true)
test -z "$matches"
'@
    if ($LASTEXITCODE -ne 0) { throw "$image contains a forbidden environment, dump or debug file" }
}

Write-Host 'Application and edge images are non-root and contain no .env, dump, backup or PDB files.'
