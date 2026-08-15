<#
Drives the twenty-essay corpus through the REAL Manuscript UI on a connected
phone, pulls what the app actually shipped, and scores it.

Headless runs are not accepted for this work: they do not show the defects that
matter. Every defect the corpus has ever found was found by pressing real
buttons and then reading the corrected text.

    .\tools\run_essay_corpus.ps1 -Serial RFCRA0A6CFH -ExpectedModel SM-G988U1 -Essay 07
    .\tools\run_essay_corpus.ps1 -Serial RFCRA0A6CFH -ExpectedModel SM-G988U1

The device needs the local AI weights installed through the app's own download
flow before any of this works. That is a one-time, user-consented step and this
script will not attempt it.
#>
param(
    # No device default. The installer's S23 defaults exist because that is the
    # device of record; naming the phone here has to be deliberate, because a
    # wrong default silently produces evidence for the wrong hardware.
    [Parameter(Mandatory)][string]$Serial,

    [Parameter(Mandatory)][string]$ExpectedModel,

    # Two digits, e.g. 07. Omitting it runs all twenty, one app launch each.
    [string]$Essay,

    [string]$RoundId,

    # Folder under evidence/device for this phone. Defaults to the serial, which
    # is always honest; pass a shorthand like s20 to match the ledger's naming.
    [string]$EvidenceName,

    # Run a corpus that is not the hash-pinned animal-pet twenty. Its essays get
    # pushed and pulled the same way, but there are no per-error keys for them,
    # so score_all.py is skipped and a reader judges the shipped text.
    [string]$CorpusDirectory,

    # -Backend legacy runs the same essays on the llama.cpp runtime.
    [string]$Backend,

    [switch]$SkipInstall,

    [switch]$AllowDisabledAccessibilityBeforeInstall
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Accented place names are part of this corpus. Without this, capturing the
# app's output through adb mangles "Almeria" and the scorer then reports a
# correct repair as a miss.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$package = 'com.remote.Manuscript.v2.debug'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'com.echolink.mobile.EssayUiBatchDeviceTest'
$targetApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$corpusTools = Join-Path $PSScriptRoot 'corpus'
$work = Join-Path $corpusTools 'work'
$outDirectory = Join-Path $work 'out'
$stagingDirectory = '/data/local/tmp/manuscript-corpus'

if (-not (Test-Path -LiteralPath $adb)) { throw "ADB was not found at $adb" }
foreach ($apk in @($targetApk, $testApk)) {
    # The APK under test must be the APK just built. In Version 1 this ran
    # against whatever was already installed, so essay 17 was "verified" twice
    # against a phone that had never seen the fix.
    if (-not (Test-Path -LiteralPath $apk)) {
        throw "Build first. Missing APK: $apk"
    }
}

if ([string]::IsNullOrWhiteSpace($RoundId)) {
    $RoundId = 'corpus-' + [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss')
}
if ($RoundId -cnotmatch '^[a-z0-9-]{3,64}$') {
    throw "roundId must be lowercase letters, digits and hyphens: $RoundId"
}
$evidenceFolder = if ([string]::IsNullOrWhiteSpace($EvidenceName)) {
    $Serial.ToLowerInvariant()
} else {
    $EvidenceName
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments, [switch]$AllowFailure)
    $output = @(& $adb @Arguments)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "ADB failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    [pscustomobject]@{ ExitCode = $exitCode; Text = ($output -join "`n").Trim() }
}

function Invoke-RunAs {
    param([Parameter(Mandatory)][string]$Command, [switch]$AllowFailure)
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', "run-as $package $Command") `
        -AllowFailure:$AllowFailure
}

if ([string]::IsNullOrWhiteSpace($CorpusDirectory)) {
    # The pinned corpus is hash-checked. Reuse the campaign module rather than
    # writing a second hash check that can drift from the first.
    Import-Module (Join-Path $PSScriptRoot 'EssayQualityCampaign.psm1') -Force
    $corpus = Get-ManuscriptEssayCorpus -ProjectRoot $projectRoot
    $essays = @($corpus.Essays | ForEach-Object {
        [pscustomobject]@{ Id = $_.Id; Path = $_.FixturePath; Sections = $_.Sections.Count }
    })
    $scoreAgainstKeys = $true
    Write-Host "Corpus verified: $($essays.Count) essays, $($corpus.SectionCount) sections."
} else {
    # A second corpus that is not pinned in the tree. There are no per-error
    # keys for it, so score_all.py is skipped and the judging is done by
    # reading the shipped text. The damage scan still runs, because a word the
    # app invented is a defect in any document.
    $corpusRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($CorpusDirectory)
    if (-not (Test-Path -LiteralPath $corpusRoot -PathType Container)) {
        throw "No corpus directory at $corpusRoot"
    }
    $essays = @(Get-ChildItem -LiteralPath $corpusRoot -Filter '*.txt' | Sort-Object Name | ForEach-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName)
        $blocks = @($text -split '(?:\r?\n){2,}' | Where-Object { $_.Trim() })
        [pscustomobject]@{
            Id = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
            Path = $_.FullName
            Sections = $blocks.Count
        }
    })
    if ($essays.Count -eq 0) { throw "No .txt essays in $corpusRoot" }
    $scoreAgainstKeys = $false
    $env:MANUSCRIPT_CORPUS_SRC = $corpusRoot
    $sectionTotal = ($essays | Measure-Object -Property Sections -Sum).Sum
    Write-Host "Corpus (unpinned): $($essays.Count) essays, $sectionTotal sections, from $corpusRoot"
}

$selected = if ([string]::IsNullOrWhiteSpace($Essay)) {
    @($essays)
} else {
    $matching = @($essays | Where-Object { $_.Id.StartsWith($Essay) })
    if ($matching.Count -ne 1) { throw "Essay filter '$Essay' matched $($matching.Count) essays." }
    $matching
}

if (-not $SkipInstall) {
    $installArguments = @{
        Variant = 'debug'
        Serial = $Serial
        ExpectedModel = $ExpectedModel
        ApkPath = $targetApk
        ReceiptPath = Join-Path $projectRoot "evidence\device\$evidenceFolder\install-receipt.json"
    }
    if ($AllowDisabledAccessibilityBeforeInstall) {
        $installArguments['AllowDisabledAccessibilityBeforeInstall'] = $true
    }
    & (Join-Path $PSScriptRoot 'install_s23_manuscript.ps1') @installArguments | Out-Null
    Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', '-t', $testApk) | Out-Null
}

$apkSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetApk).Hash.ToLowerInvariant()

# EssayUiBatchDeviceTest reads app-internal storage, not external, because
# scoped storage can hide shell-created external fixtures from the target app
# even when adb can list them. The app's uid cannot traverse /data/local/tmp, so
# the copy is piped on the device: the shell user reads, run-as writes.
Invoke-Adb -Arguments @('-s', $Serial, 'shell', "rm -rf $stagingDirectory") -AllowFailure | Out-Null
Invoke-Adb -Arguments @('-s', $Serial, 'shell', "mkdir -p $stagingDirectory") | Out-Null
# Clear the inbox first. Two corpora share the same two-digit prefixes, so a
# leftover 01_Dogs_in_Bakersfield beside a new 01_urban_beekeeping makes the
# test's -e essay 01 filter match both and run whichever sorts first.
Invoke-RunAs -Command 'rm -rf files/essays' -AllowFailure | Out-Null
Invoke-RunAs -Command 'mkdir -p files/essays' | Out-Null
foreach ($record in $essays) {
    $name = [System.IO.Path]::GetFileName($record.Path)
    Invoke-Adb -Arguments @('-s', $Serial, 'push', $record.Path, "$stagingDirectory/$name") | Out-Null
    Invoke-Adb -Arguments @(
        '-s', $Serial, 'shell',
        "cat $stagingDirectory/$name | run-as $package sh -c 'cat > files/essays/$name'"
    ) | Out-Null
}
$pushed = @((Invoke-RunAs -Command 'ls files/essays').Text -split "`n" | Where-Object { $_.Trim() })
if ($pushed.Count -ne $essays.Count) {
    throw "Pushed $($pushed.Count) essays but the corpus has $($essays.Count)."
}

New-Item -ItemType Directory -Force -Path $outDirectory | Out-Null
$env:MANUSCRIPT_CORPUS_OUT = $outDirectory
$failures = @()

foreach ($record in $selected) {
    $number = $record.Id.Substring(0, 2)
    $outputName = "$($record.Id).out.txt"
    $localOutput = Join-Path $outDirectory $outputName
    Write-Host ''
    Write-Host "== essay $number =="

    # Clear both copies before the run. A harness that can read a previous
    # result is a harness that can report success for work that did not happen:
    # in Version 1 three essays "passed" in fifteen seconds each while the app
    # was dying on launch, because the scanner read the earlier run's file.
    Invoke-RunAs -Command "rm -f files/essays-ui-corrected/$outputName" -AllowFailure | Out-Null
    if (Test-Path -LiteralPath $localOutput) { Remove-Item -LiteralPath $localOutput -Force }

    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') -AllowFailure | Out-Null
    Invoke-Adb -Arguments @('-s', $Serial, 'logcat', '-c') -AllowFailure | Out-Null

    # One essay per invocation. Omitting the filter makes the test iterate the
    # whole corpus inside one app process, which is what produced unexplained
    # TIMEOUTs on essays 01 and 02 in Version 1.
    $instrumentArguments = @(
        '-s', $Serial, 'shell', 'am', 'instrument', '-w',
        '-e', 'class', $testClass,
        '-e', 'essay', $number,
        '-e', 'roundId', $RoundId,
        '-e', 'apkSha256', $apkSha256
    )
    if (-not [string]::IsNullOrWhiteSpace($Backend)) {
        $instrumentArguments += @('-e', 'backend', $Backend)
    }
    $instrumentArguments += $runner
    $runLog = Join-Path $work "run-$number.txt"
    $result = Invoke-Adb -Arguments $instrumentArguments -AllowFailure
    Set-Content -LiteralPath $runLog -Value $result.Text -Encoding utf8

    if ($result.Text -match 'Process crashed') {
        $failures += "essay ${number}: the app crashed, no correction happened"
        Write-Host '  FAILED: the app crashed - no correction happened'
        continue
    }
    if ($result.Text -notmatch '(?m)^OK \(') {
        $failures += "essay ${number}: instrumentation did not report OK"
        Write-Host '  FAILED: the instrumentation did not report OK'
        # Show the thrown exception, not the junit banner around it. Matching a
        # bare "Error" first put "Error in <test name>" at the top and pushed
        # the line that says what actually went wrong off the end.
        $reasons = @($result.Text -split "`n" |
            Where-Object { $_ -match 'Exception|AssertionError|shortMsg' })
        if ($reasons.Count -eq 0) {
            $reasons = @($result.Text -split "`n" | Where-Object { $_ -match 'FAILURES|Error' })
        }
        ($reasons | Select-Object -Unique -First 4) | ForEach-Object { Write-Host "    $($_.Trim())" }
        Write-Host "    full log: $runLog"
        continue
    }

    $pulled = Invoke-Adb -Arguments @(
        '-s', $Serial, 'exec-out', 'run-as', $package, 'cat',
        "files/essays-ui-corrected/$outputName"
    ) -AllowFailure
    if ($pulled.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($pulled.Text)) {
        $failures += "essay ${number}: the app wrote no output"
        Write-Host '  FAILED: the app wrote no output file'
        continue
    }
    [System.IO.File]::WriteAllText(
        $localOutput,
        $pulled.Text,
        [System.Text.UTF8Encoding]::new($false)
    )

    & python (Join-Path $corpusTools 'scan.py') $outputName
    if ($scoreAgainstKeys) {
        & python (Join-Path $corpusTools 'score_all.py') $number
        if ($LASTEXITCODE -ne 0) { $failures += "essay ${number}: below the 97% floor" }
    } else {
        Write-Host "  shipped text pulled to $localOutput - no per-error keys, judge by reading it"
    }

    # A kept-original section is a section the app gave up on. The run summary
    # still reports untouched=0 for it, so it has to be called out here or it
    # passes for success.
    $paths = @((Invoke-Adb -Arguments @(
        '-s', $Serial, 'logcat', '-d', '-s', 'ManuscriptLocalAI:I'
    ) -AllowFailure).Text -split "`n" | Where-Object { $_ -match 'paragraph=' })
    $degraded = @($paths | Where-Object { $_ -notmatch 'path=model' })
    $degraded | Select-Object -First 4 | ForEach-Object {
        Write-Host ('  PATH ' + ($_ -replace '^.*ManuscriptLocalAI: ', ''))
    }
    $kept = @($paths | Where-Object { $_ -match 'path=kept-original' }).Count
    if ($kept -gt 0) {
        $failures += "essay ${number}: $kept section(s) kept the original text"
        Write-Host "  DEFECT: $kept section(s) kept the original text - not corrected"
    }
}

Write-Host ''
if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL $_" }
    throw "$($failures.Count) essay failure(s)."
}
Write-Host "All $($selected.Count) essay(s) passed at or above the floor with no kept-original sections."
