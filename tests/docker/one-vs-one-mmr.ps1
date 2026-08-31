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

Write-Host '[1v1] OAuth, matchmaking, ready-check, Watcher result and Glicko-2 projection'

$username = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'LOCAL_IDENTITY_USERNAME'
$password = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'LOCAL_IDENTITY_PASSWORD'
$accessToken = Get-PlayerAccessToken -BaseUri $BaseUri -EnvironmentFile $EnvironmentFile `
    -Username $username -Password $password
$headers = New-BearerHeaders -AccessToken $accessToken
$profile = Invoke-RestMethod "$BaseUri/api/v2/players/me" -Headers $headers
$before = Invoke-RestMethod "$BaseUri/api/v2/matches/duel/statistics?region=EUW" -Headers $headers
$fiveBefore = Invoke-RestMethod "$BaseUri/api/v2/matches/statistics?region=EUW" -Headers $headers

Assert-Equal 'GLICKO_2' $before.algorithm 'the 1v1 ladder must use Glicko-2'
Assert-True ($before.ratingDeviation -gt 0) 'the initial Glicko-2 deviation must be positive'

$joinResponse = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
    -Method Post `
    -Headers ($headers + @{ 'Idempotency-Key' = 'docker-1v1-' + [Guid]::NewGuid().ToString('N') }) `
    -ContentType 'application/json' `
    -Body '{"region":"EUW","mode":"ONE_V_ONE","primaryRole":"MID","secondaryRole":"JUNGLE"}'
$queue = $joinResponse.Content | ConvertFrom-Json
Assert-Equal 201 $joinResponse.StatusCode 'joining the 1v1 queue must return HTTP 201'
Assert-Equal 'ONE_V_ONE' $queue.mode 'the queue entry must retain the 1v1 mode'
Assert-Equal $before.mmr $queue.mmr 'the first queue entry must snapshot the current Glicko-2 MMR'

$readyCheck = Wait-ForHttpJson `
    -Uri "$BaseUri/api/v2/matches/current" `
    -Headers $headers `
    -Description 'the 1v1 bot ready-check'
$human = @($readyCheck.players | Where-Object { -not $_.bot })
$bot = @($readyCheck.players | Where-Object bot)
Assert-Equal 'ONE_V_ONE' $readyCheck.mode 'the created match must be a 1v1'
Assert-Equal 'READY_CHECK' $readyCheck.status 'the 1v1 must enter ready-check'
Assert-Equal 2 @($readyCheck.players).Count 'the 1v1 roster must contain two players'
Assert-Equal 1 $human.Count 'the 1v1 roster must contain one human'
Assert-Equal 1 $bot.Count 'the 1v1 roster must contain one local bot'
Assert-Equal 'PENDING' $human[0].readyState 'the human must explicitly accept'
Assert-Equal 'ACCEPTED' $bot[0].readyState 'the local bot must auto-accept'
Assert-True ($human[0].team -ne $bot[0].team) 'the human and bot must be on opposite teams'

$confirmed = Invoke-RestMethod "$BaseUri/api/v2/matches/$($readyCheck.matchId)/ready" `
    -Method Post `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body '{"accepted":true}'
Assert-Equal 'CONFIRMED' $confirmed.status 'accepting the 1v1 must confirm the lobby'
Assert-True ($confirmed.lobbyName -match '^SWD-[A-F0-9]{8}$') 'the 1v1 lobby name must be generated'
Assert-True ($confirmed.lobbyPassword -match '^[A-HJ-NP-Z2-9]{8}$') 'the 1v1 lobby password must be generated'

[void](Wait-ForHttpStatus `
    -Uri "$BaseUri/api/v2/matchmaking/queue" `
    -Headers $headers `
    -ExpectedStatus 404 `
    -Description 'the confirmed 1v1 reservation release')
[void](Wait-ForHttpStatus `
    -Uri "$BaseUri/api/v2/matches/current" `
    -Headers $headers `
    -ExpectedStatus 404 `
    -Description 'the ready-check closure')
$lobby = Wait-ForHttpJson `
    -Uri "$BaseUri/api/v2/matches/current-lobby" `
    -Headers $headers `
    -Description 'the confirmed 1v1 lobby'
Assert-Equal $confirmed.matchId $lobby.matchId 'the active lobby must be the confirmed 1v1'

$publicAssignment = Invoke-WebRequest "$BaseUri/api/v2/matches/$($lobby.matchId)/bot-assignment" `
    -Headers $headers `
    -SkipHttpErrorCheck
Assert-Equal 403 $publicAssignment.StatusCode 'a public player token must not read the bot assignment'

$humanWon = [double]$before.rating -le 1500.0
$expectedOutcome = if ($humanWon) { 'VICTORY' } else { 'DEFEAT' }
$expectedWinner = if ($humanWon) { $human[0].team } else { $bot[0].team }
$resultBody = @{
    humanWon = $humanWon
    objective = 'FIRST_BLOOD'
    observedAt = [DateTime]::UtcNow.ToString('o')
} | ConvertTo-Json -Compress
$publicResult = Invoke-WebRequest "$BaseUri/api/v2/matches/$($lobby.matchId)/bot-result" `
    -Method Post `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $resultBody `
    -SkipHttpErrorCheck
Assert-Equal 403 $publicResult.StatusCode 'a public player token must not submit a bot result'

$watcherToken = Get-WatcherAccessToken `
    -BaseUri $BaseUri `
    -EnvironmentFile $EnvironmentFile `
    -Scope 'service:match:bot-result'
$watcherHeaders = New-BearerHeaders -AccessToken $watcherToken
$assignment = Invoke-RestMethod "$BaseUri/api/v2/matches/$($lobby.matchId)/bot-assignment" `
    -Headers $watcherHeaders
Assert-Equal $lobby.lobbyName $assignment.lobbyName 'the Watcher assignment must expose the same lobby name'
Assert-Equal $lobby.lobbyPassword $assignment.lobbyPassword 'the Watcher assignment must expose the same lobby password'

$completed = Invoke-RestMethod "$BaseUri/api/v2/matches/$($lobby.matchId)/bot-result" `
    -Method Post `
    -Headers $watcherHeaders `
    -ContentType 'application/json' `
    -Body $resultBody
Assert-Equal 'COMPLETED' $completed.status 'the trusted 1v1 result must complete the match'
Assert-Equal $expectedWinner $completed.winningTeam 'the recorded winner must match the Watcher observation'

$duplicate = Invoke-WebRequest "$BaseUri/api/v2/matches/$($lobby.matchId)/bot-result" `
    -Method Post `
    -Headers $watcherHeaders `
    -ContentType 'application/json' `
    -Body $resultBody `
    -SkipHttpErrorCheck
Assert-Equal 409 $duplicate.StatusCode 'a closed bot duel must reject a second Watcher result'

$after = Invoke-RestMethod "$BaseUri/api/v2/matches/duel/statistics?region=EUW" -Headers $headers
Assert-Equal ($before.games + 1) $after.games 'Glicko-2 games must increment exactly once'
Assert-Equal ($before.wins + [int]$humanWon) $after.wins 'Glicko-2 wins must be consistent'
Assert-Equal ($before.losses + [int](-not $humanWon)) $after.losses 'Glicko-2 losses must be consistent'
Assert-True ($after.mmr -ne $before.mmr) 'the completed 1v1 must change the displayed MMR'
Assert-True ($after.ratingDeviation -lt $before.ratingDeviation) 'the completed 1v1 must reduce Glicko-2 uncertainty'
if ($humanWon) {
    Assert-True ($after.mmr -gt $before.mmr) 'a 1v1 victory must increase MMR'
}
else {
    Assert-True ($after.mmr -lt $before.mmr) 'a 1v1 defeat must decrease MMR'
}

$history = Invoke-RestMethod "$BaseUri/api/v2/matches/history?region=EUW&mode=ONE_V_ONE&size=25" -Headers $headers
$historyEntry = @($history.content | Where-Object matchId -eq $lobby.matchId | Select-Object -First 1)
Assert-Equal 1 $historyEntry.Count 'the completed 1v1 must appear once in history'
Assert-Equal $expectedOutcome $historyEntry[0].outcome 'the 1v1 history outcome must be correct'
Assert-Equal $before.mmr $historyEntry[0].previousMmr 'history must retain the pre-match Glicko-2 MMR'
Assert-Equal $after.mmr $historyEntry[0].newMmr 'history must retain the post-match Glicko-2 MMR'
Assert-Equal ($after.mmr - $before.mmr) $historyEntry[0].mmrDelta 'history must expose the exact Glicko-2 delta'

$detail = Invoke-RestMethod "$BaseUri/api/v2/matches/history/$($lobby.matchId)" -Headers $headers
Assert-Equal 1 @($detail.teammates).Count 'a 1v1 detail must contain the player as its sole teammate'
Assert-Equal 1 @($detail.opponents).Count 'a 1v1 detail must contain one opponent'
Assert-Equal 1 @($detail.opponents | Where-Object bot).Count 'the 1v1 opponent must be identified as a bot'

$fiveAfter = Invoke-RestMethod "$BaseUri/api/v2/matches/statistics?region=EUW" -Headers $headers
Assert-Equal $fiveBefore.games $fiveAfter.games 'a 1v1 must not increment the 5v5 ledger'
Assert-Equal $fiveBefore.mmr $fiveAfter.mmr 'a 1v1 must not change TrueSkill MMR'

$change = Invoke-ComposeSql `
    -ProjectName $ProjectName `
    -EnvironmentFile $EnvironmentFile `
    -ComposeFile $ComposeFile `
    -Service 'match-postgres' `
    -DatabaseUser 'match_service' `
    -Database 'pinkward_matches' `
    -Query "SELECT previous_mmr || '|' || rating_delta || '|' || new_mmr FROM duel_rating_changes WHERE match_id = '$($lobby.matchId)' AND player_id = '$($profile.playerId)';"
Assert-Equal "$($before.mmr)|$($after.mmr - $before.mmr)|$($after.mmr)" $change 'the Glicko-2 ledger row must match the API'

$ratingEventId = Invoke-ComposeSql `
    -ProjectName $ProjectName `
    -EnvironmentFile $EnvironmentFile `
    -ComposeFile $ComposeFile `
    -Service 'match-postgres' `
    -DatabaseUser 'match_service' `
    -Database 'pinkward_matches' `
    -Query "SELECT id FROM outbox_events WHERE event_type = 'DUEL_RATING_UPDATED' AND payload::jsonb #>> '{payload,playerId}' = '$($profile.playerId)' ORDER BY created_at DESC LIMIT 1;"
Assert-True (-not [string]::IsNullOrWhiteSpace($ratingEventId)) 'the 1v1 result must emit DUEL_RATING_UPDATED'

$projection = Wait-ForSqlValue `
    -Description 'the Glicko-2 RabbitMQ projection in Matchmaking' `
    -Query {
        Invoke-ComposeSql `
            -ProjectName $ProjectName `
            -EnvironmentFile $EnvironmentFile `
            -ComposeFile $ComposeFile `
            -Service 'postgres' `
            -DatabaseUser 'matchmaking_service' `
            -Database 'pinkward_matchmaking' `
            -Query "SELECT mmr || '|' || games FROM duel_rating_snapshots WHERE player_id = '$($profile.playerId)';"
    } `
    -Predicate {
        param($value)
        $value -eq "$($after.mmr)|$($after.games)"
    }
Assert-Equal "$($after.mmr)|$($after.games)" $projection 'the projected 1v1 rating must match the authoritative ledger'

$inboxCount = Invoke-ComposeSql `
    -ProjectName $ProjectName `
    -EnvironmentFile $EnvironmentFile `
    -ComposeFile $ComposeFile `
    -Service 'postgres' `
    -DatabaseUser 'matchmaking_service' `
    -Database 'pinkward_matchmaking' `
    -Query "SELECT count(*) FROM inbox_events WHERE event_id = '$ratingEventId';"
Assert-Equal '1' $inboxCount 'Matchmaking must consume the 1v1 rating event exactly once'

$projectedQueueResponse = Invoke-WebRequest "$BaseUri/api/v2/matchmaking/queue" `
    -Method Post `
    -Headers ($headers + @{ 'Idempotency-Key' = 'docker-1v1-projection-' + [Guid]::NewGuid().ToString('N') }) `
    -ContentType 'application/json' `
    -Body '{"region":"EUW","mode":"ONE_V_ONE","primaryRole":"MID","secondaryRole":"JUNGLE"}'
try {
    $projectedQueue = $projectedQueueResponse.Content | ConvertFrom-Json
    Assert-Equal $after.mmr $projectedQueue.mmr 'a new 1v1 queue entry must use the projected Glicko-2 MMR'
}
finally {
    Remove-DisposableQueueEntry -BaseUri $BaseUri -Headers $headers
}

[void](Wait-ForHttpStatus `
    -Uri "$BaseUri/api/v2/matches/current-lobby" `
    -Headers $headers `
    -ExpectedStatus 404 `
    -Description 'the completed 1v1 lobby closure')

[pscustomobject]@{
    scenario = '1v1 Glicko-2'
    matchId = $lobby.matchId
    result = $expectedOutcome
    previousMmr = $before.mmr
    newMmr = $after.mmr
    mmrDelta = $after.mmr - $before.mmr
    ratingDeviationBefore = $before.ratingDeviation
    ratingDeviationAfter = $after.ratingDeviation
    rabbitProjection = 'VERIFIED'
}
