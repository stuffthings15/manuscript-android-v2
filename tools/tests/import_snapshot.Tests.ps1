$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scriptPath = Join-Path $root 'tools\write_import_snapshot.ps1'
$receiptPath = Join-Path $root 'quality\import-snapshot-receipt.json'

Describe 'Version 2 import snapshot' {
    It 'writes a complete, deterministic receipt for the frozen import' {
        & $scriptPath -ProjectRoot $root -ReceiptPath $receiptPath
        $LASTEXITCODE | Should Be 0

        $receipt = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json
        $receipt.sourceCommit | Should Be 'b2baa9442858a8f20218d50d0f9d1d235ec17e55'
        $receipt.vendorCommit | Should Be 'c0bc8591e8815c63cb01dd3f051a8b0df02501c9'
        $receipt.importCommit | Should Be 'a055eba7fcdb4ea4ad8e7a5ea9a585eaf89416b1'
        $receipt.canonicalZipSha256 | Should Be 'C80A6FBA0978596A464E1E9FF3C17D17D6A928FA56445A97C8E045A0EB9FF771'
        ([string]$receipt.sourceDirtyDiffSha256) | Should Match '^[A-F0-9]{64}$'
        ([string]$receipt.vendorDirtyDiffSha256) | Should Match '^[A-F0-9]{64}$'
        $receipt.copiedFiles.Count | Should BeGreaterThan 3000
        $receipt.copiedFileManifestSha256 | Should Match '^[A-F0-9]{64}$'
        $receipt.copiedUntrackedFiles | Should Not BeNullOrEmpty
        $receipt.corpusFiles.Count | Should Be 20
        $receipt.officialIcons.Count | Should BeGreaterThan 0
        ($receipt.exclusions -join "`n") | Should Match '(?m)^local\.properties$'
        ($receipt.exclusions -join "`n") | Should Match '(?m)^nested Git data$'
    }

    It 'rejects real secret patterns while excluding only llama.cpp test fixture' {
        $gate = Join-Path $root 'tools\security_release_gate.ps1'

        $testRoot = Join-Path $TestDrive 'secret-scan'
        New-Item -ItemType Directory -Force -Path "$testRoot\tools", "$testRoot\gradle\wrapper", "$testRoot\app", "$testRoot\llama-runtime", "$testRoot\play", "$testRoot\vendor\llama.cpp\tools\server\tests\unit" | Out-Null
        Copy-Item -LiteralPath $gate -Destination "$testRoot\tools\security_release_gate.ps1"
        Set-Content -LiteralPath "$testRoot\gradle\verification-metadata.xml" -Value '<verification-metadata />'
        Set-Content -LiteralPath "$testRoot\gradle.lockfile" -Value ''
        Set-Content -LiteralPath "$testRoot\app\gradle.lockfile" -Value ''
        Set-Content -LiteralPath "$testRoot\llama-runtime\gradle.lockfile" -Value ''
        $wrapperHash = ('a' * 64) -join ''
        Set-Content -LiteralPath "$testRoot\gradle\wrapper\gradle-wrapper.properties" -Value ('distributionSha256Sum=' + $wrapperHash) -NoNewline
        Set-Content -LiteralPath "$testRoot\play\PRIVACY_POLICY.md" -Value 'valid policy'
        Set-Content -LiteralPath "$testRoot\play\DATA_SAFETY.md" -Value 'valid disclosure'
        Set-Content -LiteralPath "$testRoot\play\UPLOAD_READINESS.md" -Value 'valid readiness'
        & git -C $testRoot init --quiet
        & git -C $testRoot config user.email 'test@example.invalid'
        & git -C $testRoot config user.name 'Test Runner'
        $secret = 'BEGIN ' + 'RSA PRIVATE ' + 'KEY'
        Set-Content -LiteralPath "$testRoot\vendor\llama.cpp\tools\server\tests\unit\test_security.py" -Value $secret
        & git -C $testRoot add .
        & git -C $testRoot commit --quiet -m 'fixture'

        { & "$testRoot\tools\security_release_gate.ps1" -ProjectRoot $testRoot } | Should Not Throw
        Set-Content -LiteralPath "$testRoot\fixture.txt" -Value $secret
        & git -C $testRoot add fixture.txt
        & git -C $testRoot commit --quiet -m 'real secret fixture'
        { & "$testRoot\tools\security_release_gate.ps1" -ProjectRoot $testRoot } | Should Throw
    }
}
