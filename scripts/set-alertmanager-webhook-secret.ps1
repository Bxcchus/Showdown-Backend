[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SecretFile,
    [Security.SecureString]$WebhookUrl
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-StrictMode -Version Latest

if ($null -eq $WebhookUrl) {
    $WebhookUrl = Read-Host 'URL HTTPS du webhook Alertmanager' -AsSecureString
}
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($WebhookUrl)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $uri = $null
    if (-not [Uri]::TryCreate($plain, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or $plain.Length -gt 2048 -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw 'The Alertmanager webhook must be an absolute HTTPS URL without a fragment.'
    }
    $target = [IO.Path]::GetFullPath($SecretFile)
    $parent = Split-Path -Parent $target
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    [IO.File]::WriteAllText($target, $plain, [Text.UTF8Encoding]::new($false))
}
finally {
    if ($pointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
    $plain = $null
}

if ($IsWindows) {
    $acl = Get-Acl -LiteralPath $target
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) { $acl.RemoveAccessRuleSpecific($rule) }
    foreach ($identity in @(
            [Security.Principal.WindowsIdentity]::GetCurrent().User,
            [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
            [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
                $identity, 'FullControl', 'Allow'))
    }
    Set-Acl -LiteralPath $target -AclObject $acl
} else {
    & chmod 600 $target
}

Write-Output "Alertmanager webhook secret written with restricted permissions: $target"
