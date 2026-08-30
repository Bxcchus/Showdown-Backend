[CmdletBinding()]
param(
    [ValidateRange(10, 100000)]
    [int]$Count = 1000,
    [ValidateSet('EUW', 'EUNE', 'NA')]
    [string]$Region = 'EUW',
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$compose = Join-Path $root 'infra/compose.yml'
$environment = Join-Path $root 'infra/.env'

if ($Count % 10 -ne 0) {
    throw 'Count must be a multiple of 10 so every simulated bot can enter a match.'
}

function Invoke-MatchmakingSql {
    param([Parameter(Mandatory)][string]$Sql)
    $result = & docker compose --env-file $environment -f $compose exec -T postgres `
        psql -v ON_ERROR_STOP=1 -U matchmaking_service -d pinkward_matchmaking -Atc $Sql
    if ($LASTEXITCODE -ne 0) { throw 'Matchmaking database command failed.' }
    return $result
}

function Invoke-MatchSql {
    param([Parameter(Mandatory)][string]$Sql)
    $result = & docker compose --env-file $environment -f $compose exec -T match-postgres `
        psql -v ON_ERROR_STOP=1 -U match_service -d pinkward_matches -Atc $Sql
    if ($LASTEXITCODE -ne 0) { throw 'Match database command failed.' }
    return $result
}

$runId = [Guid]::NewGuid().ToString('N')
$prefix = "local-bot:load:${runId}:"
$expectedMatches = [int]($Count / 10)
$reservationIds = @()
$eventIds = @()
$started = Get-Date

try {
    $insert = @"
INSERT INTO queue_entries (
    id, player_id, region, mode, idempotency_key, joined_at,
    primary_role, secondary_role, status, reservation_id)
SELECT gen_random_uuid(), gen_random_uuid(), '$Region', 'FIVE_V_FIVE',
       '$prefix' || n, clock_timestamp() + (n * interval '1 microsecond'),
       (ARRAY['TOP','JUNGLE','MID','BOT','SUPPORT'])[((n - 1) % 5) + 1],
       (ARRAY['TOP','JUNGLE','MID','BOT','SUPPORT'])[(n % 5) + 1],
       'QUEUED', NULL
FROM generate_series(1, $Count) AS bots(n);
"@
    Invoke-MatchmakingSql $insert | Out-Null

    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 250
        $reserved = [int](Invoke-MatchmakingSql `
            "SELECT count(*) FROM queue_entries WHERE idempotency_key LIKE '$prefix%' AND status = 'RESERVED';")
    } while ($reserved -lt $Count -and (Get-Date) -lt $deadline)
    if ($reserved -ne $Count) { throw "Only $reserved/$Count bots were reserved before timeout." }

    $reservationIds = @(
        Invoke-MatchmakingSql `
            "SELECT DISTINCT reservation_id FROM queue_entries WHERE idempotency_key LIKE '$prefix%' ORDER BY reservation_id;" |
            Where-Object { $_ } |
            ForEach-Object { $_.Trim() }
    )
    $quotedReservations = ($reservationIds | ForEach-Object { "'$_'" }) -join ','

    do {
        Start-Sleep -Milliseconds 250
        $confirmed = [int](Invoke-MatchSql `
            "SELECT count(*) FROM matches WHERE reservation_id IN ($quotedReservations) AND status = 'CONFIRMED';")
    } while ($confirmed -lt $expectedMatches -and (Get-Date) -lt $deadline)
    if ($confirmed -ne $expectedMatches) {
        throw "Only $confirmed/$expectedMatches bot matches were confirmed before timeout."
    }

    $eventIds = @(
        Invoke-MatchmakingSql `
            "SELECT id FROM outbox_events WHERE payload::jsonb #>> '{payload,reservationId}' IN ($quotedReservations);" |
            Where-Object { $_ } |
            ForEach-Object { $_.Trim() }
    )
    $elapsed = (Get-Date) - $started
    [pscustomobject]@{
        region = $Region
        bots = $Count
        matches_confirmed = $confirmed
        elapsed_seconds = [Math]::Round($elapsed.TotalSeconds, 3)
        bots_per_second = [Math]::Round($Count / $elapsed.TotalSeconds, 1)
        data_kept = [bool]$KeepData
        run_id = $runId
    } | Format-List
} finally {
    if (-not $KeepData) {
        if ($reservationIds.Count -gt 0) {
            $quotedReservations = ($reservationIds | ForEach-Object { "'$_'" }) -join ','
            if ($eventIds.Count -gt 0) {
                $quotedEvents = ($eventIds | ForEach-Object { "'$_'" }) -join ','
                Invoke-MatchSql "DELETE FROM inbox_events WHERE event_id IN ($quotedEvents);" | Out-Null
                Invoke-MatchmakingSql "DELETE FROM outbox_events WHERE id IN ($quotedEvents);" | Out-Null
            }
            Invoke-MatchSql "DELETE FROM matches WHERE reservation_id IN ($quotedReservations);" | Out-Null
        }
        Invoke-MatchmakingSql "DELETE FROM queue_entries WHERE idempotency_key LIKE '$prefix%';" | Out-Null
    }
}
