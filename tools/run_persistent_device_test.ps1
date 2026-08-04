param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$TestClass,

    [string]$Tone,

    [int]$ParagraphIndex
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$targetApk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $projectRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB was not found at $adb"
}
if (-not (Test-Path -LiteralPath $targetApk)) {
    throw "Build the debug APK before running this script."
}
if (-not (Test-Path -LiteralPath $testApk)) {
    throw "Build the debug Android test APK before running this script."
}

& $adb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Android device $Serial is not available."
}

& (Join-Path $PSScriptRoot 'install_s23_manuscript.ps1') `
    -Variant debug `
    -Serial $Serial `
    -ApkPath $targetApk
& $adb -s $Serial install -r -t $testApk
if ($LASTEXITCODE -ne 0) {
    throw "Could not update the test app."
}

$arguments = @(
    "-s", $Serial,
    "shell", "am", "instrument",
    "-w", "-r",
    "-e", "class", $TestClass
)
if (-not [string]::IsNullOrWhiteSpace($Tone)) {
    $arguments += @("-e", "tone", $Tone)
}
if ($ParagraphIndex -gt 0) {
    $arguments += @("-e", "paragraphIndex", $ParagraphIndex.ToString())
}
$arguments += "com.remote.Manuscript.v2.debug.test/androidx.test.runner.AndroidJUnitRunner"

& $adb @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Device test failed."
}
