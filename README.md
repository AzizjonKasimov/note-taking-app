# Notes

A simple, offline-first note-taking app for Android. Native **Kotlin + Jetpack Compose** (Material 3), local storage with **Room**.

## Features

- List, create, edit, and delete notes
- Search across titles and content
- Swipe a note to delete
- Light / dark theme with Android 12+ dynamic color and an in-app theme override
- Local storage (Room) + one-tap backup/restore to a file in your Google Drive — no account or setup
- In-app auto-update from GitHub Releases

## Tech stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.0 |
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM (`NotesViewModel`), manual DI in `NotesApplication` |
| Persistence | Room (`notes.db`), KSP codegen |
| Navigation | navigation-compose |
| Build | Gradle (wrapper), AGP 8.7, version catalog |
| SDK | minSdk 26, target/compileSdk 35 |

## Project layout

```
app/src/main/java/com/azizjon/notes/
  MainActivity.kt            # Compose entry point
  NotesApplication.kt        # owns the DB/repository
  data/                      # Note entity, DAO, Room DB, repository
  ui/                        # ViewModel, nav host, list + edit screens, theme
```

## Build & run

Prerequisites: JDK 17 and the Android SDK (build-tools 35, platform 35). `local.properties`
must point `sdk.dir` at your SDK (it is gitignored).

```powershell
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
# Convenience copy for sharing -> Notes-debug.apk in the project root
.\gradlew.bat assembleDebug

# Install onto a connected device / emulator (USB debugging enabled)
.\gradlew.bat installDebug
```

To put it on your phone: enable **Developer options → USB debugging**, connect via USB,
then `installDebug` — or copy `app-debug.apk` to the phone and open it (allow installs
from this source).

## Releasing a new version

Updates reach the phone through the in-app updater, which reads `version.json` from the
public [releases repo](https://github.com/AzizjonKasimov/note-taking-app-releases). To cut
a release:

```powershell
.\release.ps1 -VersionName 1.2 -VersionCode 3 -Notes "What changed"
```

This bumps the version, builds a signed APK, publishes a GitHub release, and updates
`version.json`. The app offers the update on next launch.

## Signing

Release builds are signed with `release.keystore` using credentials in `keystore.properties`
(both gitignored). **Back these up** — the same key must sign every update, or installs fail
with a signature mismatch.

## Backup & restore

Backup uses Android's Storage Access Framework — **no Google account, API keys, or
permissions**. In the app's **gear** screen, *Set up Drive backup* lets you pick a file in
your Google Drive once; the app then writes your notes there automatically a few seconds
after each change (`backup/BackupManager.kt`). *Restore* reads them back — handy on a new
phone. Notes are merged by id, newest-wins.

## Status

- Working: notes CRUD, search, swipe-delete, themes, in-app auto-update, Google Drive backup/restore
- Backlog: markdown, tags/folders, pin, reminders, share/export
