# Notes: People and privacy

The People tab (Network App until Notes 2.5) keeps private information about people: who they are,
dated notes of conversations, what they need, and what they can offer. This page says what it keeps,
where, and what leaves the phone. The rest of Notes, the notes repo, is described in the
[README](README.md).

## Where People's data lives

- In its own Room database on the phone (`people.db`), which is what counts.
- A copy in your notes repo, which must be private on GitHub: a few seconds after each change,
  People writes the `people/` folder, one JSON file per person and `reports.json` (below), and the
  next sync pushes it with your notes. Contact values (phone numbers, emails) are never written
  there; they stay on the phone. Anyone who can open your notes repo can read these files, and
  git keeps their earlier versions in the repo's history.
- Before saving, People asks GitHub whether the repo is private (at most once an hour) and saves
  nothing into a public repo, or while it cannot ask.
- Android's own cloud backup and device-to-device transfer leave out every People file (its
  database and its settings). The rest of Notes uses that backup for its settings.
- There is no account, analytics, telemetry, advertising, contact scraping, or automatic import of
  your address book.
- Manual editing, browsing, local search, and the check-in work without the AI gateway.

## Moving from Network App

Once, when you move: Network App 0.21 saves one last backup to its own data repo (if backups are
set up), then hands everything to Notes on the phone, app to app: every person and record, the
reports, and the check-in's answers. Nothing leaves the phone in that hand-over. Network App sends
it only to Notes signed with Notes' own key, and Notes takes it only from Network App signed with
Network App's key, and only while its People is empty. Notes then writes the `people/` folder.

## The assistant

The **Assistant** section of People takes what you tell it about people and answers questions about
them. The first message asks once for permission; you can take it back in People's settings, and
without it everything else still works.

Each message starts an agent turn on your self-hosted AI gateway, with the app's one AI key
(Settings → Assistant). The turn carries your message, a few earlier messages, the date and time
zone, the assistant's instructions, and the list of its tools, but no saved records. The assistant
then asks for tool calls; the phone runs each one against `people.db` and sends back only that
call's result. A result can hold names, positions and education, notes and their dates, needs,
capabilities, background facts, record ids, and statuses. It never holds a saved contact value; a
profile says only whether one is saved. A contact value you type into a message is sent with it.

Tool results reach the gateway's operator and Anthropic, like the message. The gateway keeps no
tool results after the turn and logs only tool names.

**The people assistant never searches the web or opens web pages.** Its turns never ask the gateway
for web tools. (Notes saved by Network App's earlier web lookups keep their label.)

What the assistant saves is written at once and listed under its reply with **Undo**. Deleting a
person, a note, or a record, and merging two people, never happen on the assistant's word: they wait
on a card for your tap, and cannot be undone afterwards.

Earlier messages are sent again with a new one (at most six, and 3,000 characters), from memory
only; they are never stored.

The "tell me anything" assistant on Today works on your notes repo, so it can also read a person's
file in `people/` when a question needs it, which sends that file to the gateway like any note it
reads. It cannot change those files.

## Photos

You can attach up to three photos to a message (the photo picker, the camera app, or a share from
another app); none needs a storage or camera permission. A photo goes only with its message, after
the phone makes a new, smaller JPEG of it, which drops its metadata, such as location. It is held in
memory only: never stored, saved to the repo, or sent again. The assistant's instructions forbid
recognizing anyone by face.

## Check-in

The **Check-in** asks about people your phone or chat apps show but your list does not have, and
about saved details that may be out of date. Each source is off until you turn it on:

- **Phone contacts**, with Android's contacts permission: only each contact's name and Android's
  lookup key are read.
- **Chat apps**, with Android's notification access: from Telegram, WhatsApp, KakaoTalk, and
  LinkedIn only, the sender's name, the app, and the time of one-to-one chats. Message text is never
  stored.
- **An evening reminder**, with Android's notification permission.

What the check-in collects stays on the phone, in its own table: it is not in the `people/` files,
the assistant cannot read it, and nothing of it goes to the gateway. A name leaves the phone only in
a message you send.

## Drafts from your PC

`scripts/queue-draft.ps1` on your PC puts a draft, as a plain text file, in your notes repo's
`people/drafts/` through GitHub. After the phone's next sync it waits above People's message box,
and the phone deletes the file once you send, edit, or discard it. A draft reaches the gateway only
when you tap **Send**.

## Reported answers

**Report a problem** on a reply stores, on the phone, your message, the reply, and its tool calls
(contact values removed). Reports are saved in `people/reports.json` in your notes repo and never go
to the gateway. Delete one in People's settings once its fault is fixed.

## Lookups on your PC

`scripts/network-lookup.ps1` reads the `people/` files of your notes repo's clone on a PC and prints
only what was asked for: one person's profile with their latest notes, or the people whose records
hold some words (never a contact value). Whatever it prints goes to whoever runs it, including an AI
coding agent and that agent's provider.

## Keys

The AI key and the GitHub token for your notes repo are entered by you, never built into the app
(releases are public), and kept sealed with the Android Keystore. A key stored on a phone can still
be taken from a rooted or compromised phone; revoke it if the phone is lost. People has no key or
passphrase of its own.

## Deleting

Deleting a person deletes their notes and records, and the next save removes their file from your
notes repo; earlier versions stay in the repo's git history. A save that would remove the files of
more than five people at once waits for you to confirm it in People's settings. Taking back the
assistant's permission stops new requests but does not delete what the gateway's operator or
Anthropic already received.
