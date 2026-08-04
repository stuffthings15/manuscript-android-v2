param(
    [string]$CredentialFile = "$env:LOCALAPPDATA\Manuscript\play-upload-credential.xml",
    [string]$KeyStoreFile = "$env:LOCALAPPDATA\Manuscript\play-upload.jks"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$credential = Import-Clixml -LiteralPath $CredentialFile
$password = $credential.GetNetworkCredential().Password
$expectedUploadCertificateSha256 = '4521793E05165F4C7745B14285959594EB08B7A91BB937AD264492621F852F8B'

if (-not (Test-Path -LiteralPath $KeyStoreFile)) {
    throw "Manuscript upload key is missing: $KeyStoreFile"
}

if (-not $env:JAVA_HOME) {
    $javaCandidates = @(
        'C:\Users\stuff\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2',
        'C:\Program Files\Android\openjdk\jdk-21.0.8'
    )
    $env:JAVA_HOME = $javaCandidates | Where-Object {
        Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')
    } | Select-Object -First 1
}

$env:MANUSCRIPT_UPLOAD_STORE_FILE = $KeyStoreFile
$env:MANUSCRIPT_UPLOAD_STORE_PASSWORD = $password
$env:MANUSCRIPT_UPLOAD_KEY_ALIAS = 'manuscript-upload'
$env:MANUSCRIPT_UPLOAD_KEY_PASSWORD = $password

try {
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (-not (Test-Path -LiteralPath $keytool)) {
        throw "keytool is missing from JAVA_HOME: $keytool"
    }
    $keyDetails = & $keytool -list -v `
        -keystore $KeyStoreFile `
        -alias $env:MANUSCRIPT_UPLOAD_KEY_ALIAS `
        -storepass:env MANUSCRIPT_UPLOAD_STORE_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the Manuscript upload certificate."
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

    Push-Location $projectRoot
    try {
        & "$PSScriptRoot\security_release_gate.ps1" -PlayUpload
        & "$PSScriptRoot\generate_dependency_sbom.ps1"
        & "$PSScriptRoot\check_dependency_advisories.ps1"
        & .\gradlew.bat :app:testReleaseUnitTest :app:lintRelease `
            :app:assembleRelease validatePlayBundle
        if ($LASTEXITCODE -ne 0) { throw "Manuscript release build failed." }

        $aabPath = Join-Path $projectRoot 'app\build\outputs\bundle\release\app-release.aab'
        $apkPath = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
        foreach ($artifact in @($aabPath, $apkPath)) {
            if (-not (Test-Path -LiteralPath $artifact)) {
                throw "Expected signed release artifact is missing: $artifact"
            }
        }

        $aabCertificate = & $keytool -printcert -jarfile $aabPath 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Could not verify the signed AAB certificate."
        }
        $aabMatch = [regex]::Match(
            ($aabCertificate -join "`n"),
            'SHA256:\s*([0-9A-F:]+)',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        $aabFingerprint = $aabMatch.Groups[1].Value.Replace(':', '').ToUpperInvariant()
        if ($aabFingerprint -ne $expectedUploadCertificateSha256) {
            throw "Signed AAB certificate does not match the approved Manuscript certificate."
        }

        $apksigner = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.0.0\apksigner.bat'
        if (-not (Test-Path -LiteralPath $apksigner)) {
            throw "apksigner is missing: $apksigner"
        }
        $apkVerification = & $apksigner verify --verbose --print-certs $apkPath 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "The release APK signature is invalid."
        }
        $apkMatch = [regex]::Match(
            ($apkVerification -join "`n"),
            'certificate SHA-256 digest:\s*([0-9A-F]+)',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        $apkFingerprint = $apkMatch.Groups[1].Value.ToUpperInvariant()
        if ($apkFingerprint -ne $expectedUploadCertificateSha256) {
            throw "Signed APK certificate does not match the approved Manuscript certificate."
        }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:MANUSCRIPT_UPLOAD_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:MANUSCRIPT_UPLOAD_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:MANUSCRIPT_UPLOAD_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:MANUSCRIPT_UPLOAD_KEY_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}

Write-Output (Join-Path $projectRoot 'app\build\outputs\bundle\release\app-release.aab')
