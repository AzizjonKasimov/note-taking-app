#Requires -Version 5
<#
.SYNOPSIS
    Build, sign, and publish a new Notes release, and update the in-app updater manifest.

.DESCRIPTION
    1. Bumps versionCode / versionName in app/build.gradle.kts
    2. Builds a signed release APK with the Gradle wrapper
    3. Creates a GitHub Release (with the APK) on the public releases repo
    4. Updates version.json in the releases repo so the app offers the update on next launch

    The version bump in THIS repo is left uncommitted for you to review and commit.

.EXAMPLE
    .\release.ps1 -VersionName 1.7 -VersionCode 8 -Notes "Added GitHub SQL backup"
#>
param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [string]$Notes = "Bug fixes and improvements."
)
$ErrorActionPreference = 'Stop'
$proj = $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }

$releasesRepo = 'AzizjonKasimov/note-taking-app-releases'
$releasesDir  = Join-Path (Split-Path $proj -Parent) 'note-taking-app-releases'
$apkUrl = "https://github.com/$releasesRepo/releases/download/v$VersionName/Notes-$VersionName.apk"

Write-Host "==> Bumping version to $VersionName (code $VersionCode)" -ForegroundColor Cyan
$gradleFile = Join-Path $proj 'app\build.gradle.kts'
$content = Get-Content $gradleFile -Raw
$content = [regex]::Replace($content, 'versionCode = \d+', "versionCode = $VersionCode")
$content = [regex]::Replace($content, 'versionName = "[^"]*"', "versionName = `"$VersionName`"")
Set-Content -Path $gradleFile -Value $content -NoNewline

Write-Host "==> Building signed release APK" -ForegroundColor Cyan
& (Join-Path $proj 'gradlew.bat') -p $proj assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }
$built = Join-Path $proj 'app\build\outputs\apk\release\app-release.apk'
$named = Join-Path $proj "app\build\outputs\apk\release\Notes-$VersionName.apk"
Copy-Item $built $named -Force
Copy-Item $named (Join-Path $proj "Notes-$VersionName.apk") -Force

Write-Host "==> Publishing GitHub release v$VersionName" -ForegroundColor Cyan
gh release create "v$VersionName" $named --repo $releasesRepo --title "v$VersionName" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "gh release create failed (is gh authenticated?)." }

Write-Host "==> Updating version.json in the releases repo" -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $releasesDir '.git'))) {
    gh repo clone $releasesRepo $releasesDir
} else {
    git -C $releasesDir pull --quiet
}
$json = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    apkUrl      = $apkUrl
    notes       = $Notes
} | ConvertTo-Json
Set-Content -Path (Join-Path $releasesDir 'version.json') -Value $json
git -C $releasesDir add version.json
git -C $releasesDir commit -m "Release v$VersionName (code $VersionCode)" | Out-Null
git -C $releasesDir push --quiet

Write-Host ""
Write-Host "Done. v$VersionName published; your phone will offer the update on next launch." -ForegroundColor Green
Write-Host "Now commit the version bump in this repo:" -ForegroundColor DarkGray
Write-Host "  git add app/build.gradle.kts; git commit -m `"Release v$VersionName`"" -ForegroundColor DarkGray
