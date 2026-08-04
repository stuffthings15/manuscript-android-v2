param(
    [string]$ReportPath = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $projectRoot 'build\reports\security\osv-advisories.json'
}

$lockFiles = @(
    (Join-Path $projectRoot 'gradle.lockfile'),
    (Join-Path $projectRoot 'app\gradle.lockfile'),
    (Join-Path $projectRoot 'llama-runtime\gradle.lockfile')
)

$packageMap = @{}
foreach ($lockFile in $lockFiles) {
    if (-not (Test-Path -LiteralPath $lockFile)) {
        throw "Dependency lockfile is missing: $lockFile"
    }
    foreach ($line in Get-Content -LiteralPath $lockFile) {
        if ($line.StartsWith('#') -or [string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $lockParts = $line.Split('=', 2)
        $coordinate = $lockParts[0]
        $parts = $coordinate.Split(':')
        if ($parts.Count -eq 3 -and $parts[2] -ne 'empty') {
            $name = "$($parts[0]):$($parts[1])"
            $version = $parts[2]
            $key = "$name`n$version"
            if (-not $packageMap.ContainsKey($key)) {
                $packageMap[$key] = [ordered]@{
                    Name = $name
                    Version = $version
                    Configurations = [System.Collections.Generic.HashSet[string]]::new(
                        [System.StringComparer]::Ordinal
                    )
                    LockFiles = [System.Collections.Generic.HashSet[string]]::new(
                        [System.StringComparer]::Ordinal
                    )
                }
            }
            $relativeLockFile = $lockFile.Replace("$projectRoot\", '')
            [void]$packageMap[$key].LockFiles.Add($relativeLockFile)
            if ($lockParts.Count -eq 2) {
                foreach ($configuration in $lockParts[1].Split(',')) {
                    if (-not [string]::IsNullOrWhiteSpace($configuration)) {
                        [void]$packageMap[$key].Configurations.Add($configuration)
                    }
                }
            }
        }
    }
}
$packages = @(
    $packageMap.Values | ForEach-Object {
        $configurations = @($_.Configurations | Sort-Object)
        $scope = if (
            $configurations -contains 'debugRuntimeClasspath' -or
            $configurations -contains 'releaseRuntimeClasspath'
        ) {
            'runtime'
        } elseif ($configurations -contains 'bundletoolCli') {
            'release-tool'
        } else {
            'build-test'
        }
        [pscustomobject]@{
            Name = $_.Name
            Version = $_.Version
            Scope = $scope
            Configurations = $configurations
            LockFiles = @($_.LockFiles | Sort-Object)
        }
    } | Sort-Object Name, Version
)

$queries = @(
    $packages | ForEach-Object {
        [ordered]@{
            package = [ordered]@{
                ecosystem = 'Maven'
                name = $_.Name
            }
            version = $_.Version
        }
    }
)

$response = Invoke-RestMethod `
    -Uri 'https://api.osv.dev/v1/querybatch' `
    -Method Post `
    -ContentType 'application/json' `
    -Body (@{ queries = $queries } | ConvertTo-Json -Depth 6 -Compress)

$findings = @()
for ($index = 0; $index -lt $packages.Count; $index += 1) {
    $vulnerabilities = @(
        $response.results[$index].vulns |
            Where-Object { $null -ne $_ -and -not [string]::IsNullOrWhiteSpace($_.id) }
    )
    foreach ($vulnerability in $vulnerabilities) {
        $findings += [ordered]@{
            package = $packages[$index].Name
            version = $packages[$index].Version
            scope = $packages[$index].Scope
            id = $vulnerability.id
            aliases = @($vulnerability.aliases)
            modified = $vulnerability.modified
            lockFiles = $packages[$index].LockFiles
            configurations = $packages[$index].Configurations
        }
    }
}

$blockingFindings = @(
    $findings | Where-Object { $_.scope -in @('runtime', 'release-tool') }
)
$buildTestFindings = @(
    $findings | Where-Object { $_.scope -eq 'build-test' }
)
$report = [ordered]@{
    scannedAtUtc = [DateTime]::UtcNow.ToString('o')
    source = 'https://api.osv.dev/v1/querybatch'
    ecosystem = 'Maven'
    packageCount = $packages.Count
    vulnerabilityCount = $findings.Count
    blockingVulnerabilityCount = $blockingFindings.Count
    buildTestVulnerabilityCount = $buildTestFindings.Count
    vulnerabilities = $findings
}

$reportDirectory = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $ReportPath -Encoding UTF8

if ($blockingFindings.Count -gt 0) {
    $summary = $blockingFindings | ForEach-Object {
        "$($_.id) in $($_.package):$($_.version)"
    }
    throw "OSV reported shipped runtime or release-tool advisories: $($summary -join '; ')"
}

Write-Output (
    ("OSV runtime and release-tool gate passed for {0} locked Maven packages. " +
    "{1} build-only or test-only findings were recorded.") -f
        $packages.Count,
        $buildTestFindings.Count
)
