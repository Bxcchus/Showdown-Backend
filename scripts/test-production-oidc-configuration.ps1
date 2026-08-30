[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baseCompose = Join-Path $root 'infra/compose.yml'
$productionCompose = Join-Path $root 'infra/compose.production.yml'
$template = Join-Path $root 'infra/production.env.example'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('showdown-production-config-' + [Guid]::NewGuid().ToString('N'))
$environmentFile = Join-Path $temporaryRoot 'production.env'
$webhookFile = Join-Path $temporaryRoot 'alertmanager-webhook-url'
$fakeApiDomain = 'api.showdown.test'
$fakeWebOrigin = 'https://showdown.test'
$fakeIssuer = 'http://host.docker.internal:65500/showdown'
$overrides = @{
    SHOWDOWN_API_DOMAIN = $fakeApiDomain
    SHOWDOWN_WEB_ORIGIN = $fakeWebOrigin
    OIDC_ISSUER_URI = $fakeIssuer
    OIDC_CLIENT_ID = 'showdown-production-configuration-test'
    OIDC_CLIENT_SECRET = 'configuration-only-secret-not-used-over-the-network'
    ALERTMANAGER_WEBHOOK_URL_FILE = $webhookFile
}
$previousEnvironment = @{}

try {
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    [IO.File]::WriteAllText($webhookFile, 'https://alerts.showdown.test/placeholder')
    & (Join-Path $PSScriptRoot 'local-secrets.ps1') `
        -Mode Initialize `
        -EnvironmentFile $environmentFile `
        -TemplateFile $template | Out-Null
    foreach ($name in $overrides.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $overrides[$name], 'Process')
    }

    $json = & docker compose `
        --env-file $environmentFile `
        -f $baseCompose `
        -f $productionCompose `
        config --format json
    if ($LASTEXITCODE -ne 0) { throw 'The fictitious production Compose configuration is invalid' }
    $config = $json | ConvertFrom-Json
    $identity = $config.services.'identity-service'.environment
    $gateway = $config.services.'api-gateway'.environment
    $web = $config.services.'web-app'.environment
    $player = $config.services.'player-service'.environment
    $match = $config.services.'match-service'.environment

    if ($identity.LOCAL_IDENTITY_ENABLED -ne 'false') { throw 'Production must disable local identities' }
    if ($identity.EXTERNAL_IDENTITY_ENABLED -ne 'true') { throw 'Production must enable external OIDC identities' }
    if ($identity.SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_PRODUCTION_ISSUER_URI -ne $fakeIssuer) {
        throw 'Production did not retain the simulated OIDC issuer'
    }
    if ($identity.SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_PRODUCTION_REDIRECT_URI -ne
            '{baseUrl}/login/oauth2/code/{registrationId}') {
        throw 'Production OIDC callback template is invalid'
    }
    if ($identity.SESSION_COOKIE_SECURE -ne 'true' -or $identity.SESSION_COOKIE_SAME_SITE -ne 'lax') {
        throw 'Production session cookies are not Secure with SameSite=Lax'
    }
    if ($identity.WEB_REDIRECT_URI -ne "$fakeWebOrigin/oauth/callback") {
        throw 'Production web OAuth redirect URI does not use the configured origin'
    }
    if ($gateway.WEB_ORIGIN -ne $fakeWebOrigin -or
            $web.SHOWDOWN_WEB_ORIGIN -ne $fakeWebOrigin -or
            $player.PINKWARD_WEB_ORIGINS -ne $fakeWebOrigin -or
            $match.PINKWARD_WEB_ORIGINS -ne $fakeWebOrigin) {
        throw 'Production CORS origins are not restricted to the fictitious web domain'
    }
    foreach ($service in @('api-gateway', 'identity-service', 'player-service', 'matchmaking-service',
            'match-service', 'postgres', 'match-postgres', 'player-postgres', 'identity-postgres',
            'redis', 'rabbitmq', 'prometheus', 'alertmanager')) {
        $serviceConfiguration = $config.services.PSObject.Properties[$service].Value
        $portsProperty = $serviceConfiguration.PSObject.Properties['ports']
        if ($null -ne $portsProperty -and @($portsProperty.Value).Count -ne 0) {
            throw "Production service $service unexpectedly publishes a host port"
        }
    }
    $caddyPorts = @($config.services.caddy.ports | ForEach-Object { [string]$_.published })
    if ($caddyPorts -notcontains '80' -or $caddyPorts -notcontains '443') {
        throw 'Production Caddy must be the only public HTTP/HTTPS entry point'
    }

    Write-Host 'Fictitious production configuration passed: OIDC, callback, Secure cookie, CORS and port isolation.'
}
finally {
    foreach ($name in $overrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
