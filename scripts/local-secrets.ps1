[CmdletBinding()]
param(
    [ValidateSet('Initialize', 'Validate', 'Rotate')]
    [string]$Mode = 'Rotate',
    [string]$EnvironmentFile,
    [string]$TemplateFile,
    [switch]$SkipBackup
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$defaultEnvironmentFile = Join-Path $root 'infra/.env'
$composeFile = Join-Path $root 'infra/compose.yml'
$backupScript = Join-Path $PSScriptRoot 'backup-postgres.ps1'
$placeholder = 'GENERATE_ON_FIRST_START'

if ([string]::IsNullOrWhiteSpace($EnvironmentFile)) {
    $EnvironmentFile = $defaultEnvironmentFile
}
else {
    $EnvironmentFile = [IO.Path]::GetFullPath($EnvironmentFile)
}
if ([string]::IsNullOrWhiteSpace($TemplateFile)) {
    $TemplateFile = Join-Path $root 'infra/.env.example'
}
else {
    $TemplateFile = [IO.Path]::GetFullPath($TemplateFile)
}

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

function New-Base64UrlSecret {
    param([int]$ByteCount = 32)

    $bytes = New-Object byte[] $ByteCount
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-SecretSet {
    param([hashtable]$PreviousValues = @{})

    $result = @{}
    $used = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $secretNames) {
        do {
            $candidate = New-Base64UrlSecret
        } while ($used.Contains($candidate) -or
            ($PreviousValues.ContainsKey($name) -and $candidate -ceq $PreviousValues[$name]))
        [void]$used.Add($candidate)
        $result[$name] = $candidate
    }
    return $result
}

function Assert-SecretSet {
    param([Parameter(Mandatory)][hashtable]$Values)

    $used = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $secretNames) {
        if (-not $Values.ContainsKey($name)) {
            throw "Missing local secret: $name"
        }
        $value = [string]$Values[$name]
        if ($value.Length -lt 40 -or $value -notmatch '^[A-Za-z0-9_-]+$') {
            throw "Local secret $name must be a Base64URL value generated from at least 32 random bytes"
        }
        if ($value -ceq $placeholder -or $value -match '(?i)change[-_ ]?me|placeholder|example|local[-_ ].*secret') {
            throw "Local secret $name still contains a development placeholder"
        }
        if (-not $used.Add($value)) {
            throw "Local secret $name duplicates another credential"
        }
    }
}

function Build-EnvironmentContent {
    param(
        [Parameter(Mandatory)][hashtable]$Secrets,
        [hashtable]$ExistingValues = @{}
    )

    $output = [Collections.Generic.List[string]]::new()
    foreach ($line in Get-Content -LiteralPath $TemplateFile) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            $name = $matches[1]
            if ($Secrets.ContainsKey($name)) {
                $output.Add("$name=$($Secrets[$name])")
            }
            elseif ($ExistingValues.ContainsKey($name)) {
                $output.Add("$name=$($ExistingValues[$name])")
            }
            else {
                $output.Add($line)
            }
        }
        else {
            $output.Add($line)
        }
    }
    return ($output -join [Environment]::NewLine) + [Environment]::NewLine
}

function Protect-SecretFile {
    param([Parameter(Mandatory)][string]$Path)

    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $result = @(& icacls $Path '/inheritance:r' '/grant:r' `
            ('*' + $currentSid + ':(F)') '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not restrict the local secret file ACL'
        }
        return
    }

    & chmod 600 -- $Path
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not restrict the local secret file permissions'
    }
}

function Write-EnvironmentAtomically {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Content
    )

    $directory = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = Join-Path $directory ('.env.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary, $Content, [Text.UTF8Encoding]::new($false))
        Protect-SecretFile -Path $temporary
        if (Test-Path -LiteralPath $Path) {
            Move-Item -LiteralPath $temporary -Destination $Path -Force
        }
        else {
            [IO.File]::Move($temporary, $Path)
        }
        Protect-SecretFile -Path $Path
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Invoke-ComposeCapture {
    param([Parameter(Mandatory)][string[]]$ComposeArguments)

    $arguments = @('compose', '--env-file', $EnvironmentFile, '-f', $composeFile) + $ComposeArguments
    $result = @(& docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose operation failed: $($ComposeArguments[0])"
    }
    return $result
}

function Invoke-ComposeQuiet {
    param([Parameter(Mandatory)][string[]]$ComposeArguments)

    $arguments = @('compose', '--env-file', $EnvironmentFile, '-f', $composeFile) + $ComposeArguments
    $result = @(& docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose operation failed: $($ComposeArguments[0])"
    }
}

function Set-PostgresRolePassword {
    param(
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)][string]$Role,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Password
    )

    $sql = "ALTER ROLE $Role WITH PASSWORD '$Password';"
    $arguments = @(
        'compose', '--env-file', $EnvironmentFile, '-f', $composeFile,
        'exec', '-T', $Service, 'psql', '-q', '-v', 'ON_ERROR_STOP=1',
        '-U', $Role, '-d', $Database
    )
    $result = @($sql | & docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not rotate the PostgreSQL role used by $Service"
    }
}

function Set-RabbitPassword {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password
    )

    Invoke-ComposeQuiet -ComposeArguments @(
        'exec', '-T', 'rabbitmq', 'rabbitmqctl', 'change_password', $User, $Password)
}

if (-not (Test-Path -LiteralPath $TemplateFile)) {
    throw "Local environment template not found: $TemplateFile"
}

if ($Mode -eq 'Initialize') {
    if (Test-Path -LiteralPath $EnvironmentFile) {
        $existing = Read-DotEnv -Path $EnvironmentFile
        Assert-SecretSet -Values $existing
        Protect-SecretFile -Path $EnvironmentFile
        Write-Host 'Local secret file already exists and passed validation.'
        return
    }
    $secrets = New-SecretSet
    Assert-SecretSet -Values $secrets
    $content = Build-EnvironmentContent -Secrets $secrets
    Write-EnvironmentAtomically -Path $EnvironmentFile -Content $content
    Write-Host 'Generated a unique local secret set with restricted file permissions.'
    return
}

if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    throw 'infra/.env does not exist; initialize it before validation or rotation'
}

$oldValues = Read-DotEnv -Path $EnvironmentFile
if ($Mode -eq 'Validate') {
    $missing = @($secretNames | Where-Object { -not $oldValues.ContainsKey($_) })
    if ($missing.Count -ne 0) {
        $used = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $oldValues.Values | ForEach-Object { [void]$used.Add([string]$_) }
        foreach ($name in $missing) {
            do { $candidate = New-Base64UrlSecret } while (-not $used.Add($candidate))
            $oldValues[$name] = $candidate
        }
        Write-EnvironmentAtomically -Path $EnvironmentFile -Content (
            Build-EnvironmentContent -Secrets $oldValues -ExistingValues $oldValues)
        Write-Host 'Added newly required local credentials without rotating existing values.'
    }
    Assert-SecretSet -Values $oldValues
    Protect-SecretFile -Path $EnvironmentFile
    Write-Host 'Local secrets passed validation and file permissions were restricted.'
    return
}

if ([IO.Path]::GetFullPath($EnvironmentFile) -cne [IO.Path]::GetFullPath($defaultEnvironmentFile)) {
    throw 'Rotation is restricted to the project infra/.env file'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is required for coordinated secret rotation'
}

$newSecrets = New-SecretSet -PreviousValues $oldValues
Assert-SecretSet -Values $newSecrets
$newContent = Build-EnvironmentContent -Secrets $newSecrets -ExistingValues $oldValues
$oldContent = [IO.File]::ReadAllText($EnvironmentFile)
$postgresRoles = @(
    @{ Service = 'postgres'; Role = 'matchmaking_service'; Database = 'pinkward_matchmaking'; Secret = 'POSTGRES_PASSWORD' },
    @{ Service = 'match-postgres'; Role = 'match_service'; Database = 'pinkward_matches'; Secret = 'MATCH_POSTGRES_PASSWORD' },
    @{ Service = 'player-postgres'; Role = 'player_service'; Database = 'pinkward_players'; Secret = 'PLAYER_POSTGRES_PASSWORD' },
    @{ Service = 'identity-postgres'; Role = 'identity_service'; Database = 'pinkward_identity'; Secret = 'IDENTITY_POSTGRES_PASSWORD' }
)
$rabbitUsers = @(
    @{ User = 'showdown_admin'; Secret = 'RABBITMQ_ADMIN_PASSWORD' },
    @{ User = 'matchmaking_service'; Secret = 'MATCHMAKING_RABBITMQ_PASSWORD' },
    @{ User = 'match_service'; Secret = 'MATCH_RABBITMQ_PASSWORD' }
)
$applicationServices = @(
    'caddy', 'api-gateway', 'identity-service', 'player-service',
    'matchmaking-service', 'match-service', 'redis-exporter',
    'matchmaking-postgres-exporter', 'match-postgres-exporter',
    'player-postgres-exporter', 'identity-postgres-exporter', 'prometheus'
)
$rotatedPostgres = [Collections.Generic.List[hashtable]]::new()
$rotatedRabbit = [Collections.Generic.List[hashtable]]::new()
$stackExists = $false

Push-Location $root
try {
    $containerIds = @(Invoke-ComposeCapture -ComposeArguments @(
        'ps', '-a', '-q', 'postgres', 'match-postgres', 'player-postgres',
        'identity-postgres', 'rabbitmq')) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $stackExists = $containerIds.Count -gt 0
    if (-not $stackExists) {
        Write-EnvironmentAtomically -Path $EnvironmentFile -Content $newContent
        Write-Host 'Rotated the local secret file. The stack has not been initialized yet.'
        return
    }

    Invoke-ComposeQuiet -ComposeArguments @(
        'up', '--detach', '--wait', 'postgres', 'match-postgres',
        'player-postgres', 'identity-postgres', 'rabbitmq')

    if (-not $SkipBackup) {
        & $backupScript -RetentionDays 14
    }

    Invoke-ComposeQuiet -ComposeArguments (@('stop') + $applicationServices)

    $activeLobbyOutput = @(Invoke-ComposeCapture -ComposeArguments @(
        'exec', '-T', 'match-postgres', 'psql', '-U', 'match_service',
        '-d', 'pinkward_matches', '-Atc',
        "select count(*) from matches where status = 'CONFIRMED' and lobby_password_encrypted is not null;"))
    $activeLobbyCount = [int](($activeLobbyOutput -join '').Trim())
    if ($activeLobbyCount -ne 0) {
        throw 'Secret rotation is blocked while a confirmed lobby still needs its encrypted password'
    }

    foreach ($entry in $postgresRoles) {
        Set-PostgresRolePassword -Service $entry.Service -Role $entry.Role `
            -Database $entry.Database -Password $newSecrets[$entry.Secret]
        $rotatedPostgres.Add($entry)
    }
    foreach ($entry in $rabbitUsers) {
        Set-RabbitPassword -User $entry.User -Password $newSecrets[$entry.Secret]
        $rotatedRabbit.Add($entry)
    }

    Write-EnvironmentAtomically -Path $EnvironmentFile -Content $newContent
    Invoke-ComposeQuiet -ComposeArguments @('up', '--detach', '--force-recreate', '--wait')
    Write-Host 'Rotated local credentials and restarted the Docker stack without exposing secret values.'
}
catch {
    $rotationError = $_
    try {
        foreach ($entry in $rotatedPostgres) {
            Set-PostgresRolePassword -Service $entry.Service -Role $entry.Role `
                -Database $entry.Database -Password $oldValues[$entry.Secret]
        }
        foreach ($entry in $rotatedRabbit) {
            Set-RabbitPassword -User $entry.User -Password $oldValues[$entry.Secret]
        }
        Write-EnvironmentAtomically -Path $EnvironmentFile -Content $oldContent
        if ($stackExists) {
            Invoke-ComposeQuiet -ComposeArguments @('up', '--detach', '--force-recreate', '--wait')
        }
    }
    catch {
        throw "Secret rotation failed and automatic rollback also failed. Original error: $($rotationError.Exception.Message)"
    }
    throw $rotationError
}
finally {
    Pop-Location
}
