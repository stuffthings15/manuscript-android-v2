Set-StrictMode -Version Latest

$script:AllowedProvenance = @('model_accepted', 'model_plus_safe_repairs')
$script:ShaPattern = '^[0-9a-fA-F]{64}$'

function Get-EssaySha256 {
    param([Parameter(Mandatory)][string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-EssayTextSha256 {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Text)
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $hasher.ComputeHash($bytes)
        ([System.BitConverter]::ToString($digest)).Replace('-', '').ToLowerInvariant()
    } finally {
        $hasher.Dispose()
    }
}

function Get-RequiredEssayProperty {
    param(
        [Parameter(Mandatory)][psobject]$Object,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Context
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { throw "$Context is missing $Name." }
    $property.Value
}

function Assert-EssaySha256 {
    param([string]$Value, [string]$Context)
    if ($Value -notmatch $script:ShaPattern) { throw "$Context must be a SHA-256 value." }
}

function Split-EssaySections {
    param([Parameter(Mandatory)][string]$Text)
    @($Text -split '(?:\r?\n){2,}' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-ManuscriptEssayCorpus {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ProjectRoot)

    $root = [System.IO.Path]::GetFullPath($ProjectRoot)
    $corpusRoot = Join-Path $root 'quality\animal-pet-essays'
    $zipPath = Join-Path $corpusRoot '20_Animal_Pet_Essays_800_Percent_More_Errors.zip'
    $manifestPath = Join-Path $corpusRoot 'fixture-manifest.json'
    foreach ($path in @($zipPath, $manifestPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing corpus artifact: $path" }
    }
    $zipSha = Get-EssaySha256 $zipPath
    $expectedZip = 'c80a6fba0978596a464e1e9ff3c17d17d6a928fa56445a97c8e045a0eb9ff771'
    if ($zipSha -cne $expectedZip) { throw "Corpus ZIP hash mismatch: $zipSha" }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ([int]$manifest.schema -ne 1) { throw 'Essay fixture manifest schema must be 1.' }
    if (@($manifest.essays).Count -ne 20) { throw 'Essay fixture manifest must contain 20 essays.' }
    $records = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in @($manifest.essays)) {
        $sourcePath = Join-Path (Join-Path $corpusRoot 'source') ([string]$entry.source)
        $fixturePath = Join-Path (Join-Path $corpusRoot 'fixtures') ([string]$entry.fixture)
        if ((Get-EssaySha256 $sourcePath) -cne ([string]$entry.sourceSha256).ToLowerInvariant()) {
            throw "Source hash mismatch: $($entry.source)"
        }
        if ((Get-EssaySha256 $fixturePath) -cne ([string]$entry.fixtureSha256).ToLowerInvariant()) {
            throw "Fixture hash mismatch: $($entry.fixture)"
        }
        $sections = Split-EssaySections ([System.IO.File]::ReadAllText($fixturePath))
        if ($sections.Count -ne 10 -or [int]$entry.sections -ne 10) {
            throw "Essay must have ten sections: $($entry.fixture)"
        }
        $records.Add([pscustomobject][ordered]@{
            Id = [System.IO.Path]::GetFileNameWithoutExtension([string]$entry.fixture)
            SourcePath = $sourcePath
            SourceSha256 = ([string]$entry.sourceSha256).ToLowerInvariant()
            FixturePath = $fixturePath
            FixtureSha256 = ([string]$entry.fixtureSha256).ToLowerInvariant()
            Sections = $sections
            WordCount = [int]$entry.words
        })
    }
    [pscustomobject][ordered]@{
        SchemaVersion = 1
        ZipPath = $zipPath
        ZipSha256 = $zipSha
        ManifestPath = $manifestPath
        ManifestSha256 = Get-EssaySha256 $manifestPath
        Essays = @($records)
        SectionCount = [int](($records | ForEach-Object { $_.Sections.Count } | Measure-Object -Sum).Sum)
        WordCount = [int](($records | ForEach-Object { $_.WordCount } | Measure-Object -Sum).Sum)
    }
}

function Get-ManuscriptPendingEssayIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][psobject]$Corpus,
        [string[]]$CompletedIds = @()
    )
    $completed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($id in $CompletedIds) { [void]$completed.Add($id) }
    @($Corpus.Essays | ForEach-Object { $_.Id } | Where-Object { -not $completed.Contains($_) })
}

function Test-ManuscriptEssayDeviceReport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][psobject]$Report,
        [Parameter(Mandatory)][string]$ExpectedApkSha256
    )
    Assert-EssaySha256 $ExpectedApkSha256 'Expected APK hash'
    if ([int](Get-RequiredEssayProperty $Report 'schema_version' 'Report') -ne 1) {
        throw 'Report schema_version must be 1.'
    }
    $sections = @(Get-RequiredEssayProperty $Report 'sections' 'Report')
    if ($sections.Count -ne 10 -or [int]$Report.section_count -ne 10) {
        throw 'Report must contain exactly ten sections.'
    }
    if ([int]$Report.accepted_section_count -ne 10) {
        throw 'Report must contain ten accepted sections.'
    }
    $sectionIndices = @($sections | ForEach-Object {
        [int](Get-RequiredEssayProperty $_ 'index' 'Section')
    } | Sort-Object)
    if (($sectionIndices -join ',') -cne ((1..10) -join ',')) {
        throw 'Report must contain the exact section indices 1 through 10.'
    }
    $product = Get-RequiredEssayProperty $Report 'product' 'Report'
    if ([string]$product.package -cne 'com.remote.Manuscript.v2.debug') {
        throw 'Report package must be the V2 debug package.'
    }
    if (([string]$product.apk_sha256).ToLowerInvariant() -cne $ExpectedApkSha256.ToLowerInvariant()) {
        throw 'Report APK hash does not match the exact candidate.'
    }
    $model = Get-RequiredEssayProperty $Report 'model' 'Report'
    foreach ($name in @('identifier', 'tier', 'backend', 'sha256')) {
        $value = [string](Get-RequiredEssayProperty $model $name 'Report model')
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Report model $name is blank." }
    }
    Assert-EssaySha256 ([string]$model.sha256) 'Report model hash'
    $live = 0
    foreach ($section in $sections) {
        $index = [int](Get-RequiredEssayProperty $section 'index' 'Section')
        if ($index -lt 1 -or $index -gt 10) { throw "Section index is out of range: $index" }
        if (-not [bool]$section.nonblank) { throw "Section $index must be nonblank." }
        if ([int]$section.live_ai_attempts -lt 1) { throw "Section $index has no live AI attempt." }
        $live++
        $provenance = [string]$section.terminal_provenance
        if ($provenance -cnotin $script:AllowedProvenance) {
            throw "Section $index has forbidden terminal provenance: $provenance"
        }
        if ([string]$section.terminal_state -cne 'valid_text') {
            throw "Section $index did not end with valid text."
        }
        if (-not [bool]$section.accepted) { throw "Section $index was not accepted." }
        Assert-EssaySha256 ([string]$section.source_sha256) "Section $index source hash"
        Assert-EssaySha256 ([string]$section.output_sha256) "Section $index output hash"
        if (
            [bool]$Report.source_has_known_errors -and
            ([string]$section.source_sha256).ToLowerInvariant() -ceq
                ([string]$section.output_sha256).ToLowerInvariant()
        ) {
            throw "Section $index known-error section unchanged."
        }
        if (@($section.hard_gate_failures).Count -ne 0) {
            throw "Section $index has hard-gate failures."
        }
        if ([long]$section.duration_ms -lt 0) { throw "Section $index duration is invalid." }
    }
    if (@($Report.hard_gate_failures).Count -ne 0) { throw 'Report has hard-gate failures.' }
    [pscustomobject][ordered]@{
        Pass = $true
        EssayId = [string]$Report.essay_id
        Tone = [string]$Report.tone
        LiveAiSections = $live
        AcceptedSections = [int]$Report.accepted_section_count
        OutputSha256 = ([string]$Report.output_sha256).ToLowerInvariant()
    }
}

function New-ManuscriptEssayJudgePackage {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][psobject]$Report,
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Output,
        [Parameter(Mandatory)][string]$Tone
    )
    $sourceSections = Split-EssaySections $Source
    $outputSections = Split-EssaySections $Output
    if ($sourceSections.Count -ne 10 -or $outputSections.Count -ne 10) {
        throw 'Judge package requires ten source and ten output sections.'
    }
    $sectionMap = 0..9 | ForEach-Object {
        [pscustomobject][ordered]@{
            index = $_ + 1
            source_sha256 = Get-EssayTextSha256 $sourceSections[$_]
            output_sha256 = Get-EssayTextSha256 $outputSections[$_]
        }
    }
    [pscustomobject][ordered]@{
        document_id = "animal-pet-essays-v1/$($Report.essay_id)"
        tone = $Tone
        source = $Source
        output = $Output
        section_map = @($sectionMap)
        rubric = [pscustomobject][ordered]@{
            grammar = 25
            spelling_vocabulary = 15
            punctuation_capitalization = 10
            sentence_construction = 15
            meaning_preservation = 20
            tone = 10
            completeness = 5
            required_score = 100
            allowed_em_dashes = 0
        }
        hashes = [pscustomobject][ordered]@{
            source = Get-EssayTextSha256 $Source
            output = Get-EssayTextSha256 $Output
        }
    }
}

function Test-ManuscriptDualJudgeResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][psobject]$Package,
        [Parameter(Mandatory)][psobject]$JudgeA,
        [Parameter(Mandatory)][psobject]$JudgeB
    )
    if (
        [string]$JudgeA.judge_id -ceq [string]$JudgeB.judge_id -or
        [string]$JudgeA.context_id -ceq [string]$JudgeB.context_id
    ) {
        throw 'Two independent judge identities and contexts are required.'
    }
    $requiredScores = [ordered]@{
        grammar = 25
        spelling_vocabulary = 15
        punctuation_capitalization = 10
        sentence_construction = 15
        meaning_preservation = 20
        tone = 10
        completeness = 5
    }
    foreach ($judge in @($JudgeA, $JudgeB)) {
        if ([int]$judge.schema_version -ne 1) { throw 'Judge schema must be 1.' }
        if ([string]$judge.document_id -cne [string]$Package.document_id) {
            throw 'Judge document ID mismatch.'
        }
        if ([string]$judge.tone -cne [string]$Package.tone) { throw 'Judge tone mismatch.' }
        if ([string]$judge.output_sha256 -cne [string]$Package.hashes.output) {
            throw 'Judge output hash mismatch.'
        }
        if (-not [bool]$judge.pass -or [double]$judge.score_percent -ne 100) {
            throw 'Both judges must pass at exactly 100.'
        }
        if (@($judge.hard_gate_failures).Count -ne 0) { throw 'Judge has hard-gate failures.' }
        foreach ($entry in $requiredScores.GetEnumerator()) {
            $actual = Get-RequiredEssayProperty $judge.category_scores $entry.Key 'Judge category scores'
            if ([int]$actual -ne [int]$entry.Value) {
                throw "Judge category $($entry.Key) must score $($entry.Value)."
            }
        }
    }
    [pscustomobject][ordered]@{
        Pass = $true
        ScorePercent = 100
        JudgeIds = @([string]$JudgeA.judge_id, [string]$JudgeB.judge_id)
        OutputSha256 = [string]$Package.hashes.output
    }
}

Export-ModuleMember -Function @(
    'Get-ManuscriptEssayCorpus',
    'Get-ManuscriptPendingEssayIds',
    'Test-ManuscriptEssayDeviceReport',
    'New-ManuscriptEssayJudgePackage',
    'Test-ManuscriptDualJudgeResult'
)
