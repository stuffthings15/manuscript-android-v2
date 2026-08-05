$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-Throws([scriptblock]$Action, [string]$Expected, [string]$Message) {
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notlike "*$Expected*") {
            throw "$Message Expected '$Expected', got '$($_.Exception.Message)'."
        }
        return
    }
    throw "$Message Expected an exception."
}

function New-Section([int]$Index, [string]$Provenance = 'model_accepted') {
    [pscustomobject][ordered]@{
        index = $Index
        nonblank = $true
        live_ai_attempts = 1
        terminal_provenance = $Provenance
        terminal_state = 'valid_text'
        accepted = $true
        source_sha256 = ('a' * 63) + ($Index % 10)
        output_sha256 = ('b' * 63) + ($Index % 10)
        duration_ms = 125
        hard_gate_failures = @()
    }
}

function New-Report {
    [pscustomobject][ordered]@{
        schema_version = 1
        essay_id = '01_Dogs_in_Bakersfield_USA'
        tone = 'PROOFREAD'
        source_has_known_errors = $true
        source_sha256 = ('c' * 64)
        fixture_sha256 = ('d' * 64)
        output_sha256 = ('e' * 64)
        section_count = 10
        accepted_section_count = 10
        product = [pscustomobject]@{
            package = 'com.remote.Manuscript.v2.debug'
            apk_sha256 = '285f68e4c4311f24a180c4a1831d23d7e8d372b498d40020e3e0fdd49828986c'
        }
        model = [pscustomobject]@{
            identifier = 'local-model.gguf'
            tier = 'BALANCED'
            backend = 'LEGACY_LLAMA_CPU'
            sha256 = ('f' * 64)
        }
        sections = 1..10 | ForEach-Object { New-Section $_ }
        hard_gate_failures = @()
        total_duration_ms = 1250
    }
}

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$modulePath = Join-Path $projectRoot 'tools\EssayQualityCampaign.psm1'
Import-Module $modulePath -Force

$corpus = Get-ManuscriptEssayCorpus -ProjectRoot $projectRoot
Assert-True ($corpus.ZipSha256 -ceq 'c80a6fba0978596a464e1e9ff3c17d17d6a928fa56445a97c8e045a0eb9ff771') 'ZIP hash changed.'
Assert-True ($corpus.Essays.Count -eq 20) 'Corpus must contain 20 essays.'
Assert-True ($corpus.SectionCount -eq 200) 'Corpus must contain 200 sections.'
Assert-True ($corpus.WordCount -eq 18254) 'Corpus word count changed.'

$pending = @(Get-ManuscriptPendingEssayIds -Corpus $corpus -CompletedIds @(
    '01_Dogs_in_Bakersfield_USA',
    '02_Cats_in_Bath_England'
))
Assert-True ($pending.Count -eq 18) 'Resume selection must leave exactly 18 essays.'
Assert-True ($pending[0] -ceq '03_Rabbits_in_Utrecht_Netherlands') 'Resume order must stay deterministic.'

$report = New-Report
$receipt = Test-ManuscriptEssayDeviceReport `
    -Report $report `
    -ExpectedApkSha256 '285F68E4C4311F24A180C4A1831D23D7E8D372B498D40020E3E0FDD49828986C'
Assert-True $receipt.Pass 'Valid ten-section live-model report must pass.'
Assert-True ($receipt.LiveAiSections -eq 10) 'All ten sections must prove live AI.'

$noAttempt = New-Report
$noAttempt.sections[3].live_ai_attempts = 0
Assert-Throws {
    Test-ManuscriptEssayDeviceReport -Report $noAttempt -ExpectedApkSha256 $report.product.apk_sha256
} 'live AI attempt' 'A section without live AI must fail.'

$keptOriginal = New-Report
$keptOriginal.sections[5].terminal_provenance = 'kept_original'
Assert-Throws {
    Test-ManuscriptEssayDeviceReport -Report $keptOriginal -ExpectedApkSha256 $report.product.apk_sha256
} 'terminal provenance' 'Kept-original provenance must fail.'

$unchangedKnownError = New-Report
$unchangedKnownError.sections[6].output_sha256 = $unchangedKnownError.sections[6].source_sha256
Assert-Throws {
    Test-ManuscriptEssayDeviceReport -Report $unchangedKnownError -ExpectedApkSha256 $report.product.apk_sha256
} 'known-error section unchanged' 'A known-error source section cannot pass unchanged.'

$safeRepair = New-Report
$safeRepair.sections[2].terminal_provenance = 'model_plus_safe_repairs'
Assert-True ((Test-ManuscriptEssayDeviceReport -Report $safeRepair -ExpectedApkSha256 $report.product.apk_sha256).Pass) 'Safe-repair provenance must pass.'

$duplicateIndex = New-Report
$duplicateIndex.sections[9].index = 9
Assert-Throws {
    Test-ManuscriptEssayDeviceReport -Report $duplicateIndex -ExpectedApkSha256 $report.product.apk_sha256
} 'exact section indices 1 through 10' 'Duplicate and missing section indices must fail.'

$fixturePath = Join-Path $projectRoot 'quality\animal-pet-essays\fixtures\01_Dogs_in_Bakersfield_USA.txt'
$source = [System.IO.File]::ReadAllText($fixturePath)
$output = $source.Replace('errro-filled', 'error-filled')
$package = New-ManuscriptEssayJudgePackage `
    -Report $report `
    -Source $source `
    -Output $output `
    -Tone 'PROOFREAD'
$packageFields = @($package.PSObject.Properties.Name | Sort-Object)
$expectedFields = @('document_id','hashes','output','rubric','section_map','source','tone') | Sort-Object
Assert-True (($packageFields -join ',') -ceq ($expectedFields -join ',')) 'Judge package leaked unexpected fields.'
Assert-True ($package.section_map.Count -eq 10) 'Judge package must bind ten sections.'
Assert-True ($package.hashes.output -match '^[0-9a-f]{64}$') 'Output hash missing.'

function New-Judge([string]$JudgeId, [string]$ContextId) {
    [pscustomobject][ordered]@{
        schema_version = 1
        judge_id = $JudgeId
        context_id = $ContextId
        document_id = $package.document_id
        tone = $package.tone
        output_sha256 = $package.hashes.output
        score_percent = 100
        pass = $true
        hard_gate_failures = @()
        category_scores = [pscustomobject]@{
            grammar = 25
            spelling_vocabulary = 15
            punctuation_capitalization = 10
            sentence_construction = 15
            meaning_preservation = 20
            tone = 10
            completeness = 5
        }
    }
}

$judgeA = New-Judge 'gpt-5.6-sol-a' 'isolated-context-a'
$judgeB = New-Judge 'gpt-5.6-sol-b' 'isolated-context-b'
$dual = Test-ManuscriptDualJudgeResult -Package $package -JudgeA $judgeA -JudgeB $judgeB
Assert-True $dual.Pass 'Two independent perfect judges must pass.'
Assert-True ($dual.ScorePercent -eq 100) 'Dual result must remain exactly 100.'

$judgeB.score_percent = 99
$judgeB.pass = $false
Assert-Throws {
    Test-ManuscriptDualJudgeResult -Package $package -JudgeA $judgeA -JudgeB $judgeB
} '100' 'A 99 score must fail.'

$judgeB = New-Judge 'gpt-5.6-sol-b' 'isolated-context-a'
Assert-Throws {
    Test-ManuscriptDualJudgeResult -Package $package -JudgeA $judgeA -JudgeB $judgeB
} 'independent' 'Shared judge context must fail.'

Write-Output 'PASS: EssayQualityCampaign'
