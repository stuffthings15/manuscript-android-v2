param(
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $projectRoot 'build\reports\security\manuscript-dependencies.cdx.json'
}

$lockFiles = @(
    (Join-Path $projectRoot 'gradle.lockfile'),
    (Join-Path $projectRoot 'app\gradle.lockfile'),
    (Join-Path $projectRoot 'llama-runtime\gradle.lockfile')
)

$coordinates = foreach ($lockFile in $lockFiles) {
    if (-not (Test-Path -LiteralPath $lockFile)) {
        throw "Dependency lockfile is missing: $lockFile"
    }
    foreach ($line in Get-Content -LiteralPath $lockFile) {
        if ($line.StartsWith('#') -or [string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $coordinate = $line.Split('=')[0]
        $parts = $coordinate.Split(':')
        if ($parts.Count -eq 3 -and $parts[2] -ne 'empty') {
            [pscustomobject]@{
                Group = $parts[0]
                Name = $parts[1]
                Version = $parts[2]
            }
        }
    }
}

$components = $coordinates |
    Sort-Object Group, Name, Version -Unique |
    ForEach-Object {
        $namespace = $_.Group.Replace('.', '/')
        $purl = "pkg:maven/$namespace/$($_.Name)@$($_.Version)"
        [ordered]@{
            type = 'library'
            group = $_.Group
            name = $_.Name
            version = $_.Version
            'bom-ref' = $purl
            purl = $purl
        }
    }

$versionText = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'app\build.gradle')
$versionMatch = [regex]::Match($versionText, 'versionName\s+"([^"]+)"')
$appVersion = if ($versionMatch.Success) { $versionMatch.Groups[1].Value } else { 'unknown' }

$bom = [ordered]@{
    bomFormat = 'CycloneDX'
    specVersion = '1.5'
    version = 1
    metadata = [ordered]@{
        timestamp = [DateTime]::UtcNow.ToString('o')
        component = [ordered]@{
            type = 'application'
            group = 'com.remote'
            name = 'Manuscript Android Version 2'
            version = $appVersion
            purl = "pkg:apk/com.remote.Manuscript.v2@$appVersion"
        }
        tools = @(
            [ordered]@{
                vendor = 'Manuscript'
                name = 'generate_dependency_sbom.ps1'
            }
        )
    }
    components = @($components)
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$bom | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $OutputPath -Encoding UTF8

Write-Output $OutputPath
