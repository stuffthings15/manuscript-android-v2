Set-StrictMode -Version Latest

function Test-ManuscriptReleaseManifestData {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][psobject]$Manifest
    )

    $required = @(
        'gitCommit',
        'gitTreeCleanForReleaseFiles',
        'contractVersion',
        'contractSha256',
        'applicationId',
        'versionCode',
        'versionName',
        'signerSha256',
        'apkSha256',
        'aabSha256',
        'windowsBrandSvgSha256',
        'generatedBrandProvenanceSha256',
        'unitTestReceiptSha256',
        'lintReceiptSha256',
        's23EvidenceManifestSha256',
        's23TargetApkSha256',
        's23PassLoops'
    )
    foreach ($name in $required) {
        if ($name -notin $Manifest.PSObject.Properties.Name) {
            throw "Release manifest field is missing: $name"
        }
    }
    if ($Manifest.applicationId -ne 'com.remote.Manuscript') {
        throw 'The frozen Play application ID must be com.remote.Manuscript.'
    }
    if ([int]$Manifest.contractVersion -ne 1) {
        throw 'The frozen product contract version must be 1.'
    }
    if (-not [bool]$Manifest.gitTreeCleanForReleaseFiles) {
        throw 'Release files were dirty when the manifest was created.'
    }
    if ([int]$Manifest.s23PassLoops -ne 3) {
        throw 'Exactly three unchanged S23 pass loops are required.'
    }
    if ($Manifest.s23TargetApkSha256 -ne $Manifest.apkSha256) {
        throw 'S23 evidence does not refer to the frozen release APK.'
    }
    if ([int64]$Manifest.versionCode -le 0 -or
        [string]::IsNullOrWhiteSpace([string]$Manifest.versionName)) {
        throw 'Version identity is invalid.'
    }
    if ([string]::IsNullOrWhiteSpace([string]$Manifest.gitCommit)) {
        throw 'Git commit is missing.'
    }
    $digestFields = @(
        'contractSha256',
        'signerSha256',
        'apkSha256',
        'aabSha256',
        'windowsBrandSvgSha256',
        'generatedBrandProvenanceSha256',
        'unitTestReceiptSha256',
        'lintReceiptSha256',
        's23EvidenceManifestSha256',
        's23TargetApkSha256'
    )
    foreach ($name in $digestFields) {
        if ([string]$Manifest.$name -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Release manifest digest is invalid: $name"
        }
    }
}

Export-ModuleMember -Function Test-ManuscriptReleaseManifestData
