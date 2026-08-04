param([string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot))

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$safeDirectory = $projectRoot.Replace('\', '/')

foreach ($required in @(
    'gradle\\verification-metadata.xml',
    'gradle.lockfile',
    'app\\gradle.lockfile',
    'llama-runtime\\gradle.lockfile',
    'gradle\\wrapper\\gradle-wrapper.properties'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $required))) {
        throw "Required security file is missing: $required"
    }
}

$wrapper = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'gradle\\wrapper\\gradle-wrapper.properties')
if ($wrapper -notmatch '(?m)^distributionSha256Sum=[0-9a-f]{64}$') {
    throw 'The Gradle wrapper distribution is not pinned by SHA-256.'
}

$git = Get-Command git -ErrorAction Stop
& $git.Source -c "safe.directory=$safeDirectory" -C $projectRoot diff --check -- .
if ($LASTEXITCODE -ne 0) { throw 'Git whitespace validation failed.' }

$secretPattern = '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|sk-[A-Za-z0-9_-]{20,}|MANUSCRIPT_UPLOAD_STORE_PASSWORD[[:space:]]*=)'
$dotNetSecretPattern = '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|sk-[A-Za-z0-9_-]{20,}|MANUSCRIPT_UPLOAD_STORE_PASSWORD\s*=)'
$trackedMatches = & $git.Source -c "safe.directory=$safeDirectory" -C $projectRoot grep -l -I -E $secretPattern -- . ':(exclude)tools/security_release_gate.ps1' 2>$null
$grepExitCode = $LASTEXITCODE
$allowedFixturePath = 'vendor/llama.cpp/tools/server/tests/unit/test_security.py'
$allowedFixtureDummy = 'sk-this-is-the-secret-key'
$unexpectedMatches = @($trackedMatches | Where-Object {
    if ($_ -ne $allowedFixturePath) {
        return $true
    }
    $fixtureContent = Get-Content -Raw -LiteralPath (Join-Path $projectRoot $_)
    $fixtureMatches = [regex]::Matches($fixtureContent, $dotNetSecretPattern)
    return $fixtureMatches.Count -ne 1 -or
        $fixtureMatches[0].Value -ne $allowedFixtureDummy
})
if ($grepExitCode -eq 0 -and $unexpectedMatches.Count -gt 0) {
    throw 'A tracked file matches a private-key, API-key, or signing-password pattern.'
}
if ($grepExitCode -notin @(0, 1)) { throw 'Tracked secret scan failed to run.' }

Write-Output 'Manuscript local security gate passed.'
