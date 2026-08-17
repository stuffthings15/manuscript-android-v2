<#
.SYNOPSIS
Creates the Version 2 Play upload key, once.

.DESCRIPTION
Version 2 ships as its own Play app under com.remote.Manuscript.v2. A brand new
Play app has no upload key until the first bundle is uploaded, so V2 can have
its own rather than inheriting Version 1's. That keeps the two apps
independent: losing or rotating one key does not touch the other.

The password is generated here from the cryptographic RNG, is never shown, and
is written straight into a DPAPI-protected credential file that only this
Windows account can decrypt. It is never typed, never logged and never stored
in the repository.

THIS SCRIPT REFUSES TO OVERWRITE AN EXISTING KEY. An upload key that Play has
already seen cannot be replaced without Google's key-reset process, so
destroying one locally would be unrecoverable damage rather than an
inconvenience. Move the old files aside deliberately if a fresh key is really
wanted.

.EXAMPLE
.\tools\create_upload_key.ps1
#>
param(
    [string]$KeyStoreFile = "$env:LOCALAPPDATA\Manuscript\manuscript-v2-upload.jks",
    [string]$CredentialFile = "$env:LOCALAPPDATA\Manuscript\manuscript-v2-upload-credential.xml",
    [string]$KeyAlias = 'manuscript-v2-upload',
    [int]$ValidityDays = 10000
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (Test-Path -LiteralPath $KeyStoreFile) {
    throw "An upload key already exists at $KeyStoreFile. Refusing to overwrite it: if Play has seen this key, replacing it needs Google's key reset process."
}
if (Test-Path -LiteralPath $CredentialFile) {
    throw "A credential already exists at $CredentialFile. Refusing to overwrite it."
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:JAVA_HOME = @(
        'C:\Program Files\Android\openjdk\jdk-21.0.8',
        'C:\Users\stuff\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
    ) | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME is required to run keytool.'
}
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) {
    throw "keytool is missing from JAVA_HOME: $keytool"
}

$directory = Split-Path -Parent $KeyStoreFile
if (-not (Test-Path -LiteralPath $directory)) {
    New-Item -ItemType Directory -Path $directory | Out-Null
}

# 40 characters from the cryptographic RNG. Long enough that the passphrase is
# never the weak part, and it never has to be typed by a human.
$alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
# RNGCryptoServiceProvider rather than RandomNumberGenerator.Fill: this runs
# on Windows PowerShell 5.1, which is .NET Framework, and Fill does not exist
# there. That mistake throws, which is fine. Reaching for Get-Random instead
# would NOT throw, and would not be cryptographic, which is the dangerous one.
$bytes = New-Object byte[] 40
$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
try {
    $rng.GetBytes($bytes)
} finally {
    $rng.Dispose()
}
$password = -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })

$createdKeyStore = $false
$createdCredential = $false
try {
    $env:MANUSCRIPT_V2_KEY_PASSWORD = $password

    # keytool writes its progress to stderr even when it succeeds. Under
    # ErrorActionPreference Stop that becomes a terminating NativeCommandError
    # and a working key reads as a failure, which is exactly what happened on
    # the first attempt here. Judge it by exit code.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $keytool -genkeypair `
        -keystore $KeyStoreFile `
        -storetype PKCS12 `
        -alias $KeyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -validity $ValidityDays `
        -dname 'CN=Manuscript, OU=Manuscript Android, O=Echo-Link, L=Unknown, ST=Unknown, C=US' `
        -storepass:env MANUSCRIPT_V2_KEY_PASSWORD `
        -keypass:env MANUSCRIPT_V2_KEY_PASSWORD
    $keytoolExit = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($keytoolExit -ne 0) { throw 'keytool failed to create the upload key.' }
    $createdKeyStore = $true

    # DPAPI, current user. The file is useless on any other account or machine.
    $secure = ConvertTo-SecureString -String $password -AsPlainText -Force
    $credential = New-Object System.Management.Automation.PSCredential($KeyAlias, $secure)
    $credential | Export-Clixml -LiteralPath $CredentialFile
    $createdCredential = $true

    $ErrorActionPreference = 'Continue'
    $details = & $keytool -list -v `
        -keystore $KeyStoreFile `
        -alias $KeyAlias `
        -storepass:env MANUSCRIPT_V2_KEY_PASSWORD
    $listExit = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($listExit -ne 0) { throw 'Could not read back the new certificate.' }
    $match = [regex]::Match(
        ($details -join "`n"),
        'SHA256:\s*([0-9A-F:]+)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    $fingerprint = $match.Groups[1].Value.Replace(':', '').ToUpperInvariant()

    Write-Output "Created upload key: $KeyStoreFile"
    Write-Output "Alias: $KeyAlias"
    Write-Output "Certificate SHA256: $fingerprint"
    Write-Output 'Put that fingerprint in build_play_bundle.ps1 so a wrong key fails before a build rather than at upload.'
} catch {
    # A keystore whose password was never persisted is unusable, and it would
    # block the retry because this script refuses to overwrite an existing key.
    # These two files are only useful together and neither has been used yet,
    # so a failed run leaves nothing behind rather than a trap for the next one.
    if ($createdKeyStore -and -not $createdCredential) {
        Remove-Item -LiteralPath $KeyStoreFile -Force -ErrorAction SilentlyContinue
    }
    throw
} finally {
    $password = $null
    Remove-Item Env:MANUSCRIPT_V2_KEY_PASSWORD -ErrorAction SilentlyContinue
}
