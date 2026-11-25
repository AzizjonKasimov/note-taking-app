# Notes

A simple, offline-first note-taking app for Android. Native **Kotlin + Jetpack Compose** (Material 3).
Your notes are the Markdown files of your own private GitHub repo: the app keeps a git clone of it
on the phone (**JGit**) and syncs it, so the phone and your computer share one set of notes.

## Features

- Notes are the Markdown files of your private GitHub repo; notebooks are its top-level folders
- Sync with GitHub: commit, pull, and push, offline edits included, without ever losing an edit
  made on the phone or on your computer (see below)
- A Markdown editor that keeps each file exactly as written, and WYSIWYG editing (bold, italic,
  links, nested lists, Markdown paste) for notes it can't change by accident
- Crash-safe local autosave with resumable editing after the app is closed or killed
- Search across titles and text, newest notes first
- Visual notebook markers: automatic initials, colored folders, emoji, preset covers, or cropped gallery photos
- Confirm before deleting a note; a copy waits in Trash on the phone until you restore it or delete it forever
- Light / dark theme with Android 12+ dynamic color and an in-app theme override
- In-app auto-update from GitHub Releases
- Voice notes: speech becomes text on the phone, offline, with an open speech model (see below)
- Today and reminders: the app opens on what is due from the repo's reminder list
  (`reminders/upcoming.md`), and reminds you at 8:00 and at 20:00 the evening before an event
  (see below)

## Tech stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.3 |
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM (`NotesViewModel`), manual DI in `NotesApplication` |
| Notes | A git clone of your repo, through JGit 5.13 (the last line that runs on Android 8); WorkManager for background syncs |
| Old notes | Room (`notes.db`), read once to move notes from the app's earlier local list into the repo |
| Navigation | navigation-compose |
| Speech to text | sherpa-onnx 2.0.3 (ONNX Runtime) with NVIDIA Parakeet TDT 0.6B v2 int8 and Silero VAD |
| Build | Gradle (wrapper), AGP 8.13, version catalog |
| SDK | minSdk 26, targetSdk 35, compileSdk 36 |

## Project layout

```
app/src/main/java/com/azizjon/notes/
  MainActivity.kt            # Compose entry point
  NotesApplication.kt        # owns the repository and voice services
  life/                      # The notes repo: clone, files, sync, three-way merge, token store
  reminders/                 # The reminder list: its rules, changes from Today, alarms, notifications
  data/                      # The old Room list (read once), editor session, private marker images
  ui/                        # Screens, marker picker/cropper, navigation, and theme
  voice/                     # Voice notes: recording, the speech model, speech to text
app/src/sharedTest/          # The clash harness, run on the PC and on a device
tools/speech-bench/          # Scores speech models on your own recordings (PC)
tools/sync-test-server/      # A local stand-in for GitHub, for trying sync on an emulator (PC)
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

## Your notes repo and sync

On first run, the app asks for a GitHub **fine-grained token** that you make yourself: repository
access "Only select repositories" (your notes repo), permission **Contents: Read and write**. The
app asks GitHub which repo the token opens, clones its `main` branch into private app storage, and
from then on syncs: when the app comes to the front, a few seconds after a note is closed, when the
app goes to the background (waiting for a connection if there is none), every 30 minutes, and with
the sync button. The token is encrypted with an AES-256-GCM key held in the Android Keystore,
excluded from backups, and given to git only as a password, never in the repo's URL.

A sync commits the phone's edits, fetches, and, when both sides changed, merges every file in
memory and puts one new commit on top of GitHub's, so the history stays a straight line that
`git pull --ff-only` on a computer can always follow. Where the phone and the computer changed the
same lines, both versions stay in the note between `<<<<<<< phone` and `>>>>>>> PC`; the note is
flagged, and one tap keeps the phone's, the computer's, or both. A note deleted on one side and
edited on the other is kept, edited. The app never force-pushes. If Android stops the app in the
middle of a sync, a small journal lets the next sync finish it.

This is checked by a **clash harness** (`app/src/sharedTest/.../ClashHarness.kt`) that makes random,
clashing edits on a phone and a computer, kills syncs at every step, and checks that no line is
lost, that both sides end with the same files, and that GitHub's history only moves forward. It
runs as a unit test and on a device:

```powershell
.\gradlew.bat testDebugUnitTest "-Pclash.seeds=40"
adb shell am instrument -w -e class com.azizjon.notes.life.ClashHarnessDeviceTest -e seeds 8 com.azizjon.notes.debug.test/androidx.test.runner.AndroidJUnitRunner
```

To try sync on an emulator without GitHub, serve a bare copy of a repo with
`tools/sync-test-server/serve.py` (git's http-backend behind a made-up token) and connect the debug
app to `http://10.0.2.2:8765/<name>.git`; only debug builds may use plain HTTP, and only to 10.0.2.2.

## Today and reminders

If the repo has a reminder list, `reminders/upcoming.md` (dated events with their tasks, follow-ups,
and questions, written as `- [ ] YYYY-MM-DD text`), the app opens on **Today**: the day's events
and tasks, tasks up to a week late, open questions, and the next 7 days' events. Tap an event to see
everything still open for it. Tick a task to mark it done (with Undo), or use its menu to skip it or
move it to another day; tap a question to answer it, typed or spoken. Each change edits that one
line and is a commit of its own, synced like any edit; while the list holds both versions of a
clash, Today changes nothing until you settle it.

At 8:00 a notification lists what is due, and at 20:00 the evening before an event another lists
what to prepare; tapping either opens Today. They come from exact alarms, set again after a restart
or a change of the clock, and a reminder missed while the phone was off comes when it starts, until
noon (morning) or 23:00 (evening). Once a day, after taking in GitHub's changes, the app also keeps
the list up: it adds the steps of an event's checklist (`reminders/checklists/<name>.md`), adds the
coming events of a checklist that repeats (`repeat: weekly Thu 10:30`, `monthly 1st,3rd Sat`, or
`yearly 06-18`), and moves events two weeks old to `reminders/past.md`. An answer to a question goes
to `reminders/inbox.md`.

These rules are a port of a small Python tool that the same list was written for, kept in step with
it: `ReminderPlanTest` holds its tests, and `ReminderParityTest` compares the two on a real list, day
after day, when given that tool's output (`-Preminders.parity=<folder>`).

## Deletion and recovery

Notes cannot be deleted by swiping the list. Use the delete button inside a note and confirm; the
file is removed from the repo at the next sync, and a copy waits in **Notebooks -> Trash** on the
phone until you restore it or choose **Delete forever**. The deleted note also stays in the repo's
history on GitHub.

## Autosave and editor recovery

Editing is local-first: each change is written to the note's file after 350 ms of idle time and at
least every two seconds during uninterrupted typing, and moving the app to the background also
writes at once. Each write replaces the file whole (a temporary file is renamed over it), so a crash
leaves the old text or the new, never half. A new note gets its file on its first save, and is named
after its title when you close it.

If Android closes the app while an editor is active, the app reopens that note in edit mode on the
next cold launch. **Back** and **Done** both wait for the latest local write and end that resumable
session. A failed write keeps the editor open with Retry and an explicit discard-and-leave option.
A new note left empty is removed.

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

## Status

- Working: notes in your GitHub repo with sync and clash handling, Markdown and rich editing, nested lists, automatic Markdown paste, crash-safe autosave/editor resume, search, visual notebook markers, recoverable Trash, themes, in-app auto-update, voice notes, Today and reminders from the repo's list
- Backlog: a capture box where an AI agent files what you type or say, tags, pinning, share/export
