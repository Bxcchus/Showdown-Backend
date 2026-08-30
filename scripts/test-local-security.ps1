[CmdletBinding()]
param(
    [switch]$SkipActiveEnvironment
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$templateFile = Join-Path $root 'infra/.env.example'
$environmentFile = Join-Path $root 'infra/.env'
$composeFile = Join-Path $root 'infra/compose.yml'
$caddyFile = Join-Path $root 'infra/Caddyfile'
$secretManager = Join-Path $PSScriptRoot 'local-secrets.ps1'
$placeholder = 'GENERATE_ON_FIRST_START'
$secretNames = @(
    'POSTGRES_PASSWORD',
    'MATCH_POSTGRES_PASSWORD',
    'PLAYER_POSTGRES_PASSWORD',
    'IDENTITY_POSTGRES_PASSWORD',
    'LOBBY_CREDENTIAL_KEY',
    'REDIS_PASSWORD',
    'RABBITMQ_ADMIN_PASSWORD',
    'MATCHMAKING_RABBITMQ_PASSWORD',
    'MATCH_RABBITMQ_PASSWORD',
    'LOCAL_IDENTITY_PASSWORD',
    'LOCAL_IDENTITY_SECONDARY_PASSWORD',
    'MATCHMAKING_CLIENT_SECRET',
    'PLAYER_CLIENT_SECRET',
    'MATCH_CLIENT_SECRET',
    'RESULT_INGESTOR_CLIENT_SECRET',
    'WATCHER_CLIENT_SECRET'
)
$failures = [Collections.Generic.List[string]]::new()

function Read-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            $values[$matches[1]] = $matches[2].Trim()
        }
    }
    return $values
}

function Test-GeneratedSecrets {
    param(
        [Parameter(Mandatory)][hashtable]$Values,
        [Parameter(Mandatory)][string]$Label
    )

    $used = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $secretNames) {
        if (-not $Values.ContainsKey($name)) {
            $failures.Add("$Label is missing $name")
            continue
        }
        $value = [string]$Values[$name]
        if ($value.Length -lt 40 -or $value -notmatch '^[A-Za-z0-9_-]+$') {
            $failures.Add("$Label contains an invalid generated value for $name")
        }
        if ($value -ceq $placeholder -or $value -match '(?i)change[-_ ]?me|placeholder|example') {
            $failures.Add("$Label contains a placeholder for $name")
        }
        if (-not $used.Add($value)) {
            $failures.Add("$Label reuses the value assigned to $name")
        }
    }
}

Push-Location $root
try {
    $template = Read-DotEnv -Path $templateFile
    foreach ($name in $secretNames) {
        if (-not $template.ContainsKey($name) -or $template[$name] -cne $placeholder) {
            $failures.Add("infra/.env.example must use the generator sentinel for $name")
        }
    }
    if ($template.ContainsKey('LEGACY_RABBITMQ_PASSWORD')) {
        $failures.Add('The obsolete LEGACY_RABBITMQ_PASSWORD must not return')
    }

    $composeText = [IO.File]::ReadAllText($composeFile)
    foreach ($name in $secretNames) {
        if ($composeText -match ('\$\{' + [Regex]::Escape($name) + ':-')) {
            $failures.Add("Compose gives sensitive variable $name a fallback")
        }
    }
    if ($composeText -notmatch 'topic-permissions/Pinkward/matchmaking_service' -or
        $composeText -notmatch 'topic-permissions/Pinkward/match_service') {
        $failures.Add('RabbitMQ topic permissions are missing for service routing keys')
    }
    $caddyText = [IO.File]::ReadAllText($caddyFile)
    if ($caddyText -notmatch 'reverse_proxy\s+web-app:80' -or
        $caddyText -match 'redir\s+http://localhost:3000') {
        $failures.Add('Caddy must serve the built web application through the secured edge')
    }
    foreach ($querySecret in @('code', 'access_token', 'refresh_token', 'token', 'client_secret', 'password')) {
        if ($caddyText -notmatch ('replace\s+' + [Regex]::Escape($querySecret) + '\s+REDACTED')) {
            $failures.Add("Caddy access logs do not redact the $querySecret query parameter")
        }
    }
    if ($caddyText -notmatch 'request>headers>Sec-Websocket-Protocol\s+delete') {
        $failures.Add('Caddy access logs must delete the WebSocket subprotocol header carrying bearer tokens')
    }
    foreach ($sensitiveHeader in @('Authorization', 'Cookie')) {
        if ($caddyText -notmatch ('request>headers>' + $sensitiveHeader + '\s+delete')) {
            $failures.Add("Caddy access logs must delete the $sensitiveHeader header")
        }
    }

    $configOutput = @(& docker compose --env-file $templateFile -f $composeFile config --format json 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $failures.Add('Docker Compose security configuration could not be rendered')
    }
    else {
        $config = ($configOutput -join [Environment]::NewLine) | ConvertFrom-Json
        $publishedPorts = @($config.services.PSObject.Properties.Value | ForEach-Object {
            $portsProperty = $_.PSObject.Properties['ports']
            if ($null -ne $portsProperty) {
                @($portsProperty.Value)
            }
        }) | Where-Object { $null -ne $_ }
        foreach ($port in $publishedPorts) {
            if ([string]$port.host_ip -cne '127.0.0.1') {
                $failures.Add("Published port $($port.published) is not restricted to IPv4 loopback")
            }
        }
        foreach ($service in $config.services.PSObject.Properties.Value) {
            if (@($service.security_opt) -notcontains 'no-new-privileges:true') {
                $failures.Add('Every container must set no-new-privileges')
                break
            }
        }
        $caddyPorts = @($config.services.caddy.ports)
        if ($caddyPorts.Count -ne 1 -or [int]$caddyPorts[0].target -ne 8088 -or
            [int]$caddyPorts[0].published -ne 8088 -or [string]$caddyPorts[0].host_ip -cne '127.0.0.1') {
            $failures.Add('Caddy must publish exactly 127.0.0.1:8088 -> 8088')
        }
    }

    & git check-ignore --quiet infra/.env
    if ($LASTEXITCODE -ne 0) {
        $failures.Add('infra/.env is not ignored by Git')
    }
    $trackedSensitiveEnvironmentFiles = @(@(& git ls-files) |
        Where-Object { $_ -match '(^|/)\.env($|\.)' -and $_ -notmatch '\.env\.example$' })
    if ($trackedSensitiveEnvironmentFiles.Count -ne 0) {
        $failures.Add('A non-example environment file is tracked by Git')
    }

    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('showdown-secrets-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    try {
        $firstPath = Join-Path $temporaryRoot '.env.first'
        $secondPath = Join-Path $temporaryRoot '.env.second'
        & $secretManager -Mode Initialize -EnvironmentFile $firstPath -TemplateFile $templateFile *> $null
        & $secretManager -Mode Initialize -EnvironmentFile $secondPath -TemplateFile $templateFile *> $null
        $first = Read-DotEnv -Path $firstPath
        $second = Read-DotEnv -Path $secondPath
        Test-GeneratedSecrets -Values $first -Label 'first generated environment'
        Test-GeneratedSecrets -Values $second -Label 'second generated environment'
        foreach ($name in $secretNames) {
            if ($first.ContainsKey($name) -and $second.ContainsKey($name) -and
                $first[$name] -ceq $second[$name]) {
                $failures.Add("Two independent generations reused $name")
            }
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporaryRoot) {
            Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
        }
    }

    if (-not $SkipActiveEnvironment -and (Test-Path -LiteralPath $environmentFile)) {
        $active = Read-DotEnv -Path $environmentFile
        Test-GeneratedSecrets -Values $active -Label 'infra/.env'
        if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
            $acl = Get-Acl -LiteralPath $environmentFile
            if (-not $acl.AreAccessRulesProtected) {
                $failures.Add('infra/.env still inherits filesystem permissions')
            }
            $usersSid = 'S-1-5-32-545'
            $broadWrite = @($acl.Access | Where-Object {
                $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
                $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -eq $usersSid -and
                ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -ne 0
            })
            if ($broadWrite.Count -ne 0) {
                $failures.Add('infra/.env grants write access to the built-in Users group')
            }
        }
    }
}
finally {
    Pop-Location
}

if ($failures.Count -ne 0) {
    $failures | ForEach-Object { Write-Error $_ }
    throw "Local security validation failed with $($failures.Count) issue(s)"
}

Write-Host 'Local security checks passed without exposing secret values.'
