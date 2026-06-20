# Notes

A simple, offline-first note-taking app for Android. Native **Kotlin + Jetpack Compose** (Material 3), local storage with **Room**.

## Features

- List, create, edit, and delete notes
- Search across titles and content
- Swipe a note to delete
- Light / dark theme with Android 12+ dynamic color
- 100% local — no account, no network

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
.\gradlew.bat assembleDebug

# Install onto a connected device / emulator (USB debugging enabled)
.\gradlew.bat installDebug
```

To put it on your phone: enable **Developer options → USB debugging**, connect via USB,
then `installDebug` — or copy `app-debug.apk` to the phone and open it (allow installs
from this source).

## Status

MVP. See the project context in `my_agent_configs` for the backlog (markdown, tags,
reminders, share/export, optional sync).
