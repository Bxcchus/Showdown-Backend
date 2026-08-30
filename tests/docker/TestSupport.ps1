Set-StrictMode -Version Latest

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )

    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
}

function Assert-Equal {
    param(
        [AllowNull()]$Expected,
        [AllowNull()]$Actual,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Expected -ne $Actual) {
        throw "Assertion failed: $Message (expected '$Expected', got '$Actual')"
    }
}

function Read-DotEnvValue {
    param(
        [Parameter(Mandatory)][string]$EnvironmentFile,
        [Parameter(Mandatory)][string]$Name
    )

    $line = Get-Content -LiteralPath $EnvironmentFile |
        Where-Object { $_ -like "$Name=*" } |
        Select-Object -First 1
    if (-not $line) {
        throw "Missing $Name in $EnvironmentFile"
    }
    return $line.Substring($line.IndexOf('=') + 1)
}

function Read-HiddenInput {
    param(
        [Parameter(Mandatory)][string]$Html,
        [Parameter(Mandatory)][string]$Name
    )

    $inputPattern = '<input\b[^>]*\bname="' + [Regex]::Escape($Name) + '"[^>]*>'
    $input = [Regex]::Match(
        $Html,
        $inputPattern,
        [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $input.Success) {
        throw "OAuth form field '$Name' was not found"
    }
    $value = [Regex]::Match(
        $input.Value,
        '\bvalue="([^"]*)"',
        [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $value.Success) {
        throw "OAuth form field '$Name' has no value"
    }
    return $value.Groups[1].Value
}

function Get-PlayerAccessToken {
    param(
        [Parameter(Mandatory)][string]$BaseUri,
        [Parameter(Mandatory)][string]$Username,
        [Parameter(Mandatory)][string]$Password
    )

    $random = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $verifier = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $digest = [Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::ASCII.GetBytes($verifier))
    $challenge = [Convert]::ToBase64String($digest).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $redirectUri = "$BaseUri/oauth/callback"
    $requestState = [Guid]::NewGuid().ToString('N')
    $scopes = @(
        'openid',
        'profile:read',
        'profile:write',
        'party:manage',
        'queue:write',
        'match:read',
        'match:ready'
    )
    $query = @(
        'response_type=code'
        'client_id=pinkward-web'
        ('scope=' + [Uri]::EscapeDataString(($scopes -join ' ')))
        ('redirect_uri=' + [Uri]::EscapeDataString($redirectUri))
        ('state=' + $requestState)
        ('code_challenge=' + $challenge)
        'code_challenge_method=S256'
    ) -join '&'

    $loginPage = Invoke-WebRequest "$BaseUri/oauth2/authorize?$query" -SessionVariable oauthSession
    # Chrome DevTools requests this URL automatically. It must never reach the
    # identity service, otherwise Spring Security replaces the pending OAuth
    # request and the user has to start the sign-in flow a second time.
    $devToolsProbe = Invoke-WebRequest `
        "$BaseUri/.well-known/appspecific/com.chrome.devtools.json" `
        -WebSession $oauthSession `
        -SkipHttpErrorCheck
    if ($devToolsProbe.StatusCode -ne 404) {
        throw "Chrome DevTools probe unexpectedly entered the identity flow (HTTP $($devToolsProbe.StatusCode))"
    }
    $afterLogin = Invoke-WebRequest "$BaseUri/login" `
        -Method Post `
        -WebSession $oauthSession `
        -Body @{
            username = $Username
            password = $Password
            _csrf = Read-HiddenInput -Html $loginPage.Content -Name '_csrf'
        } `
        -MaximumRedirection 1 `
        -SkipHttpErrorCheck `
        -ErrorAction SilentlyContinue

    $consentPage = $afterLogin
    if ($afterLogin.StatusCode -eq 302 -and
            [string]$afterLogin.Headers.Location[0] -like '*/oauth2/consent*') {
        $consentPage = Invoke-WebRequest $afterLogin.Headers.Location[0] -WebSession $oauthSession
    }

    if ($consentPage.StatusCode -eq 200) {
        $consentState = Read-HiddenInput -Html $consentPage.Content -Name 'state'
        $consentBody = @(
            'client_id=pinkward-web'
            ('state=' + [Uri]::EscapeDataString($consentState))
            ($scopes | ForEach-Object { 'scope=' + [Uri]::EscapeDataString($_) })
        ) -join '&'
        $authorization = Invoke-WebRequest "$BaseUri/oauth2/authorize" `
            -Method Post `
            -WebSession $oauthSession `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body $consentBody `
            -MaximumRedirection 0 `
            -SkipHttpErrorCheck `
            -ErrorAction SilentlyContinue
    }
    else {
        $authorization = $afterLogin
    }

    if ($authorization.StatusCode -ne 302) {
        throw "OAuth authorization for $Username returned HTTP $($authorization.StatusCode)"
    }
    $callbackLocation = [string]$authorization.Headers.Location[0]
    $callbackQuery = [System.Web.HttpUtility]::ParseQueryString(([Uri]$callbackLocation).Query)
    if ($callbackQuery.Get('state') -ne $requestState) {
        throw "OAuth state validation failed for $Username"
    }
    $authorizationCode = $callbackQuery.Get('code')
    if ([string]::IsNullOrWhiteSpace($authorizationCode)) {
        throw "OAuth authorization code was not returned for $Username"
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
    return $token.access_token
}

function Get-WatcherAccessToken {
    param(
        [Parameter(Mandatory)][string]$BaseUri,
        [Parameter(Mandatory)][string]$EnvironmentFile,
        [Parameter(Mandatory)][string]$Scope
    )

    $secret = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'WATCHER_CLIENT_SECRET'
    $basic = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes("pinkward-watcher:$secret"))
    $token = Invoke-RestMethod "$BaseUri/oauth2/token" `
        -Method Post `
        -Headers @{ Authorization = "Basic $basic" } `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            grant_type = 'client_credentials'
            scope = $Scope
        }
    return $token.access_token
}

function Get-ResultIngestorAccessToken {
    param(
        [Parameter(Mandatory)][string]$BaseUri,
        [Parameter(Mandatory)][string]$EnvironmentFile
    )

    $secret = Read-DotEnvValue -EnvironmentFile $EnvironmentFile -Name 'RESULT_INGESTOR_CLIENT_SECRET'
    $basic = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes("pinkward-result-ingestor:$secret"))
    $token = Invoke-RestMethod "$BaseUri/oauth2/token" `
        -Method Post `
        -Headers @{ Authorization = "Basic $basic" } `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            grant_type = 'client_credentials'
            scope = 'service:match:result'
        }
    return $token.access_token
}

function New-BearerHeaders {
    param([Parameter(Mandatory)][string]$AccessToken)
    return @{ Authorization = "Bearer $AccessToken" }
}

function Wait-ForHttpJson {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Headers,
        [int]$ExpectedStatus = 200,
        [int]$TimeoutSeconds = 45,
        [int]$IntervalMilliseconds = 250,
        [string]$Description = 'HTTP condition'
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = $null
    do {
        $response = Invoke-WebRequest $Uri -Headers $Headers -SkipHttpErrorCheck
        $lastStatus = $response.StatusCode
        if ($response.StatusCode -eq $ExpectedStatus) {
            if ([string]::IsNullOrWhiteSpace($response.Content)) {
                return $null
            }
            return $response.Content | ConvertFrom-Json
        }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Description (last HTTP status: $lastStatus)"
}

function Wait-ForHttpStatus {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][int]$ExpectedStatus,
        [int]$TimeoutSeconds = 45,
        [int]$IntervalMilliseconds = 250,
        [string]$Description = 'HTTP status'
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = $null
    do {
        $response = Invoke-WebRequest $Uri -Headers $Headers -SkipHttpErrorCheck
        $lastStatus = $response.StatusCode
        if ($response.StatusCode -eq $ExpectedStatus) {
            return $response.StatusCode
        }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Description (last HTTP status: $lastStatus)"
}

function Invoke-ComposeSql {
    param(
        [Parameter(Mandatory)][string]$ProjectName,
        [Parameter(Mandatory)][string]$EnvironmentFile,
        [Parameter(Mandatory)][string]$ComposeFile,
        [Parameter(Mandatory)][string]$Service,
        [Parameter(Mandatory)][string]$DatabaseUser,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Query
    )

    $arguments = @(
        'compose',
        '--project-name', $ProjectName,
        '--env-file', $EnvironmentFile,
        '-f', $ComposeFile,
        'exec', '-T', $Service,
        'psql', '-v', 'ON_ERROR_STOP=1', '-U', $DatabaseUser, '-d', $Database,
        '-Atc', $Query
    )
    $result = @(& docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "SQL assertion failed in Docker service $Service"
    }
    return (($result -join [Environment]::NewLine).Trim())
}

function Wait-ForSqlValue {
    param(
        [Parameter(Mandatory)][scriptblock]$Query,
        [Parameter(Mandatory)][scriptblock]$Predicate,
        [int]$TimeoutSeconds = 45,
        [int]$IntervalMilliseconds = 250,
        [string]$Description = 'database projection'
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastValue = $null
    do {
        $lastValue = & $Query
        if (& $Predicate $lastValue) {
            return $lastValue
        }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Description (last value: '$lastValue')"
}
