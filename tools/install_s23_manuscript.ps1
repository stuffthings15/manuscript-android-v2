param(
    [Parameter(Mandatory)]
    [ValidateSet('debug', 'release')]
    [string]$Variant,

    [string]$Serial = 'R5CW20R46WD',

    [string]$ExpectedModel = 'SM-S916U1',

    [string]$ApkPath,

    [string]$ReceiptPath,

    [switch]$AllowDisabledAccessibilityBeforeInstall
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $PSScriptRoot 'ManuscriptS23InstallPolicy.psm1') -Force

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:JAVA_HOME = @(
        'C:\Program Files\Android\openjdk\jdk-21.0.8',
        'C:\Users\stuff\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
    ) | Where-Object {
        Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')
    } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME is required to inspect the APK.'
}

$sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) {
    throw 'Android build tools are missing.'
}
$apkSignerTool = Join-Path $buildTools.FullName 'apksigner.bat'
$apkanalyzerCandidates = @(
    (Join-Path $sdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'),
    (Join-Path $sdkRoot 'tools\bin\apkanalyzer.bat')
)
$apkanalyzer = $apkanalyzerCandidates |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

foreach ($requiredTool in @($adb, $apkSignerTool, $apkanalyzer)) {
    if ([string]::IsNullOrWhiteSpace($requiredTool) -or
        -not (Test-Path -LiteralPath $requiredTool)) {
        throw "Required Android tool is missing: $requiredTool"
    }
}

$expectedPackage = if ($Variant -eq 'debug') {
    'com.remote.Manuscript.debug'
} else {
    'com.remote.Manuscript'
}
$oppositePackage = if ($Variant -eq 'debug') {
    'com.remote.Manuscript'
} else {
    'com.remote.Manuscript.debug'
}
$expectedService = "$expectedPackage/com.echolink.mobile.ManuscriptAccessibilityService"
$defaultApk = if ($Variant -eq 'debug') {
    Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
} else {
    Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
}
$resolvedApk = if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $defaultApk
} else {
    $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ApkPath)
}
$resolvedReceipt = if ([string]::IsNullOrWhiteSpace($ReceiptPath)) {
    Join-Path $projectRoot 'evidence\device\s23\install-receipt.json'
} else {
    $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ReceiptPath)
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $adb @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "ADB failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    [pscustomobject]@{
        ExitCode = $exitCode
        Text = ($output -join "`n").Trim()
    }
}

function Get-StableSecureSetting {
    param(
        [Parameter(Mandatory)][string]$Key,
        [Parameter(Mandatory)][AllowEmptyString()][string]$ExpectedValue
    )
    $previous = $null
    for ($attempt = 0; $attempt -lt 12; $attempt += 1) {
        $current = (
            Invoke-Adb -Arguments @(
                '-s', $Serial, 'shell', 'settings', 'get', 'secure', $Key
            )
        ).Text
        if (
            $null -ne $previous -and
            $current -eq $previous -and
            $current -eq $ExpectedValue
        ) {
            return $current
        }
        $previous = $current
        Start-Sleep -Milliseconds 500
    }
    throw "Secure setting did not settle after install: $Key"
}

function Get-ApkSigner {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$SignerTool
    )
    $output = @(& $SignerTool verify --verbose --print-certs $Path 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed: $Path"
    }
    $match = [regex]::Match(
        ($output -join "`n"),
        'certificate SHA-256 digest:\s*([0-9A-F]+)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    if (-not $match.Success) {
        throw "APK signer digest is missing: $Path"
    }
    $match.Groups[1].Value.ToUpperInvariant()
}

function Get-InstalledPackageFacts {
    param(
        [Parameter(Mandatory)][string]$Package,
        [Parameter(Mandatory)][string]$SignerTool
    )
    $pathResult = Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $Package) -AllowFailure
    if ($pathResult.ExitCode -ne 0 -or $pathResult.Text -notmatch '^package:(.+base\.apk)') {
        return [pscustomobject]@{
            Exists = $false
            Package = $Package
            Signer = ''
            VersionName = ''
            VersionCode = ''
            UpdateTime = ''
        }
    }

    $remoteApk = $matches[1].Trim()
    $tempDirectory = Join-Path $env:TEMP "manuscript-installed-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    try {
        $localApk = Join-Path $tempDirectory 'base.apk'
        Invoke-Adb -Arguments @('-s', $Serial, 'pull', $remoteApk, $localApk) | Out-Null
        $signer = Get-ApkSigner -Path $localApk -SignerTool $SignerTool
    } finally {
        if ($tempDirectory.StartsWith(
            [System.IO.Path]::GetFullPath($env:TEMP),
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            Remove-Item -LiteralPath $tempDirectory -Recurse -Force
        }
    }

    $dump = (Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'package', $Package)).Text
    $versionName = [regex]::Match($dump, '(?m)^\s*versionName=(.+)$').Groups[1].Value.Trim()
    $versionCode = [regex]::Match($dump, '(?m)^\s*versionCode=(\d+)').Groups[1].Value
    $updateTime = [regex]::Match($dump, '(?m)^\s*lastUpdateTime=(.+)$').Groups[1].Value.Trim()
    [pscustomobject]@{
        Exists = $true
        Package = $Package
        Signer = $signer
        VersionName = $versionName
        VersionCode = $versionCode
        UpdateTime = $updateTime
    }
}

$startedAt = [DateTimeOffset]::Now
$deviceLines = @(& $adb devices)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not list Android devices.'
}
$devices = @(
    $deviceLines |
        Select-Object -Skip 1 |
        ForEach-Object {
            if ($_ -match '^([^\s]+)\s+device$') { $matches[1] }
        }
)
$actualModel = (Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')).Text

$apkExists = Test-Path -LiteralPath $resolvedApk
$apkPackage = ''
$apkVersionName = ''
$apkVersionCode = ''
$apkSigner = ''
if ($apkExists) {
    $apkPackage = (& $apkanalyzer manifest application-id $resolvedApk).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the APK package.' }
    $apkVersionName = (& $apkanalyzer manifest version-name $resolvedApk).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the APK version name.' }
    $apkVersionCode = (& $apkanalyzer manifest version-code $resolvedApk).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the APK version code.' }
    $apkSigner = Get-ApkSigner -Path $resolvedApk -SignerTool $apkSignerTool
}

$preAccessibility = (
    Invoke-Adb -Arguments @(
        '-s', $Serial, 'shell', 'settings', 'get', 'secure',
        'enabled_accessibility_services'
    )
).Text
$preKeyboard = (
    Invoke-Adb -Arguments @(
        '-s', $Serial, 'shell', 'settings', 'get', 'secure',
        'default_input_method'
    )
).Text
$installedBefore = Get-InstalledPackageFacts `
    -Package $expectedPackage `
    -SignerTool $apkSignerTool
$oppositeInstalled = (
    Get-InstalledPackageFacts -Package $oppositePackage -SignerTool $apkSignerTool
).Exists

Test-ManuscriptS23Preflight `
    -Devices $devices `
    -Serial $Serial `
    -ExpectedModel $ExpectedModel `
    -ActualModel $actualModel `
    -ApkExists $apkExists `
    -ApkPackage $apkPackage `
    -ExpectedPackage $expectedPackage `
    -ApkSigner $apkSigner `
    -InstalledTargetExists $installedBefore.Exists `
    -InstalledSigner $installedBefore.Signer `
    -OppositePackageInstalled $oppositeInstalled `
    -AccessibilityServices $preAccessibility `
    -ExpectedAccessibilityService $expectedService `
    -RequireAccessibilityEnabled (-not $AllowDisabledAccessibilityBeforeInstall)

$dataDirectoryBefore = if ($installedBefore.Exists) {
    (Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'run-as', $expectedPackage, 'pwd') -AllowFailure).Text
} else {
    ''
}
$localAiStateBefore = if ($installedBefore.Exists) {
    (
        Invoke-Adb -Arguments @(
            '-s', $Serial, 'shell', 'run-as', $expectedPackage, 'sh', '-c',
            'if [ -d files/models ]; then echo present; else echo absent; fi'
        ) -AllowFailure
    ).Text
} else {
    'not-installed'
}

$packagesToStop = @(
    'com.remote.Manuscript',
    'com.remote.Manuscript.debug',
    'com.remote.Manuscript.debug.test'
)
foreach ($package in $packagesToStop) {
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'force-stop', $package) | Out-Null
}
$stoppedProcessIds = @{}
foreach ($package in $packagesToStop) {
    $stoppedProcessIds[$package] = (
        Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'pidof', $package) -AllowFailure
    ).Text
}
Test-ManuscriptStoppedProcesses -ProcessIds $stoppedProcessIds

$installResult = Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', '-t', $resolvedApk) -AllowFailure
if ($installResult.Text -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    throw 'INSTALL_FAILED_UPDATE_INCOMPATIBLE. Signer or package conflict. No uninstall was attempted.'
}
if ($installResult.ExitCode -ne 0 -or $installResult.Text -notmatch '(?i)success') {
    throw "Replacement install failed. No uninstall was attempted.`n$($installResult.Text)"
}

$installedAfter = Get-InstalledPackageFacts `
    -Package $expectedPackage `
    -SignerTool $apkSignerTool
Invoke-Adb -Arguments @(
    '-s', $Serial, 'shell', 'monkey', '-p', $expectedPackage,
    '-c', 'android.intent.category.LAUNCHER', '1'
) | Out-Null
$processIdAfter = (
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'pidof', $expectedPackage) -AllowFailure
).Text
$postAccessibility = Get-StableSecureSetting `
    -Key 'enabled_accessibility_services' `
    -ExpectedValue $preAccessibility
$postKeyboard = Get-StableSecureSetting `
    -Key 'default_input_method' `
    -ExpectedValue $preKeyboard

Test-ManuscriptS23Postflight `
    -PreAccessibility $preAccessibility `
    -PostAccessibility $postAccessibility `
    -PreKeyboard $preKeyboard `
    -PostKeyboard $postKeyboard `
    -ExpectedPackage $expectedPackage `
    -InstalledPackage $installedAfter.Package `
    -ExpectedSigner $apkSigner `
    -InstalledSigner $installedAfter.Signer `
    -ProcessId $processIdAfter

$gitCommit = (& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the Git commit.' }
$receipt = [ordered]@{
    schema = 'manuscript-s23-install-receipt-v1'
    startedAt = $startedAt.ToString('o')
    completedAt = [DateTimeOffset]::Now.ToString('o')
    serial = $Serial
    model = $actualModel
    variant = $Variant
    package = $expectedPackage
    versionName = $apkVersionName
    versionCode = $apkVersionCode
    signerSha256 = $apkSigner
    apkPath = $resolvedApk
    apkSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedApk).Hash
    commit = $gitCommit
    installedUpdateTime = $installedAfter.UpdateTime
    dataDirectoryExistedBefore = -not [string]::IsNullOrWhiteSpace($dataDirectoryBefore)
    localAiDirectoryStateBefore = $localAiStateBefore
    accessibilityBefore = $preAccessibility
    accessibilityAfter = $postAccessibility
    keyboardBefore = $preKeyboard
    keyboardAfter = $postKeyboard
    processIdAfter = $processIdAfter
}
$receiptDirectory = Split-Path -Parent $resolvedReceipt
New-Item -ItemType Directory -Force -Path $receiptDirectory | Out-Null
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resolvedReceipt -Encoding utf8

Write-Output $resolvedReceipt
