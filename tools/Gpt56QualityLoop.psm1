Set-StrictMode -Version Latest

$script:Gpt56DocumentId = "northstar-five-page-all-tones-sealed-v2"
$script:Gpt56ArtifactPaths = [ordered]@{
    corpus = "app/src/androidTest/assets/device_tests/gpt56-five-page-all-tones-v2.json"
    source = "app/src/androidTest/assets/device_tests/gpt56-five-page-errors-v2.md"
    reference = "app/src/androidTest/assets/device_tests/gpt56-five-page-proofread-reference-v2.md"
    rubric = "quality/gpt56/gpt56-five-page-tone-rubrics-v2.json"
}
$script:Gpt56ArtifactHashes = [ordered]@{
    corpus = "a2aca4386415a30df8f97320051529002011a17cc5f1110d168a0191f8756e78"
    source = "4358a8521631c2d962de3ffb7a55f7ea04afdc1b95c2eedbffb0ea641c656dd9"
    reference = "7a82fec7bf4637ad80494152c8ec30cc09047d26238f03b768296941b8557fda"
    rubric = "29167bc4c1ce6e0fcf2e174de5bc2e87240e35b67bc84a0788e07a42954c0d79"
}
$script:Gpt56ToneOrder = @(
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

function Resolve-Gpt56ProjectPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectRoot,

        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    $rootPath = [System.IO.Path]::GetFullPath($ProjectRoot)
    $candidatePath = [System.IO.Path]::GetFullPath((Join-Path $rootPath $RelativePath))
    $rootPrefix = $rootPath.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar

    if (
        -not $candidatePath.Equals($rootPath, [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $candidatePath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    ) {
        throw "Project path escapes ProjectRoot: $RelativePath."
    }

    return (Resolve-Path -LiteralPath $candidatePath).Path
}

function Get-Gpt56RequiredProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object]$InputObject,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    $property = $InputObject.PSObject.Properties | Where-Object { $_.Name -ceq $Name } | Select-Object -First 1
    if ($null -eq $property) {
        throw "$Context must contain $Name."
    }

    return $property.Value
}

function Get-Gpt56Utf8Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Text
    )

    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $utf8.GetBytes($Text)
        return ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function ConvertFrom-Gpt56JsonSnapshot {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [byte[]]$Bytes,

        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = ([System.BitConverter]::ToString($sha256.ComputeHash($Bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }

    $strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
    try {
        $text = $strictUtf8.GetString($Bytes)
    } catch [System.Text.DecoderFallbackException] {
        throw "$Context must contain valid UTF-8 bytes."
    }

    try {
        $value = $text | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "$Context must contain valid JSON."
    }

    return [pscustomobject][ordered]@{
        Sha256 = $hash
        Text = $text
        Value = $value
    }
}

function Get-Gpt56Sha256 {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    return (Get-FileHash -LiteralPath $resolvedPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Gpt56SealedCampaign {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectRoot
    )

    $manifestPath = Resolve-Gpt56ProjectPath `
        -ProjectRoot $ProjectRoot `
        -RelativePath "quality/gpt56/sealed-campaign-v2.json"
    return (Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Assert-Gpt56SealedCampaign {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectRoot
    )

    $campaign = Get-Gpt56SealedCampaign -ProjectRoot $ProjectRoot
    $fixedFields = [ordered]@{
        schema_version = 2
        document_id = $script:Gpt56DocumentId
        corpus_path = $script:Gpt56ArtifactPaths.corpus
        source_path = $script:Gpt56ArtifactPaths.source
        reference_path = $script:Gpt56ArtifactPaths.reference
        rubric_path = $script:Gpt56ArtifactPaths.rubric
        required_score_percent = 100
        allowed_introduced_errors = 0
        allowed_em_dashes = 0
    }
    foreach ($field in $fixedFields.Keys) {
        $actualValue = Get-Gpt56RequiredProperty -InputObject $campaign -Name $field -Context "Sealed campaign"
        $expectedValue = $fixedFields[$field]
        if (
            $null -eq $actualValue -or
            $actualValue.GetType() -ne $expectedValue.GetType() -or
            $actualValue -cne $expectedValue
        ) {
            throw "Sealed campaign $field must be '$expectedValue'."
        }
    }

    $toneOrder = @(Get-Gpt56RequiredProperty -InputObject $campaign -Name "tone_order" -Context "Sealed campaign")
    if ($toneOrder.Count -ne $script:Gpt56ToneOrder.Count) {
        throw "Sealed campaign tone_order must contain the exact 23-tone campaign order."
    }
    for ($index = 0; $index -lt $script:Gpt56ToneOrder.Count; $index++) {
        if (($toneOrder[$index] -isnot [string]) -or $toneOrder[$index] -cne $script:Gpt56ToneOrder[$index]) {
            throw "Sealed campaign tone_order must contain the exact 23-tone campaign order."
        }
    }

    $hashes = Get-Gpt56RequiredProperty -InputObject $campaign -Name "sha256" -Context "Sealed campaign"
    if ($hashes -isnot [pscustomobject]) {
        throw "Sealed campaign sha256 must be an object."
    }
    $hashNames = @($hashes.PSObject.Properties.Name)
    if ($hashNames.Count -ne $script:Gpt56ArtifactHashes.Count) {
        throw "Sealed campaign sha256 must contain exactly corpus, source, reference, and rubric."
    }

    foreach ($artifact in $script:Gpt56ArtifactHashes.Keys) {
        $sealedHash = [string](Get-Gpt56RequiredProperty `
            -InputObject $hashes `
            -Name $artifact `
            -Context "Sealed campaign sha256")
        $expected = [string]$script:Gpt56ArtifactHashes[$artifact]
        if ($sealedHash -cne $expected) {
            throw "Sealed hash mismatch for ${artifact}: expected $expected, got $sealedHash."
        }

        $artifactPath = Resolve-Gpt56ProjectPath `
            -ProjectRoot $ProjectRoot `
            -RelativePath ([string]$script:Gpt56ArtifactPaths[$artifact])
        $actual = Get-Gpt56Sha256 -Path $artifactPath
        if ($actual -cne $expected) {
            throw "Sealed hash mismatch for ${artifact}: expected $expected, got $actual."
        }
    }
}

function Test-Gpt56PreGradeGate {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Report,

        [Parameter(Mandatory = $true)]
        [string]$Tone
    )

    $schemaVersion = Get-Gpt56RequiredProperty -InputObject $Report -Name "schema_version" -Context "Report"
    if ($schemaVersion -ne 2) {
        throw "Report schema_version must be 2."
    }

    $documentId = [string](Get-Gpt56RequiredProperty -InputObject $Report -Name "document_id" -Context "Report")
    if ($documentId -cne $script:Gpt56DocumentId) {
        throw "Report document_id must be '$script:Gpt56DocumentId'."
    }

    $sourceHash = [string](Get-Gpt56RequiredProperty -InputObject $Report -Name "source_sha256" -Context "Report")
    if ($sourceHash -cne $script:Gpt56ArtifactHashes.source) {
        throw "Report source_sha256 must be '$($script:Gpt56ArtifactHashes.source)'."
    }

    $referenceHash = [string](Get-Gpt56RequiredProperty `
        -InputObject $Report `
        -Name "proofread_reference_sha256" `
        -Context "Report")
    if ($referenceHash -cne $script:Gpt56ArtifactHashes.reference) {
        throw "Report proofread_reference_sha256 must be '$($script:Gpt56ArtifactHashes.reference)'."
    }

    $reportTone = [string](Get-Gpt56RequiredProperty -InputObject $Report -Name "tone" -Context "Report")
    if ($reportTone -cne $Tone) {
        throw "Report tone '$reportTone' does not equal requested tone '$Tone'."
    }

    $completedToneCount = Get-Gpt56RequiredProperty `
        -InputObject $Report `
        -Name "completed_tone_count" `
        -Context "Report"
    if ($completedToneCount -ne 1) {
        throw "Report completed_tone_count must be 1."
    }

    $failureCount = Get-Gpt56RequiredProperty -InputObject $Report -Name "failure_count" -Context "Report"
    if ($failureCount -ne 0) {
        throw "Report failure_count must be 0."
    }

    $results = @(Get-Gpt56RequiredProperty -InputObject $Report -Name "results" -Context "Report")
    if ($results.Count -ne 1) {
        throw "Report must contain exactly one result."
    }

    $result = $results[0]
    $resultTone = [string](Get-Gpt56RequiredProperty -InputObject $result -Name "tone" -Context "Result")
    if ($resultTone -cne $Tone) {
        throw "Result tone '$resultTone' does not equal requested tone '$Tone'."
    }

    $success = Get-Gpt56RequiredProperty -InputObject $result -Name "success" -Context "Result"
    if (($success -isnot [bool]) -or (-not $success)) {
        throw "Result success must be true."
    }

    $output = Get-Gpt56RequiredProperty -InputObject $result -Name "output" -Context "Result"
    if (($output -isnot [string]) -or [string]::IsNullOrWhiteSpace([string]$output)) {
        throw "Result output must be nonempty."
    }
    $output = [string]$output

    $emDashCount = 0
    foreach ($character in $output.ToCharArray()) {
        if ($character -eq [char]0x2014) {
            $emDashCount++
        }
    }
    if ($emDashCount -ne 0) {
        throw "Result output must contain zero em dash characters."
    }

    $headingCount = [regex]::Matches($output, "(?m)^# [^\r\n]+$").Count
    if ($headingCount -ne 1) {
        throw "Result output must contain exactly one '# ' heading."
    }

    $pageHeadingCount = [regex]::Matches($output, "(?m)^## Page [^\r\n]+$").Count
    if ($pageHeadingCount -ne 5) {
        throw "Result output must contain exactly five '## Page ' headings."
    }

    $listItemCount = [regex]::Matches($output, "(?m)^- [^\r\n]+$").Count
    $hasJonah = [regex]::Matches($output, "(?m)^- Jonah:[^\r\n]+$").Count -eq 1
    $hasElena = [regex]::Matches($output, "(?m)^- Elena:[^\r\n]+$").Count -eq 1
    $hasMira = [regex]::Matches($output, "(?m)^- Mira:[^\r\n]+$").Count -eq 1
    if ($listItemCount -ne 3 -or -not $hasJonah -or -not $hasElena -or -not $hasMira) {
        throw "Result output must contain the required three-item assignment list."
    }

    $freshOutputHash = Get-Gpt56Utf8Sha256 -Text $output
    $reportedOutputHash = [string](Get-Gpt56RequiredProperty `
        -InputObject $result `
        -Name "output_sha256" `
        -Context "Result")
    if ($reportedOutputHash.ToLowerInvariant() -cne $freshOutputHash) {
        throw "Result output hash does not equal the fresh host hash."
    }

    return [pscustomobject][ordered]@{
        Pass = $true
        Tone = $Tone
        OutputSha256 = $freshOutputHash
        EmDashCount = 0
    }
}

function New-Gpt56GraderInput {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectRoot,

        [Parameter(Mandatory = $true)]
        [pscustomobject]$Report,

        [Parameter(Mandatory = $true)]
        [string]$OutputPath
    )

    Assert-Gpt56SealedCampaign -ProjectRoot $ProjectRoot
    $tone = [string](Get-Gpt56RequiredProperty -InputObject $Report -Name "tone" -Context "Report")
    $gate = Test-Gpt56PreGradeGate -Report $Report -Tone $tone
    $campaign = Get-Gpt56SealedCampaign -ProjectRoot $ProjectRoot

    $corpusPath = Resolve-Gpt56ProjectPath `
        -ProjectRoot $ProjectRoot `
        -RelativePath ([string]$campaign.corpus_path)
    $sourcePath = Resolve-Gpt56ProjectPath `
        -ProjectRoot $ProjectRoot `
        -RelativePath ([string]$campaign.source_path)
    $referencePath = Resolve-Gpt56ProjectPath `
        -ProjectRoot $ProjectRoot `
        -RelativePath ([string]$campaign.reference_path)
    $rubricPath = Resolve-Gpt56ProjectPath `
        -ProjectRoot $ProjectRoot `
        -RelativePath ([string]$campaign.rubric_path)

    $corpus = Get-Content -LiteralPath $corpusPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $rubrics = Get-Content -LiteralPath $rubricPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $rubricProperty = $rubrics.PSObject.Properties |
        Where-Object { $_.Name -ceq $tone } |
        Select-Object -First 1
    if ($null -eq $rubricProperty) {
        throw "Sealed rubric does not contain tone '$tone'."
    }

    $result = @(Get-Gpt56RequiredProperty -InputObject $Report -Name "results" -Context "Report")[0]
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $graderInput = [pscustomobject][ordered]@{
        document_id = [string]$campaign.document_id
        tone = $tone
        source = [System.IO.File]::ReadAllText($sourcePath, $utf8)
        reference = [System.IO.File]::ReadAllText($referencePath, $utf8)
        rubric = $rubricProperty.Value
        protected_facts = @($corpus.protected_facts)
        objective_error_inventory = @($corpus.objective_error_inventory)
        output = [string]$result.output
        hashes = [pscustomobject][ordered]@{
            corpus = ([string]$campaign.sha256.corpus).ToLowerInvariant()
            source = ([string]$campaign.sha256.source).ToLowerInvariant()
            reference = ([string]$campaign.sha256.reference).ToLowerInvariant()
            rubric = ([string]$campaign.sha256.rubric).ToLowerInvariant()
            output = $gate.OutputSha256
        }
    }

    $fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
    $outputDirectory = Split-Path $fullOutputPath -Parent
    if (-not [string]::IsNullOrEmpty($outputDirectory)) {
        [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    }
    $json = $graderInput | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($fullOutputPath, $json + [Environment]::NewLine, $utf8)

    return $graderInput
}

function Assert-Gpt56ExactProperties {
    param(
        [Parameter(Mandatory = $true)]
        [object]$InputObject,

        [Parameter(Mandatory = $true)]
        [string[]]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    if ($InputObject -isnot [pscustomobject]) {
        throw "$Context must be an object."
    }

    $actual = @($InputObject.PSObject.Properties.Name | Sort-Object)
    $expectedSorted = @($Expected | Sort-Object)
    if (($actual -join "|") -cne ($expectedSorted -join "|")) {
        throw "$Context properties must be exactly: $($Expected -join ', ')."
    }
}

function Assert-Gpt56StringArray {
    param(
        [AllowEmptyCollection()]
        [object]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Context,

        [switch]$RequireNonempty
    )

    if ($Value -isnot [System.Array]) {
        throw "$Context must be an array."
    }

    $items = @($Value)
    if ($RequireNonempty -and $items.Count -eq 0) {
        throw "$Context must contain at least one item."
    }
    foreach ($item in $items) {
        if (($item -isnot [string]) -or [string]::IsNullOrWhiteSpace([string]$item)) {
            throw "$Context must contain only nonempty strings."
        }
    }
}

function Test-Gpt56IntegerValue {
    param([object]$Value)

    return (
        $Value -is [byte] -or
        $Value -is [sbyte] -or
        $Value -is [int16] -or
        $Value -is [uint16] -or
        $Value -is [int32] -or
        $Value -is [uint32] -or
        $Value -is [int64] -or
        $Value -is [uint64]
    )
}

function Test-Gpt56NumberValue {
    param([object]$Value)

    return (
        (Test-Gpt56IntegerValue -Value $Value) -or
        $Value -is [single] -or
        $Value -is [double] -or
        $Value -is [decimal]
    )
}

function Test-Gpt56MeasuredResults {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Value,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedCount,

        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    if ($Value -isnot [System.Array]) {
        throw "$Context must be an array."
    }
    $results = @($Value)
    if ($results.Count -ne $ExpectedCount) {
        throw "$Context must contain exactly $ExpectedCount results."
    }

    for ($index = 0; $index -lt $results.Count; $index++) {
        $result = $results[$index]
        Assert-Gpt56ExactProperties `
            -InputObject $result `
            -Expected @("index", "pass", "evidence", "reason") `
            -Context "$Context item $index"
        $resultIndex = Get-Gpt56RequiredProperty -InputObject $result -Name "index" -Context "$Context item $index"
        if (-not (Test-Gpt56IntegerValue -Value $resultIndex) -or [long]$resultIndex -ne $index) {
            throw "$Context item $index index must equal its zero-based array position."
        }
        $resultPass = Get-Gpt56RequiredProperty -InputObject $result -Name "pass" -Context "$Context item $index"
        if ($resultPass -isnot [bool]) {
            throw "$Context item $index pass must be a boolean."
        }
        $evidence = $result.PSObject.Properties["evidence"].Value
        Assert-Gpt56StringArray -Value $evidence -Context "$Context item $index evidence" -RequireNonempty
        $reason = Get-Gpt56RequiredProperty -InputObject $result -Name "reason" -Context "$Context item $index"
        if (($reason -isnot [string]) -or [string]::IsNullOrWhiteSpace([string]$reason)) {
            throw "$Context item $index reason must be a nonempty string."
        }
    }

    return $results
}

function Assert-Gpt56GraderInput {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$GraderInput
    )

    $allowedProperties = @(
        "document_id"
        "tone"
        "source"
        "reference"
        "rubric"
        "protected_facts"
        "objective_error_inventory"
        "output"
        "hashes"
    )
    Assert-Gpt56ExactProperties `
        -InputObject $GraderInput `
        -Expected $allowedProperties `
        -Context "Grader input"

    $documentId = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "document_id" -Context "Grader input"
    if (($documentId -isnot [string]) -or [string]$documentId -cne $script:Gpt56DocumentId) {
        throw "Grader input document_id must be '$script:Gpt56DocumentId'."
    }

    $tone = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "tone" -Context "Grader input"
    if (($tone -isnot [string]) -or $script:Gpt56ToneOrder -cnotcontains [string]$tone) {
        throw "Grader input tone must be in sealed tone_order."
    }

    $hashes = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "hashes" -Context "Grader input"
    Assert-Gpt56ExactProperties `
        -InputObject $hashes `
        -Expected @("corpus", "source", "reference", "rubric", "output") `
        -Context "Grader input hashes"
    foreach ($name in @("corpus", "source", "reference", "rubric", "output")) {
        $hash = Get-Gpt56RequiredProperty -InputObject $hashes -Name $name -Context "Grader input hashes"
        if (($hash -isnot [string]) -or [string]$hash -cnotmatch '^[0-9a-f]{64}$') {
            throw "Grader input hashes.$name must be 64 lowercase hex characters."
        }
    }
    foreach ($name in @("corpus", "source", "reference", "rubric")) {
        if ([string]$hashes.$name -cne [string]$script:Gpt56ArtifactHashes[$name]) {
            throw "Grader input hashes.$name must match the fixed sealed $name hash."
        }
    }

    foreach ($name in @("source", "reference", "output")) {
        $content = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name $name -Context "Grader input"
        if (($content -isnot [string]) -or [string]::IsNullOrWhiteSpace([string]$content)) {
            throw "Grader input $name must be a nonempty string."
        }
        $freshHash = Get-Gpt56Utf8Sha256 -Text ([string]$content)
        if ($freshHash -cne [string]$hashes.$name) {
            throw "Grader input $name content must match hashes.$name."
        }
    }

    $objectiveInventory = $GraderInput.PSObject.Properties["objective_error_inventory"].Value
    if ($objectiveInventory -isnot [System.Array] -or @($objectiveInventory).Count -eq 0) {
        throw "Grader input objective_error_inventory must be a nonempty array."
    }
    Assert-Gpt56StringArray -Value $objectiveInventory -Context "Grader input objective_error_inventory" -RequireNonempty

    $protectedFacts = $GraderInput.PSObject.Properties["protected_facts"].Value
    if ($protectedFacts -isnot [System.Array] -or @($protectedFacts).Count -eq 0) {
        throw "Grader input protected_facts must be a nonempty array."
    }
    Assert-Gpt56StringArray -Value $protectedFacts -Context "Grader input protected_facts" -RequireNonempty

    $rubric = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "rubric" -Context "Grader input"
    Assert-Gpt56ExactProperties `
        -InputObject $rubric `
        -Expected @("objective_correctness_gate", "acceptance_criteria") `
        -Context "Grader input rubric"
    $objectiveGate = Get-Gpt56RequiredProperty `
        -InputObject $rubric `
        -Name "objective_correctness_gate" `
        -Context "Grader input rubric"
    if (($objectiveGate -isnot [string]) -or [string]::IsNullOrWhiteSpace([string]$objectiveGate)) {
        throw "Grader input rubric objective_correctness_gate must be a nonempty string."
    }
    $criteria = $rubric.PSObject.Properties["acceptance_criteria"].Value
    if ($criteria -isnot [System.Array] -or @($criteria).Count -ne 3) {
        throw "Grader input rubric acceptance_criteria must contain exactly three criteria."
    }
    Assert-Gpt56StringArray -Value $criteria -Context "Grader input rubric acceptance_criteria" -RequireNonempty

    return [pscustomobject][ordered]@{
        DocumentId = [string]$documentId
        Tone = [string]$tone
        OutputSha256 = [string]$hashes.output
    }
}

function Test-Gpt56Grade {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Grade,

        [Parameter(Mandatory = $true)]
        [pscustomobject]$GraderInput,

        [Parameter(Mandatory = $true)]
        [string]$GraderInputSha256
    )

    $gradeProperties = @(
        "schema_version"
        "document_id"
        "tone"
        "output_sha256"
        "grader_input_sha256"
        "pass"
        "score_percent"
        "submission_complete"
        "objective_results"
        "introduced_errors"
        "protected_fact_results"
        "meaning_integrity"
        "formatting"
        "tone_criteria"
        "em_dash_count"
        "failure_summary"
    )
    Assert-Gpt56ExactProperties -InputObject $Grade -Expected $gradeProperties -Context "Grade"
    $validatedInput = Assert-Gpt56GraderInput -GraderInput $GraderInput

    if ($GraderInputSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw "GraderInputSha256 must be 64 lowercase hex characters."
    }
    $gradeGraderInputHash = Get-Gpt56RequiredProperty -InputObject $Grade -Name "grader_input_sha256" -Context "Grade"
    if (($gradeGraderInputHash -isnot [string]) -or [string]$gradeGraderInputHash -cne $GraderInputSha256) {
        throw "Grade grader_input_sha256 must match the exact frozen grader-input.json SHA-256."
    }

    $schemaVersion = Get-Gpt56RequiredProperty -InputObject $Grade -Name "schema_version" -Context "Grade"
    if (-not (Test-Gpt56IntegerValue -Value $schemaVersion) -or [long]$schemaVersion -ne 2) {
        throw "Grade schema_version must be integer 2."
    }

    $inputDocumentId = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "document_id" -Context "Grader input"
    $documentId = Get-Gpt56RequiredProperty -InputObject $Grade -Name "document_id" -Context "Grade"
    if (($documentId -isnot [string]) -or [string]$documentId -cne [string]$inputDocumentId) {
        throw "Grade document_id must match grader input document_id."
    }

    $inputTone = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "tone" -Context "Grader input"
    $tone = Get-Gpt56RequiredProperty -InputObject $Grade -Name "tone" -Context "Grade"
    if (($tone -isnot [string]) -or [string]$tone -cne [string]$inputTone) {
        throw "Grade tone must match grader input tone."
    }

    $hashes = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "hashes" -Context "Grader input"
    if ($hashes -isnot [pscustomobject]) {
        throw "Grader input hashes must be an object."
    }
    $expectedOutputHash = Get-Gpt56RequiredProperty -InputObject $hashes -Name "output" -Context "Grader input hashes"
    if (($expectedOutputHash -isnot [string]) -or [string]$expectedOutputHash -cnotmatch '^[0-9a-f]{64}$') {
        throw "Grader input hashes.output must be 64 lowercase hex characters."
    }
    $inputOutput = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "output" -Context "Grader input"
    if (($inputOutput -isnot [string]) -or [string]::IsNullOrWhiteSpace([string]$inputOutput)) {
        throw "Grader input output must be nonempty."
    }
    $freshOutputHash = Get-Gpt56Utf8Sha256 -Text ([string]$inputOutput)
    if ([string]$expectedOutputHash -cne $freshOutputHash) {
        throw "Grader input hashes.output must equal the fresh output hash."
    }
    $gradeOutputHash = Get-Gpt56RequiredProperty -InputObject $Grade -Name "output_sha256" -Context "Grade"
    if (($gradeOutputHash -isnot [string]) -or [string]$gradeOutputHash -cnotmatch '^[0-9a-f]{64}$') {
        throw "Grade output_sha256 must be 64 lowercase hex characters."
    }
    if ([string]$gradeOutputHash -cne [string]$expectedOutputHash) {
        throw "Grade output_sha256 must match grader input hashes.output."
    }

    $gradePass = Get-Gpt56RequiredProperty -InputObject $Grade -Name "pass" -Context "Grade"
    if ($gradePass -isnot [bool]) {
        throw "Grade pass must be a boolean."
    }
    $score = Get-Gpt56RequiredProperty -InputObject $Grade -Name "score_percent" -Context "Grade"
    if (-not (Test-Gpt56NumberValue -Value $score) -or [double]$score -lt 0 -or [double]$score -gt 100) {
        throw "Grade score_percent must be a number from 0 through 100."
    }
    $submissionComplete = Get-Gpt56RequiredProperty -InputObject $Grade -Name "submission_complete" -Context "Grade"
    if ($submissionComplete -isnot [bool]) {
        throw "Grade submission_complete must be a boolean."
    }

    $objectiveInventory = $GraderInput.PSObject.Properties["objective_error_inventory"].Value
    if ($objectiveInventory -isnot [System.Array]) {
        throw "Grader input objective_error_inventory must be an array."
    }
    $objectiveResultsValue = $Grade.PSObject.Properties["objective_results"].Value
    $objectiveResults = @(Test-Gpt56MeasuredResults `
        -Value $objectiveResultsValue `
        -ExpectedCount @($objectiveInventory).Count `
        -Context "Grade objective_results")

    $introducedErrors = $Grade.PSObject.Properties["introduced_errors"].Value
    Assert-Gpt56StringArray -Value $introducedErrors -Context "Grade introduced_errors"
    $introducedErrors = @($introducedErrors)

    $protectedFacts = $GraderInput.PSObject.Properties["protected_facts"].Value
    if ($protectedFacts -isnot [System.Array]) {
        throw "Grader input protected_facts must be an array."
    }
    $protectedResultsValue = $Grade.PSObject.Properties["protected_fact_results"].Value
    $protectedResults = @(Test-Gpt56MeasuredResults `
        -Value $protectedResultsValue `
        -ExpectedCount @($protectedFacts).Count `
        -Context "Grade protected_fact_results")

    $meaning = Get-Gpt56RequiredProperty -InputObject $Grade -Name "meaning_integrity" -Context "Grade"
    Assert-Gpt56ExactProperties -InputObject $meaning -Expected @("pass", "findings") -Context "Grade meaning_integrity"
    $meaningPass = Get-Gpt56RequiredProperty -InputObject $meaning -Name "pass" -Context "Grade meaning_integrity"
    if ($meaningPass -isnot [bool]) {
        throw "Grade meaning_integrity.pass must be a boolean."
    }
    $meaningFindings = $meaning.PSObject.Properties["findings"].Value
    Assert-Gpt56StringArray -Value $meaningFindings -Context "Grade meaning_integrity.findings"

    $formatting = Get-Gpt56RequiredProperty -InputObject $Grade -Name "formatting" -Context "Grade"
    Assert-Gpt56ExactProperties `
        -InputObject $formatting `
        -Expected @("pass", "heading_count", "page_heading_count", "list_item_count", "findings") `
        -Context "Grade formatting"
    $formattingPass = Get-Gpt56RequiredProperty -InputObject $formatting -Name "pass" -Context "Grade formatting"
    if ($formattingPass -isnot [bool]) {
        throw "Grade formatting.pass must be a boolean."
    }
    foreach ($countName in @("heading_count", "page_heading_count", "list_item_count")) {
        $countValue = Get-Gpt56RequiredProperty -InputObject $formatting -Name $countName -Context "Grade formatting"
        if (-not (Test-Gpt56IntegerValue -Value $countValue) -or [long]$countValue -lt 0) {
            throw "Grade formatting.$countName must be a nonnegative integer."
        }
    }
    $formattingFindings = $formatting.PSObject.Properties["findings"].Value
    Assert-Gpt56StringArray -Value $formattingFindings -Context "Grade formatting.findings"

    $rubric = Get-Gpt56RequiredProperty -InputObject $GraderInput -Name "rubric" -Context "Grader input"
    if ($rubric -isnot [pscustomobject]) {
        throw "Grader input rubric must be an object."
    }
    $criteria = $rubric.PSObject.Properties["acceptance_criteria"].Value
    if ($criteria -isnot [System.Array] -or @($criteria).Count -ne 3) {
        throw "Grader input rubric acceptance_criteria must contain exactly three criteria."
    }
    $toneCriteriaValue = $Grade.PSObject.Properties["tone_criteria"].Value
    $toneResults = @(Test-Gpt56MeasuredResults -Value $toneCriteriaValue -ExpectedCount 3 -Context "Grade tone_criteria")

    $emDashCount = Get-Gpt56RequiredProperty -InputObject $Grade -Name "em_dash_count" -Context "Grade"
    if (-not (Test-Gpt56IntegerValue -Value $emDashCount) -or [long]$emDashCount -lt 0) {
        throw "Grade em_dash_count must be a nonnegative integer."
    }
    $failureSummary = $Grade.PSObject.Properties["failure_summary"].Value
    Assert-Gpt56StringArray -Value $failureSummary -Context "Grade failure_summary"
    $failureSummary = @($failureSummary)

    $gateFailures = New-Object System.Collections.Generic.List[string]
    if (-not [bool]$submissionComplete) { $gateFailures.Add("submission_complete") }
    if (@($objectiveResults | Where-Object { -not $_.pass }).Count -gt 0) { $gateFailures.Add("objective_results") }
    if ($introducedErrors.Count -gt 0) { $gateFailures.Add("introduced_errors") }
    if (@($protectedResults | Where-Object { -not $_.pass }).Count -gt 0) { $gateFailures.Add("protected_fact_results") }
    if (-not [bool]$meaningPass) { $gateFailures.Add("meaning_integrity") }
    if (
        -not [bool]$formattingPass -or
        [long]$formatting.heading_count -ne 1 -or
        [long]$formatting.page_heading_count -ne 5 -or
        [long]$formatting.list_item_count -ne 3
    ) {
        $gateFailures.Add("formatting")
    }
    if (@($toneResults | Where-Object { -not $_.pass }).Count -gt 0) { $gateFailures.Add("tone_criteria") }
    if ([long]$emDashCount -ne 0) { $gateFailures.Add("em_dash_count") }

    if ([bool]$gradePass) {
        if ([double]$score -ne 100) {
            throw "Grade pass true requires score_percent 100."
        }
        if ($gateFailures.Count -gt 0) {
            throw "Grade pass true requires $($gateFailures[0]) to pass."
        }
        if ($failureSummary.Count -ne 0) {
            throw "Grade failure_summary must be empty when pass is true."
        }
    } else {
        if ([double]$score -ge 100) {
            throw "Grade score_percent must be below 100 when pass is false."
        }
        if ($gateFailures.Count -eq 0) {
            throw "Grade pass false requires at least one failed gate."
        }
        if ($failureSummary.Count -eq 0) {
            throw "Grade failure_summary must report failed gates when pass is false."
        }
    }

    return [pscustomobject][ordered]@{
        Pass = [bool]$gradePass
        ScorePercent = [double]$score
        DocumentId = [string]$documentId
        Tone = [string]$tone
        OutputSha256 = [string]$gradeOutputHash
        GraderInputSha256 = [string]$gradeGraderInputHash
        FailedGates = $gateFailures.ToArray()
    }
}

function Write-Gpt56ActiveMatrix {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$LedgerPath,

        [Parameter(Mandatory = $true)]
        [string]$MatrixPath
    )

    $active = [ordered]@{}
    $fullLedgerPath = [System.IO.Path]::GetFullPath($LedgerPath)
    if (Test-Path -LiteralPath $fullLedgerPath -PathType Leaf) {
        $lines = [System.IO.File]::ReadAllLines($fullLedgerPath)
        for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
            if ([string]::IsNullOrWhiteSpace($lines[$lineIndex])) {
                throw "Campaign ledger line $($lineIndex + 1) must not be blank."
            }
            try {
                $record = $lines[$lineIndex] | ConvertFrom-Json
            } catch {
                throw "Campaign ledger line $($lineIndex + 1) is not valid JSON."
            }
            $tone = Get-Gpt56RequiredProperty -InputObject $record -Name "tone" -Context "Campaign ledger record"
            if (($tone -isnot [string]) -or $script:Gpt56ToneOrder -cnotcontains [string]$tone) {
                throw "Campaign ledger record tone must be in sealed tone_order."
            }
            $invalidated = $record.PSObject.Properties["invalidated_tones"].Value
            if ($invalidated -isnot [System.Array]) {
                throw "Campaign ledger record invalidated_tones must be an array."
            }
            foreach ($invalidatedTone in @($invalidated)) {
                if (($invalidatedTone -isnot [string]) -or $script:Gpt56ToneOrder -cnotcontains [string]$invalidatedTone) {
                    throw "Campaign ledger invalidated_tones must contain only sealed tones."
                }
                $active.Remove([string]$invalidatedTone)
            }
            $active.Remove([string]$tone)
            $recordPass = Get-Gpt56RequiredProperty -InputObject $record -Name "pass" -Context "Campaign ledger record"
            if ($recordPass -isnot [bool]) {
                throw "Campaign ledger record pass must be a boolean."
            }
            if ([bool]$recordPass) {
                $active[[string]$tone] = $record
            }
        }
    }

    $activeTones = New-Object System.Collections.Generic.List[object]
    foreach ($toneName in $script:Gpt56ToneOrder) {
        if ($active.Contains($toneName)) {
            $record = $active[$toneName]
            $activeTones.Add([pscustomobject][ordered]@{
                tone = [string]$record.tone
                round_id = [string]$record.round_id
                grade_sha256 = [string]$record.grade_sha256
                grader_input_sha256 = [string]$record.grader_input_sha256
                output_sha256 = [string]$record.output_sha256
                score_percent = [double]$record.score_percent
                git_commit = [string]$record.git_commit
                app_identity = $record.app_identity
                model_identity = $record.model_identity
                recorded_at_utc = [string]$record.recorded_at_utc
            })
        }
    }

    $matrix = [pscustomobject][ordered]@{
        schema_version = 1
        document_id = $script:Gpt56DocumentId
        active_tones = @($activeTones.ToArray())
    }
    $fullMatrixPath = [System.IO.Path]::GetFullPath($MatrixPath)
    $matrixDirectory = Split-Path $fullMatrixPath -Parent
    if (-not [string]::IsNullOrEmpty($matrixDirectory)) {
        [System.IO.Directory]::CreateDirectory($matrixDirectory) | Out-Null
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $json = ($matrix | ConvertTo-Json -Depth 20) + [Environment]::NewLine
    $temporaryPath = $fullMatrixPath + "." + [guid]::NewGuid().ToString("N") + ".tmp"
    try {
        [System.IO.File]::WriteAllText($temporaryPath, $json, $utf8)
        Move-Item -LiteralPath $temporaryPath -Destination $fullMatrixPath -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }

    return $matrix
}

function Get-Gpt56InvalidatedTones {
    [CmdletBinding()]
    [OutputType([string[]])]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet(
            "tone_prompt",
            "shared_prompt",
            "model",
            "sampling",
            "validator",
            "chunker",
            "cleanup",
            "pipeline",
            "evidence_only"
        )]
        [string]$Scope,

        [Parameter(Mandatory = $true)]
        [string]$Tone,

        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$PassedTones
    )

    if ($Scope -eq "evidence_only") {
        return @()
    }

    if ($Scope -eq "tone_prompt") {
        $invalidated = New-Object System.Collections.Generic.List[string]
        foreach ($passedTone in $PassedTones) {
            if (
                ($passedTone -ceq "PROOFREAD" -or $passedTone -ceq $Tone) -and
                -not $invalidated.Contains($passedTone)
            ) {
                $invalidated.Add($passedTone)
            }
        }
        return $invalidated.ToArray()
    }

    return @($PassedTones)
}

function Add-Gpt56LedgerRecord {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$LedgerPath,

        [Parameter(Mandatory = $true)]
        [pscustomobject]$Record
    )

    $fullLedgerPath = [System.IO.Path]::GetFullPath($LedgerPath)
    $ledgerDirectory = Split-Path $fullLedgerPath -Parent
    if (-not [string]::IsNullOrEmpty($ledgerDirectory)) {
        [System.IO.Directory]::CreateDirectory($ledgerDirectory) | Out-Null
    }

    $json = $Record | ConvertTo-Json -Depth 20 -Compress
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $bytes = $utf8.GetBytes($json + [Environment]::NewLine)
    $stream = [System.IO.File]::Open(
        $fullLedgerPath,
        [System.IO.FileMode]::Append,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::Read
    )
    try {
        $stream.Write($bytes, 0, $bytes.Length)
    } finally {
        $stream.Dispose()
    }
}

Export-ModuleMember -Function @(
    "ConvertFrom-Gpt56JsonSnapshot"
    "Get-Gpt56Sha256"
    "Get-Gpt56SealedCampaign"
    "Assert-Gpt56SealedCampaign"
    "Test-Gpt56PreGradeGate"
    "New-Gpt56GraderInput"
    "Test-Gpt56Grade"
    "Write-Gpt56ActiveMatrix"
    "Get-Gpt56InvalidatedTones"
    "Add-Gpt56LedgerRecord"
)
