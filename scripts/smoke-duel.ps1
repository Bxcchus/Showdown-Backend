[CmdletBinding()]
param([string]$BaseUri = 'http://localhost:8088')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$environmentFile = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')) 'infra/.env'

function Read-DotEnvValue([string]$Name) {
    $line = Get-Content -LiteralPath $environmentFile | Where-Object { $_ -like "$Name=*" } | Select-Object -First 1
    if (-not $line) { throw "Missing $Name" }
    $line.Substring($line.IndexOf('=') + 1)
}

function Read-HiddenInput([string]$Html, [string]$Name) {
    $input = [Regex]::Match($Html, '<input\b[^>]*\bname="' + [Regex]::Escape($Name) + '"[^>]*>', 'IgnoreCase')
    $value = [Regex]::Match($input.Value, '\bvalue="([^"]*)"', 'IgnoreCase')
    if (-not $value.Success) { throw "OAuth field $Name was not found" }
    $value.Groups[1].Value
}

function Read-LocationHeader($Response) {
    $value = $Response.Headers.Location
    if ($value -is [Array]) { return [string]$value[0] }
    [string]$value
}

function Get-AccessToken([string]$Username, [string]$Password) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $verifier = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $digest = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($verifier))
    $challenge = [Convert]::ToBase64String($digest).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $redirect = 'http://localhost:8088/oauth/callback'
    $state = [Guid]::NewGuid().ToString('N')
    $scope = 'openid profile:read profile:write party:manage queue:write match:read match:ready'
    $query = 'response_type=code&client_id=pinkward-web&scope=' + [Uri]::EscapeDataString($scope) +
        '&redirect_uri=' + [Uri]::EscapeDataString($redirect) + '&state=' + $state +
        '&code_challenge=' + $challenge + '&code_challenge_method=S256'
    $login = Invoke-WebRequest "$BaseUri/oauth2/authorize?$query" -SessionVariable oauth
    $afterLogin = Invoke-WebRequest "$BaseUri/login" -Method Post -WebSession $oauth -Body @{
        username = $Username; password = $Password; _csrf = Read-HiddenInput $login.Content '_csrf'
    } -MaximumRedirection 0 -SkipHttpErrorCheck -ErrorAction SilentlyContinue
    Write-Verbose "OAuth login status for ${Username}: $($afterLogin.StatusCode)"
    if ($afterLogin.StatusCode -ne 302) { throw "OAuth login failed for $Username" }
    $authorizeLocation = Read-LocationHeader $afterLogin
    if ([string]::IsNullOrWhiteSpace($authorizeLocation)) { throw "OAuth authorize redirect missing for $Username" }
    $authorizeUri = [Uri]::new([Uri]"$BaseUri/", $authorizeLocation).AbsoluteUri
    $authorization = Invoke-WebRequest $authorizeUri -WebSession $oauth -MaximumRedirection 0 `
        -SkipHttpErrorCheck -ErrorAction SilentlyContinue
    for ($redirectCount = 0; $redirectCount -lt 3 -and $authorization.StatusCode -eq 302; $redirectCount++) {
        $nextLocation = Read-LocationHeader $authorization
        if ([string]::IsNullOrWhiteSpace($nextLocation)) { throw "OAuth intermediate redirect missing for $Username" }
        $nextUri = [Uri]::new([Uri]"$BaseUri/", $nextLocation)
        if ($nextUri.AbsolutePath -eq '/oauth/callback') { break }
        $authorization = Invoke-WebRequest $nextUri.AbsoluteUri -WebSession $oauth -MaximumRedirection 0 `
            -SkipHttpErrorCheck -ErrorAction SilentlyContinue
    }
    if ($authorization.StatusCode -eq 200) {
        $consentState = Read-HiddenInput $authorization.Content 'state'
        $body = 'client_id=pinkward-web&state=' + [Uri]::EscapeDataString($consentState) +
            '&scope=openid&scope=profile%3Aread&scope=profile%3Awrite&scope=party%3Amanage' +
            '&scope=queue%3Awrite&scope=match%3Aread&scope=match%3Aready'
        $authorization = Invoke-WebRequest "$BaseUri/oauth2/authorize" -Method Post -WebSession $oauth `
            -ContentType 'application/x-www-form-urlencoded' -Body $body -MaximumRedirection 0 `
            -SkipHttpErrorCheck -ErrorAction SilentlyContinue
    }
    if ($authorization.StatusCode -ne 302) { throw "OAuth failed for $Username" }
    $location = Read-LocationHeader $authorization
    if ([string]::IsNullOrWhiteSpace($location)) { throw "OAuth redirect missing for $Username" }
    $locationUri = [Uri]::new([Uri]"$BaseUri/", $location)
    $callback = [System.Web.HttpUtility]::ParseQueryString($locationUri.Query)
    Write-Verbose "OAuth redirect target for ${Username}: $($locationUri.AbsolutePath); parameters: $($callback.AllKeys -join ', ')"
    $authorizationCode = $callback.Get('code')
    if ([string]::IsNullOrWhiteSpace($authorizationCode)) { throw "OAuth authorization code missing for $Username" }
    Write-Verbose "OAuth authorization code received for ${Username} (length $($authorizationCode.Length))"
    (Invoke-RestMethod "$BaseUri/oauth2/token" -Method Post -ContentType 'application/x-www-form-urlencoded' -Body @{
        grant_type = 'authorization_code'; client_id = 'pinkward-web'; redirect_uri = $redirect
        code = $authorizationCode; code_verifier = $verifier
    }).access_token
}

function Auth([string]$Token) { @{ Authorization = "Bearer $Token" } }
function Get-WatcherServiceToken([string]$Scope) {
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
        'pinkward-watcher:' + (Read-DotEnvValue 'WATCHER_CLIENT_SECRET')))
    (Invoke-RestMethod "$BaseUri/oauth2/token" -Method Post `
        -Headers @{ Authorization = "Basic $basic" } `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'client_credentials'; scope = $Scope }).access_token
}
function WatcherAuth([string]$RawToken, [string]$ServiceToken) {
    @{ Authorization = "Bearer $ServiceToken"; 'X-Watcher-Token' = $RawToken }
}

$hostToken = Get-AccessToken (Read-DotEnvValue 'LOCAL_IDENTITY_USERNAME') (Read-DotEnvValue 'LOCAL_IDENTITY_PASSWORD')
$guestToken = Get-AccessToken (Read-DotEnvValue 'LOCAL_IDENTITY_SECONDARY_USERNAME') (Read-DotEnvValue 'LOCAL_IDENTITY_SECONDARY_PASSWORD')
$hostHeaders = Auth $hostToken
$guestHeaders = Auth $guestToken

$hostProfile = Invoke-RestMethod "$BaseUri/api/v2/players/me" -Headers $hostHeaders
$guestProfile = Invoke-RestMethod "$BaseUri/api/v2/players/me" -Headers $guestHeaders
$hostLink = Invoke-RestMethod "$BaseUri/api/v2/players/me/riot-link-challenges" -Method Post -Headers $hostHeaders
$guestLink = Invoke-RestMethod "$BaseUri/api/v2/players/me/riot-link-challenges" -Method Post -Headers $guestHeaders
Invoke-RestMethod "$BaseUri/api/v2/players/me/riot-link-challenges/$($hostLink.challengeId)/complete" `
    -Method Post -Headers $hostHeaders -ContentType 'application/json' -Body (@{
        puuid = 'smoke-duel-host-puuid-0001'; gameName = 'Claude Code'; tagLine = 'JAVA'
        profileIconId = 1; summonerLevel = 30
    } | ConvertTo-Json) | Out-Null
Invoke-RestMethod "$BaseUri/api/v2/players/me/riot-link-challenges/$($guestLink.challengeId)/complete" `
    -Method Post -Headers $guestHeaders -ContentType 'application/json' -Body (@{
        puuid = 'smoke-duel-guest-puuid-0001'; gameName = 'Codex'; tagLine = 'GPT'
        profileIconId = 1; summonerLevel = 30
    } | ConvertTo-Json) | Out-Null
$hostProfile = Invoke-RestMethod "$BaseUri/api/v2/players/me" -Headers $hostHeaders
$guestProfile = Invoke-RestMethod "$BaseUri/api/v2/players/me" -Headers $guestHeaders

$challenge = Invoke-RestMethod "$BaseUri/api/v2/matches/duels" -Method Post -Headers $hostHeaders `
    -ContentType 'application/json' -Body (@{ opponentId = $guestProfile.playerId } | ConvertTo-Json)
$accepted = Invoke-RestMethod "$BaseUri/api/v2/matches/duels/$($challenge.challengeId)/accept" -Method Post -Headers $guestHeaders
$hostWatcher = Invoke-RestMethod "$BaseUri/api/v2/matches/duels/$($accepted.matchId)/watcher-token" -Method Post -Headers $hostHeaders
$guestWatcher = Invoke-RestMethod "$BaseUri/api/v2/matches/duels/$($accepted.matchId)/watcher-token" -Method Post -Headers $guestHeaders
$resultToken = Get-WatcherServiceToken 'service:duel:observe'
$hostAssignment = Invoke-RestMethod "$BaseUri/api/v2/watchers/duels/$($accepted.matchId)" -Headers (WatcherAuth $hostWatcher.token $resultToken)
$guestAssignment = Invoke-RestMethod "$BaseUri/api/v2/watchers/duels/$($accepted.matchId)" -Headers (WatcherAuth $guestWatcher.token $resultToken)

$observation = @{ objective = 'FIRST_BLOOD'; winnerRiotId = $hostProfile.riotId; observedAt = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json
$first = Invoke-RestMethod "$BaseUri/api/v2/watchers/duels/$($accepted.matchId)/observations" -Method Post `
    -Headers (WatcherAuth $hostWatcher.token $resultToken) -ContentType 'application/json' -Body $observation
$second = Invoke-RestMethod "$BaseUri/api/v2/watchers/duels/$($accepted.matchId)/observations" -Method Post `
    -Headers (WatcherAuth $guestWatcher.token $resultToken) -ContentType 'application/json' -Body $observation

if ($hostAssignment.role -ne 'HOST' -or $guestAssignment.role -ne 'GUEST' `
        -or $hostAssignment.ownPuuid -ne 'smoke-duel-host-puuid-0001' `
        -or $guestAssignment.ownPuuid -ne 'smoke-duel-guest-puuid-0001' `
        -or $first.status -ne 'WAITING_FOR_SECOND_WATCHER' -or $second.status -ne 'VERIFIED') {
    throw 'Direct duel watcher consensus is inconsistent'
}

[pscustomobject]@{
    challenge = $challenge.status
    match = $accepted.matchId
    host = "$($hostProfile.displayName) -> $($hostProfile.riotId)"
    guest = "$($guestProfile.displayName) -> $($guestProfile.riotId)"
    host_watcher = $hostAssignment.role
    guest_watcher = $guestAssignment.role
    host_expected_puuid = $hostAssignment.ownPuuid
    guest_expected_puuid = $guestAssignment.ownPuuid
    first_observation = $first.status
    second_observation = $second.status
    glicko2_result = 'RECORDED'
}
