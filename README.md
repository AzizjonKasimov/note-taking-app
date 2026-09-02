# Notes

A simple, offline-first note-taking app for Android. Native **Kotlin + Jetpack Compose** (Material 3), local storage with **Room**.

## Features

- List, create, edit, and move notes to Trash
- WYSIWYG Markdown editing with bold, italic, links, numbered/bulleted lists, and nested lists
- Phone-friendly indent/outdent controls plus automatic formatting of pasted Markdown
- Crash-safe local autosave with resumable editing after the app is closed or killed
- Search across titles and content
- Visual notebook markers: automatic initials, colored folders, emoji, preset covers, or cropped gallery photos
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
  data/                      # Entities, Room, repository, and private marker images
  ui/                        # Screens, marker picker/cropper, navigation, and theme
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

## Autosave and editor recovery

Editing is local-first: a new note receives its permanent Room database ID before the editor opens,
then title, rich Markdown content, formatting, and notebook changes are saved after 350 ms of idle
time and at least every two seconds during uninterrupted typing. Moving the app to the background
also requests an immediate local flush. GitHub auto backup remains separately debounced and starts
only after a local Room write succeeds, so it does not make a network request for every keystroke.

If Android closes the app while an editor is active, the app validates that note and reopens it in
edit mode on the next cold launch. **Back** and **Done** both wait for the latest local write and end
that resumable session; Done returns an existing note to read view, while Back (and Done on a newly
created note) returns to the list. A failed write keeps the editor open with Retry and an explicit
discard-and-leave option. Untouched empty new drafts are cleaned up, but clearing an existing note
never deletes its row.

## Notebook appearance

Every notebook starts with a stable colored initial. Open **Notebooks**, use a notebook's overflow
menu, and choose **Appearance** to switch to a colored folder, initial, emoji, one of twelve bundled
photo covers, or a photo from the Android system picker. Gallery photos can be positioned with
pinch-to-zoom and drag before saving. The app keeps an optimized full-frame editing source (up to
2048 px) and a 256 px square crop in private app storage; it never needs broad photo-library
permission.

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

Custom notebook photos are backed up beside the configured SQL file under
`notebook-images/<notebook-id>/source.webp` and `crop.webp`. Image files are uploaded before
`notes.sql`, and restore falls back to the notebook's automatic initial if no usable crop is
available. Bundled photo covers need no extra backup files.

## Status

- Working: notes CRUD, rich Markdown editing, nested lists, automatic Markdown paste, crash-safe autosave/editor resume, search, visual notebook markers, recoverable Trash, themes, in-app auto-update, GitHub SQL backup/restore
- Backlog: tags, pinning, reminders, share/export
