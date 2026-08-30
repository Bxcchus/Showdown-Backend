[CmdletBinding()]
param(
    [string]$BaseUri = 'http://localhost:8088',
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot '../infra/.env')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../tests/docker/TestSupport.ps1')

if (-not (Test-Path -LiteralPath $EnvironmentFile)) { throw "Missing $EnvironmentFile" }
$username = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'LOCAL_IDENTITY_USERNAME'
$password = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'LOCAL_IDENTITY_PASSWORD'
$playerToken = Get-PlayerAccessToken -BaseUri $BaseUri -Username $username -Password $password
$watcherToken = Get-WatcherAccessToken -BaseUri $BaseUri -EnvironmentFile $EnvironmentFile -Scope 'service:duel:observe'
Write-Host 'OAuth tokens acquired.'

$wrongScope = Invoke-WebRequest "$BaseUri/api/v2/matches/00000000-0000-4000-8000-000000000001/result" `
    -Method Post -Headers (New-BearerHeaders $watcherToken) -ContentType 'application/json' `
    -Body '{"winningTeam":"BLUE"}' -SkipHttpErrorCheck
Assert-Equal 403 $wrongScope.StatusCode 'watcher scope cannot submit a trusted result'
Write-Host 'Scope isolation verified.'

$cors = Invoke-WebRequest "$BaseUri/api/v2/players/me" -Method Options -Headers @{
    Origin = 'https://attacker.example'
    'Access-Control-Request-Method' = 'GET'
} -SkipHttpErrorCheck
Assert-True (-not $cors.Headers.ContainsKey('Access-Control-Allow-Origin')) `
    'malicious CORS origin must not receive an allow-origin response'
Write-Host 'CORS rejection verified.'

$rateBaseUri = $BaseUri
$rateToken = $playerToken
$rateStatuses = 1..120 | ForEach-Object -Parallel {
    (Invoke-WebRequest "$using:rateBaseUri/api/v2/players/me" `
        -Headers @{ Authorization = "Bearer $using:rateToken" } -SkipHttpErrorCheck).StatusCode
} -ThrottleLimit 120
Write-Host ('Rate status distribution: ' + (($rateStatuses | Group-Object | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ', '))
Assert-True (429 -in $rateStatuses) 'gateway rate limiter must reject a sustained burst'
Assert-True (500 -notin $rateStatuses) 'gateway must not return 500 while enforcing a rate limit'
Write-Host 'Rate limiting verified.'

$socket = [Net.WebSockets.ClientWebSocket]::new()
$socketTimeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(8))
try {
    $socket.Options.AddSubProtocol('showdown-v1')
    $socket.Options.AddSubProtocol("bearer.$playerToken")
    $socket.Options.SetRequestHeader('Origin', 'https://attacker.example')
    $wsUri = [Uri]($BaseUri.Replace('http://', 'ws://').Replace('https://', 'wss://') + '/api/v2/realtime/matches')
    $rejected = $false
    try { $socket.ConnectAsync($wsUri, $socketTimeout.Token).GetAwaiter().GetResult() }
    catch [Net.WebSockets.WebSocketException] { $rejected = $true }
    Assert-True $rejected 'malicious WebSocket origin must be rejected'
    Write-Host 'WebSocket origin rejection verified.'
}
finally {
    $socketTimeout.Dispose()
    $socket.Dispose()
}

$probeUsername = 'security-probe-' + [Guid]::NewGuid().ToString('N')
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
for ($attempt = 1; $attempt -le 6; $attempt++) {
    $login = Invoke-WebRequest "$BaseUri/login" -WebSession $session
    $csrf = Read-HiddenInput -Html $login.Content -Name '_csrf'
    $response = Invoke-WebRequest "$BaseUri/login" -Method Post -WebSession $session -Body @{
        username = $probeUsername; password = 'definitely-wrong'; _csrf = $csrf
    } -MaximumRedirection 0 -SkipHttpErrorCheck -ErrorAction SilentlyContinue
}
Assert-Equal 429 $response.StatusCode 'sixth repeated login attempt must be throttled'
Write-Host 'Login throttling verified.'

$compose = Join-Path $PSScriptRoot '../infra/compose.yml'
$rabbitProbe = @(& docker compose --env-file $EnvironmentFile -f $compose run --rm --no-deps `
    --entrypoint /bin/sh rabbitmq-bootstrap -ec @'
body='{"properties":{},"routing_key":"pinkward.match.result.unauthorized","payload":"{}","payload_encoding":"string"}'
status=$(curl -sS -o /tmp/response -w '%{http_code}' -u "matchmaking_service:$MATCHMAKING_RABBITMQ_PASSWORD" -H 'content-type: application/json' -X POST http://rabbitmq:15672/api/exchanges/Pinkward/pinkward.events/publish -d "$body")
test "$status" -ge 400
'@ 2>&1)
if ($LASTEXITCODE -ne 0) { throw "RabbitMQ accepted an unauthorized producer routing key: $($rabbitProbe -join ' ')" }

$caddyLogs = (& docker compose --env-file $EnvironmentFile -f $compose logs --no-color --since 10m caddy 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Caddy access logs.' }
Assert-True (-not $caddyLogs.Contains($playerToken)) 'Caddy logs must mask player bearer tokens'
Assert-True (-not $caddyLogs.Contains($watcherToken)) 'Caddy logs must mask watcher bearer tokens'

Write-Host 'Runtime security probes passed: scopes, CORS, WebSocket origin, rate limits, login throttling, RabbitMQ ACL and log masking.'
