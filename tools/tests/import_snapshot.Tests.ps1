$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scriptPath = Join-Path $root 'tools\write_import_snapshot.ps1'
$receiptPath = Join-Path $root 'quality\import-snapshot-receipt.json'

function Get-GitBlobSha256([string]$ProjectRoot, [string]$Revision) {
    $safeDirectory = $ProjectRoot.Replace('\', '/')
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.Arguments = ('-c "safe.directory={0}" -C "{1}" show "{2}"' -f $safeDirectory, $ProjectRoot, $Revision)
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $bytes = [System.IO.MemoryStream]::new()
    $copyTask = $process.StandardOutput.BaseStream.CopyToAsync($bytes)
    [void]$process.StandardError.ReadToEnd()
    [void]$copyTask.GetAwaiter().GetResult()
    [void]$process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "git show failed: $Revision" }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.BitConverter]::ToString($sha256.ComputeHash($bytes.ToArray())) -replace '-', ''
    }
    finally {
        [void]$sha256.Dispose()
        [void]$bytes.Dispose()
        [void]$process.Dispose()
    }
}

Describe 'Version 2 import snapshot' {
    It 'writes a complete, deterministic receipt for the frozen import' {
        & $scriptPath -ProjectRoot $root -ReceiptPath $receiptPath
        $LASTEXITCODE | Should Be 0

        $receipt = Get-Content -Raw -LiteralPath $receiptPath | ConvertFrom-Json
        ([System.IO.File]::ReadAllText($receiptPath) -match "`r`n") | Should Be $false
        $receipt.sourceCommit | Should Be 'b2baa9442858a8f20218d50d0f9d1d235ec17e55'
        $receipt.vendorCommit | Should Be 'c0bc8591e8815c63cb01dd3f051a8b0df02501c9'
        $receipt.importCommit | Should Be 'a055eba7fcdb4ea4ad8e7a5ea9a585eaf89416b1'
        $receipt.canonicalZipSha256 | Should Be 'C80A6FBA0978596A464E1E9FF3C17D17D6A928FA56445A97C8E045A0EB9FF771'
        ([string]$receipt.sourceDirtyDiffSha256) | Should Match '^[A-F0-9]{64}$'
        ([string]$receipt.vendorDirtyDiffSha256) | Should Match '^[A-F0-9]{64}$'
        $receipt.copiedFiles.Count | Should BeGreaterThan 3000
        $receipt.copiedFileManifestSha256 | Should Match '^[A-F0-9]{64}$'
        $knownPath = 'tools/security_release_gate.ps1'
        $knownReceiptEntry = @($receipt.copiedFiles | Where-Object { $_.path -eq $knownPath })
        $knownReceiptEntry.Count | Should Be 1
        $knownReceiptEntry[0].sha256 | Should Be (Get-GitBlobSha256 $root "$($receipt.importCommit):$knownPath")
        $receipt.copiedUntrackedFiles | Should Not BeNullOrEmpty
        $receipt.corpusFiles.Count | Should Be 20
        $receipt.officialIcons.Count | Should BeGreaterThan 0
        ($receipt.exclusions -join "`n") | Should Match '(?m)^local\.properties$'
        ($receipt.exclusions -join "`n") | Should Match '(?m)^nested Git data$'
    }

    It 'allows only the known llama.cpp dummy key fixture' {
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
        $dummy = 'sk-this-is-' + 'the-secret-key'
        Set-Content -LiteralPath "$testRoot\vendor\llama.cpp\tools\server\tests\unit\test_security.py" -Value $dummy
        & git -C $testRoot add .
        & git -C $testRoot commit --quiet -m 'fixture'

        { & "$testRoot\tools\security_release_gate.ps1" -ProjectRoot $testRoot } | Should Not Throw

        $signingPassword = 'MANUSCRIPT_UPLOAD_' + 'STORE_PASSWORD=not-allowed'
        Set-Content -LiteralPath "$testRoot\signing.env" -Value $signingPassword
        & git -C $testRoot add signing.env
        & git -C $testRoot commit --quiet -m 'fixture with signing password'
        $portablePattern = '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|sk-[A-Za-z0-9_-]{20,}|MANUSCRIPT_UPLOAD_STORE_PASSWORD[[:space:]]*=)'
        $secretMatches = & git -C $testRoot grep -l -I -E $portablePattern -- .
        $LASTEXITCODE | Should Be 0
        (@($secretMatches) -contains 'signing.env') | Should Be $true
        { & "$testRoot\tools\security_release_gate.ps1" -ProjectRoot $testRoot } | Should Throw
        Remove-Item -LiteralPath "$testRoot\signing.env" -Force
        & git -C $testRoot add -u signing.env
        & git -C $testRoot commit --quiet -m 'remove signing password fixture'

        $secret = 'BEGIN ' + 'RSA PRIVATE ' + 'KEY'
        Set-Content -LiteralPath "$testRoot\vendor\llama.cpp\tools\server\tests\unit\test_security.py" -Value $secret
        & git -C $testRoot add vendor/llama.cpp/tools/server/tests/unit/test_security.py
        & git -C $testRoot commit --quiet -m 'fixture with real secret'
        { & "$testRoot\tools\security_release_gate.ps1" -ProjectRoot $testRoot } | Should Throw
    }
}
