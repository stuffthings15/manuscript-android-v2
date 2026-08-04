Set-StrictMode -Version Latest

function Test-ManuscriptS23Preflight {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][string[]]$Devices,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$ExpectedModel,
        [Parameter(Mandatory)][string]$ActualModel,
        [Parameter(Mandatory)][bool]$ApkExists,
        [Parameter(Mandatory)][string]$ApkPackage,
        [Parameter(Mandatory)][string]$ExpectedPackage,
        [Parameter(Mandatory)][string]$ApkSigner,
        [Parameter(Mandatory)][bool]$InstalledTargetExists,
        [AllowEmptyString()][string]$InstalledSigner,
        [Parameter(Mandatory)][bool]$OppositePackageInstalled,
        [AllowEmptyString()][string]$AccessibilityServices,
        [Parameter(Mandatory)][string]$ExpectedAccessibilityService,
        [bool]$RequireAccessibilityEnabled = $true
    )

    if ($Devices.Count -ne 1) {
        throw "Exactly one authorized Android device must be connected."
    }
    if ($Devices[0] -ne $Serial) {
        throw "Connected device $($Devices[0]) is not the authorized S23 Plus $Serial."
    }
    if ($ActualModel -ne $ExpectedModel) {
        throw "Device model $ActualModel does not match $ExpectedModel."
    }
    if (-not $ApkExists) {
        throw "The requested APK is missing."
    }
    if ($ApkPackage -ne $ExpectedPackage) {
        throw "APK package $ApkPackage does not match $ExpectedPackage."
    }
    if ([string]::IsNullOrWhiteSpace($ApkSigner)) {
        throw "The APK signer could not be verified."
    }
    if ($OppositePackageInstalled -and -not $InstalledTargetExists) {
        throw "The opposite Manuscript variant is installed. Stop and confirm package intent."
    }
    if ($InstalledTargetExists -and $InstalledSigner -ne $ApkSigner) {
        throw "The installed signer does not match the replacement APK signer."
    }
    $enabledServices = @(
        $AccessibilityServices -split ':' |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if (
        $RequireAccessibilityEnabled -and
        $ExpectedAccessibilityService -notin $enabledServices
    ) {
        throw "Manuscript accessibility must already be enabled before a replacement update."
    }
}

function Test-ManuscriptStoppedProcesses {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$ProcessIds
    )

    $alive = @(
        $ProcessIds.GetEnumerator() |
            Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.Value) } |
            ForEach-Object { "$($_.Key)=$($_.Value)" }
    )
    if ($alive.Count -gt 0) {
        throw "Manuscript process still alive after force-stop: $($alive -join ', ')"
    }
}

function Test-ManuscriptS23Postflight {
    [CmdletBinding()]
    param(
        [AllowEmptyString()][string]$PreAccessibility,
        [AllowEmptyString()][string]$PostAccessibility,
        [AllowEmptyString()][string]$PreKeyboard,
        [AllowEmptyString()][string]$PostKeyboard,
        [Parameter(Mandatory)][string]$ExpectedPackage,
        [Parameter(Mandatory)][string]$InstalledPackage,
        [Parameter(Mandatory)][string]$ExpectedSigner,
        [Parameter(Mandatory)][string]$InstalledSigner,
        [AllowEmptyString()][string]$ProcessId
    )

    if ($InstalledPackage -ne $ExpectedPackage) {
        throw "Installed package does not match the requested package."
    }
    if ($InstalledSigner -ne $ExpectedSigner) {
        throw "Installed signer changed during replacement install."
    }
    if ($PostAccessibility -ne $PreAccessibility) {
        throw "Accessibility authorization changed during replacement install."
    }
    if ($PostKeyboard -ne $PreKeyboard) {
        throw "The selected keyboard changed during replacement install."
    }
    if ([string]::IsNullOrWhiteSpace($ProcessId)) {
        throw "Manuscript did not start after replacement install."
    }
}

Export-ModuleMember -Function `
    Test-ManuscriptS23Preflight, `
    Test-ManuscriptStoppedProcesses, `
    Test-ManuscriptS23Postflight
