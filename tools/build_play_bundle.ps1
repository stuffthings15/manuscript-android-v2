<#
.SYNOPSIS
Builds the signed Version 2 Play bundle using the existing Manuscript upload key.

.DESCRIPTION
Curtis decided on 2026-08-16 that Version 2 ships as its own Play app under
com.remote.Manuscript.v2, signed with the SAME upload key as Version 1. A Play
upload key may be reused across apps; the package is what separates them.

The key never enters this repository. It is read from the DPAPI-protected
credential in %LOCALAPPDATA%\Manuscript, which only this Windows account can
decrypt, exactly as the archive tree's build_play_bundle.ps1 does.

The certificate fingerprint is checked before anything is built. Signing an
upload with the wrong key produces a bundle Play rejects at the very end of the
process, with an error about the certificate rather than about the key, so it
is worth failing here instead.

The password is never printed, never written to a file, and is removed from the
environment in the finally block.

.EXAMPLE
.\tools\build_play_bundle.ps1
#>
param(
    [string]$CredentialFile = "$env:LOCALAPPDATA\Manuscript\play-upload-credential.xml",
    [string]$KeyStoreFile = "$env:LOCALAPPDATA\Manuscript\play-upload.jks",
    [string]$KeyAlias = 'manuscript-upload'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot

# The same certificate the archive tree pins. Version 2 reuses the key, so it
# must present the same fingerprint; a different one means a different key.
$expectedUploadCertificateSha256 = '4521793E05165F4C7745B14285959594EB08B7A91BB937AD264492621F852F8B'

if (-not (Test-Path -LiteralPath $KeyStoreFile)) {
    throw "Manuscript upload key is missing: $KeyStoreFile"
}
if (-not (Test-Path -LiteralPath $CredentialFile)) {
    throw "Manuscript upload credential is missing: $CredentialFile"
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:JAVA_HOME = @(
        'C:\Program Files\Android\openjdk\jdk-21.0.8',
        'C:\Users\stuff\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
    ) | Where-Object {
        Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')
    } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME is required to sign and inspect the bundle.'
}
if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

$credential = Import-Clixml -LiteralPath $CredentialFile
$password = $credential.GetNetworkCredential().Password

$env:MANUSCRIPT_UPLOAD_STORE_FILE = $KeyStoreFile
$env:MANUSCRIPT_UPLOAD_STORE_PASSWORD = $password
$env:MANUSCRIPT_UPLOAD_KEY_ALIAS = $KeyAlias
$env:MANUSCRIPT_UPLOAD_KEY_PASSWORD = $password

try {
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (-not (Test-Path -LiteralPath $keytool)) {
        throw "keytool is missing from JAVA_HOME: $keytool"
    }
    $keyDetails = & $keytool -list -v `
        -keystore $KeyStoreFile `
        -alias $KeyAlias `
        -storepass:env MANUSCRIPT_UPLOAD_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect the Manuscript upload certificate.'
    }
    $keyMatch = [regex]::Match(
        ($keyDetails -join "`n"),
        'SHA256:\s*([0-9A-F:]+)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    $keyFingerprint = $keyMatch.Groups[1].Value.Replace(':', '').ToUpperInvariant()
    if ($keyFingerprint -ne $expectedUploadCertificateSha256) {
        throw "Upload certificate fingerprint does not match the approved Manuscript certificate."
    }
    Write-Output "Upload certificate verified: $keyFingerprint"

    Push-Location $projectRoot
    try {
        # Gradle writes progress to stderr even on success. Under
        # ErrorActionPreference Stop that becomes a terminating NativeCommandError
        # and a green build reads as a failure, so judge it by exit code, which
        # is the only thing that actually says whether it worked.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & .\gradlew.bat testDebugUnitTest lintDebug bundleRelease
            $buildExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($buildExit -ne 0) { throw 'Manuscript Version 2 release build failed.' }
    } finally {
        Pop-Location
    }

    $bundle = Join-Path $projectRoot 'app\build\outputs\bundle\release\app-release.aab'
    if (-not (Test-Path -LiteralPath $bundle)) {
        throw "The release bundle was not produced: $bundle"
    }

    # Prove the bundle is actually signed. An unsigned AAB is byte-for-byte
    # plausible in every other respect and is only rejected at upload, which is
    # the worst place to find out.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($bundle)
    try {
        $signatureEntries = @(
            $zip.Entries | Where-Object {
                $_.FullName -like 'META-INF/*.RSA' -or
                $_.FullName -like 'META-INF/*.DSA' -or
                $_.FullName -like 'META-INF/*.EC'
            }
        )
        if ($signatureEntries.Count -eq 0) {
            throw 'The release bundle is UNSIGNED. Play will reject it.'
        }
        Write-Output "Bundle signature entries: $($signatureEntries.Count)"
    } finally {
        $zip.Dispose()
    }

    $hash = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash
    $size = (Get-Item -LiteralPath $bundle).Length
    Write-Output "Signed bundle: $bundle"
    Write-Output "Size: $size bytes"
    Write-Output "SHA256: $hash"
} finally {
    $password = $null
    Remove-Item Env:MANUSCRIPT_UPLOAD_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:MANUSCRIPT_UPLOAD_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:MANUSCRIPT_UPLOAD_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:MANUSCRIPT_UPLOAD_KEY_PASSWORD -ErrorAction SilentlyContinue
}
