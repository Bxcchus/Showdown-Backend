[CmdletBinding()]
param(
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot '../infra/production.env.example')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$base = Join-Path $root 'infra/compose.yml'
$override = Join-Path $root 'infra/compose.production.yml'
$caddy = Join-Path $root 'infra/Caddyfile.production'
$failures = [Collections.Generic.List[string]]::new()

$renderEnvironment = Join-Path ([IO.Path]::GetTempPath()) ('showdown-production-' + [Guid]::NewGuid().ToString('N') + '.env')
try {
    $content = Get-Content -LiteralPath $EnvironmentFile -Raw
    $content = $content.Replace('REPLACE_WITH_PUBLIC_DOMAIN', 'showdown.example.test')
    $content = $content.Replace('GENERATE_IN_SECRET_MANAGER', ('x' * 48))
    Set-Content -LiteralPath $renderEnvironment -Value $content -Encoding utf8
    $json = @(& docker compose --env-file $renderEnvironment -f $base -f $override config --format json 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ($json -join [Environment]::NewLine) }
    $config = ($json -join [Environment]::NewLine) | ConvertFrom-Json

    $published = @($config.services.PSObject.Properties | ForEach-Object {
        $name = $_.Name
        $portsProperty = $_.Value.PSObject.Properties['ports']
        @($(if ($null -ne $portsProperty) { $portsProperty.Value })) | Where-Object { $null -ne $_ } | ForEach-Object {
            [pscustomobject]@{ service = $name; published = [int]$_.published; target = [int]$_.target }
        }
    })
    if (@($published | Where-Object service -ne 'caddy').Count -ne 0) {
        $failures.Add('Production publishes a container other than Caddy')
    }
    $edgePorts = @($published | Where-Object service -eq 'caddy' | Select-Object -ExpandProperty published -Unique)
    if (80 -notin $edgePorts -or 443 -notin $edgePorts) {
        $failures.Add('Production Caddy must publish HTTP and HTTPS')
    }

    foreach ($property in $config.services.PSObject.Properties) {
        $service = $property.Value
        if (@($service.security_opt) -notcontains 'no-new-privileges:true') {
            $failures.Add("$($property.Name) lacks no-new-privileges")
        }
        $deployProperty = $service.PSObject.Properties['deploy']
        $memoryLimit = if ($null -ne $deployProperty) {
            $deployProperty.Value.resources.limits.memory
        } else { $null }
        if ($property.Name -notin @('rabbitmq-bootstrap', 'redis-exporter', 'matchmaking-postgres-exporter',
                'match-postgres-exporter', 'player-postgres-exporter', 'identity-postgres-exporter',
                'alertmanager', 'prometheus') -and $null -eq $memoryLimit) {
            $failures.Add("$($property.Name) lacks a production memory limit")
        }
    }

    $identity = $config.services.'identity-service'.environment
    if ($identity.LOCAL_IDENTITY_ENABLED -ne 'false' -or $identity.WATCHER_LOCAL_CLIENT_ENABLED -ne 'false') {
        $failures.Add('Production must disable local identities and the shared local watcher client')
    }
    if ($identity.WEB_DEV_REDIRECT_URI) {
        $failures.Add('Production must not register a localhost OAuth redirect')
    }
    foreach ($name in @('player-service', 'match-service')) {
        $origins = [string]$config.services.$name.environment.PINKWARD_WEB_ORIGINS
        if ($origins -match 'localhost|127\.0\.0\.1|^http:') {
            $failures.Add("$name contains a development origin in production")
        }
    }

    $caddyText = Get-Content -LiteralPath $caddy -Raw
    foreach ($needle in @('Strict-Transport-Security', 'Authorization delete', 'Cookie delete',
            'Sec-Websocket-Protocol delete', 'replace access_token REDACTED', 'replace client_secret REDACTED')) {
        if ($caddyText -notmatch [Regex]::Escape($needle)) {
            $failures.Add("Production Caddy is missing: $needle")
        }
    }
}
finally {
    if (Test-Path -LiteralPath $renderEnvironment) { Remove-Item -LiteralPath $renderEnvironment -Force }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    throw "Production security validation failed with $($failures.Count) issue(s)"
}
Write-Host 'Production security configuration validated without exposing secret values.'
