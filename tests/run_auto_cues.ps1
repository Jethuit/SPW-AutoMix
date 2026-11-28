<#
.EXAMPLE
& .\tests\run_auto_cues.ps1 -Package .\build\libs\SPW-AutoMix-v0.1.0.spmod
#>
param(
    [Parameter(Mandatory)][string]$Package,
    [string]$Toolchain = "$PSScriptRoot/../../spw status/build/tools",
    [string]$HostJar = "E:/SteamLibrary/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll",
    [string]$Ffmpeg
)

$ErrorActionPreference = 'Stop'
$packagePath = (Resolve-Path -LiteralPath $Package).ProviderPath
$hostPath = (Resolve-Path -LiteralPath $HostJar).ProviderPath
$kotlinLib = (Resolve-Path -LiteralPath "$Toolchain/kotlin/kotlinc/lib").ProviderPath
$runtimeJava = Get-ChildItem -LiteralPath "$Toolchain/jdk25" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    Get-Item -LiteralPath "$($_.FullName)/bin/java.exe" -ErrorAction SilentlyContinue
} | Select-Object -First 1 -ExpandProperty FullName
if (!$runtimeJava) { $runtimeJava = (Get-Command java -ErrorAction SilentlyContinue).Source }
if (!$runtimeJava) { throw "JDK 25 is missing from $Toolchain/jdk25 and PATH" }
$compilerJava = $env:AUTOMIX_BUILD_JAVA
if (!$compilerJava) { $compilerJava = (Get-Command java -ErrorAction SilentlyContinue).Source }
if (!$compilerJava) { $compilerJava = $runtimeJava }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$tempRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).ProviderPath
$work = (New-Item -ItemType Directory -Path (Join-Path $tempRoot "automix-cues-$([Guid]::NewGuid())")).FullName
try {
    [IO.Compression.ZipFile]::ExtractToDirectory($packagePath, "$work/plugin")
    $pluginClasses = "$work/plugin/classes"
    $testClasses = "$work/tests"
    $classpath = "$pluginClasses;$kotlinLib/kotlin-stdlib.jar;$hostPath"
    & $compilerJava -cp "$kotlinLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib `
        -classpath $classpath "-Xfriend-paths=$pluginClasses" -jvm-target 25 -d $testClasses "$PSScriptRoot/AutoCueTests.kt" "$PSScriptRoot/../test/SpotifyMixTests.kt"
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile the Kotlin cue tests.' }
    & $runtimeJava -cp "$testClasses;$classpath" automix.AutoCueTestsKt
    if ($LASTEXITCODE -ne 0) { throw 'The Kotlin cue tests failed.' }
    $mixArgs = @()
    if ($Ffmpeg) { $mixArgs = @((Resolve-Path -LiteralPath $Ffmpeg).ProviderPath, $work) }
    & $runtimeJava -cp "$testClasses;$classpath" automix.SpotifyMixTestsKt @mixArgs
    if ($LASTEXITCODE -ne 0) { throw 'The Spotify Mix tests failed.' }
} finally {
    if ([IO.Path]::GetDirectoryName($work) -eq $tempRoot.TrimEnd('\', '/')) {
        Add-Type -AssemblyName Microsoft.VisualBasic
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($work, "OnlyErrorDialogs", "SendToRecycleBin")
    }
}
