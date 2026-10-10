#!/usr/bin/env pwsh
# Compiles the app's pure-logic Kotlin against stubbed Android classes and runs the
# verification harness, so the Android code can be checked without an Android SDK.
#
# Usage:  pwsh tools/verify-android.ps1
# Requires: JDK 17 and the Kotlin compiler jars under .tools/ (see README).

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo 'android'
$tools = Join-Path $repo '.tools'
$build = Join-Path $android '.verify-build'

# Resource references first: a dangling @string/R.id is the cheapest way to break the CI
# build, and it needs neither an Android SDK nor Kotlin to detect.
Write-Host '[0/3] checking Android resource references'
$python = if ($env:AHU_PYTHON) { $env:AHU_PYTHON } else { 'python' }
if (Get-Command $python -ErrorAction SilentlyContinue) {
    & $python (Join-Path $PSScriptRoot 'check_android_resources.py') $android
    if ($LASTEXITCODE -ne 0) { throw 'resource references do not resolve' }
} else {
    Write-Host "python not found; skipping the resource check (set AHU_PYTHON to enable it)"
}

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

# Android / org.json stubs. BitmapFactory really decodes (ImageIO) and Log prints to stdout,
# so the captcha path (decode -> scale -> getPixels -> model) is exercised, not just compiled.
$stubFiles = @(
    'verify-stubs\android\graphics\Color.java',
    'verify-stubs\android\graphics\Bitmap.java',
    'verify-stubs\android\graphics\BitmapFactory.java',
    'verify-stubs\android\util\Log.java',
    'verify-stubs\android\content\Context.java',
    'verify-stubs\android\content\res\AssetManager.java',
    'verify-stubs\org\json\Json.java',
    'verify-stubs\org\json\JSONObject.java',
    'verify-stubs\org\json\JSONArray.java'
) | ForEach-Object { Join-Path $android $_ }

Write-Host '[1/3] compiling Java stubs (javac)'
# javac cannot compile Kotlin and kotlinc cannot resolve a nested type out of class files,
# so the stubs are compiled here for the runtime and also handed to kotlinc as sources.
& $javac -d $build @stubFiles
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
if (-not (Test-Path (Join-Path $build 'org\json\JSONObject.class'))) {
    throw "stubs did not compile into $build"
}

Write-Host '[2/3] compiling Kotlin sources (kotlinc)'
$sources = @(
    'app\src\main\java\cn\edu\ahu\cardqr\net\HttpClient.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\data\CampusCardSession.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\data\AutoLogin.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\captcha\CompactCaptchaModel.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\captcha\CaptchaRecognizer.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\qr\QrEncoder.kt',
    'app\src\main\java\cn\edu\ahu\cardqr\qr\QrRenderer.kt',
    'verify-harness\VerifyMain.kt'
) | ForEach-Object { Join-Path $android $_ }

& $java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -d $build -no-reflect -nowarn -classpath "$stdlib;$build" @stubFiles @sources 2>&1 |
    Where-Object { $_ -notmatch 'kotlin home directory' }
if ($LASTEXITCODE -ne 0) { throw 'kotlinc failed' }

Write-Host '[3/3] running verification harness'
$out = Join-Path $repo 'reports\kotlin_verify.txt'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
& $java -cp "$build;$stdlib" cn.edu.ahu.cardqr.verify.VerifyMain all > $out 2>&1
$harnessExit = $LASTEXITCODE

Write-Host ''
Get-Content $out | Select-String -Pattern '^(CHECK FAIL|CHECK WARN|SUMMARY|NOTE|ANDROID-LOG)' |
    ForEach-Object { $_.Line }

$failures = @(Select-String -Path $out -Pattern '^CHECK FAIL' -ErrorAction SilentlyContinue).Count
$summary = @(Select-String -Path $out -Pattern '^SUMMARY OK' -ErrorAction SilentlyContinue).Count

Write-Host ''
if ($harnessExit -eq 0 -and $failures -eq 0 -and $summary -eq 1) {
    $checks = @(Select-String -Path $out -Pattern '^CHECK ' -ErrorAction SilentlyContinue).Count
    Write-Host "All $checks checks passed. Full output: $out" -ForegroundColor Green
} else {
    Write-Host "FAILED: exit=$harnessExit checkFailures=$failures summaryOk=$summary" -ForegroundColor Red
    exit 1
}
