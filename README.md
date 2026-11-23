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
- Voice notes: speech becomes text on the phone, offline, with an open speech model (see below)

## Tech stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.3 |
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM (`NotesViewModel`), manual DI in `NotesApplication` |
| Persistence | Room (`notes.db`), KSP codegen |
| Navigation | navigation-compose |
| Speech to text | sherpa-onnx 2.0.3 (ONNX Runtime) with NVIDIA Parakeet TDT 0.6B v2 int8 and Silero VAD |
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
  voice/                     # Voice notes: recording, the speech model, speech to text
tools/speech-bench/          # Scores speech models on your own recordings (PC)
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

Debug builds install as a separate app, **Notes debug** (`com.azizjon.notes.debug`), beside the
released one, so a phone keeps its real notes while a build is tested. They skip the update check.

The speech engine, sherpa-onnx, ships as an AAR on its GitHub release rather than on Maven Central:
the first build downloads it into `app/libs/` (gitignored) and checks it against the release's
SHA-256. Release builds include only arm64 native code; debug builds add x86_64 for emulators.

## Voice notes

Tap the mic in a note, or the small mic button above **+** for a new note, and talk for as long as
you like. The text is added where the cursor is, or as a new paragraph at the end.

- **On the phone, offline.** The first time, the app downloads the speech model once (661 MB, over
  Wi-Fi, with Android's download manager), pinned to one revision and checked file by file against
  its SHA-256. Audio never leaves the phone.
- **Heard while you talk.** The voice detector (Silero VAD) finds pauses, and every finished piece
  of about 8-20 s is turned into text in the background, so the wait after **Stop** stays short
  however long the note is. Parakeet loses words on long clips, so pieces are cut inside pauses.
- **Nothing lost.** The recording is written to a WAV file as it goes and kept until its text is
  in a note; a recording left by a crash is offered again on the next dictation. A foreground
  service keeps recording with the screen off, with **Stop** in its notification.
- **Names.** Settings → Voice notes takes names to spell right (one per line); they are boosted
  in decoding (sherpa-onnx hotwords with modified beam search).

Measured on 181 recordings of the owner's voice (`tools/speech-bench`): 4.9% word errors with the
names list (5.4% without), about the same as Whisper large-v3-turbo on a desktop GPU (4.8%), and
the same text on the phone as on a PC. Newer Parakeet versions (v3, Ultra, Redux) scored worse in
English on these recordings.

### Choosing or checking a model: `tools/speech-bench`

`speech_bench.py` (a uv script) transcribes a set of your recordings with a sherpa-onnx model on
the PC, the way the phone runs it, and scores the word errors; `export` writes the recordings in
the app's format for `TranscriberDeviceTest`, which runs the same check on a phone. Its docstring
has the commands. Keep recordings, model files, and results out of git (`out/` and `models/` are
ignored).

### Credits

- [NVIDIA Parakeet TDT 0.6B v2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2), CC-BY-4.0,
  exported for sherpa-onnx by its project.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0.
- [Silero VAD](https://github.com/snakers4/silero-vad), MIT (`app/src/main/assets/silero_vad.onnx`).

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
