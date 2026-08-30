[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BaseUri,
    [Parameter(Mandatory)][string]$EnvironmentFile,
    [Parameter(Mandatory)][string]$ProjectName,
    [Parameter(Mandatory)][string]$ComposeFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'TestSupport.ps1')

Write-Host '[5v5] OAuth, matchmaking, ready-check, trusted result and TrueSkill projection'

$username = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'LOCAL_IDENTITY_USERNAME'
$password = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'LOCAL_IDENTITY_PASSWORD'
$accessToken = Get-PlayerAccessToken -BaseUri $BaseUri -Username $username -Password $password
$headers = New-BearerHeaders -AccessToken $accessToken
$profile = Invoke-RestMethod "$BaseUri/api/v2/players/me" -Headers $headers
$before = Invoke-RestMethod "$BaseUri/api/v2/matches/statistics?region=EUW" -Headers $headers
$duelBefore = Invoke-RestMethod "$BaseUri/api/v2/matches/duel/statistics?region=EUW" -Headers $headers

$joinResponse = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
    -Method Post `
    -Headers ($headers + @{ 'Idempotency-Key' = 'docker-5v5-' + [Guid]::NewGuid().ToString('N') }) `
    -ContentType 'application/json' `
    -Body '{"region":"EUW","mode":"FIVE_V_FIVE","primaryRole":"JUNGLE","secondaryRole":"MID"}'
$queue = $joinResponse.Content | ConvertFrom-Json
Assert-Equal 201 $joinResponse.StatusCode 'joining the 5v5 queue must return HTTP 201'
Assert-Equal 'FIVE_V_FIVE' $queue.mode 'the queue entry must retain the 5v5 mode'
Assert-Equal $before.mmr $queue.mmr 'the first queue entry must snapshot the current TrueSkill MMR'

$readyCheck = Wait-ForHttpJson `
    -Uri "$BaseUri/api/v2/matches/current" `
    -Headers $headers `
    -Description 'the 5v5 bot ready-check'
$humans = @($readyCheck.players | Where-Object { -not $_.bot })
$bots = @($readyCheck.players | Where-Object bot)
Assert-Equal 'FIVE_V_FIVE' $readyCheck.mode 'the created match must be a 5v5'
Assert-Equal 'READY_CHECK' $readyCheck.status 'the 5v5 must enter ready-check'
Assert-Equal 10 @($readyCheck.players).Count 'the 5v5 roster must contain ten players'
Assert-Equal 1 $humans.Count 'the local 5v5 must contain one human'
Assert-Equal 9 $bots.Count 'the local 5v5 must contain nine bots'
Assert-Equal 'JUNGLE' $humans[0].assignedRole 'the human primary role must remain prioritized'
Assert-Equal 'PENDING' $humans[0].readyState 'the human must explicitly accept'
Assert-Equal 9 @($bots | Where-Object readyState -eq 'ACCEPTED').Count 'all local bots must auto-accept'
foreach ($team in @('BLUE', 'RED')) {
    $teamPlayers = @($readyCheck.players | Where-Object team -eq $team)
    $teamRoles = @($teamPlayers | Select-Object -ExpandProperty assignedRole -Unique)
    Assert-Equal 5 $teamPlayers.Count "team $team must contain five players"
    Assert-Equal 5 $teamRoles.Count "team $team must contain five unique roles"
}

$confirmed = Invoke-RestMethod "$BaseUri/api/v2/matches/$($readyCheck.matchId)/ready" `
    -Method Post `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body '{"accepted":true}'
Assert-Equal 'CONFIRMED' $confirmed.status 'accepting the 5v5 must confirm the lobby'
Assert-True ($confirmed.lobbyName -match '^SWD-[A-F0-9]{8}$') 'the 5v5 lobby name must be generated'
Assert-True ($confirmed.lobbyPassword -match '^[A-HJ-NP-Z2-9]{8}$') 'the 5v5 lobby password must be generated'
Assert-Equal 10 @($confirmed.players | Where-Object readyState -eq 'ACCEPTED').Count 'all ten players must be ready'

[void](Wait-ForHttpStatus `
    -Uri "$BaseUri/api/v2/matchmaking/queue" `
    -Headers $headers `
    -ExpectedStatus 404 `
    -Description 'the confirmed 5v5 reservation release')
[void](Wait-ForHttpStatus `
    -Uri "$BaseUri/api/v2/matches/current" `
    -Headers $headers `
    -ExpectedStatus 404 `
    -Description 'the 5v5 ready-check closure')
$lobby = Wait-ForHttpJson `
    -Uri "$BaseUri/api/v2/matches/current-lobby" `
    -Headers $headers `
    -Description 'the confirmed 5v5 lobby'
Assert-Equal $confirmed.matchId $lobby.matchId 'the active lobby must be the confirmed 5v5'

$resultBody = @{ winningTeam = $humans[0].team } | ConvertTo-Json -Compress
$publicResult = Invoke-WebRequest "$BaseUri/api/v2/matches/$($lobby.matchId)/result" `
    -Method Post `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $resultBody `
    -SkipHttpErrorCheck
Assert-Equal 403 $publicResult.StatusCode 'a public player token must not submit a 5v5 result'

$watcherToken = Get-ResultIngestorAccessToken `
    -BaseUri $BaseUri `
    -EnvironmentFile $EnvironmentFile
$watcherHeaders = New-BearerHeaders -AccessToken $watcherToken
$completed = Invoke-RestMethod "$BaseUri/api/v2/matches/$($lobby.matchId)/result" `
    -Method Post `
    -Headers $watcherHeaders `
    -ContentType 'application/json' `
    -Body $resultBody
Assert-Equal 'COMPLETED' $completed.status 'the trusted 5v5 result must complete the match'
Assert-Equal $humans[0].team $completed.winningTeam 'the human team must be recorded as the winner'

$after = Invoke-RestMethod "$BaseUri/api/v2/matches/statistics?region=EUW" -Headers $headers
Assert-Equal ($before.games + 1) $after.games 'TrueSkill games must increment exactly once'
Assert-Equal ($before.wins + 1) $after.wins 'TrueSkill wins must increment exactly once'
Assert-Equal $before.losses $after.losses 'a victory must not increment TrueSkill losses'
Assert-True ($after.mmr -gt $before.mmr) 'a 5v5 victory must increase MMR'
Assert-True ($after.skillMean -gt $before.skillMean) 'a 5v5 victory must increase the TrueSkill mean'
Assert-True ($after.skillDeviation -lt $before.skillDeviation) 'a completed 5v5 must reduce TrueSkill uncertainty'

$duplicate = Invoke-RestMethod "$BaseUri/api/v2/matches/$($lobby.matchId)/result" `
    -Method Post `
    -Headers $watcherHeaders `
    -ContentType 'application/json' `
    -Body $resultBody
Assert-Equal 'COMPLETED' $duplicate.status 'repeating the same trusted result must be idempotent'
$afterDuplicate = Invoke-RestMethod "$BaseUri/api/v2/matches/statistics?region=EUW" -Headers $headers
Assert-Equal $after.games $afterDuplicate.games 'an idempotent result must not increment games twice'
Assert-Equal $after.mmr $afterDuplicate.mmr 'an idempotent result must not apply TrueSkill twice'

$history = Invoke-RestMethod "$BaseUri/api/v2/matches/history?region=EUW&mode=FIVE_V_FIVE&outcome=VICTORY&size=25" -Headers $headers
$historyEntry = @($history.content | Where-Object matchId -eq $lobby.matchId | Select-Object -First 1)
Assert-Equal 1 $historyEntry.Count 'the completed 5v5 must appear once in history'
Assert-Equal 'VICTORY' $historyEntry[0].outcome 'the 5v5 history outcome must be a victory'
Assert-Equal $before.mmr $historyEntry[0].previousMmr 'history must retain the pre-match TrueSkill MMR'
Assert-Equal $after.mmr $historyEntry[0].newMmr 'history must retain the post-match TrueSkill MMR'
Assert-Equal ($after.mmr - $before.mmr) $historyEntry[0].mmrDelta 'history must expose the exact TrueSkill delta'

$detail = Invoke-RestMethod "$BaseUri/api/v2/matches/history/$($lobby.matchId)" -Headers $headers
Assert-Equal 5 @($detail.teammates).Count 'a 5v5 detail must contain five teammates including self'
Assert-Equal 5 @($detail.opponents).Count 'a 5v5 detail must contain five opponents'
Assert-Equal 1 @($detail.teammates | Where-Object self).Count 'the detailed roster must identify the current player'

$duelAfter = Invoke-RestMethod "$BaseUri/api/v2/matches/duel/statistics?region=EUW" -Headers $headers
Assert-Equal $duelBefore.games $duelAfter.games 'a 5v5 must not increment the Glicko-2 ledger'
Assert-Equal $duelBefore.mmr $duelAfter.mmr 'a 5v5 must not change the Glicko-2 MMR'

$change = Invoke-ComposeSql `
    -ProjectName $ProjectName `
    -EnvironmentFile $EnvironmentFile `
    -ComposeFile $ComposeFile `
    -Service 'match-postgres' `
    -DatabaseUser 'match_service' `
    -Database 'pinkward_matches' `
    -Query "SELECT previous_rating || '|' || rating_delta || '|' || new_rating FROM rating_changes WHERE match_id = '$($lobby.matchId)' AND player_id = '$($profile.playerId)';"
Assert-Equal "$($before.mmr)|$($after.mmr - $before.mmr)|$($after.mmr)" $change 'the TrueSkill ledger row must match the API'

$ratingEventId = Invoke-ComposeSql `
    -ProjectName $ProjectName `
    -EnvironmentFile $EnvironmentFile `
    -ComposeFile $ComposeFile `
    -Service 'match-postgres' `
    -DatabaseUser 'match_service' `
    -Database 'pinkward_matches' `
    -Query "SELECT id FROM outbox_events WHERE event_type = 'PLAYER_RATING_UPDATED' AND payload::jsonb #>> '{payload,playerId}' = '$($profile.playerId)' ORDER BY created_at DESC LIMIT 1;"
Assert-True (-not [string]::IsNullOrWhiteSpace($ratingEventId)) 'the 5v5 result must emit PLAYER_RATING_UPDATED'

$projection = Wait-ForSqlValue `
    -Description 'the TrueSkill RabbitMQ projection in Matchmaking' `
    -Query {
        Invoke-ComposeSql `
            -ProjectName $ProjectName `
            -EnvironmentFile $EnvironmentFile `
            -ComposeFile $ComposeFile `
            -Service 'postgres' `
            -DatabaseUser 'matchmaking_service' `
            -Database 'pinkward_matchmaking' `
            -Query "SELECT rating || '|' || games FROM player_rating_snapshots WHERE player_id = '$($profile.playerId)';"
    } `
    -Predicate {
        param($value)
        $value -eq "$($after.mmr)|$($after.games)"
    }
Assert-Equal "$($after.mmr)|$($after.games)" $projection 'the projected 5v5 rating must match the authoritative ledger'

$inboxCount = Invoke-ComposeSql `
    -ProjectName $ProjectName `
    -EnvironmentFile $EnvironmentFile `
    -ComposeFile $ComposeFile `
    -Service 'postgres' `
    -DatabaseUser 'matchmaking_service' `
    -Database 'pinkward_matchmaking' `
    -Query "SELECT count(*) FROM inbox_events WHERE event_id = '$ratingEventId';"
Assert-Equal '1' $inboxCount 'Matchmaking must consume the 5v5 rating event exactly once'

$projectedQueueResponse = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
    -Method Post `
    -Headers ($headers + @{ 'Idempotency-Key' = 'docker-5v5-projection-' + [Guid]::NewGuid().ToString('N') }) `
    -ContentType 'application/json' `
    -Body '{"region":"EUW","mode":"FIVE_V_FIVE","primaryRole":"JUNGLE","secondaryRole":"MID"}'
try {
    $projectedQueue = $projectedQueueResponse.Content | ConvertFrom-Json
    Assert-Equal $after.mmr $projectedQueue.mmr 'a new 5v5 queue entry must use the projected TrueSkill MMR'
}
finally {
    Remove-DisposableQueueEntry -BaseUri $BaseUri -Headers $headers
}

[void](Wait-ForHttpStatus `
    -Uri "$BaseUri/api/v2/matches/current-lobby" `
    -Headers $headers `
    -ExpectedStatus 404 `
    -Description 'the completed 5v5 lobby closure')

[pscustomobject]@{
    scenario = '5v5 TrueSkill'
    matchId = $lobby.matchId
    result = 'VICTORY'
    previousMmr = $before.mmr
    newMmr = $after.mmr
    mmrDelta = $after.mmr - $before.mmr
    skillMeanBefore = $before.skillMean
    skillMeanAfter = $after.skillMean
    skillDeviationBefore = $before.skillDeviation
    skillDeviationAfter = $after.skillDeviation
    rabbitProjection = 'VERIFIED'
}
