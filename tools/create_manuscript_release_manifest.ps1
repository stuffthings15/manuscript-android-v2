param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$AabPath,
    [Parameter(Mandatory)][string]$SignerSha256,
    [string]$UnitTestReceiptPath = 'evidence\release\unit-tests.json',
    [string]$LintReceiptPath = 'evidence\release\lint.json',
    [string]$S23EvidenceManifestPath = 'evidence\device\s23\three-run-manifest.json',
    [string]$OutputPath = 'evidence\release\manuscript-release-manifest.json'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$apkanalyzer = Join-Path $sdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'
if (-not (Test-Path -LiteralPath $apkanalyzer)) {
    throw "apkanalyzer is missing: $apkanalyzer"
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
}

function Resolve-ProjectPath {
    param([Parameter(Mandatory)][string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Path))
}

function Relative-ProjectPath {
    param([Parameter(Mandatory)][string]$Path)
    (Resolve-ProjectPath $Path).Substring($projectRoot.Length).TrimStart('\')
}

$apk = Resolve-ProjectPath $ApkPath
$aab = Resolve-ProjectPath $AabPath
$unitReceipt = Resolve-ProjectPath $UnitTestReceiptPath
$lintReceipt = Resolve-ProjectPath $LintReceiptPath
$s23ManifestPath = Resolve-ProjectPath $S23EvidenceManifestPath
$contractPath = Join-Path $projectRoot 'docs\product-contract\manuscript-android-v2.json'
$brandSvgPath = Join-Path $projectRoot 'brand\canonical\manuscript.svg'
$brandProvenancePath = Join-Path $projectRoot 'evidence\brand\generated-brand-assets.json'

foreach ($path in @(
    $apk,
    $aab,
    $unitReceipt,
    $lintReceipt,
    $s23ManifestPath,
    $contractPath,
    $brandSvgPath,
    $brandProvenancePath
)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Release input is missing: $path"
    }
}
if ($SignerSha256 -notmatch '^[0-9A-Fa-f]{64}$') {
    throw 'SignerSha256 must be a full SHA256 digest.'
}

$applicationId = (& $apkanalyzer manifest application-id $apk).Trim()
$versionName = (& $apkanalyzer manifest version-name $apk).Trim()
$versionCode = [int](& $apkanalyzer manifest version-code $apk).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Could not inspect the frozen release APK.'
}
$contract = Get-Content -Raw -LiteralPath $contractPath | ConvertFrom-Json
$s23 = Get-Content -Raw -LiteralPath $s23ManifestPath | ConvertFrom-Json
$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
$releaseStatus = @(
    & git -C $projectRoot status --porcelain -- `
        app/src/main `
        app/build.gradle `
        tools `
        docs/product-contract `
        docs/qa
)

$manifest = [ordered]@{
    schema = 'manuscript-frozen-release-manifest-v1'
    gitCommit = (& git -C $projectRoot rev-parse HEAD).Trim()
    gitTreeCleanForReleaseFiles = $releaseStatus.Count -eq 0
    contractVersion = [int]$contract.version
    contractSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $contractPath).Hash
    applicationId = $applicationId
    versionCode = $versionCode
    versionName = $versionName
    signerSha256 = $SignerSha256.ToUpperInvariant()
    apkSha256 = $apkHash
    aabSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $aab).Hash
    windowsBrandSvgSha256 =
        (Get-FileHash -Algorithm SHA256 -LiteralPath $brandSvgPath).Hash
    generatedBrandProvenanceSha256 =
        (Get-FileHash -Algorithm SHA256 -LiteralPath $brandProvenancePath).Hash
    unitTestReceiptSha256 =
        (Get-FileHash -Algorithm SHA256 -LiteralPath $unitReceipt).Hash
    lintReceiptSha256 =
        (Get-FileHash -Algorithm SHA256 -LiteralPath $lintReceipt).Hash
    s23EvidenceManifestSha256 =
        (Get-FileHash -Algorithm SHA256 -LiteralPath $s23ManifestPath).Hash
    s23TargetApkSha256 = $s23.targetApkSha256
    s23PassLoops = @($s23.runs | Where-Object { $_.passed -eq $true }).Count
    files = [ordered]@{
        contract = Relative-ProjectPath $contractPath
        apk = Relative-ProjectPath $apk
        aab = Relative-ProjectPath $aab
        windowsBrandSvg = Relative-ProjectPath $brandSvgPath
        generatedBrandProvenance = Relative-ProjectPath $brandProvenancePath
        unitTestReceipt = Relative-ProjectPath $unitReceipt
        lintReceipt = Relative-ProjectPath $lintReceipt
        s23EvidenceManifest = Relative-ProjectPath $s23ManifestPath
    }
}

Import-Module (Join-Path $PSScriptRoot 'ManuscriptReleaseManifestPolicy.psm1') -Force
Test-ManuscriptReleaseManifestData -Manifest ([pscustomobject]$manifest)
$output = Resolve-ProjectPath $OutputPath
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $output -Encoding utf8
& (Join-Path $PSScriptRoot 'verify_manuscript_release_manifest.ps1') `
    -ManifestPath $output | Out-Null
Write-Output $output
