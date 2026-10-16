param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$javaHome = 'C:\Program Files\Java\jdk-17'
$javac = Join-Path $javaHome 'bin\javac.exe'
$jar = Join-Path $javaHome 'bin\jar.exe'
$apiVersion = '2026.7'
$dependencyDir = Join-Path $projectRoot '.deps'
$apiJar = Join-Path $dependencyDir "montoya-api-$apiVersion.jar"
$classesDir = Join-Path $projectRoot 'build\classes'
$testClassesDir = Join-Path $projectRoot 'build\test-classes'
$outputDir = Join-Path $projectRoot 'build\libs'
$tempDir = Join-Path $projectRoot 'build\tmp'
$outputJar = Join-Path $outputDir 'burp-tab-lock-3.0.jar'

if ($Clean -and (Test-Path (Join-Path $projectRoot 'build'))) {
    $buildTarget = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
    if ($buildTarget -ne ([IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\build')) {
        throw 'Refusing to clean an unexpected path.'
    }
    Remove-Item -LiteralPath $buildTarget -Recurse -Force
}

if (-not (Test-Path $javac)) {
    throw "JDK 17 was not found at $javaHome"
}

New-Item -ItemType Directory -Force -Path $dependencyDir, $classesDir, $testClassesDir, $outputDir, $tempDir | Out-Null

if (-not (Test-Path $apiJar)) {
    $apiUrl = "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/$apiVersion/montoya-api-$apiVersion.jar"
    Write-Host "Downloading Montoya API $apiVersion..."
    Invoke-WebRequest -Uri $apiUrl -OutFile $apiJar
}

$sources = Get-ChildItem -Path (Join-Path $projectRoot 'src\main\java') -Filter '*.java' -Recurse |
    Select-Object -ExpandProperty FullName

$compilerArgs = @(
    '--release', '17',
    '-encoding', 'UTF-8',
    '-cp', $apiJar,
    '-d', $classesDir
) + $sources
& $javac $compilerArgs
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

$testSources = Get-ChildItem -Path (Join-Path $projectRoot 'src\test\java') -Filter '*.java' -Recurse |
    Select-Object -ExpandProperty FullName
$testCompilerArgs = @(
    '--release', '17',
    '-encoding', 'UTF-8',
    '-cp', "$apiJar;$classesDir",
    '-d', $testClassesDir
) + $testSources
& $javac $testCompilerArgs
if ($LASTEXITCODE -ne 0) {
    throw "test compilation failed with exit code $LASTEXITCODE"
}

& (Join-Path $javaHome 'bin\java.exe') '-Djava.awt.headless=true' -cp "$apiJar;$classesDir;$testClassesDir" burp.TabLockEventQueueTest
if ($LASTEXITCODE -ne 0) {
    throw "tests failed with exit code $LASTEXITCODE"
}

if (Test-Path $outputJar) {
    Remove-Item -LiteralPath $outputJar -Force
}
& $jar "-J-Djava.io.tmpdir=$tempDir" --create --file $outputJar -C $classesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

Write-Host "Built: $outputJar"
