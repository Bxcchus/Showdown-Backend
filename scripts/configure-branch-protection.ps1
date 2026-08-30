[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
    [string]$Repository,
    [string]$Branch = 'main'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'GitHub CLI (gh) is required.'
}
& gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI is not authenticated.' }

$requiredChecks = @('java', 'java-sca', 'web', 'watcher', 'docker')
$payload = @{
    required_status_checks = @{
        strict = $true
        contexts = $requiredChecks
    }
    enforce_admins = $true
    required_pull_request_reviews = @{
        dismiss_stale_reviews = $true
        require_code_owner_reviews = $false
        required_approving_review_count = 1
        require_last_push_approval = $true
    }
    restrictions = $null
    required_linear_history = $true
    allow_force_pushes = $false
    allow_deletions = $false
    block_creations = $false
    required_conversation_resolution = $true
    lock_branch = $false
    allow_fork_syncing = $true
} | ConvertTo-Json -Depth 6 -Compress

$temporaryPayload = New-TemporaryFile
try {
    Set-Content -LiteralPath $temporaryPayload -Value $payload -Encoding utf8NoBOM
    & gh api --method PUT "/repos/$Repository/branches/$Branch/protection" `
        --input $temporaryPayload
    if ($LASTEXITCODE -ne 0) { throw 'GitHub rejected the branch protection configuration.' }
}
finally {
    Remove-Item -LiteralPath $temporaryPayload -Force -ErrorAction SilentlyContinue
}

Write-Host "Branch $Branch is protected; all five backend CI jobs are mandatory."
