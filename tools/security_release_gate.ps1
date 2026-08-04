param(
    [switch]$PlayUpload,
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$safeDirectory = $projectRoot.Replace('\', '/')

function Require-File([string]$RelativePath) {
    $path = Join-Path $projectRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required security file is missing: $RelativePath"
    }
}

function Require-TextAbsent([string]$RelativePath, [string[]]$ForbiddenText) {
    $path = Join-Path $projectRoot $RelativePath
    $content = Get-Content -Raw -LiteralPath $path
    foreach ($forbidden in $ForbiddenText) {
        if ($content.IndexOf(
            $forbidden,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -ge 0) {
            throw "Security contradiction in ${RelativePath}: $forbidden"
        }
    }
}

foreach ($required in @(
    'gradle\verification-metadata.xml',
    'gradle.lockfile',
    'app\gradle.lockfile',
    'llama-runtime\gradle.lockfile',
    'gradle\wrapper\gradle-wrapper.properties',
    'play\PRIVACY_POLICY.md',
    'play\DATA_SAFETY.md'
)) {
    Require-File $required
}

$wrapper = Get-Content -Raw -LiteralPath (
    Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.properties'
)
if ($wrapper -notmatch '(?m)^distributionSha256Sum=[0-9a-f]{64}$') {
    throw 'The Gradle wrapper distribution is not pinned by SHA-256.'
}

Require-TextAbsent 'play\PRIVACY_POLICY.md' @(
    'does not request Internet access',
    '[PUBLIC SUPPORT EMAIL]',
    '[DEVELOPER OR COMPANY NAME]'
)
Require-TextAbsent 'play\DATA_SAFETY.md' @(
    'merged manifest has no `android.permission.INTERNET`'
)
Require-TextAbsent 'play\UPLOAD_READINESS.md' @(
    'No Internet permission, microphone permission'
)

$git = Get-Command git -ErrorAction Stop
& $git.Source -c "safe.directory=$safeDirectory" `
    -C $projectRoot diff --check -- .
if ($LASTEXITCODE -ne 0) {
    throw 'Git whitespace validation failed.'
}

$trackedMatches = & $git.Source -c "safe.directory=$safeDirectory" `
    -C $projectRoot grep -l -I -E `
    '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|sk-[A-Za-z0-9_-]{20,}|MANUSCRIPT_UPLOAD_STORE_PASSWORD\s*=)' `
    -- . `
    ':(exclude)tools/build_play_bundle.ps1' `
    ':(exclude)tools/security_release_gate.ps1' `
    ':(exclude)vendor/llama.cpp/tools/server/tests/unit/test_security.py' 2>$null
if ($LASTEXITCODE -eq 0 -and $trackedMatches) {
    throw 'A tracked file matches a private-key, API-key, or signing-password pattern.'
}
if ($LASTEXITCODE -notin @(0, 1)) {
    throw 'Tracked secret scan failed to run.'
}

if ($PlayUpload) {
    Require-File 'play\release-contact.properties'
    $contactPath = Join-Path $projectRoot 'play\release-contact.properties'
    $contact = Get-Content -Raw -LiteralPath $contactPath |
        ConvertFrom-StringData
    if ([string]::IsNullOrWhiteSpace($contact.developerName) -or
        [string]::IsNullOrWhiteSpace($contact.supportEmail) -or
        $contact.supportEmail -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$' -or
        [string]::IsNullOrWhiteSpace($contact.privacyPolicyUrl) -or
        $contact.privacyPolicyUrl -notmatch '^https://') {
        throw 'Verified developer name, support email, and HTTPS privacy URL are required for Play upload.'
    }

    $appBuild = Get-Content -Raw -LiteralPath (
        Join-Path $projectRoot 'app\build.gradle'
    )
    $versionCodeMatch = [regex]::Match(
        $appBuild,
        '(?m)^\s*versionCode\s+(\d+)\s*$'
    )
    if (-not $versionCodeMatch.Success -or
        [int]$versionCodeMatch.Groups[1].Value -le 28) {
        throw 'Google Play Internal requires a Manuscript version code greater than 28.'
    }
}

Write-Output 'Manuscript security release gate passed.'
