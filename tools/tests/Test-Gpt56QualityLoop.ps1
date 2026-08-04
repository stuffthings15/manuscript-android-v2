$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-Throws(
    [scriptblock]$Action,
    [string]$MessageFragment,
    [string]$FailureMessage
) {
    try {
        & $Action
    } catch {
        Assert-True ($_.Exception.Message -like "*$MessageFragment*") (
            "$FailureMessage Expected '$MessageFragment', got '$($_.Exception.Message)'."
        )
        return
    }

    throw "$FailureMessage Expected an exception containing '$MessageFragment'."
}

function Get-TestUtf8Sha256([string]$Text) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $utf8.GetBytes($Text)
        return ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Write-TestUtf8Json([string]$Path, [pscustomobject]$Value) {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $json = $Value | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, $utf8)
}

function New-TestReport(
    [string]$Tone,
    [string]$Output,
    [string]$RoundId = "test-round"
) {
    $report = [ordered]@{
        schema_version = 2
        round_id = $RoundId
        tone = $Tone
        document_id = "northstar-five-page-all-tones-sealed-v2"
        source_sha256 = "4358a8521631c2d962de3ffb7a55f7ea04afdc1b95c2eedbffb0ea641c656dd9"
        proofread_reference_sha256 = "7a82fec7bf4637ad80494152c8ec30cc09047d26238f03b768296941b8557fda"
        source_word_count = 1000
        application_id = "com.remote.Manuscript.v2.debug"
        version_name = "1.0-test"
        version_code = 1
        device_manufacturer = "Samsung"
        device_model = "SM-S911U"
        android_api = 35
        supported_abis = @("arm64-v8a")
        ai_file = "gemma-3-4b-it-q4.gguf"
        ai_tier = "BALANCED"
        backend = "LLAMA_CPP"
        quality_mode = "THOROUGH"
        tone_count = 1
        completed_tone_count = 1
        failure_count = 0
        diagnostics = [ordered]@{
            device_model = "must-not-leak"
            prompt = "must-not-leak"
        }
        results = @(
            [ordered]@{
                tone = $Tone
                success = $true
                duration_ms = 120000
                output = $Output
                output_sha256 = Get-TestUtf8Sha256 $Output
                paragraph_evidence = @("must-not-leak")
                rejected_candidate = "must-not-leak"
            }
        )
    }

    return ($report | ConvertTo-Json -Depth 20 | ConvertFrom-Json)
}

function New-TestGrade([pscustomobject]$GraderInput, [string]$GraderInputSha256) {
    $objectiveResults = @(
        for ($index = 0; $index -lt @($GraderInput.objective_error_inventory).Count; $index++) {
            [pscustomobject][ordered]@{
                index = $index
                pass = $true
                evidence = @("Objective item $index is corrected.")
                reason = "The output contains the required correction."
            }
        }
    )
    $protectedFactResults = @(
        for ($index = 0; $index -lt @($GraderInput.protected_facts).Count; $index++) {
            [pscustomobject][ordered]@{
                index = $index
                pass = $true
                evidence = @("Protected fact $index is preserved.")
                reason = "The output preserves the supplied fact."
            }
        }
    )
    $toneResults = @(
        for ($index = 0; $index -lt @($GraderInput.rubric.acceptance_criteria).Count; $index++) {
            [pscustomobject][ordered]@{
                index = $index
                pass = $true
                evidence = @("Tone criterion $index is measured and satisfied.")
                reason = "The output meets the assigned tone criterion."
            }
        }
    )

    return [pscustomobject][ordered]@{
        schema_version = 2
        document_id = [string]$GraderInput.document_id
        tone = [string]$GraderInput.tone
        output_sha256 = [string]$GraderInput.hashes.output
        grader_input_sha256 = $GraderInputSha256
        pass = $true
        score_percent = 100
        submission_complete = $true
        objective_results = $objectiveResults
        introduced_errors = @()
        protected_fact_results = $protectedFactResults
        meaning_integrity = [pscustomobject][ordered]@{
            pass = $true
            findings = @()
        }
        formatting = [pscustomobject][ordered]@{
            pass = $true
            heading_count = 1
            page_heading_count = 5
            list_item_count = 3
            findings = @()
        }
        tone_criteria = $toneResults
        em_dash_count = 0
        failure_summary = @()
    }
}

function Copy-TestObject([pscustomobject]$Value) {
    return ($Value | ConvertTo-Json -Depth 30 | ConvertFrom-Json)
}

function New-FakeAdbExecutable([string]$Path) {
    $scriptPath = [System.IO.Path]::ChangeExtension($Path, ".ps1")
    $script = @'
$AdbArguments = @($args)

$logPath = $env:GPT56_FAKE_ADB_LOG
if (-not [string]::IsNullOrEmpty($logPath)) {
    [System.IO.File]::AppendAllText(
        $logPath,
        (($AdbArguments -join "`t") + [Environment]::NewLine),
        (New-Object System.Text.UTF8Encoding($false))
    )
}

if (
    $AdbArguments.Count -eq 2 -and
    $AdbArguments[0] -ceq "devices" -and
    $AdbArguments[1] -ceq "-l"
) {
    $barrierPath = $env:GPT56_FAKE_ADB_BARRIER
    if (-not [string]::IsNullOrEmpty($barrierPath)) {
        [System.IO.Directory]::CreateDirectory($barrierPath) | Out-Null
        [System.IO.File]::WriteAllText(
            (Join-Path $barrierPath "$PID.ready"),
            "ready"
        )
        $wait = [System.Diagnostics.Stopwatch]::StartNew()
        while (
            @(Get-ChildItem -LiteralPath $barrierPath -Filter "*.ready").Count -lt 2 -and
            $wait.ElapsedMilliseconds -lt 3000
        ) {
            Start-Sleep -Milliseconds 25
        }
    }
    [Console]::Out.WriteLine("List of devices attached")
    [Console]::Out.WriteLine(
        "$($env:GPT56_FAKE_ADB_SERIAL) device product:dm1q model:SM-S911U device:dm1q transport_id:1"
    )
    exit 0
}

if ($AdbArguments -ccontains "instrument") {
    [Console]::Out.Write($env:GPT56_FAKE_ADB_INSTRUMENTATION_STDOUT)
    [Console]::Error.Write($env:GPT56_FAKE_ADB_INSTRUMENTATION_STDERR)
    $exitCode = 0
    if (-not [int]::TryParse($env:GPT56_FAKE_ADB_INSTRUMENTATION_EXIT, [ref]$exitCode)) {
        $exitCode = 0
    }
    exit $exitCode
}

if ($AdbArguments -ccontains "exec-out") {
    if ($AdbArguments -ccontains "sh") {
        if ($env:GPT56_FAKE_ADB_REPORT_EXISTS -ceq "1") {
            [Console]::Out.Write("exists")
        } else {
            [Console]::Out.Write("absent")
        }
        exit 0
    }
    $reportPath = $env:GPT56_FAKE_ADB_REPORT
    if (
        -not [string]::IsNullOrEmpty($reportPath) -and
        (Test-Path -LiteralPath $reportPath -PathType Leaf)
    ) {
        $reportBytes = [System.IO.File]::ReadAllBytes($reportPath)
        $stdout = [Console]::OpenStandardOutput()
        $stdout.Write($reportBytes, 0, $reportBytes.Length)
    }
}

exit 0
'@
    $wrapper = @'
@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dpn0.ps1" %*
exit /b %ERRORLEVEL%
'@
    [System.IO.File]::WriteAllText(
        $scriptPath,
        $script,
        (New-Object System.Text.UTF8Encoding($false))
    )
    [System.IO.File]::WriteAllText(
        $Path,
        $wrapper,
        [System.Text.Encoding]::ASCII
    )
}
function Invoke-TestController(
    [string]$ControllerPath,
    [string]$Serial,
    [string]$Tone,
    [string]$RoundId,
    [string]$CampaignRoot,
    [string]$AdbPath
) {
    $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Source
    $originalErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $processOutput = & $powershellPath `
            -NoProfile `
            -ExecutionPolicy Bypass `
            -File $ControllerPath `
            -Serial $Serial `
            -Tone $Tone `
            -RoundId $RoundId `
            -CampaignRoot $CampaignRoot `
            -AdbPath $AdbPath `
            -SkipBuild 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $originalErrorActionPreference
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $processOutput
    }
}

function Start-TestControllerProcess(
    [string]$ControllerPath,
    [string]$Serial,
    [string]$Tone,
    [string]$RoundId,
    [string]$CampaignRoot,
    [string]$AdbPath
) {
    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $ControllerPath,
        "-Serial",
        $Serial,
        "-Tone",
        $Tone,
        "-RoundId",
        $RoundId,
        "-CampaignRoot",
        $CampaignRoot,
        "-AdbPath",
        $AdbPath,
        "-SkipBuild"
    ) | ForEach-Object { '"' + $_ + '"' }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = (Get-Command powershell.exe -ErrorAction Stop).Source
    $startInfo.Arguments = $arguments -join " "
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    Assert-True ($process.Start()) "The concurrent controller process must start."
    return [pscustomobject]@{
        Process = $process
        StdOut = $process.StandardOutput.ReadToEndAsync()
        StdErr = $process.StandardError.ReadToEndAsync()
    }
}

function Complete-TestControllerProcess([pscustomobject]$Run) {
    if (-not $Run.Process.WaitForExit(30000)) {
        try { $Run.Process.Kill() } catch { }
        throw "The concurrent controller process timed out."
    }
    [System.Threading.Tasks.Task]::WaitAll(@($Run.StdOut, $Run.StdErr))
    $result = [pscustomobject]@{
        ExitCode = $Run.Process.ExitCode
        Output = $Run.StdOut.Result + $Run.StdErr.Result
    }
    $Run.Process.Dispose()
    return $result
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$modulePath = Join-Path $projectRoot "tools\Gpt56QualityLoop.psm1"
$controllerPath = Join-Path $projectRoot "tools\run_gpt56_quality_round.ps1"
$gradeRecorderPath = Join-Path $projectRoot "tools\record_gpt56_grade.ps1"
Import-Module $modulePath -Force

$snapshotBytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes('{"value":"A"}')
$snapshot = ConvertFrom-Gpt56JsonSnapshot -Bytes $snapshotBytes -Context "Test snapshot"
Assert-True ($snapshot.Sha256 -ceq "1de418f49147a1fd7f9cb95af03aba735181bbb3d014de4e2bc545ea99e60388") "Snapshot hash must come from the exact supplied bytes."
Assert-True ($snapshot.Value.value -ceq "A") "Snapshot JSON must be parsed from the supplied bytes."
$snapshotBytes[10] = [byte][char]"B"
Assert-True ($snapshot.Value.value -ceq "A") "A returned snapshot must not re-read or re-parse later byte changes."
Assert-Throws {
    ConvertFrom-Gpt56JsonSnapshot -Bytes ([byte[]](0xC3, 0x28)) -Context "Invalid UTF-8 snapshot" | Out-Null
} "UTF-8" "Invalid UTF-8 bytes must be rejected."
Assert-Throws {
    $invalidJsonBytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes("not-json")
    ConvertFrom-Gpt56JsonSnapshot -Bytes $invalidJsonBytes -Context "Invalid JSON snapshot" | Out-Null
} "JSON" "Invalid JSON bytes must be rejected."

$validOutput = @'
# Project Northstar

## Page 1: Purpose

The team has three immediate tasks:

- Jonah: Deliver the kits.
- Elena: Verify the seals.
- Mira: Send the consent script.

## Page 2: Safety

Keep participant data private.

## Page 3: Readiness

Verify every required control.

## Page 4: Support

Complete the training.

## Page 5: Closeout

Record the verified result.
'@

$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("gpt56-contract-" + [guid]::NewGuid().ToString("N"))
$ledgerPath = Join-Path $fixtureRoot "evidence\campaign-ledger.jsonl"
$graderInputPath = Join-Path $fixtureRoot "round\grader-input.json"

try {
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null

    $manifestRelativePath = "quality\gpt56\sealed-campaign-v2.json"
    $sourceManifestPath = Join-Path $projectRoot $manifestRelativePath
    $fixtureManifestPath = Join-Path $fixtureRoot $manifestRelativePath
    New-Item -ItemType Directory -Path (Split-Path $fixtureManifestPath -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $sourceManifestPath -Destination $fixtureManifestPath

    $manifest = Get-Content -LiteralPath $sourceManifestPath -Raw | ConvertFrom-Json
    $artifactPaths = @(
        [string]$manifest.corpus_path
        [string]$manifest.source_path
        [string]$manifest.reference_path
        [string]$manifest.rubric_path
    )
    foreach ($relativePath in $artifactPaths) {
        $sourcePath = Join-Path $projectRoot $relativePath
        $fixturePath = Join-Path $fixtureRoot $relativePath
        New-Item -ItemType Directory -Path (Split-Path $fixturePath -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $sourcePath -Destination $fixturePath
    }

    Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot

    $fixedToneOrder = @(
        "PROOFREAD"
        "IMPROVE"
        "REPHRASE"
        "SHORTEN"
        "CONCISE"
        "SIMPLIFIED"
        "HIGH_SCHOOL"
        "CAVEMAN"
        "FORMAL"
        "PROFESSIONAL"
        "ACADEMIC"
        "COLLEGE"
        "TECHNICAL"
        "DETAILED"
        "FRIENDLY"
        "CONVERSATIONAL"
        "CASUAL"
        "COOPERATIVE"
        "EMPATHETIC"
        "DIPLOMATIC"
        "PERSUASIVE"
        "CONFIDENT"
        "ASSERTIVE"
    )
    $alteredToneOrder = @($fixedToneOrder)
    $alteredToneOrder[0] = "IMPROVE"
    $alteredToneOrder[1] = "PROOFREAD"
    $sealedFieldCases = @(
        [pscustomobject]@{ Name = "schema_version"; Altered = 3 }
        [pscustomobject]@{ Name = "document_id"; Altered = "other-document" }
        [pscustomobject]@{ Name = "corpus_path"; Altered = [string]$manifest.source_path }
        [pscustomobject]@{ Name = "source_path"; Altered = [string]$manifest.reference_path }
        [pscustomobject]@{ Name = "reference_path"; Altered = [string]$manifest.source_path }
        [pscustomobject]@{ Name = "rubric_path"; Altered = [string]$manifest.corpus_path }
        [pscustomobject]@{ Name = "required_score_percent"; Altered = 99 }
        [pscustomobject]@{ Name = "allowed_introduced_errors"; Altered = 1 }
        [pscustomobject]@{ Name = "allowed_em_dashes"; Altered = 1 }
        [pscustomobject]@{ Name = "tone_order"; Altered = $alteredToneOrder }
    )
    $fixtureManifestJson = [System.IO.File]::ReadAllText($fixtureManifestPath)

    foreach ($case in $sealedFieldCases) {
        $missingManifest = $fixtureManifestJson | ConvertFrom-Json
        $missingManifest.PSObject.Properties.Remove([string]$case.Name)
        Write-TestUtf8Json -Path $fixtureManifestPath -Value $missingManifest
        Assert-Throws {
            Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot
        } ([string]$case.Name) "A missing sealed $($case.Name) field must be rejected."

        $alteredManifest = $fixtureManifestJson | ConvertFrom-Json
        $alteredManifest.PSObject.Properties[[string]$case.Name].Value = $case.Altered
        Write-TestUtf8Json -Path $fixtureManifestPath -Value $alteredManifest
        Assert-Throws {
            Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot
        } ([string]$case.Name) "An altered sealed $($case.Name) field must be rejected."
    }

    $missingHashesManifest = $fixtureManifestJson | ConvertFrom-Json
    $missingHashesManifest.PSObject.Properties.Remove("sha256")
    Write-TestUtf8Json -Path $fixtureManifestPath -Value $missingHashesManifest
    Assert-Throws {
        Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot
    } "sha256" "A missing sealed sha256 object must be rejected."

    foreach ($hashKey in @("corpus", "source", "reference", "rubric")) {
        $missingHashManifest = $fixtureManifestJson | ConvertFrom-Json
        $missingHashManifest.sha256.PSObject.Properties.Remove($hashKey)
        Write-TestUtf8Json -Path $fixtureManifestPath -Value $missingHashManifest
        Assert-Throws {
            Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot
        } $hashKey "A missing sealed $hashKey hash must be rejected."

        $alteredHashManifest = $fixtureManifestJson | ConvertFrom-Json
        $alteredHashManifest.sha256.PSObject.Properties[$hashKey].Value = ("0" * 64)
        Write-TestUtf8Json -Path $fixtureManifestPath -Value $alteredHashManifest
        Assert-Throws {
            Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot
        } "Sealed hash mismatch for $hashKey" "An altered sealed $hashKey hash must be rejected."
    }
    [System.IO.File]::WriteAllText(
        $fixtureManifestPath,
        $fixtureManifestJson,
        (New-Object System.Text.UTF8Encoding($false))
    )
    Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot

    $fixtureSourcePath = Join-Path $fixtureRoot ([string]$manifest.source_path)
    $sourceBytes = [System.IO.File]::ReadAllBytes($fixtureSourcePath)
    $sourceBytes[0] = $sourceBytes[0] -bxor 1
    [System.IO.File]::WriteAllBytes($fixtureSourcePath, $sourceBytes)
    Assert-Throws {
        Assert-Gpt56SealedCampaign -ProjectRoot $fixtureRoot
    } "Sealed hash mismatch for source" "A changed source character must break the seal."
    Copy-Item -LiteralPath (Join-Path $projectRoot ([string]$manifest.source_path)) -Destination $fixtureSourcePath -Force

    $validReport = New-TestReport -Tone "IMPROVE" -Output $validOutput
    $gate = Test-Gpt56PreGradeGate -Report $validReport -Tone "IMPROVE"
    Assert-True ($gate.Pass -eq $true) "A valid one-tone schema-v2 report must pass."
    Assert-True ($gate.Tone -eq "IMPROVE") "The gate must return the requested tone."
    Assert-True ($gate.OutputSha256 -eq (Get-TestUtf8Sha256 $validOutput)) "The gate must return a fresh host output hash."
    Assert-True ($gate.EmDashCount -eq 0) "The gate must report zero em dashes."

    $reportBindingCases = @(
        [pscustomobject]@{ Name = "document_id"; Altered = "other-document" }
        [pscustomobject]@{ Name = "source_sha256"; Altered = ("0" * 64) }
        [pscustomobject]@{ Name = "proofread_reference_sha256"; Altered = ("0" * 64) }
    )
    foreach ($case in $reportBindingCases) {
        $unboundReport = $validReport | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $unboundReport.PSObject.Properties[[string]$case.Name].Value = $case.Altered
        Assert-Throws {
            Test-Gpt56PreGradeGate -Report $unboundReport -Tone "IMPROVE" | Out-Null
        } ([string]$case.Name) "A report with the wrong $($case.Name) must fail the pre-grade gate."
    }

    $emptyOutputReport = New-TestReport -Tone "IMPROVE" -Output ""
    Assert-Throws {
        Test-Gpt56PreGradeGate -Report $emptyOutputReport -Tone "IMPROVE" | Out-Null
    } "nonempty" "An empty output must fail the pre-grade gate."

    $failedReport = New-TestReport -Tone "IMPROVE" -Output $validOutput
    $failedReport.results[0].success = $false
    Assert-Throws {
        Test-Gpt56PreGradeGate -Report $failedReport -Tone "IMPROVE" | Out-Null
    } "success" "A failed device result must fail the pre-grade gate."

    $wrongToneReport = New-TestReport -Tone "FRIENDLY" -Output $validOutput
    Assert-Throws {
        Test-Gpt56PreGradeGate -Report $wrongToneReport -Tone "IMPROVE" | Out-Null
    } "tone" "A report for the wrong tone must fail the pre-grade gate."

    $emDashOutput = $validOutput + [char]0x2014
    $emDashReport = New-TestReport -Tone "IMPROVE" -Output $emDashOutput
    Assert-Throws {
        Test-Gpt56PreGradeGate -Report $emDashReport -Tone "IMPROVE" | Out-Null
    } "em dash" "An output containing U+2014 must fail the pre-grade gate."

    $badStructureOutput = $validOutput.Replace("## Page 5:", "## Section 5:")
    $badStructureReport = New-TestReport -Tone "IMPROVE" -Output $badStructureOutput
    Assert-Throws {
        Test-Gpt56PreGradeGate -Report $badStructureReport -Tone "IMPROVE" | Out-Null
    } "five" "An output without five page headings must fail the pre-grade gate."

    $badHashReport = New-TestReport -Tone "IMPROVE" -Output $validOutput
    $badHashReport.results[0].output_sha256 = ("0" * 64)
    Assert-Throws {
        Test-Gpt56PreGradeGate -Report $badHashReport -Tone "IMPROVE" | Out-Null
    } "hash" "A stale device output hash must fail the pre-grade gate."

    $graderInput = New-Gpt56GraderInput -ProjectRoot $fixtureRoot -Report $validReport -OutputPath $graderInputPath
    $allowedProperties = @(
        "document_id"
        "hashes"
        "objective_error_inventory"
        "output"
        "protected_facts"
        "reference"
        "rubric"
        "source"
        "tone"
    )
    $actualProperties = @($graderInput.PSObject.Properties.Name | Sort-Object)
    Assert-True (($actualProperties -join "|") -eq (($allowedProperties | Sort-Object) -join "|")) (
        "Grader input leaked fields. Actual: $($actualProperties -join ', ')."
    )
    Assert-True (Test-Path -LiteralPath $graderInputPath -PathType Leaf) "Grader input must be frozen to OutputPath."
    $frozenGraderInput = Get-Content -LiteralPath $graderInputPath -Raw | ConvertFrom-Json
    Assert-True ($frozenGraderInput.output -eq $validOutput) "Frozen grader input must contain the accepted output."
    Assert-True ($frozenGraderInput.PSObject.Properties.Name.Count -eq 9) "Frozen grader input must contain only nine contract fields."
    Assert-True ($frozenGraderInput.PSObject.Properties.Name -notcontains "diagnostics") "Report diagnostics must not enter grader input."
    Assert-True ($frozenGraderInput.PSObject.Properties.Name -notcontains "paragraph_evidence") "Paragraph evidence must not enter grader input."

    $graderInputSha256 = Get-Gpt56Sha256 -Path $graderInputPath
    $validGrade = New-TestGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256
    $gradeResult = Test-Gpt56Grade -Grade $validGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256
    Assert-True ($gradeResult.Pass -eq $true) "A valid 100/100 isolated grade must pass."
    Assert-True ($gradeResult.ScorePercent -eq 100) "A valid isolated grade must return score 100."
    Assert-True ($gradeResult.OutputSha256 -ceq $graderInput.hashes.output) "A valid grade must bind the frozen output hash."
    Assert-True ($gradeResult.GraderInputSha256 -ceq $graderInputSha256) "A valid grade must bind the exact grader-input bytes."

    $wrongGraderInputHashGrade = Copy-TestObject $validGrade
    $wrongGraderInputHashGrade.grader_input_sha256 = ("0" * 64)
    Assert-Throws {
        Test-Gpt56Grade `
            -Grade $wrongGraderInputHashGrade `
            -GraderInput $graderInput `
            -GraderInputSha256 $graderInputSha256 | Out-Null
    } "grader_input_sha256" "A grade for different grader-input bytes must be rejected."

    $changedSourceInput = Copy-TestObject $graderInput
    $changedSourceInput.source = $changedSourceInput.source + "Changed."
    Assert-Throws {
        Test-Gpt56Grade `
            -Grade $validGrade `
            -GraderInput $changedSourceInput `
            -GraderInputSha256 $graderInputSha256 | Out-Null
    } "source" "Changed raw grader input source content must be rejected."

    $unknownToneInput = Copy-TestObject $graderInput
    $unknownToneInput.tone = "NOT_A_TONE"
    $unknownToneGrade = Copy-TestObject $validGrade
    $unknownToneGrade.tone = "NOT_A_TONE"
    Assert-Throws {
        Test-Gpt56Grade `
            -Grade $unknownToneGrade `
            -GraderInput $unknownToneInput `
            -GraderInputSha256 $graderInputSha256 | Out-Null
    } "tone_order" "A grade with an unknown grader-input tone must be rejected."

    $missingObjectiveGrade = Copy-TestObject $validGrade
    $missingObjectiveGrade.objective_results = @($missingObjectiveGrade.objective_results | Select-Object -SkipLast 1)
    Assert-Throws {
        Test-Gpt56Grade -Grade $missingObjectiveGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "objective_results" "A grade missing one objective inventory result must be rejected."

    $introducedErrorGrade = Copy-TestObject $validGrade
    $introducedErrorGrade.introduced_errors = @("An unsupported date was added.")
    Assert-Throws {
        Test-Gpt56Grade -Grade $introducedErrorGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "introduced_errors" "A passing grade with an introduced error must be rejected."

    $missingFactGrade = Copy-TestObject $validGrade
    $missingFactGrade.protected_fact_results = @($missingFactGrade.protected_fact_results | Select-Object -SkipLast 1)
    Assert-Throws {
        Test-Gpt56Grade -Grade $missingFactGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "protected_fact_results" "A grade missing one protected fact result must be rejected."

    $emDashGrade = Copy-TestObject $validGrade
    $emDashGrade.em_dash_count = 1
    Assert-Throws {
        Test-Gpt56Grade -Grade $emDashGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "em_dash_count" "A passing grade with an em dash must be rejected."

    $failedToneGrade = Copy-TestObject $validGrade
    $failedToneGrade.tone_criteria[1].pass = $false
    Assert-Throws {
        Test-Gpt56Grade -Grade $failedToneGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "tone_criteria" "A passing grade with a failed tone criterion must be rejected."

    $wrongOutputHashGrade = Copy-TestObject $validGrade
    $wrongOutputHashGrade.output_sha256 = ("0" * 64)
    Assert-Throws {
        Test-Gpt56Grade -Grade $wrongOutputHashGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "output_sha256" "A grade for a different output hash must be rejected."

    $falseHundredGrade = Copy-TestObject $validGrade
    $falseHundredGrade.pass = $false
    $falseHundredGrade.meaning_integrity.pass = $false
    $falseHundredGrade.meaning_integrity.findings = @("Meaning changed.")
    $falseHundredGrade.failure_summary = @("Meaning integrity failed.")
    Assert-Throws {
        Test-Gpt56Grade -Grade $falseHundredGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "score_percent" "A grade that claims 100 while any gate fails must be rejected."

    foreach ($bindingCase in @(
        [pscustomobject]@{ Name = "document_id"; Altered = "other-document" }
        [pscustomobject]@{ Name = "tone"; Altered = "FRIENDLY" }
    )) {
        $unboundGrade = Copy-TestObject $validGrade
        $unboundGrade.PSObject.Properties[[string]$bindingCase.Name].Value = $bindingCase.Altered
        Assert-Throws {
            Test-Gpt56Grade -Grade $unboundGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
        } ([string]$bindingCase.Name) "A grade with the wrong $($bindingCase.Name) must be rejected."
    }

    $unexpectedFieldGrade = Copy-TestObject $validGrade
    $unexpectedFieldGrade | Add-Member -NotePropertyName "repair" -NotePropertyValue "changed output"
    Assert-Throws {
        Test-Gpt56Grade -Grade $unexpectedFieldGrade -GraderInput $graderInput -GraderInputSha256 $graderInputSha256 | Out-Null
    } "properties" "A grade containing an unexpected top-level field must be rejected."

    $passedTones = @("PROOFREAD", "IMPROVE", "FRIENDLY")
    $sharedInvalidations = @(Get-Gpt56InvalidatedTones -Scope "shared_prompt" -Tone "IMPROVE" -PassedTones $passedTones)
    Assert-True (($sharedInvalidations -join "|") -eq ($passedTones -join "|")) "A shared change must invalidate every passed tone."

    $toneInvalidations = @(Get-Gpt56InvalidatedTones -Scope "tone_prompt" -Tone "IMPROVE" -PassedTones $passedTones)
    Assert-True ($toneInvalidations.Count -eq 2) "A tone prompt change must invalidate exactly two passed tones."
    Assert-True ($toneInvalidations -contains "PROOFREAD") "A tone prompt change must invalidate PROOFREAD."
    Assert-True ($toneInvalidations -contains "IMPROVE") "A tone prompt change must invalidate the selected tone."
    Assert-True ($toneInvalidations -notcontains "FRIENDLY") "A tone prompt change must not invalidate an unrelated tone."

    $evidenceInvalidations = @(Get-Gpt56InvalidatedTones -Scope "evidence_only" -Tone "IMPROVE" -PassedTones $passedTones)
    Assert-True ($evidenceInvalidations.Count -eq 0) "An evidence-only change must invalidate no tones."
    Assert-Throws {
        Get-Gpt56InvalidatedTones -Scope "unknown" -Tone "IMPROVE" -PassedTones $passedTones | Out-Null
    } "scope" "An unknown invalidation scope must be rejected."

    $firstRecord = [pscustomobject][ordered]@{ tone = "PROOFREAD"; pass = $true; score = 100 }
    $secondRecord = [pscustomobject][ordered]@{ tone = "IMPROVE"; pass = $false; score = 80 }
    Add-Gpt56LedgerRecord -LedgerPath $ledgerPath -Record $firstRecord
    $firstLineBefore = [System.IO.File]::ReadAllLines($ledgerPath)[0]
    Add-Gpt56LedgerRecord -LedgerPath $ledgerPath -Record $secondRecord

    $ledgerLines = [System.IO.File]::ReadAllLines($ledgerPath)
    Assert-True ($ledgerLines.Count -eq 2) "Two ledger appends must produce exactly two JSONL records."
    Assert-True ($ledgerLines[0] -eq $firstLineBefore) "The second ledger append must not rewrite the first record."
    $parsedFirst = $ledgerLines[0] | ConvertFrom-Json
    $parsedSecond = $ledgerLines[1] | ConvertFrom-Json
    Assert-True ($parsedFirst.tone -eq "PROOFREAD") "The first JSONL record must remain valid."
    Assert-True ($parsedSecond.tone -eq "IMPROVE") "The second JSONL record must be valid."
    $ledgerBytes = [System.IO.File]::ReadAllBytes($ledgerPath)
    $hasBom = $ledgerBytes.Length -ge 3 -and $ledgerBytes[0] -eq 0xEF -and $ledgerBytes[1] -eq 0xBB -and $ledgerBytes[2] -eq 0xBF
    Assert-True (-not $hasBom) "The ledger must use UTF-8 without BOM."

    $campaignRecordRoot = Join-Path $fixtureRoot "recording-campaign"
    $improveRoundPath = Join-Path $campaignRecordRoot "IMPROVE\test-round"
    New-Item -ItemType Directory -Path $improveRoundPath -Force | Out-Null
    $improveGraderInputPath = Join-Path $improveRoundPath "grader-input.json"
    $improveGradePath = Join-Path $improveRoundPath "grader-result.json"
    $improveManifestPath = Join-Path $improveRoundPath "round-manifest.json"
    Write-TestUtf8Json -Path $improveGraderInputPath -Value $graderInput
    $improveGraderInputSha256 = Get-Gpt56Sha256 -Path $improveGraderInputPath
    $improveGrade = New-TestGrade -GraderInput $graderInput -GraderInputSha256 $improveGraderInputSha256
    Write-TestUtf8Json -Path $improveGradePath -Value $improveGrade
    Write-TestUtf8Json -Path $improveManifestPath -Value ([pscustomobject][ordered]@{
        schema_version = 1
        status = "passed"
        tone = "IMPROVE"
        round_id = "test-round"
        git_commit = ("a" * 40)
        output_sha256 = [string]$graderInput.hashes.output
        apk_hashes = [pscustomobject][ordered]@{
            target = ("b" * 64)
            test = ("c" * 64)
        }
        model = [pscustomobject][ordered]@{
            ai_file = "gemma-3-4b-it-q4.gguf"
            ai_tier = "BALANCED"
            backend = "LLAMA_CPP"
            quality_mode = "THOROUGH"
        }
    })

    & $gradeRecorderPath `
        -RoundDirectory $improveRoundPath `
        -GradeFile $improveGradePath `
        -ChangeScope "evidence_only" | Out-Null
    $campaignLedgerPath = Join-Path $campaignRecordRoot "campaign-ledger.jsonl"
    $activeMatrixPath = Join-Path $campaignRecordRoot "active-matrix.json"
    $firstRecordedLine = [System.IO.File]::ReadAllLines($campaignLedgerPath)[0]
    $firstRecordedGrade = $firstRecordedLine | ConvertFrom-Json
    Assert-True ($firstRecordedGrade.tone -ceq "IMPROVE") "The recorder ledger must bind the graded tone."
    Assert-True ($firstRecordedGrade.round_id -ceq "test-round") "The recorder ledger must bind the round ID."
    Assert-True ($firstRecordedGrade.grade_sha256 -ceq (Get-Gpt56Sha256 -Path $improveGradePath)) "The recorder ledger must hash the exact grade file."
    Assert-True ($firstRecordedGrade.output_sha256 -ceq $graderInput.hashes.output) "The recorder ledger must bind the exact output hash."
    Assert-True ($firstRecordedGrade.grader_input_sha256 -ceq $improveGraderInputSha256) "The recorder ledger must bind the exact grader-input file hash."
    Assert-True ($firstRecordedGrade.git_commit -ceq ("a" * 40)) "The recorder ledger must preserve the round commit."
    Assert-True ($firstRecordedGrade.app_identity.target -ceq ("b" * 64)) "The recorder ledger must preserve the target APK identity."
    Assert-True ($firstRecordedGrade.model_identity.backend -ceq "LLAMA_CPP") "The recorder ledger must preserve the model identity."
    $firstMatrix = Get-Content -LiteralPath $activeMatrixPath -Raw | ConvertFrom-Json
    Assert-True (@($firstMatrix.active_tones).Count -eq 1) "The active matrix must contain the first passing tone."
    Assert-True ($firstMatrix.active_tones[0].tone -ceq "IMPROVE") "The active matrix must identify IMPROVE as active."

    $friendlyGraderInput = Copy-TestObject $graderInput
    $friendlyGraderInput.tone = "FRIENDLY"
    $friendlyRoundPath = Join-Path $campaignRecordRoot "FRIENDLY\friendly-r001"
    New-Item -ItemType Directory -Path $friendlyRoundPath -Force | Out-Null
    $friendlyInputPath = Join-Path $friendlyRoundPath "grader-input.json"
    $friendlyGradePath = Join-Path $friendlyRoundPath "grader-result.json"
    Write-TestUtf8Json -Path $friendlyInputPath -Value $friendlyGraderInput
    $friendlyGraderInputSha256 = Get-Gpt56Sha256 -Path $friendlyInputPath
    $friendlyGrade = New-TestGrade -GraderInput $friendlyGraderInput -GraderInputSha256 $friendlyGraderInputSha256
    Write-TestUtf8Json -Path $friendlyGradePath -Value $friendlyGrade
    Write-TestUtf8Json -Path (Join-Path $friendlyRoundPath "round-manifest.json") -Value ([pscustomobject][ordered]@{
        schema_version = 1
        status = "passed"
        tone = "FRIENDLY"
        round_id = "friendly-r001"
        git_commit = ("d" * 40)
        output_sha256 = [string]$friendlyGraderInput.hashes.output
        apk_hashes = [pscustomobject][ordered]@{
            target = ("e" * 64)
            test = ("f" * 64)
        }
        model = [pscustomobject][ordered]@{
            ai_file = "gemma-3-4b-it-q4.gguf"
            ai_tier = "BALANCED"
            backend = "LLAMA_CPP"
            quality_mode = "THOROUGH"
        }
    })

    & $gradeRecorderPath `
        -RoundDirectory $friendlyRoundPath `
        -GradeFile $friendlyGradePath `
        -ChangeScope "shared_prompt" | Out-Null
    $recordedLines = [System.IO.File]::ReadAllLines($campaignLedgerPath)
    Assert-True ($recordedLines.Count -eq 2) "Two accepted grades must append two ledger lines."
    Assert-True ($recordedLines[0] -ceq $firstRecordedLine) "A later grade must not rewrite prior ledger history."
    $friendlyRecord = $recordedLines[1] | ConvertFrom-Json
    Assert-True (@($friendlyRecord.invalidated_tones).Count -eq 1) "A shared change must record the one prior active tone."
    Assert-True ($friendlyRecord.invalidated_tones[0] -ceq "IMPROVE") "A shared change must invalidate the prior IMPROVE pass."
    $sharedMatrix = Get-Content -LiteralPath $activeMatrixPath -Raw | ConvertFrom-Json
    Assert-True (@($sharedMatrix.active_tones).Count -eq 1) "The rebuilt matrix must drop invalidated passes."
    Assert-True ($sharedMatrix.active_tones[0].tone -ceq "FRIENDLY") "The new passing tone must remain active after shared invalidation."

    $ledgerBeforeInputMismatch = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($campaignLedgerPath))
    $friendlyInputBytes = [System.IO.File]::ReadAllBytes($friendlyInputPath)
    $changedInventoryInput = Copy-TestObject $friendlyGraderInput
    $changedInventoryInput.objective_error_inventory[0] = $changedInventoryInput.objective_error_inventory[0] + " changed"
    Write-TestUtf8Json -Path $friendlyInputPath -Value $changedInventoryInput
    Assert-Throws {
        & $gradeRecorderPath `
            -RoundDirectory $friendlyRoundPath `
            -GradeFile $friendlyGradePath `
            -ChangeScope "evidence_only"
    } "grader_input_sha256" "A recorder grade for modified grader-input inventory bytes must be rejected."
    $ledgerAfterInputMismatch = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($campaignLedgerPath))
    Assert-True ($ledgerAfterInputMismatch -ceq $ledgerBeforeInputMismatch) "A grader-input hash mismatch must leave ledger bytes unchanged."
    [System.IO.File]::WriteAllBytes($friendlyInputPath, $friendlyInputBytes)

    $unknownRoundPath = Join-Path $campaignRecordRoot "NOT_A_TONE\unknown-r001"
    New-Item -ItemType Directory -Path $unknownRoundPath -Force | Out-Null
    $unknownInputPath = Join-Path $unknownRoundPath "grader-input.json"
    $unknownGradePath = Join-Path $unknownRoundPath "grader-result.json"
    $unknownRecorderInput = Copy-TestObject $friendlyGraderInput
    $unknownRecorderInput.tone = "NOT_A_TONE"
    Write-TestUtf8Json -Path $unknownInputPath -Value $unknownRecorderInput
    $unknownInputSha256 = Get-Gpt56Sha256 -Path $unknownInputPath
    $unknownRecorderGrade = New-TestGrade `
        -GraderInput $unknownRecorderInput `
        -GraderInputSha256 $unknownInputSha256
    Write-TestUtf8Json -Path $unknownGradePath -Value $unknownRecorderGrade
    $unknownManifest = Get-Content -LiteralPath (Join-Path $friendlyRoundPath "round-manifest.json") -Raw | ConvertFrom-Json
    $unknownManifest.tone = "NOT_A_TONE"
    $unknownManifest.round_id = "unknown-r001"
    Write-TestUtf8Json -Path (Join-Path $unknownRoundPath "round-manifest.json") -Value $unknownManifest
    $ledgerBeforeUnknownTone = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($campaignLedgerPath))
    Assert-Throws {
        & $gradeRecorderPath `
            -RoundDirectory $unknownRoundPath `
            -GradeFile $unknownGradePath `
            -ChangeScope "evidence_only"
    } "tone_order" "An unknown tone must fail before ledger append."
    $ledgerAfterUnknownTone = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($campaignLedgerPath))
    Assert-True ($ledgerAfterUnknownTone -ceq $ledgerBeforeUnknownTone) "An unknown tone must leave ledger bytes unchanged."

    $ledgerBeforeMalformedInput = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($campaignLedgerPath))
    [System.IO.File]::WriteAllBytes($unknownInputPath, ([byte[]](0xC3, 0x28)))
    Assert-Throws {
        & $gradeRecorderPath `
            -RoundDirectory $unknownRoundPath `
            -GradeFile $unknownGradePath `
            -ChangeScope "evidence_only"
    } "UTF-8" "Invalid grader-input UTF-8 must fail before ledger mutation."
    Assert-True (
        [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($campaignLedgerPath)) -ceq $ledgerBeforeMalformedInput
    ) "Invalid grader-input UTF-8 must leave ledger bytes unchanged."

    [System.IO.File]::WriteAllBytes(
        $unknownInputPath,
        (New-Object System.Text.UTF8Encoding($false)).GetBytes("not-json")
    )
    Assert-Throws {
        & $gradeRecorderPath `
            -RoundDirectory $unknownRoundPath `
            -GradeFile $unknownGradePath `
            -ChangeScope "evidence_only"
    } "JSON" "Invalid grader-input JSON must fail before ledger mutation."
    Assert-True (
        [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($campaignLedgerPath)) -ceq $ledgerBeforeMalformedInput
    ) "Invalid grader-input JSON must leave ledger bytes unchanged."

    $invalidRecordedGrade = Copy-TestObject $friendlyGrade
    $invalidRecordedGrade.objective_results = @($invalidRecordedGrade.objective_results | Select-Object -SkipLast 1)
    Write-TestUtf8Json -Path $friendlyGradePath -Value $invalidRecordedGrade
    Assert-Throws {
        & $gradeRecorderPath `
            -RoundDirectory $friendlyRoundPath `
            -GradeFile $friendlyGradePath `
            -ChangeScope "evidence_only"
    } "objective_results" "An invalid grade must fail before recording."
    Assert-True ([System.IO.File]::ReadAllLines($campaignLedgerPath).Count -eq 2) "An invalid grade must not append the ledger."

    $fakeAdbPath = Join-Path $fixtureRoot "fake-adb.cmd"
    $fakeAdbLogPath = Join-Path $fixtureRoot "fake-adb-arguments.txt"
    $fakeReportPath = Join-Path $fixtureRoot "fake-device-report.json"
    $campaignRoot = Join-Path $fixtureRoot "campaign"
    $fakeSerial = "R5CW11S23QA"
    New-FakeAdbExecutable -Path $fakeAdbPath
    $env:GPT56_FAKE_ADB_LOG = $fakeAdbLogPath
    $env:GPT56_FAKE_ADB_SERIAL = $fakeSerial
    $env:GPT56_FAKE_ADB_INSTRUMENTATION_STDOUT = "INSTRUMENTATION_RESULT: stream=ok`r`n"
    $env:GPT56_FAKE_ADB_INSTRUMENTATION_STDERR = ""
    $env:GPT56_FAKE_ADB_INSTRUMENTATION_EXIT = "0"
    $env:GPT56_FAKE_ADB_REPORT = ""
    $env:GPT56_FAKE_ADB_REPORT_EXISTS = ""
    $env:GPT56_FAKE_ADB_BARRIER = ""

    $invalidTone = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "NOT_A_TONE" `
        -RoundId "invalid-tone-r001" `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    Assert-True ($invalidTone.ExitCode -ne 0) "A tone outside sealed tone_order must fail."
    Assert-True ($invalidTone.Output -like "*tone_order*") "An invalid tone failure must name sealed tone_order."

    $unsafeRound = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId "Proofread_R001" `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    Assert-True ($unsafeRound.ExitCode -ne 0) "An unsafe round ID must fail."
    Assert-True ($unsafeRound.Output.Contains("[a-z0-9-]{3,64}")) "An unsafe round failure must name the accepted form."

    $raceRoundId = "proofread-race-r001"
    $raceReport = New-TestReport -Tone "PROOFREAD" -Output $validOutput -RoundId $raceRoundId
    Write-TestUtf8Json -Path $fakeReportPath -Value $raceReport
    $env:GPT56_FAKE_ADB_REPORT = $fakeReportPath
    $env:GPT56_FAKE_ADB_BARRIER = Join-Path $fixtureRoot "race-barrier"
    $raceFirst = Start-TestControllerProcess `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId $raceRoundId `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    $raceSecond = Start-TestControllerProcess `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId $raceRoundId `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    $raceResults = @(
        Complete-TestControllerProcess -Run $raceFirst
        Complete-TestControllerProcess -Run $raceSecond
    )
    $env:GPT56_FAKE_ADB_BARRIER = ""
    $raceDiagnostics = @(
        $raceResults | ForEach-Object {
            "exit_code=$($_.ExitCode); output=$($_.Output.Trim())"
        }
    ) -join [Environment]::NewLine
    Assert-True (
        @($raceResults | Where-Object { $_.ExitCode -eq 0 }).Count -eq 1
    ) "Exactly one concurrent controller must win the round claim. $raceDiagnostics"
    Assert-True (@($raceResults | Where-Object { $_.ExitCode -ne 0 }).Count -eq 1) "The losing concurrent controller must fail."
    $raceRoundPath = Join-Path $campaignRoot "PROOFREAD\$raceRoundId"
    $raceClaimPath = Join-Path $raceRoundPath "round-claim.json"
    Assert-True (Test-Path -LiteralPath $raceClaimPath -PathType Leaf) "The winning controller must leave a durable round claim."
    $raceClaim = Get-Content -LiteralPath $raceClaimPath -Raw | ConvertFrom-Json
    Assert-True ($raceClaim.round_id -eq $raceRoundId) "The durable claim must bind the round ID."

    $claimedRoundId = "proofread-claimed-r001"
    $claimedRoundPath = Join-Path $campaignRoot "PROOFREAD\$claimedRoundId"
    $claimedPath = Join-Path $claimedRoundPath "round-claim.json"
    New-Item -ItemType Directory -Path $claimedRoundPath -Force | Out-Null
    $existingClaimText = '{"owner":"existing"}'
    [System.IO.File]::WriteAllText(
        $claimedPath,
        $existingClaimText,
        (New-Object System.Text.UTF8Encoding($false))
    )
    $adbCountBeforeClaim = if (Test-Path -LiteralPath $fakeAdbLogPath) {
        [System.IO.File]::ReadAllLines($fakeAdbLogPath).Count
    } else {
        0
    }
    $existingClaim = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId $claimedRoundId `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    Assert-True ($existingClaim.ExitCode -ne 0) "An existing round claim must reject a second owner."
    Assert-True ([System.IO.File]::ReadAllText($claimedPath) -ceq $existingClaimText) "A rejected owner must not alter an existing claim."
    $adbCountAfterClaim = [System.IO.File]::ReadAllLines($fakeAdbLogPath).Count
    Assert-True ($adbCountAfterClaim -eq $adbCountBeforeClaim) "An existing claim must fail before any ADB work."

    $staleRoundId = "proofread-stale-r001"
    $staleReport = New-TestReport -Tone "PROOFREAD" -Output $validOutput -RoundId $staleRoundId
    Write-TestUtf8Json -Path $fakeReportPath -Value $staleReport
    $staleReportText = [System.IO.File]::ReadAllText($fakeReportPath)
    $env:GPT56_FAKE_ADB_REPORT = $fakeReportPath
    $env:GPT56_FAKE_ADB_REPORT_EXISTS = "1"
    $instrumentCountBeforeStale = @(
        [System.IO.File]::ReadAllLines($fakeAdbLogPath) |
            Where-Object { $_ -like "*`tinstrument`t*" }
    ).Count
    $staleResult = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId $staleRoundId `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    $env:GPT56_FAKE_ADB_REPORT_EXISTS = ""
    Assert-True ($staleResult.ExitCode -ne 0) "A preexisting private report must reject the round."
    Assert-True ($staleResult.Output -like "*new round ID*") "A stale-report failure must require a new round ID."
    $instrumentCountAfterStale = @(
        [System.IO.File]::ReadAllLines($fakeAdbLogPath) |
            Where-Object { $_ -like "*`tinstrument`t*" }
    ).Count
    Assert-True ($instrumentCountAfterStale -eq $instrumentCountBeforeStale) "A stale private report must fail before instrumentation starts."
    Assert-True ([System.IO.File]::ReadAllText($fakeReportPath) -ceq $staleReportText) "A stale private report must be preserved without deletion or alteration."
    $staleRoundPath = Join-Path $campaignRoot "PROOFREAD\$staleRoundId"
    Assert-True (Test-Path -LiteralPath (Join-Path $staleRoundPath "round-claim.json")) "A stale-report failure must preserve the host round claim."
    $staleManifest = Get-Content -LiteralPath (Join-Path $staleRoundPath "round-manifest.json") -Raw | ConvertFrom-Json
    Assert-True ($staleManifest.status -eq "failed") "A stale-report rejection must freeze a failed manifest."
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $staleRoundPath "device-report.json"))) "A stale device report must not be pulled into the host round."

    $env:GPT56_FAKE_ADB_REPORT = ""
    $missingReport = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId "proofread-r001" `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    Assert-True ($missingReport.ExitCode -ne 0) "A missing private device report must fail."
    $missingRoundPath = Join-Path $campaignRoot "PROOFREAD\proofread-r001"
    Assert-True (Test-Path -LiteralPath (Join-Path $missingRoundPath "device-stdout.txt")) "A missing report failure must preserve instrumentation stdout."
    Assert-True (Test-Path -LiteralPath (Join-Path $missingRoundPath "device-stderr.txt")) "A missing report failure must preserve instrumentation stderr."
    $missingManifest = Get-Content -LiteralPath (Join-Path $missingRoundPath "round-manifest.json") -Raw | ConvertFrom-Json
    Assert-True ($missingManifest.status -eq "failed") "A missing report must freeze a failed round manifest."

    $emDashRoundId = "proofread-r002"
    $emDashReport = New-TestReport `
        -Tone "PROOFREAD" `
        -Output ($validOutput + [char]0x2014) `
        -RoundId $emDashRoundId
    Write-TestUtf8Json -Path $fakeReportPath -Value $emDashReport
    $env:GPT56_FAKE_ADB_REPORT = $fakeReportPath
    $emDashRound = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId $emDashRoundId `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    Assert-True ($emDashRound.ExitCode -ne 0) "An em dash device output must fail."
    $emDashRoundPath = Join-Path $campaignRoot "PROOFREAD\$emDashRoundId"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $emDashRoundPath "output.md"))) "A failed pre-grade gate must not freeze output.md."
    $emDashManifest = Get-Content -LiteralPath (Join-Path $emDashRoundPath "round-manifest.json") -Raw | ConvertFrom-Json
    Assert-True ($emDashManifest.status -eq "failed") "An em dash report must freeze a failed round manifest."
    Assert-True ($emDashManifest.device_duration_ms -eq 120000) "A failed output must preserve measured device timing."

    $lateRoundId = "proofread-late-r001"
    $lateReport = New-TestReport -Tone "PROOFREAD" -Output $validOutput -RoundId $lateRoundId
    $lateReport.PSObject.Properties.Remove("backend")
    Write-TestUtf8Json -Path $fakeReportPath -Value $lateReport
    $env:GPT56_FAKE_ADB_REPORT = $fakeReportPath
    $lateResult = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId $lateRoundId `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    Assert-True ($lateResult.ExitCode -ne 0) "A post-gate metadata failure must fail the round."
    $lateRoundPath = Join-Path $campaignRoot "PROOFREAD\$lateRoundId"
    $lateOutputPath = Join-Path $lateRoundPath "output.md"
    Assert-True ([System.IO.File]::ReadAllText($lateOutputPath) -ceq $validOutput) "A late failure fixture must have a nonempty frozen output."
    $lateManifest = Get-Content -LiteralPath (Join-Path $lateRoundPath "round-manifest.json") -Raw | ConvertFrom-Json
    $expectedLateOutputHash = Get-TestUtf8Sha256 $validOutput
    Assert-True ($lateManifest.status -eq "failed") "A late failure must freeze failed status."
    Assert-True ($lateManifest.output_nonempty -eq $true) "A late failure must derive output_nonempty from frozen output.md."
    Assert-True ($lateManifest.output_sha256 -eq $expectedLateOutputHash) "A late failure must hash frozen output.md."
    Assert-True ($lateManifest.device_report_sha256.Length -eq 64) "A late failure must hash the frozen device report."
    Assert-True ($lateManifest.apk_hashes.target.Length -eq 64) "A late failure must preserve the available target APK hash."
    Assert-True ($lateManifest.apk_hashes.test.Length -eq 64) "A late failure must preserve the available test APK hash."
    Assert-True (@($lateManifest.prompt_source_file_hashes.PSObject.Properties).Count -eq 4) "A late failure must preserve all available prompt source hashes."
    Assert-True ($lateManifest.sealed_source_hashes.source -eq "4358a8521631c2d962de3ffb7a55f7ea04afdc1b95c2eedbffb0ea641c656dd9") "A late failure must preserve the sealed source hash."
    foreach ($artifactName in @("round_claim", "device_stdout", "device_stderr", "device_report", "output")) {
        $hashProperty = $lateManifest.artifact_hashes.PSObject.Properties |
            Where-Object { $_.Name -ceq $artifactName } |
            Select-Object -First 1
        Assert-True ($null -ne $hashProperty -and ([string]$hashProperty.Value).Length -eq 64) "A late failure must hash existing artifact $artifactName."
    }

    $successRoundId = "proofread-r003"
    $successReport = New-TestReport -Tone "PROOFREAD" -Output $validOutput -RoundId $successRoundId
    Write-TestUtf8Json -Path $fakeReportPath -Value $successReport
    $fakeReportText = [System.IO.File]::ReadAllText($fakeReportPath)
    $env:GPT56_FAKE_ADB_REPORT = $fakeReportPath
    $successRound = Invoke-TestController `
        -ControllerPath $controllerPath `
        -Serial $fakeSerial `
        -Tone "PROOFREAD" `
        -RoundId $successRoundId `
        -CampaignRoot $campaignRoot `
        -AdbPath $fakeAdbPath
    Assert-True ($successRound.ExitCode -eq 0) "A valid fake device report must complete the round. $($successRound.Output)"

    $successRoundPath = Join-Path $campaignRoot "PROOFREAD\$successRoundId"
    $frozenDeviceReport = [System.IO.File]::ReadAllText((Join-Path $successRoundPath "device-report.json"))
    $frozenOutput = [System.IO.File]::ReadAllText((Join-Path $successRoundPath "output.md"))
    Assert-True ($frozenDeviceReport -ceq $fakeReportText) "The private device report must be frozen exactly as UTF-8 text."
    Assert-True ($frozenOutput -ceq $validOutput) "The accepted output must be frozen without normalization."

    $roundManifest = Get-Content -LiteralPath (Join-Path $successRoundPath "round-manifest.json") -Raw | ConvertFrom-Json
    Assert-True ($roundManifest.status -eq "passed") "A valid round manifest must be passed."
    Assert-True ($roundManifest.tone -eq "PROOFREAD") "The round manifest must bind the requested tone."
    Assert-True ($roundManifest.round_id -eq $successRoundId) "The round manifest must bind the requested round ID."
    Assert-True ($roundManifest.output_nonempty -eq $true) "The round manifest must record a nonempty accepted output."
    Assert-True ($roundManifest.device_duration_ms -eq 120000) "The round manifest must preserve measured device timing."
    Assert-True ($roundManifest.device.model -eq "SM-S911U") "The round manifest must preserve S23 model metadata."
    Assert-True ($roundManifest.model.backend -eq "LLAMA_CPP") "The round manifest must preserve model backend metadata."
    Assert-True (@($roundManifest.prompt_source_file_hashes.PSObject.Properties).Count -ge 3) "The round manifest must hash prompt source files."
    Assert-True ($roundManifest.apk_hashes.target.Length -eq 64) "The round manifest must hash the target APK."
    Assert-True ($roundManifest.apk_hashes.test.Length -eq 64) "The round manifest must hash the test APK."

    $frozenGrader = Get-Content -LiteralPath (Join-Path $successRoundPath "grader-input.json") -Raw | ConvertFrom-Json
    Assert-True ($frozenGrader.output -ceq $validOutput) "The isolated grader input must contain the accepted output."
    Assert-True ($frozenGrader.PSObject.Properties.Name -notcontains "diagnostics") "The controller must not leak report diagnostics to the grader."

    $adbCalls = @([System.IO.File]::ReadAllLines($fakeAdbLogPath))
    $instrumentCall = @($adbCalls | Where-Object { $_ -like "*`tinstrument`t*" })[-1].Split("`t")
    $expectedInstrumentTail = @(
        "-e"
        "class"
        "com.echolink.mobile.AllToneFullPageDeviceTest"
        "-e"
        "tone"
        "PROOFREAD"
        "-e"
        "roundId"
        $successRoundId
        "com.remote.Manuscript.v2.debug.test/androidx.test.runner.AndroidJUnitRunner"
    )
    $actualInstrumentTail = @($instrumentCall | Select-Object -Last $expectedInstrumentTail.Count)
    Assert-True (($actualInstrumentTail -join "|") -ceq ($expectedInstrumentTail -join "|")) "Instrumentation must receive the exact class, tone, roundId, and runner arguments."

    $pullCall = @($adbCalls | Where-Object { $_ -like "*`texec-out`t*" })[-1].Split("`t")
    $expectedPullTail = @(
        "exec-out"
        "run-as"
        "com.remote.Manuscript.v2.debug"
        "cat"
        "files/test-evidence/gpt56-proofread-$successRoundId.json"
    )
    $actualPullTail = @($pullCall | Select-Object -Last $expectedPullTail.Count)
    Assert-True (($actualPullTail -join "|") -ceq ($expectedPullTail -join "|")) "The controller must pull only the exact private per-round report."
    Write-Output "PASS: Gpt56QualityLoop"
} finally {
    foreach ($name in @(
        "GPT56_FAKE_ADB_LOG",
        "GPT56_FAKE_ADB_SERIAL",
        "GPT56_FAKE_ADB_INSTRUMENTATION_STDOUT",
        "GPT56_FAKE_ADB_INSTRUMENTATION_STDERR",
        "GPT56_FAKE_ADB_INSTRUMENTATION_EXIT",
        "GPT56_FAKE_ADB_REPORT",
        "GPT56_FAKE_ADB_REPORT_EXISTS",
        "GPT56_FAKE_ADB_BARRIER"
    )) {
        Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
    Remove-Module Gpt56QualityLoop -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
