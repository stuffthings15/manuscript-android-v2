[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RoundDirectory,

    [Parameter(Mandatory = $true)]
    [string]$GradeFile,

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
    [string]$ChangeScope
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-RequiredValue([pscustomobject]$InputObject, [string]$Name, [string]$Context) {
    $property = $InputObject.PSObject.Properties |
        Where-Object { $_.Name -ceq $Name } |
        Select-Object -First 1
    if ($null -eq $property) {
        throw "$Context must contain $Name."
    }
    return $property.Value
}

function Assert-NonemptyString([object]$Value, [string]$Context) {
    if (($Value -isnot [string]) -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        throw "$Context must be a nonempty string."
    }
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Import-Module (Join-Path $projectRoot "tools\Gpt56QualityLoop.psm1") -Force

$roundPath = (Resolve-Path -LiteralPath $RoundDirectory).Path
$gradePath = (Resolve-Path -LiteralPath $GradeFile).Path
$graderInputPath = Join-Path $roundPath "grader-input.json"
$manifestPath = Join-Path $roundPath "round-manifest.json"
foreach ($requiredPath in @($graderInputPath, $manifestPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required round artifact is missing: $requiredPath"
    }
}

try {
    $grade = Get-Content -LiteralPath $gradePath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    throw "GradeFile must contain valid JSON."
}
$graderInputBytes = [System.IO.File]::ReadAllBytes($graderInputPath)
$graderInputSnapshot = ConvertFrom-Gpt56JsonSnapshot `
    -Bytes $graderInputBytes `
    -Context "grader-input.json"
$graderInput = $graderInputSnapshot.Value
try {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    throw "round-manifest.json must contain valid JSON."
}
if ($grade -isnot [pscustomobject] -or $graderInput -isnot [pscustomobject] -or $manifest -isnot [pscustomobject]) {
    throw "Grade, grader input, and round manifest must each be JSON objects."
}

$graderInputSha256 = [string]$graderInputSnapshot.Sha256
$validatedGrade = Test-Gpt56Grade `
    -Grade $grade `
    -GraderInput $graderInput `
    -GraderInputSha256 $graderInputSha256

$status = Get-RequiredValue -InputObject $manifest -Name "status" -Context "Round manifest"
if (($status -isnot [string]) -or [string]$status -cne "passed") {
    throw "Round manifest status must be passed before grade recording."
}
$tone = Get-RequiredValue -InputObject $manifest -Name "tone" -Context "Round manifest"
Assert-NonemptyString -Value $tone -Context "Round manifest tone"
if ([string]$tone -cne [string]$validatedGrade.Tone) {
    throw "Round manifest tone must match the validated grade tone."
}
$roundId = Get-RequiredValue -InputObject $manifest -Name "round_id" -Context "Round manifest"
Assert-NonemptyString -Value $roundId -Context "Round manifest round_id"
if ([string]$roundId -cnotmatch '^[a-z0-9-]{3,64}$') {
    throw "Round manifest round_id must match [a-z0-9-]{3,64}."
}
$roundLeaf = Split-Path $roundPath -Leaf
$toneLeaf = Split-Path (Split-Path $roundPath -Parent) -Leaf
if ($roundLeaf -cne [string]$roundId -or $toneLeaf -cne [string]$tone) {
    throw "RoundDirectory must end with the exact manifest tone and round_id."
}
$manifestOutputHash = Get-RequiredValue -InputObject $manifest -Name "output_sha256" -Context "Round manifest"
if (($manifestOutputHash -isnot [string]) -or [string]$manifestOutputHash -cne [string]$validatedGrade.OutputSha256) {
    throw "Round manifest output_sha256 must match the validated grade output hash."
}
$gitCommit = Get-RequiredValue -InputObject $manifest -Name "git_commit" -Context "Round manifest"
if (($gitCommit -isnot [string]) -or [string]$gitCommit -cnotmatch '^[0-9a-f]{40}$') {
    throw "Round manifest git_commit must be 40 lowercase hex characters."
}
$appIdentity = $manifest.PSObject.Properties["apk_hashes"].Value
if ($null -eq $appIdentity -or $appIdentity -isnot [pscustomobject]) {
    throw "Round manifest apk_hashes must be an app identity object."
}
$appProperties = @($appIdentity.PSObject.Properties.Name | Sort-Object)
if (($appProperties -join "|") -cne "target|test") {
    throw "Round manifest apk_hashes must contain exactly target and test."
}
foreach ($name in @("target", "test")) {
    $hash = Get-RequiredValue -InputObject $appIdentity -Name $name -Context "Round manifest apk_hashes"
    if (($hash -isnot [string]) -or [string]$hash -cnotmatch '^[0-9a-f]{64}$') {
        throw "Round manifest apk_hashes.$name must be 64 lowercase hex characters."
    }
}
$modelIdentity = $manifest.PSObject.Properties["model"].Value
if ($null -eq $modelIdentity -or $modelIdentity -isnot [pscustomobject]) {
    throw "Round manifest model must be a model identity object."
}
$modelProperties = @($modelIdentity.PSObject.Properties.Name | Sort-Object)
if (($modelProperties -join "|") -cne "ai_file|ai_tier|backend|quality_mode") {
    throw "Round manifest model must contain exactly ai_file, ai_tier, backend, and quality_mode."
}
foreach ($name in @("ai_file", "ai_tier", "backend", "quality_mode")) {
    Assert-NonemptyString `
        -Value (Get-RequiredValue -InputObject $modelIdentity -Name $name -Context "Round manifest model") `
        -Context "Round manifest model.$name"
}

$campaignPath = Split-Path (Split-Path $roundPath -Parent) -Parent
$ledgerPath = Join-Path $campaignPath "campaign-ledger.jsonl"
$matrixPath = Join-Path $campaignPath "active-matrix.json"
$passedTones = @()
if (Test-Path -LiteralPath $ledgerPath -PathType Leaf) {
    $priorMatrix = Write-Gpt56ActiveMatrix -LedgerPath $ledgerPath -MatrixPath $matrixPath
    $passedTones = @($priorMatrix.active_tones | ForEach-Object { [string]$_.tone })
}
$invalidatedTones = @(Get-Gpt56InvalidatedTones `
    -Scope $ChangeScope `
    -Tone ([string]$tone) `
    -PassedTones $passedTones)

if (Test-Path -LiteralPath $ledgerPath -PathType Leaf) {
    foreach ($line in [System.IO.File]::ReadAllLines($ledgerPath)) {
        $priorRecord = $line | ConvertFrom-Json
        if (
            [string]$priorRecord.tone -ceq [string]$tone -and
            [string]$priorRecord.round_id -ceq [string]$roundId
        ) {
            throw "This tone and round_id already exist in campaign-ledger.jsonl."
        }
    }
}

$record = [pscustomobject][ordered]@{
    schema_version = 1
    document_id = [string]$validatedGrade.DocumentId
    tone = [string]$tone
    round_id = [string]$roundId
    grade_sha256 = Get-Gpt56Sha256 -Path $gradePath
    grader_input_sha256 = [string]$validatedGrade.GraderInputSha256
    output_sha256 = [string]$validatedGrade.OutputSha256
    pass = [bool]$validatedGrade.Pass
    score_percent = [double]$validatedGrade.ScorePercent
    change_scope = $ChangeScope
    invalidated_tones = $invalidatedTones
    git_commit = [string]$gitCommit
    app_identity = $appIdentity
    model_identity = $modelIdentity
    recorded_at_utc = [DateTime]::UtcNow.ToString("o")
}
Add-Gpt56LedgerRecord -LedgerPath $ledgerPath -Record $record
Write-Gpt56ActiveMatrix -LedgerPath $ledgerPath -MatrixPath $matrixPath | Out-Null
Write-Output "PASS: $ledgerPath"
