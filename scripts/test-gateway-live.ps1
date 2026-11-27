[CmdletBinding()]
param()

# Opt-in live gateway smoke test.
#
# Runs People's assistant (Network App until Notes 2.5) against the real
# gateway, with synthetic records, on a debug build of Notes.
#
# The key is never handled by this script. It is read at runtime from the
# app's own encrypted storage, so it must be saved once on the device via
# Settings -> Assistant. Nothing is pushed to the device and nothing is passed
# through Gradle arguments, command text, or test reports.
#
# `connectedDebugAndroidTest` is deliberately NOT used: it uninstalls the app
# when it finishes, which erases the saved token and forces it to be entered
# again before every run. Installing and then driving `am instrument` directly
# leaves both APKs in place, so the token survives.

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$localPropertiesPath = Join-Path $projectRoot "local.properties"

$testClass = "com.azizjon.notes.people.ai.AssistantLiveInstrumentedTest"
$testRunner = "com.azizjon.notes.debug.test/androidx.test.runner.AndroidJUnitRunner"

$sdkLine = Get-Content -LiteralPath $localPropertiesPath |
    Where-Object { $_ -match "^sdk\.dir=" } |
    Select-Object -First 1
if (-not $sdkLine) { throw "local.properties does not define sdk.dir." }
$sdkDir = ($sdkLine -replace "^sdk\.dir=", "") -replace "\\:", ":" -replace "\\\\", "\"
$adb = Join-Path $sdkDir "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb.exe was not found under sdk.dir." }

# Start adb's background server through the shell before any other adb command.
# The first adb command after the PC starts launches that server, and a server
# launched by an ordinary call keeps open what the call inherited, such as the
# pipe a caller reads this script's output through (`... 2>&1 | Out-String`),
# so the caller would wait for as long as the server runs, long after this
# script has ended. Started through the shell, the server inherits nothing.
# adb has no time limit of its own: a server that does not answer keeps every
# command waiting.
$adbStart = [Diagnostics.ProcessStartInfo]::new($adb, "start-server")
$adbStart.UseShellExecute = $true
$adbStart.WindowStyle = "Hidden"
$adbStarter = [Diagnostics.Process]::Start($adbStart)
if (-not $adbStarter.WaitForExit(15000)) {
    $adbStarter.Kill()
    throw "adb did not answer within 15 s. End it (Stop-Process -Name adb), then run this again."
}
if ($adbStarter.ExitCode -ne 0) { throw "adb could not start its server; the end of $env:TEMP\adb.log says why." }

$devices = @(& $adb devices | Select-String "\sdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($devices.Count -ne 1) {
    throw "Start exactly one Android emulator or connected test device before running the live gateway test."
}
$serial = $devices[0]

Push-Location $projectRoot
try {
    & .\gradlew.bat installDebug installDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Could not install the app and instrumentation APKs." }

    & $adb -s $serial logcat -c

    Write-Host ""
    Write-Host "Running the live gateway test with synthetic records..."
    $output = & $adb -s $serial shell am instrument -w `
        -e class $testClass `
        -e liveGateway true `
        $testRunner 2>&1
    $report = ($output | Out-String)
    Write-Host $report

    if ($report -match "Save the AI key") {
        throw "No AI key is saved on $serial. Open Notes debug, go to Settings -> Assistant, enter the key, then run this script again."
    }
    if ($report -match "FAILURES!!!" -or $report -notmatch "OK \(") {
        throw "Live gateway instrumentation test failed."
    }

    Write-Host "Stage timings:"
    & $adb -s $serial logcat -d -s LiveGatewayTiming:I |
        Select-String "took|failed" |
        ForEach-Object { Write-Host "  $($_.Line)" }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Live agent capture, multi-person update, question, queued delete, and undo passed."
Write-Host "The app is still installed and the saved key was not touched."
