[CmdletBinding()]
param(
    [string]$Serial = 'R5CW20R46WD',
    [string]$OutputPath = 'docs\qa\manuscript-current-state.json'
)

$ErrorActionPreference = 'Stop'
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$qaRoot = (Resolve-Path (Join-Path $androidRoot 'docs\qa')).Path
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $androidRoot $OutputPath))
if (-not $resolvedOutput.StartsWith(
    $qaRoot + [System.IO.Path]::DirectorySeparatorChar,
    [System.StringComparison]::OrdinalIgnoreCase
)) {
    throw "Baseline output must stay under $qaRoot"
}

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB not found at $adb"
}

$status = @(git -C $androidRoot status --porcelain=v1 -- .)
$installedPackages = @(
    & $adb -s $Serial shell pm list packages |
        Where-Object { $_ -match 'com\.remote\.Manuscript|com\.echolink\.manuscript' }
)
$enabledServices = (& $adb -s $Serial shell settings get secure enabled_accessibility_services).Trim()
$record = [ordered]@{
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    git = [ordered]@{
        branch = (git -C $androidRoot branch --show-current).Trim()
        head = (git -C $androidRoot rev-parse HEAD).Trim()
        modified = @($status | Where-Object { $_ -notmatch '^\?\?' })
        untracked = @($status | Where-Object { $_ -match '^\?\?' })
    }
    windowsBrand = [ordered]@{
        svgSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (
            Join-Path $androidRoot '..\Windows\resources\manuscript.svg'
        )).Hash
        pngSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (
            Join-Path $androidRoot '..\Windows\resources\manuscript.png'
        )).Hash
    }
    device = [ordered]@{
        serial = $Serial
        model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
        androidVersion = (& $adb -s $Serial shell getprop ro.build.version.release).Trim()
        accessibilityEnabled = (
            (& $adb -s $Serial shell settings get secure accessibility_enabled).Trim() -eq '1'
        )
        enabledAccessibilityServices = $enabledServices
        defaultInputMethod = (
            & $adb -s $Serial shell settings get secure default_input_method
        ).Trim()
        installedManuscriptPackages = $installedPackages
    }
}

$json = $record | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText(
    $resolvedOutput,
    $json + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
Write-Output $resolvedOutput
