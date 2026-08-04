param(
    [string]$Serial = 'R5CW20R46WD',
    [int]$Runs = 3
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Runs -ne 3) {
    throw 'The frozen S23 release gate requires exactly three runs.'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$targetApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$evidenceRoot = Join-Path $projectRoot 'evidence\device\s23'
$loopRoot = Join-Path $evidenceRoot 'loops'
$targetPackage = 'com.remote.Manuscript.debug'
$testPackage = 'com.remote.Manuscript.debug.test'
$service = "$targetPackage/com.echolink.mobile.ManuscriptAccessibilityService"
$testClass = 'com.echolink.mobile.S23ProductContractDeviceTest'
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"

foreach ($path in @($adb, $targetApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required S23 acceptance input is missing: $path"
    }
}
New-Item -ItemType Directory -Force -Path $loopRoot | Out-Null

function Invoke-AdbText {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $adb @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) {
        throw "ADB failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    ($output -join "`n").Trim()
}

function Get-WorkspaceFingerprint {
    $paths = @(
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src') -Recurse -File
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tools') -Recurse -File
        Get-Item -LiteralPath (Join-Path $projectRoot 'app\build.gradle')
        Get-Item -LiteralPath (Join-Path $projectRoot 'docs\product-contract\manuscript-android-v1.json')
    ) | Sort-Object FullName -Unique
    $lines = foreach ($path in $paths) {
        $relative = $path.FullName.Substring($projectRoot.Length).TrimStart('\')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path.FullName).Hash
        "$relative=$hash"
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($lines -join "`n")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        -join ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') })
    } finally {
        $sha.Dispose()
    }
}

$devices = @(
    Invoke-AdbText -Arguments @('devices') -split "`n" |
        Select-Object -Skip 1 |
        ForEach-Object {
            if ($_ -match '^([^\s]+)\s+device$') { $matches[1] }
        }
)
if ($devices.Count -ne 1 -or $devices[0] -ne $Serial) {
    throw 'Exactly the authorized S23 Plus must be attached.'
}
$model = Invoke-AdbText -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')
if ($model -ne 'SM-S916U1') {
    throw "Unexpected physical device model: $model"
}
$enabledBefore = Invoke-AdbText -Arguments @(
    '-s', $Serial, 'shell', 'settings', 'get', 'secure',
    'enabled_accessibility_services'
)
if ($service -notin ($enabledBefore -split ':')) {
    throw 'Enable Manuscript in Samsung Installed apps before the three-run gate.'
}
$keyboardBefore = Invoke-AdbText -Arguments @(
    '-s', $Serial, 'shell', 'settings', 'get', 'secure',
    'default_input_method'
)
$targetApkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetApk).Hash
$testApkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash
$workspaceFingerprint = Get-WorkspaceFingerprint
$commit = (& git -C $projectRoot rev-parse HEAD).Trim()
$results = @()

for ($run = 1; $run -le $Runs; $run += 1) {
    if ((Get-WorkspaceFingerprint) -ne $workspaceFingerprint) {
        throw "Source changed before S23 run $run."
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $targetApk).Hash -ne $targetApkHash) {
        throw "Target APK changed before S23 run $run."
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash -ne $testApkHash) {
        throw "Test APK changed before S23 run $run."
    }

    $startedAt = [DateTimeOffset]::Now
    $output = Invoke-AdbText -Arguments @(
        '-s', $Serial, 'shell', 'am', 'instrument',
        '-w', '-r', '-e', 'class', $testClass, $runner
    )
    $outputPath = Join-Path $loopRoot "run-$run.txt"
    $output | Set-Content -LiteralPath $outputPath -Encoding utf8
    if ($output -notmatch '(?m)^OK \(1 test\)$') {
        throw "S23 product-contract run $run did not pass.`n$output"
    }

    $remoteScreenshot = "/sdcard/manuscript-s23-run-$run.png"
    Invoke-AdbText -Arguments @(
        '-s', $Serial, 'shell', 'screencap', '-p', $remoteScreenshot
    ) | Out-Null
    Invoke-AdbText -Arguments @(
        '-s', $Serial, 'pull', $remoteScreenshot,
        (Join-Path $loopRoot "run-$run.png")
    ) | Out-Null

    $results += [ordered]@{
        run = $run
        startedAt = $startedAt.ToString('o')
        completedAt = [DateTimeOffset]::Now.ToString('o')
        passed = $true
        output = $outputPath.Substring($projectRoot.Length).TrimStart('\')
    }
}

$enabledAfter = Invoke-AdbText -Arguments @(
    '-s', $Serial, 'shell', 'settings', 'get', 'secure',
    'enabled_accessibility_services'
)
$keyboardAfter = Invoke-AdbText -Arguments @(
    '-s', $Serial, 'shell', 'settings', 'get', 'secure',
    'default_input_method'
)
if ($enabledAfter -ne $enabledBefore) {
    throw 'Accessibility services changed during the three-run gate.'
}
if ($keyboardAfter -ne $keyboardBefore) {
    throw 'The selected keyboard changed during the three-run gate.'
}

$manifest = [ordered]@{
    schema = 'manuscript-s23-three-run-gate-v1'
    contract = 'manuscript-android-v1'
    serial = $Serial
    model = $model
    package = $targetPackage
    commit = $commit
    workspaceFingerprint = $workspaceFingerprint
    targetApkSha256 = $targetApkHash
    testApkSha256 = $testApkHash
    accessibilityBefore = $enabledBefore
    accessibilityAfter = $enabledAfter
    keyboardBefore = $keyboardBefore
    keyboardAfter = $keyboardAfter
    runs = $results
}
$manifestPath = Join-Path $evidenceRoot 'three-run-manifest.json'
$manifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Output $manifestPath
