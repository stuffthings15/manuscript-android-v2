[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$Tone,

    [Parameter(Mandatory = $true)]
    [string]$RoundId,

    [Parameter(Mandatory = $true)]
    [string]$CampaignRoot,

    [string]$AdbPath,

    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function ConvertTo-ProcessArgument([string]$Argument) {
    if ($Argument.Contains('"')) {
        throw "Process arguments containing quotes are not supported."
    }
    if ($Argument.Length -eq 0 -or $Argument -match "\s") {
        return '"' + $Argument + '"'
    }
    return $Argument
}

function Invoke-CapturedProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Arguments,

        [string]$WorkingDirectory,

        [int]$TimeoutMilliseconds = 0
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = (($Arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join " ")
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
        $startInfo.WorkingDirectory = $WorkingDirectory
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $startInfo.StandardOutputEncoding = $utf8
    $startInfo.StandardErrorEncoding = $utf8

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        if (-not $process.Start()) {
            throw "Failed to start process '$FilePath'."
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if ($TimeoutMilliseconds -gt 0) {
            $completed = $process.WaitForExit($TimeoutMilliseconds)
        } else {
            $process.WaitForExit()
            $completed = $true
        }

        if (-not $completed) {
            try { $process.Kill() } catch { }
            $process.WaitForExit()
        }
        [System.Threading.Tasks.Task]::WaitAll(@($stdoutTask, $stderrTask))
        $stopwatch.Stop()

        return [pscustomobject][ordered]@{
            ExitCode = if ($completed) { $process.ExitCode } else { -1 }
            StdOut = $stdoutTask.Result
            StdErr = $stderrTask.Result
            TimedOut = -not $completed
            DurationMs = [long]$stopwatch.ElapsedMilliseconds
        }
    } finally {
        $stopwatch.Stop()
        $process.Dispose()
    }
}

function Write-Utf8Text([string]$Path, [AllowEmptyString()][string]$Text) {
    $directory = Split-Path $Path -Parent
    if (-not [string]::IsNullOrEmpty($directory)) {
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($Path),
        $Text,
        (New-Object System.Text.UTF8Encoding($false))
    )
}

function Write-Utf8Json([string]$Path, [pscustomobject]$Value) {
    $json = $Value | ConvertTo-Json -Depth 20
    Write-Utf8Text -Path $Path -Text ($json + [Environment]::NewLine)
}

function Get-RequiredReportValue([object]$InputObject, [string]$Name, [string]$Context) {
    $property = $InputObject.PSObject.Properties |
        Where-Object { $_.Name -ceq $Name } |
        Select-Object -First 1
    if ($null -eq $property) {
        throw "$Context must contain $Name."
    }
    return $property.Value
}

function Resolve-AdbExecutable([string]$RequestedPath) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        return (Resolve-Path -LiteralPath $RequestedPath -ErrorAction Stop).Path
    }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        $command = Get-Command adb -ErrorAction SilentlyContinue
    }
    if ($null -eq $command) {
        throw "ADB is required. Supply -AdbPath or add adb to PATH."
    }
    return $command.Source
}

function Resolve-Java21 {
    $candidatePaths = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidatePaths.Add((Join-Path $env:JAVA_HOME "bin\java.exe"))
    }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand) {
        $candidatePaths.Add($javaCommand.Source)
    }
    $androidJdkRoot = Join-Path $env:ProgramFiles "Android\openjdk"
    if (Test-Path -LiteralPath $androidJdkRoot -PathType Container) {
        Get-ChildItem -LiteralPath $androidJdkRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidatePaths.Add((Join-Path $_.FullName "bin\java.exe")) }
    }

    foreach ($candidatePath in $candidatePaths) {
        if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
            continue
        }
        $version = Invoke-CapturedProcess -FilePath $candidatePath -Arguments @("-version") -TimeoutMilliseconds 30000
        $versionText = $version.StdOut + $version.StdErr
        if ($version.ExitCode -eq 0 -and $versionText -match 'version\s+"21(?:[\.\-"]|$)') {
            return [pscustomobject][ordered]@{
                Path = (Resolve-Path -LiteralPath $candidatePath).Path
                Home = (Split-Path (Split-Path (Resolve-Path -LiteralPath $candidatePath).Path -Parent) -Parent)
                Version = $versionText.Trim()
            }
        }
    }
    throw "Java 21 is required. Set JAVA_HOME to a Java 21 JDK."
}

function Assert-ProcessSucceeded([pscustomobject]$Result, [string]$Description) {
    if ($Result.TimedOut) {
        throw "$Description timed out."
    }
    if ($Result.ExitCode -ne 0) {
        $detail = ($Result.StdErr + $Result.StdOut).Trim()
        throw "$Description failed with exit code $($Result.ExitCode): $detail"
    }
}

function Invoke-Gradle([string]$GradlePath, [string]$ProjectRoot, [string[]]$Arguments) {
    $commandParts = @((ConvertTo-ProcessArgument $GradlePath)) + @(
        $Arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }
    )
    $commandLine = $commandParts -join " "
    $result = Invoke-CapturedProcess `
        -FilePath $env:ComSpec `
        -Arguments @("/d", "/s", "/c", $commandLine) `
        -WorkingDirectory $ProjectRoot
    Assert-ProcessSucceeded -Result $result -Description "Gradle"
}

function Get-GitCommit([string]$ProjectRoot) {
    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) {
        $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    }
    if ($null -eq $gitCommand) {
        throw "Git is required to freeze the source commit."
    }
    $result = Invoke-CapturedProcess `
        -FilePath $gitCommand.Source `
        -Arguments @("-C", $ProjectRoot, "rev-parse", "HEAD") `
        -TimeoutMilliseconds 30000
    Assert-ProcessSucceeded -Result $result -Description "Git commit lookup"
    $commit = $result.StdOut.Trim()
    if ($commit -notmatch '^[0-9a-f]{40}$') {
        throw "Git commit lookup returned an invalid SHA."
    }
    return $commit
}

function Add-ExistingArtifactHash(
    [System.Collections.IDictionary]$Hashes,
    [string]$Name,
    [string]$Path
) {
    if (
        -not [string]::IsNullOrWhiteSpace($Path) -and
        (Test-Path -LiteralPath $Path -PathType Leaf)
    ) {
        $Hashes[$Name] = Get-Gpt56Sha256 -Path $Path
    }
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$modulePath = Join-Path $projectRoot "tools\Gpt56QualityLoop.psm1"
Import-Module $modulePath -Force

$roundPath = $null
$roundClaimPath = $null
$roundCreated = $false
$roundStartedAt = [DateTime]::UtcNow
$instrumentationStartedAt = $null
$instrumentationCompletedAt = $null
$instrumentationResult = $null
$deviceDurationMs = $null
$sealedCampaign = $null
$deviceReportPath = $null
$outputPath = $null
$graderInputPath = $null
$targetApkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$testApkPath = Join-Path $projectRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$promptSourcePaths = @(
    "app/src/main/java/com/echolink/mobile/TonePromptLibrary.kt",
    "app/src/main/java/com/echolink/mobile/Qwen3GrammarRuntime.kt",
    "app/src/main/java/com/echolink/mobile/LiteRtGemmaBackend.kt",
    "app/src/androidTest/java/com/echolink/mobile/AllToneFullPageDeviceTest.kt"
)

try {
    Assert-Gpt56SealedCampaign -ProjectRoot $projectRoot
    $sealedCampaign = Get-Gpt56SealedCampaign -ProjectRoot $projectRoot
    if (@($sealedCampaign.tone_order) -cnotcontains $Tone) {
        throw "Tone '$Tone' is outside sealed tone_order."
    }
    if ($RoundId -cnotmatch '^[a-z0-9-]{3,64}$') {
        throw "RoundId must match [a-z0-9-]{3,64}."
    }

    $campaignPath = [System.IO.Path]::GetFullPath($CampaignRoot)
    $roundPath = Join-Path (Join-Path $campaignPath $Tone) $RoundId
    if (Test-Path -LiteralPath $roundPath) {
        throw "Round directory already exists or is claimed: $roundPath"
    }
    [System.IO.Directory]::CreateDirectory($roundPath) | Out-Null
    $roundClaimPath = Join-Path $roundPath "round-claim.json"
    $claim = [pscustomobject][ordered]@{
        schema_version = 1
        tone = $Tone
        round_id = $RoundId
        serial = $Serial
        process_id = $PID
        claimed_at_utc = $roundStartedAt.ToString("o")
    }
    $claimText = ($claim | ConvertTo-Json -Depth 5) + [Environment]::NewLine
    $claimBytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($claimText)
    try {
        $claimStream = [System.IO.File]::Open(
            $roundClaimPath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::Read
        )
        try {
            $claimStream.Write($claimBytes, 0, $claimBytes.Length)
        } finally {
            $claimStream.Dispose()
        }
    } catch [System.IO.IOException] {
        throw "Round directory already exists or is claimed: $roundPath"
    }
    $roundCreated = $true

    $adb = Resolve-AdbExecutable -RequestedPath $AdbPath
    $java = Resolve-Java21
    $gradlePath = Join-Path $projectRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlePath -PathType Leaf)) {
        throw "Gradle wrapper is required: $gradlePath"
    }

    $deviceList = Invoke-CapturedProcess `
        -FilePath $adb `
        -Arguments @("devices", "-l") `
        -TimeoutMilliseconds 60000
    Assert-ProcessSucceeded -Result $deviceList -Description "ADB device listing"
    $selectedDevicePresent = $false
    foreach ($line in ($deviceList.StdOut -split "`r?`n")) {
        if ($line -match '^(\S+)\s+device(?:\s|$)' -and $Matches[1] -ceq $Serial) {
            $selectedDevicePresent = $true
            break
        }
    }
    if (-not $selectedDevicePresent) {
        throw "Selected serial '$Serial' is not an attached ADB device."
    }

    $env:JAVA_HOME = $java.Home
    if (-not $SkipBuild) {
        Invoke-Gradle `
            -GradlePath $gradlePath `
            -ProjectRoot $projectRoot `
            -Arguments @(
                "--no-daemon",
                "--max-workers=1",
                ":app:testReleaseUnitTest",
                ":app:lintDebug",
                ":app:assembleDebug"
            )
        Invoke-Gradle `
            -GradlePath $gradlePath `
            -ProjectRoot $projectRoot `
            -Arguments @(
                "--no-daemon",
                "--max-workers=1",
                "-Pmanuscript.testBuildType=debug",
                ":app:assembleDebugAndroidTest"
            )
    }

    foreach ($apkPath in @($targetApkPath, $testApkPath)) {
        if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
            throw "Required APK is missing: $apkPath"
        }
    }

    $targetInstall = Invoke-CapturedProcess `
        -FilePath $adb `
        -Arguments @("-s", $Serial, "install", "-r", $targetApkPath) `
        -TimeoutMilliseconds 300000
    Assert-ProcessSucceeded -Result $targetInstall -Description "Target APK install"

    $testInstall = Invoke-CapturedProcess `
        -FilePath $adb `
        -Arguments @("-s", $Serial, "install", "-r", "-t", $testApkPath) `
        -TimeoutMilliseconds 300000
    Assert-ProcessSucceeded -Result $testInstall -Description "Test APK install"

    $reportFileName = "gpt56-$($Tone.ToLowerInvariant())-$RoundId.json"
    $reportDevicePath = "files/test-evidence/$reportFileName"
    $reportPreflightScript = "if [ -e $reportDevicePath ]; then printf exists; else printf absent; fi"
    $reportPreflight = Invoke-CapturedProcess `
        -FilePath $adb `
        -Arguments @(
            "-s",
            $Serial,
            "exec-out",
            "run-as",
            "com.remote.Manuscript.debug",
            "sh",
            "-c",
            $reportPreflightScript
        ) `
        -TimeoutMilliseconds 60000
    Assert-ProcessSucceeded -Result $reportPreflight -Description "Private device report preflight"
    $reportState = $reportPreflight.StdOut.Trim()
    if ($reportState -ceq "exists") {
        throw "Private device report already exists and was preserved: $reportDevicePath. Use a new round ID."
    }
    if ($reportState -cne "absent") {
        throw "Private device report preflight returned unexpected state '$reportState'."
    }

    $instrumentationArguments = @(
        "-s",
        $Serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-e",
        "class",
        "com.echolink.mobile.AllToneFullPageDeviceTest",
        "-e",
        "tone",
        $Tone,
        "-e",
        "roundId",
        $RoundId,
        "com.remote.Manuscript.debug.test/androidx.test.runner.AndroidJUnitRunner"
    )
    $instrumentationStartedAt = [DateTime]::UtcNow
    $instrumentationResult = Invoke-CapturedProcess `
        -FilePath $adb `
        -Arguments $instrumentationArguments `
        -TimeoutMilliseconds (35 * 60 * 1000)
    $instrumentationCompletedAt = [DateTime]::UtcNow
    Write-Utf8Text `
        -Path (Join-Path $roundPath "device-stdout.txt") `
        -Text $instrumentationResult.StdOut
    Write-Utf8Text `
        -Path (Join-Path $roundPath "device-stderr.txt") `
        -Text $instrumentationResult.StdErr
    Assert-ProcessSucceeded -Result $instrumentationResult -Description "Instrumentation"

    $reportPull = Invoke-CapturedProcess `
        -FilePath $adb `
        -Arguments @(
            "-s",
            $Serial,
            "exec-out",
            "run-as",
            "com.remote.Manuscript.debug",
            "cat",
            $reportDevicePath
        ) `
        -TimeoutMilliseconds 60000
    Assert-ProcessSucceeded -Result $reportPull -Description "Private device report pull"
    if ([string]::IsNullOrWhiteSpace($reportPull.StdOut)) {
        throw "Private device report is missing: $reportDevicePath"
    }

    $deviceReportPath = Join-Path $roundPath "device-report.json"
    Write-Utf8Text -Path $deviceReportPath -Text $reportPull.StdOut
    try {
        $report = $reportPull.StdOut | ConvertFrom-Json
    } catch {
        throw "Private device report is not valid JSON: $($_.Exception.Message)"
    }

    $reportRoundId = [string](Get-RequiredReportValue `
        -InputObject $report `
        -Name "round_id" `
        -Context "Report")
    if ($reportRoundId -cne $RoundId) {
        throw "Report round_id '$reportRoundId' does not equal requested round '$RoundId'."
    }
    $reportResults = @(Get-RequiredReportValue -InputObject $report -Name "results" -Context "Report")
    if ($reportResults.Count -eq 1) {
        $durationProperty = $reportResults[0].PSObject.Properties |
            Where-Object { $_.Name -ceq "duration_ms" } |
            Select-Object -First 1
        if ($null -ne $durationProperty) {
            $deviceDurationMs = [long]$durationProperty.Value
        }
    }

    $gate = Test-Gpt56PreGradeGate -Report $report -Tone $Tone
    $output = [string]$reportResults[0].output
    $outputPath = Join-Path $roundPath "output.md"
    Write-Utf8Text -Path $outputPath -Text $output

    $promptSourceHashes = [ordered]@{}
    foreach ($relativePath in $promptSourcePaths) {
        $fullPath = Join-Path $projectRoot ($relativePath.Replace('/', '\'))
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "Prompt source file is missing: $relativePath"
        }
        $promptSourceHashes[$relativePath] = Get-Gpt56Sha256 -Path $fullPath
    }

    $completedAt = [DateTime]::UtcNow
    $manifest = [pscustomobject][ordered]@{
        schema_version = 1
        status = "passed"
        tone = $Tone
        round_id = $RoundId
        serial = $Serial
        git_commit = Get-GitCommit -ProjectRoot $projectRoot
        sealed_source_hashes = [pscustomobject][ordered]@{
            corpus = [string]$sealedCampaign.sha256.corpus
            source = [string]$sealedCampaign.sha256.source
            reference = [string]$sealedCampaign.sha256.reference
            rubric = [string]$sealedCampaign.sha256.rubric
        }
        apk_hashes = [pscustomobject][ordered]@{
            target = Get-Gpt56Sha256 -Path $targetApkPath
            test = Get-Gpt56Sha256 -Path $testApkPath
        }
        prompt_source_file_hashes = [pscustomobject]$promptSourceHashes
        device_report_sha256 = Get-Gpt56Sha256 -Path $deviceReportPath
        output_sha256 = $gate.OutputSha256
        output_nonempty = -not [string]::IsNullOrWhiteSpace($output)
        device_duration_ms = $deviceDurationMs
        host_instrumentation_duration_ms = [long]$instrumentationResult.DurationMs
        model = [pscustomobject][ordered]@{
            ai_file = Get-RequiredReportValue -InputObject $report -Name "ai_file" -Context "Report"
            ai_tier = Get-RequiredReportValue -InputObject $report -Name "ai_tier" -Context "Report"
            backend = Get-RequiredReportValue -InputObject $report -Name "backend" -Context "Report"
            quality_mode = Get-RequiredReportValue -InputObject $report -Name "quality_mode" -Context "Report"
        }
        device = [pscustomobject][ordered]@{
            manufacturer = Get-RequiredReportValue -InputObject $report -Name "device_manufacturer" -Context "Report"
            model = Get-RequiredReportValue -InputObject $report -Name "device_model" -Context "Report"
            android_api = Get-RequiredReportValue -InputObject $report -Name "android_api" -Context "Report"
            supported_abis = @(Get-RequiredReportValue -InputObject $report -Name "supported_abis" -Context "Report")
        }
        timestamps = [pscustomobject][ordered]@{
            round_started_at_utc = $roundStartedAt.ToString("o")
            instrumentation_started_at_utc = $instrumentationStartedAt.ToString("o")
            instrumentation_completed_at_utc = $instrumentationCompletedAt.ToString("o")
            completed_at_utc = $completedAt.ToString("o")
        }
    }

    $graderInputPath = Join-Path $roundPath "grader-input.json"
    New-Gpt56GraderInput `
        -ProjectRoot $projectRoot `
        -Report $report `
        -OutputPath $graderInputPath | Out-Null
    Write-Utf8Json -Path (Join-Path $roundPath "round-manifest.json") -Value $manifest
    Write-Output "PASS: $roundPath"
} catch {
    $failure = $_
    if ($roundCreated -and -not [string]::IsNullOrWhiteSpace($roundPath)) {
        $artifactHashes = [ordered]@{}
        Add-ExistingArtifactHash -Hashes $artifactHashes -Name "round_claim" -Path $roundClaimPath
        Add-ExistingArtifactHash -Hashes $artifactHashes -Name "device_stdout" -Path (Join-Path $roundPath "device-stdout.txt")
        Add-ExistingArtifactHash -Hashes $artifactHashes -Name "device_stderr" -Path (Join-Path $roundPath "device-stderr.txt")
        Add-ExistingArtifactHash -Hashes $artifactHashes -Name "device_report" -Path $deviceReportPath
        Add-ExistingArtifactHash -Hashes $artifactHashes -Name "output" -Path $outputPath
        Add-ExistingArtifactHash -Hashes $artifactHashes -Name "grader_input" -Path $graderInputPath

        $sealedSourceHashes = [ordered]@{}
        if ($null -ne $sealedCampaign) {
            foreach ($name in @("corpus", "source", "reference", "rubric")) {
                $property = $sealedCampaign.sha256.PSObject.Properties |
                    Where-Object { $_.Name -ceq $name } |
                    Select-Object -First 1
                if ($null -ne $property) {
                    $sealedSourceHashes[$name] = [string]$property.Value
                }
            }
        }

        $apkHashes = [ordered]@{}
        Add-ExistingArtifactHash -Hashes $apkHashes -Name "target" -Path $targetApkPath
        Add-ExistingArtifactHash -Hashes $apkHashes -Name "test" -Path $testApkPath

        $failedPromptHashes = [ordered]@{}
        foreach ($relativePath in $promptSourcePaths) {
            $fullPath = Join-Path $projectRoot ($relativePath.Replace('/', '\'))
            Add-ExistingArtifactHash -Hashes $failedPromptHashes -Name $relativePath -Path $fullPath
        }

        $outputNonempty = $false
        $outputHash = $null
        if (-not [string]::IsNullOrWhiteSpace($outputPath) -and (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
            $frozenOutput = [System.IO.File]::ReadAllText($outputPath)
            $outputNonempty = -not [string]::IsNullOrWhiteSpace($frozenOutput)
            $outputHash = Get-Gpt56Sha256 -Path $outputPath
        }
        $deviceReportHash = $null
        if (-not [string]::IsNullOrWhiteSpace($deviceReportPath) -and (Test-Path -LiteralPath $deviceReportPath -PathType Leaf)) {
            $deviceReportHash = Get-Gpt56Sha256 -Path $deviceReportPath
        }

        $failedManifest = [pscustomobject][ordered]@{
            schema_version = 1
            status = "failed"
            tone = $Tone
            round_id = $RoundId
            serial = $Serial
            error = $failure.Exception.Message
            sealed_source_hashes = [pscustomobject]$sealedSourceHashes
            apk_hashes = [pscustomobject]$apkHashes
            prompt_source_file_hashes = [pscustomobject]$failedPromptHashes
            artifact_hashes = [pscustomobject]$artifactHashes
            device_report_sha256 = $deviceReportHash
            output_sha256 = $outputHash
            output_nonempty = $outputNonempty
            device_duration_ms = $deviceDurationMs
            host_instrumentation_duration_ms = if ($null -ne $instrumentationResult) {
                [long]$instrumentationResult.DurationMs
            } else {
                $null
            }
            instrumentation_exit_code = if ($null -ne $instrumentationResult) {
                [int]$instrumentationResult.ExitCode
            } else {
                $null
            }
            timestamps = [pscustomobject][ordered]@{
                round_started_at_utc = $roundStartedAt.ToString("o")
                instrumentation_started_at_utc = if ($null -ne $instrumentationStartedAt) {
                    $instrumentationStartedAt.ToString("o")
                } else {
                    $null
                }
                instrumentation_completed_at_utc = if ($null -ne $instrumentationCompletedAt) {
                    $instrumentationCompletedAt.ToString("o")
                } else {
                    $null
                }
                failed_at_utc = [DateTime]::UtcNow.ToString("o")
            }
        }
        try {
            Write-Utf8Json `
                -Path (Join-Path $roundPath "round-manifest.json") `
                -Value $failedManifest
        } catch {
            Write-Error "Failed to write failed round manifest: $($_.Exception.Message)"
        }
    }
    throw $failure
} finally {
    Remove-Module Gpt56QualityLoop -ErrorAction SilentlyContinue
}
