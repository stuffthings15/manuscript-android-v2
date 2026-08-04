$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$policyPath = Join-Path $root 'tools\ManuscriptS23InstallPolicy.psm1'
$installerPath = Join-Path $root 'tools\install_s23_manuscript.ps1'

Describe 'Manuscript S23 safe installer' {
    BeforeAll {
        Import-Module $policyPath -Force
        $script:good = @{
            Devices = @('R5CW20R46WD')
            Serial = 'R5CW20R46WD'
            ExpectedModel = 'SM-S916U1'
            ActualModel = 'SM-S916U1'
            ApkExists = $true
            ApkPackage = 'com.remote.Manuscript.v2.debug'
            ExpectedPackage = 'com.remote.Manuscript.v2.debug'
            ApkSigner = 'ABC123'
            InstalledTargetExists = $true
            InstalledSigner = 'ABC123'
            OppositePackageInstalled = $false
            AccessibilityServices = 'com.remote.Manuscript.v2.debug/com.echolink.mobile.ManuscriptAccessibilityService'
            ExpectedAccessibilityService = 'com.remote.Manuscript.v2.debug/com.echolink.mobile.ManuscriptAccessibilityService'
        }
    }

    It 'rejects zero devices' {
        $facts = $good.Clone()
        $facts.Devices = @()
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects two devices' {
        $facts = $good.Clone()
        $facts.Devices = @('R5CW20R46WD', 'OTHER')
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects the wrong serial' {
        $facts = $good.Clone()
        $facts.Devices = @('OTHER')
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects the wrong model' {
        $facts = $good.Clone()
        $facts.ActualModel = 'SM-S918U1'
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects a missing APK' {
        $facts = $good.Clone()
        $facts.ApkExists = $false
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects an APK package mismatch' {
        $facts = $good.Clone()
        $facts.ApkPackage = 'com.echolink.manuscript'
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects a signer mismatch' {
        $facts = $good.Clone()
        $facts.InstalledSigner = 'DIFFERENT'
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects release installed while debug was requested' {
        $facts = $good.Clone()
        $facts.InstalledTargetExists = $false
        $facts.InstalledSigner = ''
        $facts.OppositePackageInstalled = $true
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'rejects accessibility being disabled before an update' {
        $facts = $good.Clone()
        $facts.AccessibilityServices = ''
        { Test-ManuscriptS23Preflight @facts } | Should Throw
    }

    It 'allows an explicit disabled-state recovery update without enabling access' {
        $facts = $good.Clone()
        $facts.AccessibilityServices = ''
        $facts.RequireAccessibilityEnabled = $false
        { Test-ManuscriptS23Preflight @facts } | Should Not Throw
    }

    It 'accepts one matching replacement update' {
        { Test-ManuscriptS23Preflight @good } | Should Not Throw
    }

    It 'rejects a process still alive after force-stop' {
        {
            Test-ManuscriptStoppedProcesses -ProcessIds @{
                'com.remote.Manuscript.v2' = ''
                'com.remote.Manuscript.v2.debug' = '4812'
                'com.remote.Manuscript.v2.debug.test' = ''
            }
        } | Should Throw
    }

    It 'rejects accessibility missing after install' {
        {
            Test-ManuscriptS23Postflight `
                -PreAccessibility 'service' `
                -PostAccessibility '' `
                -PreKeyboard 'gboard' `
                -PostKeyboard 'gboard' `
                -ExpectedPackage 'com.remote.Manuscript.v2.debug' `
                -InstalledPackage 'com.remote.Manuscript.v2.debug' `
                -ExpectedSigner 'ABC123' `
                -InstalledSigner 'ABC123' `
                -ProcessId '5500'
        } | Should Throw
    }

    It 'accepts an unchanged successful postflight' {
        {
            Test-ManuscriptS23Postflight `
                -PreAccessibility 'service' `
                -PostAccessibility 'service' `
                -PreKeyboard 'gboard' `
                -PostKeyboard 'gboard' `
                -ExpectedPackage 'com.remote.Manuscript.v2.debug' `
                -InstalledPackage 'com.remote.Manuscript.v2.debug' `
                -ExpectedSigner 'ABC123' `
                -InstalledSigner 'ABC123' `
                -ProcessId '5500'
        } | Should Not Throw
    }

    It 'contains no destructive package command and uses only replacement install flags' {
        $source = Get-Content -Raw -LiteralPath $installerPath
        $source | Should Not Match '(?i)\badb\s+uninstall\b'
        $source | Should Not Match '(?i)\bpm\s+uninstall\b'
        $source | Should Not Match '(?i)\bpm\s+clear\b'
        $source | Should Match 'install.+-r.+-t'
        $source | Should Match 'force-stop'
        $source | Should Match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'
    }
}
