param(
    [string]$ManifestPath = 'evidence\release\manuscript-release-manifest.json'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedManifest = $ExecutionContext.SessionState.Path
    .GetUnresolvedProviderPathFromPSPath($ManifestPath)
if (-not (Test-Path -LiteralPath $resolvedManifest)) {
    throw "Frozen release manifest is missing: $resolvedManifest"
}

Import-Module (Join-Path $PSScriptRoot 'ManuscriptReleaseManifestPolicy.psm1') -Force
$manifest = Get-Content -Raw -LiteralPath $resolvedManifest | ConvertFrom-Json
Test-ManuscriptReleaseManifestData -Manifest $manifest

if ('files' -notin $manifest.PSObject.Properties.Name) {
    throw 'Frozen release manifest file bindings are missing.'
}

function Assert-FileHash {
    param(
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string]$ExpectedSha256
    )
    $path = Join-Path $projectRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Frozen release input is missing: $RelativePath"
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
    if ($actual -ne $ExpectedSha256) {
        throw "Frozen release input changed: $RelativePath"
    }
}

Assert-FileHash $manifest.files.contract $manifest.contractSha256
Assert-FileHash $manifest.files.apk $manifest.apkSha256
Assert-FileHash $manifest.files.aab $manifest.aabSha256
Assert-FileHash $manifest.files.windowsBrandSvg $manifest.windowsBrandSvgSha256
Assert-FileHash `
    $manifest.files.generatedBrandProvenance `
    $manifest.generatedBrandProvenanceSha256
Assert-FileHash $manifest.files.unitTestReceipt $manifest.unitTestReceiptSha256
Assert-FileHash $manifest.files.lintReceipt $manifest.lintReceiptSha256
Assert-FileHash `
    $manifest.files.s23EvidenceManifest `
    $manifest.s23EvidenceManifestSha256

$s23 = Get-Content -Raw -LiteralPath (
    Join-Path $projectRoot $manifest.files.s23EvidenceManifest
) | ConvertFrom-Json
if ($s23.targetApkSha256 -ne $manifest.apkSha256) {
    throw 'S23 manifest points to a different APK.'
}
if (@($s23.runs | Where-Object { $_.passed -eq $true }).Count -ne 3) {
    throw 'S23 manifest does not contain three passing loops.'
}

$head = (& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $manifest.gitCommit) {
    throw 'Git HEAD does not match the frozen release manifest.'
}
$releaseStatus = @(
    & git -C $projectRoot status --porcelain -- `
        app/src/main `
        app/build.gradle `
        tools `
        docs/product-contract `
        docs/qa
)
if ($releaseStatus.Count -gt 0) {
    throw 'Release files are dirty after the frozen manifest was created.'
}

$changedProductFiles = @(
    & git -C $projectRoot show --pretty=format: --name-only HEAD |
        Where-Object {
            $_ -match '^(app/src/main|tools|docs/product-contract|docs/qa)'
        }
)
$repositoryRoot = (& git -C $projectRoot rev-parse --show-toplevel).Trim()
foreach ($relativeToRepo in $changedProductFiles) {
    $path = Join-Path $repositoryRoot $relativeToRepo
    if (Test-Path -LiteralPath $path) {
        $text = Get-Content -Raw -LiteralPath $path -ErrorAction SilentlyContinue
        if ($text -match [char]0x2014) {
            throw "A changed product file contains an em dash: $relativeToRepo"
        }
    }
}

Write-Output $resolvedManifest
