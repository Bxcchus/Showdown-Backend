[CmdletBinding()]
param(
    [string]$WatcherBaseUrl = 'http://127.0.0.1:43991',
    [string]$WebOrigin = 'http://localhost:3000'
)

$ErrorActionPreference = 'Stop'

$health = Invoke-RestMethod -Method Get -Uri "$WatcherBaseUrl/health"
if ($health.status -ne 'UP') {
    throw 'Le Watcher local ne répond pas correctement.'
}

$session = Invoke-RestMethod -Method Post -Uri "$WatcherBaseUrl/v1/session" -Headers @{
    Origin = $WebOrigin
}
if ([string]::IsNullOrWhiteSpace($session.token)) {
    throw "Le Watcher n'a pas créé de session locale."
}

$identity = Invoke-RestMethod -Method Get -Uri "$WatcherBaseUrl/v1/identity" -Headers @{
    Origin = $WebOrigin
    'X-Showdown-Watcher-Token' = $session.token
}

if ([string]::IsNullOrWhiteSpace($identity.puuid) -or $identity.puuid.Length -lt 16) {
    throw 'Le LCU ne fournit plus un PUUID exploitable.'
}
if ([string]::IsNullOrWhiteSpace($identity.gameName) -or
    [string]::IsNullOrWhiteSpace($identity.tagLine) -or
    $identity.riotId -ne "$($identity.gameName)#$($identity.tagLine)") {
    throw 'Le contrat Riot ID du LCU a changé ou est incomplet.'
}
if ($identity.profileIconId -le 0 -or $identity.summonerLevel -le 0) {
    throw "Le profil LCU ne fournit plus l'icône ou le niveau attendu."
}

Write-Host "Compatibilité LCU confirmée pour $($identity.riotId) (niveau $($identity.summonerLevel))." -ForegroundColor Green
