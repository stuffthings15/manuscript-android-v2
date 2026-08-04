$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$policyPath = Join-Path $root 'tools\ManuscriptReleaseManifestPolicy.psm1'
$verifierPath = Join-Path $root 'tools\verify_manuscript_release_manifest.ps1'

Describe 'Manuscript frozen release manifest' {
    BeforeAll {
        Import-Module $policyPath -Force
        $script:good = [pscustomobject]@{
            gitCommit = '1234567890abcdef'
            gitTreeCleanForReleaseFiles = $true
            contractVersion = 1
            contractSha256 = 'A' * 64
            applicationId = 'com.remote.Manuscript.v2'
            versionCode = 1
            versionName = '2.0.0-internal'
            signerSha256 = 'B' * 64
            apkSha256 = 'C' * 64
            aabSha256 = 'D' * 64
            windowsBrandSvgSha256 = 'E' * 64
            generatedBrandProvenanceSha256 = 'F' * 64
            unitTestReceiptSha256 = '1' * 64
            lintReceiptSha256 = '2' * 64
            s23EvidenceManifestSha256 = '3' * 64
            s23TargetApkSha256 = 'C' * 64
            s23PassLoops = 3
        }
    }

    It 'accepts one complete matching frozen manifest' {
        { Test-ManuscriptReleaseManifestData -Manifest $good } | Should Not Throw
    }

    It 'rejects a legacy or debug package' {
        foreach ($package in @('com.echolink.manuscript', 'com.remote.Manuscript.v2.debug')) {
            $candidate = $good.PSObject.Copy()
            $candidate.applicationId = $package
            { Test-ManuscriptReleaseManifestData -Manifest $candidate } | Should Throw
        }
    }

    It 'rejects fewer than three S23 loops' {
        $candidate = $good.PSObject.Copy()
        $candidate.s23PassLoops = 2
        { Test-ManuscriptReleaseManifestData -Manifest $candidate } | Should Throw
    }

    It 'rejects S23 evidence for another APK' {
        $candidate = $good.PSObject.Copy()
        $candidate.s23TargetApkSha256 = '9' * 64
        { Test-ManuscriptReleaseManifestData -Manifest $candidate } | Should Throw
    }

    It 'rejects a dirty release file set' {
        $candidate = $good.PSObject.Copy()
        $candidate.gitTreeCleanForReleaseFiles = $false
        { Test-ManuscriptReleaseManifestData -Manifest $candidate } | Should Throw
    }

    It 'requires every recorded digest to be SHA256' {
        $candidate = $good.PSObject.Copy()
        $candidate.lintReceiptSha256 = 'short'
        { Test-ManuscriptReleaseManifestData -Manifest $candidate } | Should Throw
    }

    It 'verifier source rejects em dashes and checks real artifact hashes' {
        $source = Get-Content -Raw -LiteralPath $verifierPath
        $source | Should Match 'Get-FileHash'
        $source | Should Match 'em dash'
        $source | Should Match 'Test-ManuscriptReleaseManifestData'
    }
}
