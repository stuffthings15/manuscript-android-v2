param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ReceiptPath,
    [string]$SourceRoot
)

$ErrorActionPreference = 'Stop'

$expectedSourceCommit = 'b2baa9442858a8f20218d50d0f9d1d235ec17e55'
$expectedVendorCommit = 'c0bc8591e8815c63cb01dd3f051a8b0df02501c9'
$importCommit = 'a055eba7fcdb4ea4ad8e7a5ea9a585eaf89416b1'
$expectedZipSha256 = 'C80A6FBA0978596A464E1E9FF3C17D17D6A928FA56445A97C8E045A0EB9FF771'

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ([string]::IsNullOrWhiteSpace($ReceiptPath)) {
    $ReceiptPath = Join-Path $ProjectRoot 'quality\import-snapshot-receipt.json'
}
if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
    $SourceRoot = Join-Path (Split-Path -Parent $ProjectRoot) 'Android'
}
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
$ReceiptPath = [System.IO.Path]::GetFullPath($ReceiptPath)

function Get-SafeDirectory([string]$Path) {
    return $Path.Replace('\', '/')
}

function Invoke-GitLines([string]$Directory, [string[]]$Arguments) {
    $safeDirectory = Get-SafeDirectory $Directory
    $output = & git -c "safe.directory=$safeDirectory" -C $Directory @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed in $Directory"
    }
    return @($output | ForEach-Object { [string]$_ })
}

function Get-GitOutputSha256([string]$Directory, [string[]]$Arguments) {
    $safeDirectory = Get-SafeDirectory $Directory
    $command = @('-c', "safe.directory=$safeDirectory", '-C', $Directory) + $Arguments
    $quotedArguments = $command | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.Arguments = $quotedArguments -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $bytes = [System.IO.MemoryStream]::new()
    $copyTask = $process.StandardOutput.BaseStream.CopyToAsync($bytes)
    $errorText = $process.StandardError.ReadToEnd()
    [void]$copyTask.GetAwaiter().GetResult()
    [void]$process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed in ${Directory}: $errorText"
    }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    $hash = $null
    try {
        $hash = [System.BitConverter]::ToString($sha256.ComputeHash($bytes.ToArray())) -replace '-', ''
    }
    finally {
        [void]$sha256.Dispose()
        [void]$bytes.Dispose()
        [void]$process.Dispose()
    }
    return $hash
}

function Get-RelativePath([string]$Root, [string]$Path) {
    $rootUri = [System.Uri]::new((($Root.TrimEnd('\') + '\')))
    $pathUri = [System.Uri]::new($Path)
    return [System.Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString()).Replace('\', '/')
}

function Get-ImportFileEntry([string]$Root, [string]$RelativePath) {
    return [pscustomobject][ordered]@{
        path = $RelativePath
        sha256 = $importBlobHashes[$RelativePath]
    }
}

function Read-GitBatchLine([System.IO.Stream]$Stream) {
    $lineBytes = [System.Collections.Generic.List[byte]]::new()
    while ($true) {
        $value = $Stream.ReadByte()
        if ($value -lt 0) { throw 'Unexpected end of git cat-file output.' }
        if ($value -eq 10) { break }
        if ($value -ne 13) { $lineBytes.Add([byte]$value) }
    }
    return [System.Text.Encoding]::ASCII.GetString($lineBytes.ToArray())
}

function Read-GitBatchBytes([System.IO.Stream]$Stream, [int]$Length) {
    $bytes = [byte[]]::new($Length)
    $offset = 0
    while ($offset -lt $Length) {
        $read = $Stream.Read($bytes, $offset, $Length - $offset)
        if ($read -le 0) { throw 'Unexpected end of git cat-file blob.' }
        $offset += $read
    }
    if ($Stream.ReadByte() -ne 10) { throw 'Git cat-file blob delimiter is missing.' }
    return ,$bytes
}

function Get-ImportBlobHashes([string]$Root, [object[]]$Entries) {
    $safeDirectory = Get-SafeDirectory $Root
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.Arguments = ('-c "safe.directory={0}" -C "{1}" cat-file --batch' -f $safeDirectory, $Root)
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $inputBytes = [System.Text.Encoding]::ASCII.GetBytes((($Entries | ForEach-Object { $_.blob }) -join "`n") + "`n")
    $inputStream = [System.IO.MemoryStream]::new($inputBytes)
    $inputTask = $inputStream.CopyToAsync($process.StandardInput.BaseStream)
    $hashes = @{}
    $stream = $process.StandardOutput.BaseStream
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        foreach ($entry in $Entries) {
            $header = Read-GitBatchLine $stream
            if ($header -notmatch '^[0-9a-f]+ blob (?<size>\d+)$') {
                throw "Unexpected git cat-file header: $header"
            }
            $bytes = Read-GitBatchBytes $stream ([int]$Matches.size)
            $hashes[$entry.path] = [System.BitConverter]::ToString($sha256.ComputeHash($bytes)) -replace '-', ''
        }
        [void]$inputTask.GetAwaiter().GetResult()
        $process.StandardInput.Close()
        [void]$process.WaitForExit()
        $errorText = $process.StandardError.ReadToEnd()
        if ($process.ExitCode -ne 0) { throw "git cat-file failed: $errorText" }
        return $hashes
    }
    finally {
        [void]$sha256.Dispose()
        [void]$inputStream.Dispose()
        [void]$process.Dispose()
    }
}

function Get-SharedFileSha256([string]$Path) {
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $stream = [System.IO.File]::Open(
                $Path,
                [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read,
                [System.IO.FileShare]::ReadWrite
            )
            $sha256 = [System.Security.Cryptography.SHA256]::Create()
            try {
                return ([System.BitConverter]::ToString($sha256.ComputeHash($stream)) -replace '-', '')
            }
            finally {
                $sha256.Dispose()
                $stream.Dispose()
            }
        }
        catch {
            if ($attempt -eq 5) { throw }
            Start-Sleep -Milliseconds 200
        }
    }
}

$sourceCommit = Invoke-GitLines $SourceRoot @('rev-parse', 'HEAD') | Select-Object -First 1
$vendorRoot = Join-Path $SourceRoot 'vendor\llama.cpp'
$vendorCommit = Invoke-GitLines $vendorRoot @('rev-parse', 'HEAD') | Select-Object -First 1
if ($sourceCommit -ne $expectedSourceCommit) {
    throw "Unexpected Version 1 source commit: $sourceCommit"
}
if ($vendorCommit -ne $expectedVendorCommit) {
    throw "Unexpected llama.cpp source commit: $vendorCommit"
}
[void](Invoke-GitLines $ProjectRoot @('cat-file', '-e', "$importCommit^{commit}"))

$importEntries = @(
    Invoke-GitLines $ProjectRoot @('ls-tree', '-r', $importCommit) |
        ForEach-Object {
            if ($_ -notmatch '^[0-9]+ blob (?<blob>[0-9a-f]+)\t(?<path>.+)$') {
                throw "Unexpected git tree entry: $_"
            }
            [pscustomobject]@{ blob = $Matches.blob; path = $Matches.path }
        }
)
$importPaths = @($importEntries | ForEach-Object { $_.path })
$importBlobHashes = Get-ImportBlobHashes $ProjectRoot $importEntries
$forbiddenPatterns = @(
    '(^|/)local\.properties$',
    '(^|/)(build|\.cxx|releases)/',
    '\.(apk|aab)$',
    '(^|/)(evidence|test-evidence)/.*\.xml$',
    '(^|/)\.git(/|$)',
    '\.(jks|keystore)$',
    '(^|/)(id_rsa|.*private.*key.*)$'
)
$forbiddenFiles = @($importPaths | Where-Object {
    $path = $_
    $forbiddenPatterns | Where-Object { $path -match $_ } | Select-Object -First 1
})
if ($forbiddenFiles.Count -gt 0) {
    throw "Forbidden import files found: $($forbiddenFiles -join ', ')"
}

$copiedFiles = @($importPaths | Sort-Object | ForEach-Object { Get-ImportFileEntry $ProjectRoot $_ })
$manifestJson = $copiedFiles | ConvertTo-Json -Depth 3 -Compress
$manifestBytes = [System.Text.Encoding]::UTF8.GetBytes($manifestJson)
$manifestHash = [System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::Create().ComputeHash($manifestBytes)
) -replace '-', ''

$sourcePrefix = Invoke-GitLines $SourceRoot @('rev-parse', '--show-prefix') | Select-Object -First 1
$sourceTracked = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($path in Invoke-GitLines $SourceRoot @('ls-tree', '-r', '--name-only', $sourceCommit)) {
    if ($path.StartsWith($sourcePrefix, [System.StringComparison]::Ordinal)) {
        [void]$sourceTracked.Add($path.Substring($sourcePrefix.Length))
    }
}
$vendorTracked = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($path in Invoke-GitLines $vendorRoot @('ls-tree', '-r', '--name-only', $vendorCommit)) {
    [void]$vendorTracked.Add($path)
}
$copiedUntrackedFiles = @($copiedFiles | Where-Object {
    if ($_.path.StartsWith('vendor/llama.cpp/', [System.StringComparison]::Ordinal)) {
        return -not $vendorTracked.Contains($_.path.Substring('vendor/llama.cpp/'.Length))
    }
    return -not $sourceTracked.Contains($_.path)
})

$sourceCorpusRoot = Join-Path (Split-Path -Parent $SourceRoot) '20_Animal_Pet_Essays_800_Percent_More_Errors'
$copiedCorpusRoot = Join-Path $ProjectRoot 'quality\animal-pet-essays\source'
$corpusFiles = @(
    Get-ChildItem -LiteralPath $copiedCorpusRoot -File -Filter '*.docx' |
        Sort-Object Name |
        ForEach-Object {
            $sourcePath = Join-Path $sourceCorpusRoot $_.Name
            if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
                throw "Source corpus file is missing: $($_.Name)"
            }
            $sourceHash = Get-SharedFileSha256 $sourcePath
            $copiedHash = Get-SharedFileSha256 $_.FullName
            if ($sourceHash -ne $copiedHash) {
                throw "Corpus file changed during import: $($_.Name)"
            }
            Get-ImportFileEntry $ProjectRoot "quality/animal-pet-essays/source/$($_.Name)"
        }
)
if ($corpusFiles.Count -ne 20) {
    throw "Expected 20 copied DOCX files, found $($corpusFiles.Count)"
}

$sourceZip = Join-Path (Split-Path -Parent $SourceRoot) '20_Animal_Pet_Essays_800_Percent_More_Errors.zip'
$copiedZip = Join-Path $ProjectRoot 'quality\animal-pet-essays\20_Animal_Pet_Essays_800_Percent_More_Errors.zip'
$sourceZipHash = Get-SharedFileSha256 $sourceZip
$copiedZipHash = Get-SharedFileSha256 $copiedZip
if ($sourceZipHash -ne $expectedZipSha256 -or $copiedZipHash -ne $expectedZipSha256) {
    throw 'Canonical ZIP changed during import.'
}

$officialIcons = @(
    @('brand/canonical/manuscript.svg', 'brand/canonical/manuscript.png') |
        ForEach-Object { Get-ImportFileEntry $ProjectRoot $_ }
)

$receiptRelativePath = Get-RelativePath $ProjectRoot $ReceiptPath
$receipt = [pscustomobject][ordered]@{
    formatVersion = 1
    sourceCommit = $sourceCommit
    vendorCommit = $vendorCommit
    sourceDirtyDiffSha256 = Get-GitOutputSha256 $SourceRoot @('diff', '--binary', '--no-ext-diff', 'HEAD', '--', '.')
    vendorDirtyDiffSha256 = Get-GitOutputSha256 $vendorRoot @('diff', '--binary', '--no-ext-diff', 'HEAD')
    copiedUntrackedFiles = $copiedUntrackedFiles
    canonicalZipSha256 = (Get-ImportFileEntry $ProjectRoot 'quality/animal-pet-essays/20_Animal_Pet_Essays_800_Percent_More_Errors.zip').sha256
    canonicalZip = Get-ImportFileEntry $ProjectRoot 'quality/animal-pet-essays/20_Animal_Pet_Essays_800_Percent_More_Errors.zip'
    corpusFiles = $corpusFiles
    officialIcons = $officialIcons
    copiedFileManifestSha256 = $manifestHash
    copiedFiles = $copiedFiles
    importCommit = $importCommit
    branch = Invoke-GitLines $ProjectRoot @('branch', '--show-current') | Select-Object -First 1
    exclusions = @(
        'Git internals', 'caches', 'build directories', '.cxx directories',
        'releases', 'APK/AAB files', 'raw XML captures', 'generated evidence',
        'credentials', 'local.properties', 'nested Git data', $receiptRelativePath
    )
}

$receiptDirectory = Split-Path -Parent $ReceiptPath
New-Item -ItemType Directory -Force -Path $receiptDirectory | Out-Null
$receiptJson = ($receipt | ConvertTo-Json -Depth 6).Replace("`r`n", "`n")
[System.IO.File]::WriteAllText(
    $ReceiptPath,
    $receiptJson + "`n",
    [System.Text.UTF8Encoding]::new($false)
)
Write-Output "Wrote import snapshot receipt: $ReceiptPath"
