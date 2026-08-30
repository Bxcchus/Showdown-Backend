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
$alertmanager = Join-Path $root 'infra/observability/alertmanager.production.yml'
$watcherProvisioning = Join-Path $root 'scripts/new-watcher-installation.ps1'
$alertmanagerProvisioning = Join-Path $root 'scripts/set-alertmanager-webhook-secret.ps1'
$productionBackup = Join-Path $root 'scripts/backup-production.ps1'
$productionRestore = Join-Path $root 'scripts/restore-production.ps1'
$failures = [Collections.Generic.List[string]]::new()

$renderEnvironment = Join-Path ([IO.Path]::GetTempPath()) ('showdown-production-' + [Guid]::NewGuid().ToString('N') + '.env')
try {
    $content = Get-Content -LiteralPath $EnvironmentFile -Raw
    $content = $content.Replace('REPLACE_WITH_API_DOMAIN', 'api.showdown.example.test')
    $content = $content.Replace('REPLACE_WITH_WEB_ORIGIN', 'https://frontend.showdown.example.test')
    $content = $content.Replace('REPLACE_WITH_OIDC_ISSUER', 'https://identity.example.test')
    $content = $content.Replace('REPLACE_WITH_OIDC_CLIENT_ID', 'showdown-production-client')
    $content = $content.Replace('REPLACE_WITH_OIDC_CLIENT_SECRET', ('o' * 48))
    $content = $content.Replace('GENERATE_IN_SECRET_MANAGER', ('x' * 48))
    $environmentValues = @{}
    foreach ($line in ($content -split '\r?\n')) {
        if ($line -match '^\s*([^#=\s]+)=(.*)$') {
            $environmentValues[$Matches[1]] = $Matches[2].Trim()
        }
    }
    $apiDomain = [string]$environmentValues.SHOWDOWN_API_DOMAIN
    $webOrigin = [string]$environmentValues.SHOWDOWN_WEB_ORIGIN
    if ($apiDomain -notmatch '^[a-zA-Z0-9.-]+$' -or $apiDomain -match '^\.|\.$|\.\.') {
        $failures.Add('SHOWDOWN_API_DOMAIN must be a bare DNS hostname without scheme, port or path')
    }
    $webUri = $null
    if (-not [Uri]::TryCreate($webOrigin, [UriKind]::Absolute, [ref]$webUri) -or
        $webUri.Scheme -ne 'https' -or $webUri.AbsolutePath -ne '/' -or
        $webOrigin.EndsWith('/')) {
        $failures.Add('SHOWDOWN_WEB_ORIGIN must be an exact HTTPS origin without path or trailing slash')
    }
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

    if ($config.services.PSObject.Properties.Name -contains 'web-app') {
        $failures.Add('The legacy integrated web-app must be disabled in the default production profile')
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
    if ($identity.EXTERNAL_IDENTITY_ENABLED -ne 'true' -or
        $identity.EXTERNAL_IDENTITY_REGISTRATION_ID -ne 'production') {
        $failures.Add('Production must enable the configured external OIDC identity')
    }
    $oidcIssuer = [string]$identity.SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_PRODUCTION_ISSUER_URI
    if ($oidcIssuer -notmatch '^https://' -or $oidcIssuer -match 'localhost|127\.0\.0\.1') {
        $failures.Add('Production OIDC issuer must be an external HTTPS origin')
    }
    if ([string]::IsNullOrWhiteSpace(
            [string]$identity.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_PRODUCTION_CLIENT_ID) -or
        [string]::IsNullOrWhiteSpace(
            [string]$identity.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_PRODUCTION_CLIENT_SECRET)) {
        $failures.Add('Production OIDC client credentials are missing')
    }
    if ($identity.WEB_DEV_REDIRECT_URI) {
        $failures.Add('Production must not register a localhost OAuth redirect')
    }
    if ($identity.SESSION_COOKIE_SECURE -ne 'true' -or $identity.SESSION_COOKIE_SAME_SITE -ne 'lax') {
        $failures.Add('Production identity cookies must be Secure and SameSite=Lax for cross-site OAuth navigation')
    }
    $expectedIssuer = "https://$apiDomain"
    foreach ($name in @('api-gateway', 'identity-service', 'player-service', 'matchmaking-service', 'match-service')) {
        if ([string]$config.services.$name.environment.JWT_ISSUER -ne $expectedIssuer) {
            $failures.Add("$name does not use the dedicated API origin as JWT issuer")
        }
    }
    if ([string]$config.services.'api-gateway'.environment.WEB_ORIGIN -ne $webOrigin) {
        $failures.Add('API Gateway does not use the exact standalone web origin')
    }
    if ([string]$identity.WEB_REDIRECT_URI -ne "$webOrigin/oauth/callback") {
        $failures.Add('Identity service does not redirect OAuth to the standalone frontend')
    }
    foreach ($name in @('player-service', 'match-service')) {
        $origins = [string]$config.services.$name.environment.PINKWARD_WEB_ORIGINS
        if ($origins -ne $webOrigin) {
            $failures.Add("$name does not allow exactly the standalone web origin")
        }
        if ($origins -match 'localhost|127\.0\.0\.1|^http:') {
            $failures.Add("$name contains a development origin in production")
        }
    }

    $caddyText = Get-Content -LiteralPath $caddy -Raw
    foreach ($needle in @('{$SHOWDOWN_API_DOMAIN}', 'Strict-Transport-Security', 'Authorization delete', 'Cookie delete',
            'Sec-Websocket-Protocol delete', 'X-Watcher-Token delete', 'X-Showdown-Watcher-Token delete',
            'replace access_token REDACTED', 'replace client_secret REDACTED')) {
        if ($caddyText -notmatch [Regex]::Escape($needle)) {
            $failures.Add("Production Caddy is missing: $needle")
        }
    }
    if ($caddyText -match 'reverse_proxy\s+web-app' -or $caddyText -match 'SHOWDOWN_DOMAIN') {
        $failures.Add('Production Caddy still contains the retired integrated frontend or legacy domain variable')
    }
    $alertmanagerText = Get-Content -LiteralPath $alertmanager -Raw
    if ($alertmanagerText -notmatch 'production-webhook' -or
        $alertmanagerText -notmatch 'url_file:\s*/run/secrets/alertmanager-webhook-url' -or
        $alertmanagerText -match 'local-console') {
        $failures.Add('Production Alertmanager must use the externally provisioned webhook secret file')
    }
    $watcherProvisioningText = Get-Content -LiteralPath $watcherProvisioning -Raw
    if ($watcherProvisioningText -notmatch '\$EnvironmentFile' -or
        $watcherProvisioningText -notmatch 'RandomNumberGenerator' -or
        $watcherProvisioningText -notmatch 'S-1-5-32-544') {
        $failures.Add('Watcher provisioning must support production environments, random credentials and restricted ACLs')
    }
    $alertmanagerProvisioningText = Get-Content -LiteralPath $alertmanagerProvisioning -Raw
    if ($alertmanagerProvisioningText -notmatch 'Read-Host.*-AsSecureString' -or
        $alertmanagerProvisioningText -notmatch "Scheme -ne 'https'" -or
        $alertmanagerProvisioningText -notmatch 'chmod 600') {
        $failures.Add('Alertmanager webhook provisioning must prompt securely, require HTTPS and restrict permissions')
    }
    $productionBackupText = Get-Content -LiteralPath $productionBackup -Raw
    foreach ($needle in @('infra/production.env', 'infra/compose.production.yml', 'S3EndpointUrl',
            's3api head-object', 'Get-FileHash', 'finally')) {
        if ($productionBackupText -notmatch [Regex]::Escape($needle)) {
            $failures.Add("Production backup is missing: $needle")
        }
    }
    $productionRestoreText = Get-Content -LiteralPath $productionRestore -Raw
    foreach ($needle in @('infra/production.env', 'infra/compose.production.yml', 'S3ObjectKey',
            'Encrypted backup checksum mismatch', 'Backup artifact checksum mismatch')) {
        if ($productionRestoreText -notmatch [Regex]::Escape($needle)) {
            $failures.Add("Production restore is missing: $needle")
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
