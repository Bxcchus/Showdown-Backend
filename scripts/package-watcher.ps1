[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist\watcher'),
    [switch]$PublicDistribution,
    [string]$CertificateThumbprint
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$watcherRoot = Join-Path $projectRoot 'watcher'
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $outputRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Le dossier de distribution doit rester dans le projet.'
}
if ($PublicDistribution -and [string]::IsNullOrWhiteSpace($CertificateThumbprint)) {
    throw 'Une distribution publique exige CertificateThumbprint.'
}

Push-Location $watcherRoot
try {
    cargo fmt --all -- --check
    if ($LASTEXITCODE -ne 0) { throw 'cargo fmt a échoué.' }
    cargo clippy --all-targets --locked -- -D warnings
    if ($LASTEXITCODE -ne 0) { throw 'cargo clippy a échoué.' }
    cargo test --locked
    if ($LASTEXITCODE -ne 0) { throw 'cargo test a échoué.' }
    cargo build --release --locked
    if ($LASTEXITCODE -ne 0) { throw 'cargo build --release a échoué.' }
} finally {
    Pop-Location
}

[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$source = Join-Path $watcherRoot 'target\release\showdown-watcher.exe'
$destination = Join-Path $outputRoot 'showdown-watcher.exe'
Copy-Item -LiteralPath $source -Destination $destination -Force

if ($PublicDistribution) {
    $certificate = Get-ChildItem Cert:\CurrentUser\My |
        Where-Object Thumbprint -eq $CertificateThumbprint |
        Select-Object -First 1
    if (-not $certificate -or -not $certificate.HasPrivateKey) {
        throw 'Certificat Authenticode avec clé privée introuvable.'
    }
    $signature = Set-AuthenticodeSignature -LiteralPath $destination `
        -Certificate $certificate -HashAlgorithm SHA256 `
        -TimestampServer 'http://timestamp.digicert.com'
    if ($signature.Status -ne 'Valid') {
        throw "Signature Authenticode invalide : $($signature.StatusMessage)"
    }
}

$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(
    (Join-Path $outputRoot 'showdown-watcher.exe.sha256'),
    "$hash  showdown-watcher.exe`n",
    [Text.UTF8Encoding]::new($false))
Write-Output "Package Watcher créé : $outputRoot"
Write-Output 'Les fichiers PDB et les artefacts Cargo ne sont pas inclus.'
