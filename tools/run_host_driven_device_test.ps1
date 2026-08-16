<#
.SYNOPSIS
Runs the review-lifecycle device tests that need a touch from OUTSIDE the app.

.DESCRIPTION
Three tests in ReviewSurfaceLifecycleDeviceTest close the review with a
"host-driven physical close": the test makes Close visible, waits for its touch
bounds to stay identical across two draw frames, logs

    event=host-touch-ready;phase=..;cycle=..;x=..;y=..;elapsed_ms=..

and then waits up to 30 seconds for the card to disappear. It never taps
anything itself, on purpose. The contract under test is that a real touch,
delivered by the system input pipeline from outside the app process, closes the
review. An injected in-process event would not prove that.

Nothing in either tree ever delivered that touch. Both this tree and the
Manuscript/Android archive ship the same run_persistent_device_test.ps1, which
only calls "am instrument", so those three tests could not pass under any
runner that existed. They were failing for want of a driver, not because the
product was broken. This script is that driver.

It streams logcat, and every time the device says it is ready it taps the
reported coordinates with "adb shell input tap", which enters through the same
system input path a finger uses.

.PARAMETER Serial
Device serial, e.g. RFCRA0A6CFH.

.PARAMETER TestClass
Test class, or Class#method to run one test.

.PARAMETER CloseDelayMs
Passed to the startup-phase test as -e closeDelayMs. The test accepts 0, 100,
500 or 1000 and rejects anything else.

.EXAMPLE
.\tools\run_host_driven_device_test.ps1 -Serial RFCRA0A6CFH
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [string]$TestClass = "com.echolink.mobile.ReviewSurfaceLifecycleDeviceTest",

    [ValidateSet(0, 100, 500, 1000)]
    [int]$CloseDelayMs = 0
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

& $adb -s $Serial install -r -t $targetApk
if ($LASTEXITCODE -ne 0) { throw "Could not update the app under test." }
& $adb -s $Serial install -r -t $testApk
if ($LASTEXITCODE -ne 0) { throw "Could not update the test app." }

# Start from an empty buffer so the tapper cannot act on coordinates logged by
# an earlier run, which would tap a screen that has moved on.
& $adb -s $Serial logcat -c

$tapper = Start-Job -ScriptBlock {
    param($adbPath, $deviceSerial)
    # -s narrows the stream to the review tag, so the loop is not parsing the
    # whole system log looking for one line.
    & $adbPath -s $deviceSerial logcat -s ManuscriptReview:I | ForEach-Object {
        if ($_ -match "event=host-touch-ready;.*;x=(\d+);y=(\d+);") {
            $x = $Matches[1]
            $y = $Matches[2]
            Write-Output "tap $x $y"
            & $adbPath -s $deviceSerial shell input tap $x $y
        }
    }
} -ArgumentList $adb, $Serial

try {
    $arguments = @(
        "-s", $Serial,
        "shell", "am", "instrument",
        "-w", "-r",
        "-e", "class", $TestClass,
        "-e", "closeDelayMs", $CloseDelayMs.ToString(),
        "com.remote.Manuscript.v2.debug.test/androidx.test.runner.AndroidJUnitRunner"
    )
    & $adb @arguments
    $instrumentExit = $LASTEXITCODE
} finally {
    # Report what the tapper did before disposing of it. A run that fails with
    # no taps recorded is a driver problem, not a product problem, and that
    # distinction is the whole reason this script exists.
    Receive-Job -Job $tapper -ErrorAction SilentlyContinue | ForEach-Object { Write-Output "HOST_TAP: $_" }
    Stop-Job -Job $tapper -ErrorAction SilentlyContinue
    Remove-Job -Job $tapper -Force -ErrorAction SilentlyContinue
}

if ($instrumentExit -ne 0) {
    throw "Device test failed."
}
