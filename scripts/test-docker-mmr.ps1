[CmdletBinding()]
param(
    [ValidateSet('All', 'OneVsOne', 'FiveVsFive')]
    [string]$Scenario = 'All',
    [ValidateRange(0, 65535)]
    [int]$Port = 0,
    [string]$ProjectName,
    [switch]$SkipBuild,
    [switch]$KeepStack
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$composeFile = Join-Path $root 'infra/compose.yml'
$templateFile = Join-Path $root 'infra/.env.example'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
if ([string]::IsNullOrWhiteSpace($ProjectName)) {
    $ProjectName = "showdown-e2e-$runId"
}
if ($ProjectName -notmatch '^showdown-e2e-[a-z0-9-]+$') {
    throw "ProjectName must start with 'showdown-e2e-' and contain only lowercase letters, digits and hyphens"
}
if ($Port -eq 0) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        $Port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

$baseUri = "http://localhost:$Port"
$environmentFile = Join-Path ([IO.Path]::GetTempPath()) "$ProjectName.env"
$tests = @()
if ($Scenario -in @('All', 'OneVsOne')) {
    $tests += Join-Path $root 'tests/docker/one-vs-one-mmr.ps1'
}
if ($Scenario -in @('All', 'FiveVsFive')) {
    $tests += Join-Path $root 'tests/docker/five-vs-five-mmr.ps1'
}

foreach ($path in @($composeFile, $templateFile) + $tests) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required E2E file is missing: $path"
    }
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is required for the real MMR integration tests'
}

$environmentOverrides = @{
    SHOWDOWN_HTTP_PORT = [string]$Port
    JWT_ISSUER = $baseUri
    WEB_REDIRECT_URI = "$baseUri/oauth/callback"
    LOCAL_BOTS_ENABLED = 'true'
    LOCAL_BOT_RESULTS_ENABLED = 'true'
    # Keep the automatic fill fast enough for the test while leaving enough
    # time for the post-result queue-snapshot assertion to clean up before the
    # scheduler can reserve that disposable entry.
    LOCAL_BOTS_FILL_AFTER = '15s'
}
$previousEnvironment = @{}
$results = @()

function Invoke-E2eCompose {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)

    $composeArguments = @(
        'compose',
        '--project-name', $ProjectName,
        '--env-file', $environmentFile,
        '-f', $composeFile
    ) + $Arguments
    & docker @composeArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed: $($Arguments -join ' ')"
    }
}

Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'local-secrets.ps1') `
        -Mode Initialize `
        -EnvironmentFile $environmentFile `
        -TemplateFile $templateFile | Out-Null

    foreach ($name in $environmentOverrides.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $environmentOverrides[$name], 'Process')
    }

    # A validated E2E-only project name makes removal precise and prevents any
    # interaction with the persistent pinkward-showdown development project.
    Invoke-E2eCompose down --volumes --remove-orphans
    $upArguments = @('up', '--detach', '--wait', '--wait-timeout', '300')
    if (-not $SkipBuild) {
        $upArguments += '--build'
    }
    $upArguments += 'caddy'
    Write-Host "Starting isolated Docker project $ProjectName on $baseUri"
    Invoke-E2eCompose @upArguments

    $readinessDeadline = (Get-Date).AddSeconds(45)
    do {
        try {
            $readiness = Invoke-RestMethod "$baseUri/actuator/health/readiness"
        }
        catch {
            $readiness = $null
        }
        if ($readiness.status -eq 'UP') {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $readinessDeadline)
    if ($null -eq $readiness -or $readiness.status -ne 'UP') {
        throw 'The isolated API Gateway did not become ready'
    }

    foreach ($test in $tests) {
        $results += & $test `
            -BaseUri $baseUri `
            -EnvironmentFile $environmentFile `
            -ProjectName $ProjectName `
            -ComposeFile $composeFile
    }

    Write-Host ''
    Write-Host 'Docker MMR integration tests passed:' -ForegroundColor Green
    $results | Format-Table scenario, result, previousMmr, newMmr, mmrDelta, rabbitProjection -AutoSize
}
catch {
    Write-Host ''
    Write-Host 'Docker MMR integration test failed. Container status:' -ForegroundColor Red
    try {
        Invoke-E2eCompose ps
        Invoke-E2eCompose logs --no-color --tail 120 api-gateway identity-service matchmaking-service match-service
    }
    catch {
        Write-Warning 'Could not collect all E2E Docker diagnostics.'
    }
    throw
}
finally {
    if (-not $KeepStack) {
        try {
            Invoke-E2eCompose down --volumes --remove-orphans --rmi local
        }
        catch {
            Write-Warning "Could not remove isolated Docker project $ProjectName"
        }
        if (Test-Path -LiteralPath $environmentFile) {
            Remove-Item -LiteralPath $environmentFile -Force
        }
    }
    else {
        Write-Host "Isolated stack kept at $baseUri (project: $ProjectName)."
        Write-Host "Its temporary environment file is $environmentFile"
    }
    foreach ($name in $environmentOverrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    Pop-Location
}
