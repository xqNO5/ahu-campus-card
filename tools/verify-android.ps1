#!/usr/bin/env pwsh
# Compiles the pure-logic Kotlin sources against stubbed Android classes and runs the
# verification harness, so the Android code can be checked without an Android SDK.
#
# Usage:  pwsh tools/verify-android.ps1
# Requires: JDK 17 and the Kotlin compiler jars under .tools/ (see README).

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo 'android'
$tools = Join-Path $repo '.tools'
$build = Join-Path $android '.verify-build'

# Pin JDK 17 (what the Android Gradle build in CI uses) so class-file versions match
# between javac, kotlinc and java. Override with AHU_JDK_HOME if needed.
$jdkHome = $env:AHU_JDK_HOME
if (-not ($jdkHome -and (Test-Path (Join-Path $jdkHome 'bin\javac.exe')))) {
    $jdkHome = $null
    foreach ($candidate in @(
            'C:\Program Files\Java\jdk-17.0.5',
            'C:\Program Files\Java\jdk-17',
            '/usr/lib/jvm/temurin-17-jdk-amd64'
        )) {
        if (Test-Path (Join-Path $candidate 'bin\javac.exe')) { $jdkHome = $candidate; break }
    }
}
if (-not $jdkHome) { throw 'JDK 17 not found; set AHU_JDK_HOME' }
$java = Join-Path $jdkHome 'bin\java.exe'
$javac = Join-Path $jdkHome 'bin\javac.exe'
Write-Host "using JDK at $jdkHome"

$compilerJars = @(
    'kotlin-compiler-embeddable.jar',
    'kotlin-stdlib.jar',
    'trove4j.jar',
    'kotlin-daemon-embeddable.jar',
    'kotlin-reflect.jar',
    'annotations.jar'
) | ForEach-Object { Join-Path $tools $_ }

foreach ($jar in $compilerJars) {
    if (-not (Test-Path $jar)) { throw "missing $jar — see README for the download list" }
}
$compilerCp = ($compilerJars -join ';')
$stdlib = Join-Path $tools 'kotlin-stdlib.jar'

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $build | Out-Null

Write-Host '[1/3] compiling Android stubs (javac)'
& $javac -d $build (Join-Path $android 'verify-stubs\android\graphics\Color.java') `
    (Join-Path $android 'verify-stubs\android\graphics\Bitmap.java') `
    (Join-Path $android 'verify-stubs\org\json\Json.java') `
    (Join-Path $android 'verify-stubs\org\json\JSONObject.java') `
    (Join-Path $android 'verify-stubs\org\json\JSONArray.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
if (-not (Test-Path (Join-Path $build 'android\graphics\Bitmap.class'))) {
    throw "Bitmap stubs did not compile into $build"
}

Write-Host '[2/3] compiling Kotlin sources (kotlinc)'
$sources = @(
    'app\src\main\java\cn\edu\ahu\cardqr\crypto\CasDes.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\crypto\WebVpnHostCrypt.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\net\HttpClient.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\data\AhuClient.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\qr\QrEncoder.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\qr\QrRenderer.kt',
    'verify-harness\VerifyMain.kt'
) | ForEach-Object { Join-Path $android $_ }

# -classpath entries are path-separated; a trailing separator is required so javac's output
# directory itself is searched for the stubbed android.* classes.
& $java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -d $build -no-reflect -nowarn -classpath "$stdlib;$build\" @sources 2>&1 |
    Where-Object { $_ -notmatch 'kotlin home directory' }
if ($LASTEXITCODE -ne 0) { throw 'kotlinc failed' }

Write-Host '[3/3] running verification harness'
$out = Join-Path $repo 'reports\kotlin_verify.txt'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
& $java -cp "$build;$stdlib" cn.edu.ahu.cardqr.verify.VerifyMain all > $out 2>&1

$desFail = (Select-String -Path $out -Pattern '^DES FAIL' -ErrorAction SilentlyContinue).Count
$hashFail = (Select-String -Path $out -Pattern '^HASH FAIL' -ErrorAction SilentlyContinue).Count

Write-Host ''
Get-Content $out | Select-String -Pattern '^(DES|HASH|TUNNEL|QRRENDER)' | ForEach-Object { $_.Line }

Write-Host ''
if ($desFail -eq 0 -and $hashFail -eq 0) {
    Write-Host "DES/HASH checks passed. Full output: $out" -ForegroundColor Green
} else {
    Write-Host "FAILURES: DES=$desFail HASH=$hashFail" -ForegroundColor Red
    exit 1
}



