$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$appBuild = Join-Path $root 'app\build.gradle'
$settings = Join-Path $root 'settings.gradle'
$contract = Join-Path $root 'docs\product-contract\manuscript-android-v2.json'
$resources = Join-Path $root 'app\src\main\res\values\strings.xml'
$llamaBuild = Join-Path $root 'llama-runtime\build.gradle'
$manifest = Join-Path $root 'app\src\main\AndroidManifest.xml'
$rootBuild = Join-Path $root 'build.gradle'

Describe 'Manuscript Android Version 2 identity isolation' {
    It 'uses the V2 project, package, version, labels, and local contract' {
        (Get-Content -Raw -LiteralPath $settings) | Should Match 'rootProject\.name\s*=\s*"ManuscriptAndroidVersion2"'

        $build = Get-Content -Raw -LiteralPath $appBuild
        $build | Should Match 'applicationId\s+"com\.remote\.Manuscript\.v2"'
        $build | Should Match 'applicationIdSuffix\s+"\.debug"'
        $build | Should Match 'testBuildType\s+"debug"'
        $build | Should Match 'testApplicationId\s+"com\.remote\.Manuscript\.v2\.debug\.test"'
        $build | Should Match 'versionCode\s+1'
        $build | Should Match 'versionName\s+"2\.0\.0-internal"'
        $build | Should Match 'rootProject\.file\("shared/contract/v1"\)'
        $build | Should Not Match '\.\./\.\./shared/contract/v1'
        ([regex]::Matches($build, 'SUBSCRIPTION_ENFORCED", "false"')).Count | Should Be 2
        (Get-Content -Raw -LiteralPath $manifest) | Should Match 'android:label="@string/launcher_name"'

        (Get-Content -Raw -LiteralPath $resources) | Should Match '<string name="app_name">Manuscript Android Version 2</string>'
        (Get-Content -Raw -LiteralPath $resources) | Should Match '<string name="launcher_name">Manuscript V2</string>'
    }

    It 'has no active V1 contract, parent path, or Play upload workflow' {
        $active = @($appBuild, $settings, $rootBuild) + @(
            Get-ChildItem -Recurse tools, play -File |
                Where-Object { $_.FullName -ne $PSCommandPath } |
                Select-Object -ExpandProperty FullName
        )
        $text = ($active | ForEach-Object { Get-Content -Raw -LiteralPath $_ }) -join "`n"
        $text | Should Not Match 'manuscript-android-v1'
        $text | Should Not Match 'Manuscript/Android'
        $text | Should Not Match 'build_play_bundle'
        $text | Should Not Match 'internaltest/'
        $text | Should Not Match 'validatePlayBundle'
    }

    It 'has a V2 product contract and copied canonical brand assets' {
        Test-Path -LiteralPath $contract | Should Be $true
        $data = Get-Content -Raw -LiteralPath $contract | ConvertFrom-Json
        $data.canonicalReleasePackage | Should Be 'com.remote.Manuscript.v2'
        $data.canonicalDebugPackage | Should Be 'com.remote.Manuscript.v2.debug'
        $data.canonicalTestPackage | Should Be 'com.remote.Manuscript.v2.debug.test'
        $data.versionName | Should Be '2.0.0-internal'
        $data.versionCode | Should Be 1
        $data.loginEnforced | Should Be $false
        $data.brand.canonicalSvg | Should Be 'brand/canonical/manuscript.svg'
        $data.brand.canonicalPng | Should Be 'brand/canonical/manuscript.png'
        (Get-FileHash (Join-Path $root $data.brand.canonicalSvg) -Algorithm SHA256).Hash | Should Be 'C85E54DD5051D33D488DEDE79A1F556235E08211E6C8F576219DD89F6C0FE84E'
        (Get-FileHash (Join-Path $root $data.brand.canonicalPng) -Algorithm SHA256).Hash | Should Be '7D4E71968686DF861B0BD8D1AC3D1D38AC7CB17BC048D1BE6F7DB1F42C9ED9E7'
    }

    It 'validates the committed llama vendor without nested Git or build-time patching' {
        $build = Get-Content -Raw -LiteralPath $llamaBuild
        $build | Should Match 'verifyManuscriptLlamaRuntime'
        $build | Should Not Match 'git apply'
        $build | Should Not Match 'llamaVendorDirectory.*\.git'
        $build | Should Not Match 'outputs\.upToDateWhen'
    }
}
