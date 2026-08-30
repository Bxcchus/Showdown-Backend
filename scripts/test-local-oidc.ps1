[CmdletBinding()]
param(
    [ValidateRange(0, 65535)][int]$ApplicationPort = 0,
    [ValidateRange(0, 65535)][int]$ProviderPort = 0,
    [switch]$SkipBuild,
    [switch]$KeepStack
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../tests/docker/TestSupport.ps1')

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$composeFile = Join-Path $root 'infra/compose.yml'
$oidcComposeFile = Join-Path $root 'tests/oidc/compose.oidc.yml'
$templateFile = Join-Path $root 'infra/.env.example'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$projectName = "showdown-oidc-$runId"
$environmentFile = Join-Path ([IO.Path]::GetTempPath()) "$projectName.env"

function Get-LoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

if ($ApplicationPort -eq 0) { $ApplicationPort = Get-LoopbackPort }
if ($ProviderPort -eq 0) { $ProviderPort = Get-LoopbackPort }
if ($ApplicationPort -eq $ProviderPort) { throw 'ApplicationPort and ProviderPort must differ' }

$baseUri = "http://localhost:$ApplicationPort"
$providerIssuer = "http://mock-oidc:$ProviderPort/showdown"
$overrides = @{
    SHOWDOWN_HTTP_PORT = [string]$ApplicationPort
    OIDC_MOCK_PORT = [string]$ProviderPort
    JWT_ISSUER = $baseUri
    WEB_REDIRECT_URI = "$baseUri/oauth/callback"
    WEB_DEV_REDIRECT_URI = ''
    LOCAL_BOTS_ENABLED = 'false'
    LOCAL_BOT_RESULTS_ENABLED = 'false'
}
$previousEnvironment = @{}

function Invoke-OidcCompose {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)

    & docker compose `
        --project-name $projectName `
        --env-file $environmentFile `
        -f $composeFile `
        -f $oidcComposeFile `
        @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed: $($Arguments -join ' ')"
    }
}

function Invoke-WithoutRedirect {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session
    )

    $requestUri = [Uri]$Uri
    $parameters = @{
        Uri = $requestUri.AbsoluteUri
        MaximumRedirection = 0
        SkipHttpErrorCheck = $true
        ErrorAction = 'SilentlyContinue'
    }
    if ($requestUri.Host -eq 'mock-oidc') {
        $parameters.Uri = "http://localhost:$($requestUri.Port)$($requestUri.PathAndQuery)"
        $parameters.Headers = @{ Host = $requestUri.Authority }
    }
    if ($null -ne $Session) { $parameters.WebSession = $Session }
    try {
        return Invoke-WebRequest @parameters
    }
    catch {
        throw "HTTP request failed for canonical URI $Uri (request URI $($parameters.Uri)): $($_.Exception.Message)"
    }
}

function Resolve-Location {
    param([Parameter(Mandatory)][string]$CurrentUri, [Parameter(Mandatory)][string]$Location)
    return [Uri]::new([Uri]$CurrentUri, $Location).AbsoluteUri
}

function Get-OidcAccessToken {
    $random = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $verifier = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $digest = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($verifier))
    $challenge = [Convert]::ToBase64String($digest).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $state = [Guid]::NewGuid().ToString('N')
    $redirectUri = "$baseUri/oauth/callback"
    $scopes = @('openid', 'profile:read', 'profile:write', 'party:manage', 'queue:write', 'match:read', 'match:ready')
    $authorizationUri = "$baseUri/oauth2/authorize?" + (@(
            'response_type=code'
            'client_id=pinkward-web'
            ('scope=' + [Uri]::EscapeDataString(($scopes -join ' ')))
            ('redirect_uri=' + [Uri]::EscapeDataString($redirectUri))
            ('state=' + $state)
            ('code_challenge=' + $challenge)
            'code_challenge_method=S256'
        ) -join '&')

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $response = Invoke-WithoutRedirect -Uri $authorizationUri -Session $session
    $currentUri = $authorizationUri
    $callback = $null
    for ($redirect = 0; $redirect -lt 12 -and $response.StatusCode -in @(301, 302, 303, 307, 308); $redirect++) {
        $nextUri = Resolve-Location -CurrentUri $currentUri -Location ([string]$response.Headers.Location[0])
        $currentUri = $nextUri
        if ($nextUri.StartsWith($redirectUri + '?', [StringComparison]::OrdinalIgnoreCase)) {
            $candidate = [Uri]$nextUri
            if (-not [string]::IsNullOrWhiteSpace([Web.HttpUtility]::ParseQueryString($candidate.Query).Get('code'))) {
                $callback = $candidate
                break
            }
        }
        if ($nextUri.StartsWith($baseUri, [StringComparison]::OrdinalIgnoreCase)) {
            $response = Invoke-WithoutRedirect -Uri $nextUri -Session $session
        }
        else {
            $response = Invoke-WithoutRedirect -Uri $nextUri
        }
    }

    if ($null -eq $callback) {
        if ($response.StatusCode -ne 200) {
            throw "OIDC authorization did not reach consent (HTTP $($response.StatusCode), URI $currentUri)"
        }
        $consentState = Read-HiddenInput -Html $response.Content -Name 'state'
        $consentBody = @(
            'client_id=pinkward-web'
            ('state=' + [Uri]::EscapeDataString($consentState))
            ($scopes | ForEach-Object { 'scope=' + [Uri]::EscapeDataString($_) })
        ) -join '&'
        $authorization = Invoke-WebRequest "$baseUri/oauth2/authorize" `
            -Method Post `
            -WebSession $session `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body $consentBody `
            -MaximumRedirection 0 `
            -SkipHttpErrorCheck `
            -ErrorAction SilentlyContinue
        Assert-Equal 302 $authorization.StatusCode 'OIDC consent must return the Pinkward authorization code'
        $callback = [Uri]([string]$authorization.Headers.Location[0])
    }
    $query = [Web.HttpUtility]::ParseQueryString($callback.Query)
    Assert-Equal $state $query.Get('state') 'OIDC login must preserve the Pinkward OAuth state'
    $code = $query.Get('code')
    Assert-True (-not [string]::IsNullOrWhiteSpace($code)) 'OIDC login must return a Pinkward authorization code'

    $tokenResponse = Invoke-WebRequest "$baseUri/oauth2/token" `
            -Method Post `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{
                grant_type = 'authorization_code'
                client_id = 'pinkward-web'
                redirect_uri = $redirectUri
                code = $code
                code_verifier = $verifier
            } `
            -MaximumRedirection 0 `
            -SkipHttpErrorCheck `
            -ErrorAction SilentlyContinue
    if ($tokenResponse.StatusCode -ne 200) {
        $tokenLocation = [string]$tokenResponse.Headers.Location[0]
        throw "Pinkward token exchange failed (HTTP $($tokenResponse.StatusCode), location '$tokenLocation'): $($tokenResponse.Content)"
    }
    return (($tokenResponse.Content | ConvertFrom-Json).access_token)
}

Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'local-secrets.ps1') `
        -Mode Initialize `
        -EnvironmentFile $environmentFile `
        -TemplateFile $templateFile | Out-Null
    foreach ($name in $overrides.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $overrides[$name], 'Process')
    }

    Invoke-OidcCompose down --volumes --remove-orphans
    $up = @('up', '--detach', '--wait', '--wait-timeout', '300')
    if (-not $SkipBuild) { $up += '--build' }
    $up += @('caddy', 'mock-oidc')
    Write-Host "Starting the isolated OIDC stack $projectName"
    try {
        Invoke-OidcCompose @up
    }
    catch {
        Write-Warning 'The first health wait failed; retrying once after transient dependencies settle.'
        Start-Sleep -Seconds 5
        Invoke-OidcCompose up --detach --wait --wait-timeout 300 caddy mock-oidc
    }

    $firstToken = Get-OidcAccessToken
    $firstProfile = Invoke-RestMethod "$baseUri/api/v2/players/me" -Headers (New-BearerHeaders $firstToken)
    $secondToken = Get-OidcAccessToken
    $secondProfile = Invoke-RestMethod "$baseUri/api/v2/players/me" -Headers (New-BearerHeaders $secondToken)

    Assert-Equal $firstProfile.playerId $secondProfile.playerId 'reconnecting with the same issuer and subject must retain the player'
    Assert-Equal $firstProfile.displayName $secondProfile.displayName 'reconnecting must retain the player display name'
    Assert-True ($firstProfile.displayName -like 'OIDC Local Player-*') 'the OIDC display name must be provisioned'

    [pscustomobject]@{
        issuer = $providerIssuer
        callback = 'VERIFIED'
        playerCreation = 'VERIFIED'
        reconnection = 'VERIFIED'
        stablePlayerId = $firstProfile.playerId
    } | Format-List
}
catch {
    try { Invoke-OidcCompose logs --no-color --tail 80 mock-oidc identity-service } catch {}
    throw
}
finally {
    if (-not $KeepStack) {
        try {
            $projectImages = @(& docker compose `
                    --project-name $projectName `
                    --env-file $environmentFile `
                    -f $composeFile `
                    -f $oidcComposeFile `
                    config --images) | Where-Object { $_ -like "$projectName-*" } | Sort-Object -Unique
            Invoke-OidcCompose down --volumes --remove-orphans
            foreach ($image in $projectImages) {
                & docker image rm $image | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Could not remove isolated OIDC image $image" }
            }
        }
        catch {
            Write-Warning "Could not remove isolated OIDC project $projectName"
        }
        if (Test-Path -LiteralPath $environmentFile) { Remove-Item -LiteralPath $environmentFile -Force }
    }
    foreach ($name in $overrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    Pop-Location
}
