# Notes

A simple, offline-first note-taking app for Android. Native **Kotlin + Jetpack Compose** (Material 3), local storage with **Room**.

## Features

- List, create, edit, and move notes to Trash
- Search across titles and content
- Confirm before moving a note to Trash; restore it or explicitly delete it forever
- Light / dark theme with Android 12+ dynamic color and an in-app theme override
- Local storage (Room) + GitHub SQL backup/restore to a private data repo
- In-app auto-update from GitHub Releases

## Tech stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.3 |
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM (`NotesViewModel`), manual DI in `NotesApplication` |
| Persistence | Room (`notes.db`), KSP codegen |
| Navigation | navigation-compose |
| Build | Gradle (wrapper), AGP 8.13, version catalog |
| SDK | minSdk 26, targetSdk 35, compileSdk 36 |

## Project layout

```
app/src/main/java/com/azizjon/notes/
  MainActivity.kt            # Compose entry point
  NotesApplication.kt        # owns the DB/repository
  backup/                    # GitHub SQL backup/restore
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

## Deletion and recovery

Notes cannot be deleted by swiping the list. Use the trash button inside a note and confirm the
action; the note moves to **Notebooks -> Trash** and remains there until you restore it or choose
**Delete forever**. GitHub SQL backups include trashed notes, so restoring a backup preserves the
same recoverable Trash state.

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

## GitHub SQL backup

The app can back up the full Room snapshot to GitHub using the Contents API. In the
app's **gear** screen, GitHub SQL backup defaults to:

- owner: `AzizjonKasimov`
- repo: `note-taking-app-data`
- branch: `main`
- SQL path: `notes.sql`

Create a private data repo, save a fine-grained GitHub token with read/write Contents
access to that repo, then tap **Save GitHub settings** and **Back up now**. Auto backup
runs a few seconds after local note/notebook changes when enabled. **Restore from
GitHub** replaces local notes with the SQL snapshot, which is useful after a fresh
install. The GitHub token is stored in encrypted preferences and excluded from Android
cloud/device backup.

## Status

- Working: notes CRUD, search, recoverable Trash, themes, in-app auto-update, GitHub SQL backup/restore
- Backlog: markdown, tags/folders, pin, reminders, share/export
