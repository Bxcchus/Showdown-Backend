[CmdletBinding()]
param(
    [string]$BaseUri = 'http://localhost:8088',
    [switch]$IncludeMatchFlow,
    [switch]$IncludeBotConfirmationFlow,
    [switch]$IncludePartyFlow,
    [switch]$MeasureRateLimits
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$environmentFile = Join-Path $root 'infra/.env'

if (@($IncludeMatchFlow, $IncludeBotConfirmationFlow, $IncludePartyFlow).Where({ $_ }).Count -gt 1) {
    throw 'Choose only one optional flow.'
}

function Read-DotEnvValue {
    param([Parameter(Mandatory)][string]$Name)

    $line = Get-Content -LiteralPath $environmentFile |
        Where-Object { $_ -like "$Name=*" } |
        Select-Object -First 1
    if (-not $line) {
        throw "Missing $Name in $environmentFile"
    }
    return $line.Substring($line.IndexOf('=') + 1)
}

function Read-HiddenInput {
    param(
        [Parameter(Mandatory)][string]$Html,
        [Parameter(Mandatory)][string]$Name
    )

    $inputPattern = '<input\b[^>]*\bname="' + [Regex]::Escape($Name) + '"[^>]*>'
    $inputMatch = [Regex]::Match($Html, $inputPattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $inputMatch.Success) {
        throw "OAuth form field '$Name' was not found"
    }
    $valueMatch = [Regex]::Match($inputMatch.Value, '\bvalue="([^"]*)"', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $valueMatch.Success) {
        throw "OAuth form field '$Name' has no value"
    }
    return $valueMatch.Groups[1].Value
}

function Test-RealtimeSocket {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$AccessToken
    )
    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $socket.Options.AddSubProtocol('showdown-v1')
    $socket.Options.AddSubProtocol("bearer.$AccessToken")
    $cancellation = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(8))
    try {
        $webSocketBase = $BaseUri -replace '^http', 'ws'
        [void]$socket.ConnectAsync([Uri]("$webSocketBase$Path"), $cancellation.Token).GetAwaiter().GetResult()
        $bytes = New-Object byte[] 4096
        $segment = [ArraySegment[byte]]::new($bytes)
        $received = $socket.ReceiveAsync($segment, $cancellation.Token).GetAwaiter().GetResult()
        $eventText = [Text.Encoding]::UTF8.GetString($bytes, 0, $received.Count)
        if ($socket.SubProtocol -ne 'showdown-v1' -or $eventText -notmatch '"type"\s*:\s*"CONNECTED"') {
            throw "WebSocket $Path did not complete its authenticated handshake: $eventText"
        }
        return 'CONNECTED'
    } finally {
        if ($socket.State -eq [Net.WebSockets.WebSocketState]::Open) {
            [void]$socket.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'smoke', [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        }
        $socket.Dispose()
        $cancellation.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw 'infra/.env is missing. Run scripts/start.ps1 first.'
}

$readiness = Invoke-RestMethod "$BaseUri/actuator/health/readiness"
if ($readiness.status -ne 'UP') {
    throw "Gateway readiness is $($readiness.status)"
}

$randomBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$verifier = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$challengeBytes = [Security.Cryptography.SHA256]::HashData(
    [Text.Encoding]::ASCII.GetBytes($verifier))
$challenge = [Convert]::ToBase64String($challengeBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$redirectUri = 'http://localhost:8088/oauth/callback'
$scope = 'openid profile:read profile:write party:manage queue:write match:read match:ready'
$requestState = [Guid]::NewGuid().ToString('N')
$authorizationQuery = @(
    'response_type=code'
    'client_id=pinkward-web'
    ('scope=' + [Uri]::EscapeDataString($scope))
    ('redirect_uri=' + [Uri]::EscapeDataString($redirectUri))
    ('state=' + $requestState)
    ('code_challenge=' + $challenge)
    'code_challenge_method=S256'
) -join '&'

$loginPage = Invoke-WebRequest "$BaseUri/oauth2/authorize?$authorizationQuery" -SessionVariable oauthSession
$loginCsrf = Read-HiddenInput -Html $loginPage.Content -Name '_csrf'
$afterLogin = Invoke-WebRequest "$BaseUri/login" `
    -Method Post `
    -WebSession $oauthSession `
    -Body @{
        username = Read-DotEnvValue 'LOCAL_IDENTITY_USERNAME'
        password = Read-DotEnvValue 'LOCAL_IDENTITY_PASSWORD'
        _csrf = $loginCsrf
    } `
    -MaximumRedirection 1 `
    -SkipHttpErrorCheck `
    -ErrorAction SilentlyContinue

$consentPage = $afterLogin
if ($afterLogin.StatusCode -eq 302 -and $afterLogin.Headers.Location[0] -like '*/oauth2/consent*') {
    $consentPage = Invoke-WebRequest $afterLogin.Headers.Location[0] -WebSession $oauthSession
}

if ($consentPage.StatusCode -eq 200) {
    $state = Read-HiddenInput -Html $consentPage.Content -Name 'state'
    $consentBody = @(
        'client_id=pinkward-web'
        ('state=' + [Uri]::EscapeDataString($state))
        'scope=openid'
        'scope=profile%3Aread'
        'scope=profile%3Awrite'
        'scope=party%3Amanage'
        'scope=queue%3Awrite'
        'scope=match%3Aread'
        'scope=match%3Aready'
    ) -join '&'

    $authorizationResponse = Invoke-WebRequest "$BaseUri/oauth2/authorize" `
        -Method Post `
        -WebSession $oauthSession `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body $consentBody `
        -MaximumRedirection 0 `
        -SkipHttpErrorCheck `
        -ErrorAction SilentlyContinue
} else {
    $authorizationResponse = $afterLogin
}

if ($authorizationResponse.StatusCode -ne 302) {
    throw "OAuth authorization returned HTTP $($authorizationResponse.StatusCode)"
}
$callbackLocation = $authorizationResponse.Headers.Location[0]
$callbackQuery = [System.Web.HttpUtility]::ParseQueryString(([Uri]$callbackLocation).Query)
$authorizationCode = $callbackQuery.Get('code')
if (-not $authorizationCode) {
    throw 'OAuth authorization code was not returned'
}
if ($callbackQuery.Get('state') -ne $requestState) {
    throw 'OAuth state validation failed'
}

$token = Invoke-RestMethod "$BaseUri/oauth2/token" `
    -Method Post `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        grant_type = 'authorization_code'
        client_id = 'pinkward-web'
        redirect_uri = $redirectUri
        code = $authorizationCode
        code_verifier = $verifier
    }
$authorization = @{ Authorization = "Bearer $($token.access_token)" }
$partyWebSocket = Test-RealtimeSocket -Path '/api/v2/realtime/party' -AccessToken $token.access_token
$matchWebSocket = Test-RealtimeSocket -Path '/api/v2/realtime/matches' -AccessToken $token.access_token
$leaderboardResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/leaderboard?region=EUW&limit=10" -Headers $authorization
$leaderboard = $leaderboardResponse.Content | ConvertFrom-Json
if ($leaderboard.season -ne 'S2026' -or $leaderboard.region -ne 'EUW' -or $leaderboard.placementGames -ne 5) {
    throw 'The active regional leaderboard is inconsistent'
}
$duelStatisticsResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/duel/statistics?region=EUW" -Headers $authorization
$duelStatistics = $duelStatisticsResponse.Content | ConvertFrom-Json
if ($duelStatistics.algorithm -ne 'GLICKO_2' -or $duelStatistics.mmr -lt 0 `
        -or $duelStatistics.ratingDeviation -le 0 -or $duelStatistics.volatility -le 0 `
        -or ($duelStatistics.games -eq 0 -and ($duelStatistics.mmr -ne 1500 `
            -or $duelStatistics.ratingDeviation -ne 350 -or $duelStatistics.volatility -ne 0.06))) {
    throw 'The initial Glicko-2 duel statistics are inconsistent'
}
$duelLeaderboardResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/duel/leaderboard?region=EUW&limit=10" -Headers $authorization
$duelLeaderboard = $duelLeaderboardResponse.Content | ConvertFrom-Json
if ($duelLeaderboard.algorithm -ne 'GLICKO_2' -or $duelLeaderboard.season -ne 'S2026' `
        -or $duelLeaderboard.region -ne 'EUW' -or $duelLeaderboard.placementGames -ne 5) {
    throw 'The regional Glicko-2 duel leaderboard is inconsistent'
}
$rateLimitAccepted = $null
$rateLimitRejected = $null
$profileReadHttp = $null
$profileUpdateHttp = $null
$presenceHttp = $null
$originalProfile = Invoke-RestMethod "$BaseUri/api/v2/players/me" -Headers $authorization
$profileReadHttp = 200
$temporaryProfileName = 'smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
try {
    $profileUpdate = Invoke-WebRequest "$BaseUri/api/v2/players/me" `
        -Method Put `
        -Headers $authorization `
        -ContentType 'application/json' `
        -Body (@{
            displayName = $temporaryProfileName
            region = 'EUW'
            primaryRole = 'JUNGLE'
            secondaryRole = 'MID'
        } | ConvertTo-Json -Compress)
    $profileUpdateHttp = $profileUpdate.StatusCode
    $updatedProfile = $profileUpdate.Content | ConvertFrom-Json
    if ($updatedProfile.displayName -ne $temporaryProfileName `
            -or $updatedProfile.primaryRole -ne 'JUNGLE' `
            -or $updatedProfile.secondaryRole -ne 'MID') {
        throw 'Player profile update was not persisted correctly'
    }

    $presence = Invoke-WebRequest "$BaseUri/api/v2/players/me/presence" `
        -Method Post `
        -Headers $authorization
    $presenceHttp = $presence.StatusCode
    if (-not ($presence.Content | ConvertFrom-Json).online) {
        throw 'Player presence heartbeat did not mark the profile online'
    }
} finally {
    Invoke-WebRequest "$BaseUri/api/v2/players/me" `
        -Method Put `
        -Headers $authorization `
        -ContentType 'application/json' `
        -Body (@{
            displayName = $originalProfile.displayName
            region = $originalProfile.region
            primaryRole = $originalProfile.primaryRole
            secondaryRole = $originalProfile.secondaryRole
        } | ConvertTo-Json -Compress) | Out-Null
}

if ($IncludePartyFlow) {
    $currentParty = Invoke-WebRequest "$BaseUri/api/v2/parties/current" `
        -Headers $authorization `
        -SkipHttpErrorCheck
    if ($currentParty.StatusCode -ne 404) {
        throw 'The local test account already belongs to a party; leave it before running IncludePartyFlow.'
    }

    $partyCreated = Invoke-WebRequest "$BaseUri/api/v2/parties" -Method Post -Headers $authorization
    $party = $partyCreated.Content | ConvertFrom-Json
    $partyId = $party.partyId
    $partySearchStarted = $false
    try {
        foreach ($name in @('Smoke Top', 'Smoke Jungle', 'Smoke ADC', 'Smoke Support')) {
            $inviteResponse = Invoke-WebRequest "$BaseUri/api/v2/parties/current/invitations" `
                -Method Post `
                -Headers $authorization `
                -ContentType 'application/json' `
                -Body (@{ displayName = $name } | ConvertTo-Json -Compress)
            $party = $inviteResponse.Content | ConvertFrom-Json
        }
        if (@($party.members).Count -ne 5) {
            throw 'The four local invitations did not produce a five-player party'
        }

        foreach ($member in @($party.members | Where-Object simulated)) {
            $readyResponse = Invoke-WebRequest "$BaseUri/api/v2/parties/current/members/$($member.playerId)/ready" `
                -Method Put `
                -Headers $authorization `
                -ContentType 'application/json' `
                -Body '{"ready":true}'
            $party = $readyResponse.Content | ConvertFrom-Json
        }
        $selfReady = Invoke-WebRequest "$BaseUri/api/v2/parties/current/ready" `
            -Method Put `
            -Headers $authorization `
            -ContentType 'application/json' `
            -Body '{"ready":true}'
        $party = $selfReady.Content | ConvertFrom-Json
        if (-not $party.allReady) {
            throw 'The party did not become ready after all five confirmations'
        }

        $partySearch = Invoke-WebRequest "$BaseUri/api/v2/parties/current/search" `
            -Method Post `
            -Headers ($authorization + @{ 'Idempotency-Key' = 'party-smoke-' + [Guid]::NewGuid().ToString('N') })
        $partySearchStarted = $true
        $partyQueue = Invoke-RestMethod "$BaseUri/api/v2/matchmaking/queue" -Headers $authorization
        if ($partyQueue.partyId -ne $partyId) {
            throw 'The leader queue entry is not attached to the party'
        }
        $queuedPartyMembers = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
            psql -U matchmaking_service -d pinkward_matchmaking -Atc `
            "SELECT count(*) FROM queue_entries WHERE party_id = '$partyId';"
        if ([int]$queuedPartyMembers -ne 5) {
            throw "Expected five atomic party queue entries, found $queuedPartyMembers"
        }

        [pscustomobject]@{
            readiness = $readiness.status
            oauth_pkce = 'OK'
            party_create_http = $partyCreated.StatusCode
            party_members = @($party.members).Count
            party_all_ready = $party.allReady
            party_search_http = $partySearch.StatusCode
            atomic_queue_entries = [int]$queuedPartyMembers
            party_id = $partyId
        } | Format-List
    } finally {
        if ($partySearchStarted) {
            Invoke-WebRequest "$BaseUri/api/v2/parties/current/search" `
                -Method Delete `
                -Headers $authorization | Out-Null
        }
        Invoke-WebRequest "$BaseUri/api/v2/parties/current" `
            -Method Delete `
            -Headers $authorization | Out-Null
    }
    return
}

$idempotencyKey = 'local-smoke-' + [Guid]::NewGuid().ToString('N')

$join = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
    -Method Post `
    -Headers ($authorization + @{ 'Idempotency-Key' = $idempotencyKey }) `
    -ContentType 'application/json' `
    -Body '{"region":"EUW","primaryRole":"JUNGLE","secondaryRole":"MID"}'
$status = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" -Headers $authorization
$matchFlowHttp = $null
$readyHttp = $null
$declinerRemovedHttp = $null
$readyAcceptHttp = $null
$confirmedReleasedHttp = $null
$confirmedCurrentClosedHttp = $null
$confirmedLobbyHttp = $null
$completedLobbyClosedHttp = $null

if ($IncludeMatchFlow) {
    $simulationPrefix = 'match-flow-' + [Guid]::NewGuid().ToString('N') + '-'
    $reservationId = $null
    $eventId = $null
    $cancellationEventId = $null
    try {
        $opponentsSql = @"
INSERT INTO queue_entries (
    id, player_id, region, mode, idempotency_key, joined_at,
    primary_role, secondary_role, status, reservation_id)
SELECT gen_random_uuid(), gen_random_uuid(), 'EUW', 'FIVE_V_FIVE',
       '$simulationPrefix' || n, clock_timestamp() + (n * interval '1 microsecond'),
       (ARRAY['TOP','TOP','JUNGLE','MID','MID','BOT','BOT','SUPPORT','SUPPORT'])[n],
       CASE (ARRAY['TOP','TOP','JUNGLE','MID','MID','BOT','BOT','SUPPORT','SUPPORT'])[n]
           WHEN 'TOP' THEN 'JUNGLE'
           WHEN 'JUNGLE' THEN 'MID'
           WHEN 'MID' THEN 'BOT'
           WHEN 'BOT' THEN 'SUPPORT'
           ELSE 'TOP'
       END,
       'QUEUED', NULL
FROM generate_series(1, 9) AS opponents(n);
"@
        & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
            psql -U matchmaking_service -d pinkward_matchmaking -Atc $opponentsSql | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not add local match-flow opponents' }

        $deadline = (Get-Date).AddSeconds(15)
        do {
            $currentMatchResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/current" `
                -Headers $authorization `
                -SkipHttpErrorCheck
            if ($currentMatchResponse.StatusCode -eq 200) { break }
            if ($currentMatchResponse.StatusCode -ne 404) {
                throw "Current match returned HTTP $($currentMatchResponse.StatusCode)"
            }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        if ($currentMatchResponse.StatusCode -ne 200) { throw 'MATCH_FOUND was not consumed in time' }

        $currentMatch = $currentMatchResponse.Content | ConvertFrom-Json
        $reservationId = $currentMatch.reservationId
        $matchFlowHttp = $currentMatchResponse.StatusCode
        $readyResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/$($currentMatch.matchId)/ready" `
            -Method Post `
            -Headers $authorization `
            -ContentType 'application/json' `
            -Body '{"accepted":false}'
        $readyHttp = $readyResponse.StatusCode

        $cancellationDeadline = (Get-Date).AddSeconds(10)
        do {
            $queueAfterDecline = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
                -Headers $authorization `
                -SkipHttpErrorCheck
            if ($queueAfterDecline.StatusCode -eq 404) { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $cancellationDeadline)
        if ($queueAfterDecline.StatusCode -ne 404) {
            throw 'MATCH_CANCELLED did not remove the declining player in time'
        }
        $declinerRemovedHttp = $queueAfterDecline.StatusCode

        $requeuedOpponents = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
            psql -U matchmaking_service -d pinkward_matchmaking -Atc `
            "SELECT count(*) FROM queue_entries WHERE idempotency_key LIKE '$simulationPrefix%' AND status = 'QUEUED';"
        if ([int]$requeuedOpponents -ne 9) {
            throw "MATCH_CANCELLED requeued $requeuedOpponents synthetic players instead of 9"
        }

        $eventId = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
            psql -U matchmaking_service -d pinkward_matchmaking -Atc `
            "SELECT id FROM outbox_events WHERE payload::jsonb #>> '{payload,reservationId}' = '$reservationId' LIMIT 1;"
        if (-not $eventId) { throw 'Outbox event was not found for the created match' }
        $cancellationEventId = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T match-postgres `
            psql -U match_service -d pinkward_matches -Atc `
            "SELECT id FROM outbox_events WHERE payload::jsonb #>> '{payload,reservationId}' = '$reservationId' LIMIT 1;"
        if (-not $cancellationEventId) { throw 'MATCH_CANCELLED outbox event was not found' }
    } finally {
        if ($reservationId) {
            $matchCleanup = @()
            if ($eventId) { $matchCleanup += "DELETE FROM inbox_events WHERE event_id = '$eventId';" }
            if ($cancellationEventId) { $matchCleanup += "DELETE FROM outbox_events WHERE id = '$cancellationEventId';" }
            $matchCleanup += "DELETE FROM matches WHERE reservation_id = '$reservationId';"
            & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T match-postgres `
                psql -U match_service -d pinkward_matches -Atc `
                ($matchCleanup -join ' ') | Out-Null

            $matchmakingCleanup = @()
            if ($cancellationEventId) { $matchmakingCleanup += "DELETE FROM inbox_events WHERE event_id = '$cancellationEventId';" }
            if ($eventId) { $matchmakingCleanup += "DELETE FROM outbox_events WHERE id = '$eventId';" }
            $matchmakingCleanup += "DELETE FROM queue_entries WHERE idempotency_key LIKE '$simulationPrefix%';"
            & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
                psql -U matchmaking_service -d pinkward_matchmaking -Atc `
                ($matchmakingCleanup -join ' ') | Out-Null
        } else {
            & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
                psql -U matchmaking_service -d pinkward_matchmaking -Atc `
                "DELETE FROM queue_entries WHERE idempotency_key LIKE '$simulationPrefix%';" | Out-Null
        }
    }
}

if ($IncludeBotConfirmationFlow) {
    $reservationId = $null
    $eventId = $null
    $confirmationEventId = $null
    $ratingEventId = $null
    $ratingPlayerId = $null
    $previousProjectedRating = $null
    $previousProjectedPeak = $null
    $previousProjectedGames = $null
    $previousProjectedMean = $null
    $previousProjectedDeviation = $null
    try {
        $deadline = (Get-Date).AddSeconds(15)
        do {
            $currentMatchResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/current" `
                -Headers $authorization `
                -SkipHttpErrorCheck
            if ($currentMatchResponse.StatusCode -eq 200) { break }
            if ($currentMatchResponse.StatusCode -ne 404) {
                throw "Current bot match returned HTTP $($currentMatchResponse.StatusCode)"
            }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        if ($currentMatchResponse.StatusCode -ne 200) { throw 'Local bots did not complete the match in time' }

        $currentMatch = $currentMatchResponse.Content | ConvertFrom-Json
        $reservationId = $currentMatch.reservationId
        $matchFlowHttp = $currentMatchResponse.StatusCode
        if (($currentMatch.players | Where-Object bot).Count -ne 9) {
            throw 'The local ready check does not contain exactly nine bots'
        }
        $readyResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/$($currentMatch.matchId)/ready" `
            -Method Post `
            -Headers $authorization `
            -ContentType 'application/json' `
            -Body '{"accepted":true}'
        $readyAcceptHttp = $readyResponse.StatusCode
        if (($readyResponse.Content | ConvertFrom-Json).status -ne 'CONFIRMED') {
            throw 'The bot match was not confirmed after the real player accepted'
        }

        $releaseDeadline = (Get-Date).AddSeconds(10)
        do {
            $queueAfterConfirmation = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
                -Headers $authorization `
                -SkipHttpErrorCheck
            if ($queueAfterConfirmation.StatusCode -eq 404) { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $releaseDeadline)
        if ($queueAfterConfirmation.StatusCode -ne 404) {
            throw 'MATCH_CONFIRMED did not release the matchmaking reservation in time'
        }
        $confirmedReleasedHttp = $queueAfterConfirmation.StatusCode

        $currentAfterConfirmation = Invoke-WebRequest "$BaseUri/api/v2/matches/current" `
            -Headers $authorization `
            -SkipHttpErrorCheck
        if ($currentAfterConfirmation.StatusCode -ne 404) {
            throw 'The confirmed match is still returned as the current ready check'
        }
        $confirmedCurrentClosedHttp = $currentAfterConfirmation.StatusCode

        $lobbyResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/current-lobby" `
            -Headers $authorization `
            -SkipHttpErrorCheck
        if ($lobbyResponse.StatusCode -ne 200) {
            throw "The confirmed lobby returned HTTP $($lobbyResponse.StatusCode)"
        }
        $confirmedLobbyHttp = $lobbyResponse.StatusCode
        $confirmedLobby = $lobbyResponse.Content | ConvertFrom-Json
        if ($confirmedLobby.lobbyName -notmatch '^SWD-[A-F0-9]{8}$') {
            throw 'The confirmed lobby name is missing or invalid'
        }
        if ($confirmedLobby.lobbyPassword -notmatch '^[A-HJ-NP-Z2-9]{8}$') {
            throw 'The confirmed lobby password is missing or invalid'
        }
        $humanPlayers = @($confirmedLobby.players | Where-Object { -not $_.bot })
        if ($humanPlayers.Count -ne 1 -or $humanPlayers[0].assignedRole -ne 'JUNGLE') {
            throw 'The real player did not keep the JUNGLE primary role'
        }
        foreach ($team in @('BLUE', 'RED')) {
            $teamRoles = @($confirmedLobby.players |
                Where-Object team -eq $team |
                Select-Object -ExpandProperty assignedRole -Unique)
            if ($teamRoles.Count -ne 5) {
                throw "Team $team does not contain five unique assigned roles"
            }
        }

        $resultCredentials = [Convert]::ToBase64String(
            [Text.Encoding]::UTF8.GetBytes('pinkward-result-ingestor:' + (Read-DotEnvValue 'RESULT_INGESTOR_CLIENT_SECRET')))
        $resultToken = Invoke-RestMethod "$BaseUri/oauth2/token" `
            -Method Post `
            -Headers @{ Authorization = "Basic $resultCredentials" } `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body 'grant_type=client_credentials&scope=service%3Amatch%3Aresult'
        $resultResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/$($confirmedLobby.matchId)/result" `
            -Method Post `
            -Headers @{ Authorization = "Bearer $($resultToken.access_token)" } `
            -ContentType 'application/json' `
            -Body ('{"winningTeam":"' + $humanPlayers[0].team + '"}')
        if (($resultResponse.Content | ConvertFrom-Json).status -ne 'COMPLETED') {
            throw 'The confirmed lobby result could not be recorded'
        }
        $historyResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/history" -Headers $authorization
        $historyPage = $historyResponse.Content | ConvertFrom-Json
        $history = @($historyPage.content)
        $historyEntry = $history | Where-Object matchId -eq $confirmedLobby.matchId | Select-Object -First 1
        if (-not $historyEntry -or $historyEntry.outcome -ne 'VICTORY' -or $historyEntry.mmrDelta -le 0) {
            throw 'The completed match is missing from history or has an invalid MMR change'
        }
        if ($historyPage.page -ne 0 -or $historyPage.size -ne 10 -or $historyPage.totalElements -lt 1) {
            throw 'The paginated history metadata is inconsistent'
        }
        $filteredHistory = Invoke-RestMethod "$BaseUri/api/v2/matches/history?region=EUW&role=$($historyEntry.role)&outcome=VICTORY&size=5" -Headers $authorization
        if (-not (@($filteredHistory.content) | Where-Object matchId -eq $confirmedLobby.matchId)) {
            throw 'The history filters did not return the completed match'
        }
        $matchDetail = Invoke-RestMethod "$BaseUri/api/v2/matches/history/$($confirmedLobby.matchId)" -Headers $authorization
        if (@($matchDetail.teammates).Count -ne 5 -or @($matchDetail.opponents).Count -ne 5 `
                -or @($matchDetail.teammates | Where-Object self).Count -ne 1) {
            throw 'The detailed match roster is incomplete'
        }
        $statisticsResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/statistics" -Headers $authorization
        $statistics = $statisticsResponse.Content | ConvertFrom-Json
        if ($statistics.games -lt 1 -or $statistics.wins -lt 1 -or $statistics.mmr -ne $historyEntry.newMmr `
                -or $statistics.skillMean -le 25 -or $statistics.skillDeviation -ge (25 / 3)) {
            throw 'The match statistics are inconsistent with the recorded result'
        }
        $ratingPlayerId = $humanPlayers[0].playerId
        $ratingEventId = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T match-postgres `
            psql -U match_service -d pinkward_matches -Atc `
            "SELECT id FROM outbox_events WHERE event_type = 'PLAYER_RATING_UPDATED' AND payload::jsonb #>> '{payload,playerId}' = '$ratingPlayerId' ORDER BY created_at DESC LIMIT 1;"
        if (-not $ratingEventId) { throw 'PLAYER_RATING_UPDATED outbox event was not found' }

        $projectionDeadline = (Get-Date).AddSeconds(10)
        do {
            $projectedSkill = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
                psql -U matchmaking_service -d pinkward_matchmaking -Atc `
                "SELECT rating || '|' || skill_mean || '|' || skill_deviation FROM player_rating_snapshots WHERE player_id = '$ratingPlayerId';"
            $projectedSkillParts = @($projectedSkill -split '\|')
            if ($projectedSkillParts.Count -eq 3 -and $projectedSkillParts[0] -eq [string]$historyEntry.newMmr) { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $projectionDeadline)
        if ($projectedSkillParts.Count -ne 3 -or $projectedSkillParts[0] -ne [string]$historyEntry.newMmr `
                -or [Math]::Abs([double]::Parse($projectedSkillParts[1], [Globalization.CultureInfo]::InvariantCulture) - [double]$statistics.skillMean) -gt 0.000001 `
                -or [Math]::Abs([double]::Parse($projectedSkillParts[2], [Globalization.CultureInfo]::InvariantCulture) - [double]$statistics.skillDeviation) -gt 0.000001) {
            throw 'The TrueSkill distribution was not replicated to Matchmaking in time'
        }

        $ratingQueueKey = 'smoke-rating-' + [Guid]::NewGuid().ToString('N')
        $ratedQueueResponse = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
            -Method Post `
            -Headers ($authorization + @{ 'Idempotency-Key' = $ratingQueueKey }) `
            -ContentType 'application/json' `
            -Body '{"region":"EUW","primaryRole":"JUNGLE","secondaryRole":"MID"}'
        if (($ratedQueueResponse.Content | ConvertFrom-Json).mmr -ne $historyEntry.newMmr) {
            throw 'A new queue entry did not snapshot the replicated MMR'
        }
        Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" -Method Delete -Headers $authorization | Out-Null
        $closedLobbyResponse = Invoke-WebRequest "$BaseUri/api/v2/matches/current-lobby" `
            -Headers $authorization `
            -SkipHttpErrorCheck
        if ($closedLobbyResponse.StatusCode -ne 404) {
            throw 'The completed lobby is still returned as active'
        }
        $completedLobbyClosedHttp = $closedLobbyResponse.StatusCode

        $eventId = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
            psql -U matchmaking_service -d pinkward_matchmaking -Atc `
            "SELECT id FROM outbox_events WHERE payload::jsonb #>> '{payload,reservationId}' = '$reservationId' LIMIT 1;"
        $confirmationEventId = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T match-postgres `
            psql -U match_service -d pinkward_matches -Atc `
            "SELECT id FROM outbox_events WHERE event_type = 'MATCH_CONFIRMED' AND payload::jsonb #>> '{payload,reservationId}' = '$reservationId' LIMIT 1;"
        if (-not $eventId -or -not $confirmationEventId) {
            throw 'The confirmation event chain could not be audited'
        }
    } finally {
        if ($reservationId) {
            if ($ratingPlayerId) {
                $ratingRollback = & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T match-postgres `
                    psql -U match_service -d pinkward_matches -Atc `
                    "SELECT rc.previous_rating || '|' || rc.previous_peak_rating || '|' || (pr.games - 1) || '|' || rc.previous_skill_mean || '|' || rc.previous_skill_deviation FROM rating_changes rc JOIN player_ratings pr ON pr.player_id = rc.player_id JOIN matches m ON m.id = rc.match_id WHERE m.reservation_id = '$reservationId' AND rc.player_id = '$ratingPlayerId';"
                if ($ratingRollback) {
                    $ratingRollbackParts = $ratingRollback.Split('|')
                    $previousProjectedRating = $ratingRollbackParts[0]
                    $previousProjectedPeak = $ratingRollbackParts[1]
                    $previousProjectedGames = $ratingRollbackParts[2]
                    $previousProjectedMean = $ratingRollbackParts[3]
                    $previousProjectedDeviation = $ratingRollbackParts[4]
                }
            }
            $matchCleanup = @()
            if ($eventId) { $matchCleanup += "DELETE FROM inbox_events WHERE event_id = '$eventId';" }
            if ($confirmationEventId) { $matchCleanup += "DELETE FROM outbox_events WHERE id = '$confirmationEventId';" }
            if ($ratingEventId) { $matchCleanup += "DELETE FROM outbox_events WHERE id = '$ratingEventId';" }
            $matchCleanup += "UPDATE player_ratings pr SET rating = rc.previous_rating, peak_rating = rc.previous_peak_rating, skill_mean = rc.previous_skill_mean, skill_deviation = rc.previous_skill_deviation, games = pr.games - 1, wins = pr.wins - CASE WHEN rc.rating_delta > 0 THEN 1 ELSE 0 END, losses = pr.losses - CASE WHEN rc.rating_delta < 0 THEN 1 ELSE 0 END, updated_at = NOW(), version = pr.version + 1 FROM rating_changes rc JOIN matches m ON m.id = rc.match_id WHERE pr.player_id = rc.player_id AND m.reservation_id = '$reservationId';"
            $matchCleanup += "DELETE FROM matches WHERE reservation_id = '$reservationId';"
            $matchCleanup += "DELETE FROM player_ratings WHERE games = 0;"
            & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T match-postgres `
                psql -U match_service -d pinkward_matches -Atc ($matchCleanup -join ' ') | Out-Null

            $matchmakingCleanup = @()
            if ($confirmationEventId) { $matchmakingCleanup += "DELETE FROM inbox_events WHERE event_id = '$confirmationEventId';" }
            if ($ratingEventId) { $matchmakingCleanup += "DELETE FROM inbox_events WHERE event_id = '$ratingEventId';" }
            if ($eventId) { $matchmakingCleanup += "DELETE FROM outbox_events WHERE id = '$eventId';" }
            if ($ratingPlayerId -and $null -ne $previousProjectedGames) {
                if ([int]$previousProjectedGames -eq 0) {
                    $matchmakingCleanup += "DELETE FROM player_rating_snapshots WHERE player_id = '$ratingPlayerId';"
                } else {
                    $matchmakingCleanup += "UPDATE player_rating_snapshots SET rating = $previousProjectedRating, peak_rating = $previousProjectedPeak, games = $previousProjectedGames, skill_mean = $previousProjectedMean, skill_deviation = $previousProjectedDeviation, updated_at = NOW(), version = version + 1 WHERE player_id = '$ratingPlayerId';"
                }
            }
            $matchmakingCleanup += "DELETE FROM queue_entries WHERE reservation_id = '$reservationId';"
            & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
                psql -U matchmaking_service -d pinkward_matchmaking -Atc ($matchmakingCleanup -join ' ') | Out-Null
        } else {
            & docker compose --env-file "$root/infra/.env" -f "$root/infra/compose.yml" exec -T postgres `
                psql -U matchmaking_service -d pinkward_matchmaking -Atc `
                "DELETE FROM queue_entries WHERE idempotency_key = '$idempotencyKey';" | Out-Null
        }
    }
}

$leaveHttp = $null
if (-not $IncludeMatchFlow -and -not $IncludeBotConfirmationFlow) {
    $leave = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
        -Method Delete `
        -Headers $authorization
    $leaveHttp = $leave.StatusCode
}

if ($MeasureRateLimits) {
    $measureBaseUri = $BaseUri
    $measureToken = $token.access_token
    $statuses = 1..60 | ForEach-Object -Parallel {
        try {
            (Invoke-WebRequest "$using:measureBaseUri/api/v2/players/me" -Headers @{ Authorization = "Bearer $using:measureToken" }).StatusCode
        } catch {
            if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        }
    } -ThrottleLimit 30
    $rateLimitAccepted = @($statuses | Where-Object { $_ -eq 200 }).Count
    $rateLimitRejected = @($statuses | Where-Object { $_ -eq 429 }).Count
    if ($rateLimitRejected -eq 0) { throw 'The measured burst did not trigger the configured rate limit' }
}

$queueEntry = $join.Content | ConvertFrom-Json
[pscustomobject]@{
    readiness = $readiness.status
    oauth_pkce = 'OK'
    party_websocket = $partyWebSocket
    match_websocket = $matchWebSocket
    leaderboard_http = $leaderboardResponse.StatusCode
    duel_statistics_http = $duelStatisticsResponse.StatusCode
    duel_leaderboard_http = $duelLeaderboardResponse.StatusCode
    rate_limit_accepted = $rateLimitAccepted
    rate_limit_rejected = $rateLimitRejected
    profile_read_http = $profileReadHttp
    profile_update_http = $profileUpdateHttp
    presence_heartbeat_http = $presenceHttp
    player_id = $queueEntry.playerId
    queue_join_http = $join.StatusCode
    queue_status_http = $status.StatusCode
    queue_leave_http = $leaveHttp
    match_current_http = $matchFlowHttp
    ready_decline_http = $readyHttp
    decliner_removed_http = $declinerRemovedHttp
    ready_accept_http = $readyAcceptHttp
    confirmed_released_http = $confirmedReleasedHttp
    confirmed_current_closed_http = $confirmedCurrentClosedHttp
    confirmed_lobby_http = $confirmedLobbyHttp
    completed_lobby_closed_http = $completedLobbyClosedHttp
} | Format-List
