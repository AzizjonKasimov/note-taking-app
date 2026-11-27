# Notes: People and privacy

The People tab (Network App until Notes 2.5) keeps private information about people: who they are,
dated notes of conversations, what they need, and what they can offer. This page says what it keeps,
where, and what leaves the phone. The rest of Notes, the notes repo, is described in the
[README](README.md).

## Where People's data lives

- In its own Room database on the phone (`people.db`), never in your notes repo, and apart from it
  in every way: the notes assistant cannot read it.
- Android's own cloud backup and device-to-device transfer leave out every People file (its
  database and its settings). The rest of Notes uses that backup for its settings.
- There is no account, analytics, telemetry, advertising, contact scraping, or automatic import of
  your address book.
- Manual editing, browsing, local search, and the check-in work without the AI gateway.

## The encrypted backup

People's backup is turned on in **People → Settings**. The whole People database (and the reports
below) is encrypted on the phone with AES-256-GCM, with a key made from your passphrase
(PBKDF2-HMAC-SHA256), and uploaded to a private GitHub repo you choose. The app refuses a repo that
is public. The GitHub token and the passphrase stay in Android's encrypted storage and are never in
the backup. Earlier versions stay readable only with the passphrase, in the repo's git history.

Notes writes `people-backup.enc.json` (debug builds: `debug-people-backup.enc.json`). It never writes
a backup with no people in it.

## Moving from Network App

Once, when you move: Network App 0.21 saves one last backup, then hands the check-in's answers to
Notes on the phone, app to app. Nothing leaves the phone in that hand-over. Network App sends them
only to Notes signed with Notes' own key, and Notes takes them only from Network App signed with
Network App's key. Then Notes opens Network App's last backup (`network-backup.enc.json`) with your
passphrase, puts its people in, and saves its own first backup. Network App's file stays as it was.

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

## Photos

You can attach up to three photos to a message (the photo picker, the camera app, or a share from
another app); none needs a storage or camera permission. A photo goes only with its message, after
the phone makes a new, smaller JPEG of it, which drops its metadata, such as location. It is held in
memory only: never stored, backed up, or sent again. The assistant's instructions forbid recognizing
anyone by face.

## Check-in

The **Check-in** asks about people your phone or chat apps show but your list does not have, and
about saved details that may be out of date. Each source is off until you turn it on:

- **Phone contacts**, with Android's contacts permission: only each contact's name and Android's
  lookup key are read.
- **Chat apps**, with Android's notification access: from Telegram, WhatsApp, KakaoTalk, and
  LinkedIn only, the sender's name, the app, and the time of one-to-one chats. Message text is never
  stored.
- **An evening reminder**, with Android's notification permission.

What the check-in collects stays on the phone, in its own table: it is not in the backup, the
assistant cannot read it, and nothing of it goes to the gateway. A name leaves the phone only in a
message you send.

## Drafts from your PC

Off until you turn it on in People's settings. The phone then makes a key pair; the private key
never leaves the phone, and the public key goes to the private data repo
(`people-backup-inbox/key.json`). `scripts/queue-draft.ps1` on your PC encrypts each draft to that
key, so the repo holds only ciphertext. The phone takes waiting drafts when the app opens, deletes
them from the repo, and shows them in the Assistant. A draft reaches the gateway only when you tap
**Send**.

## Reported answers

**Report a problem** on a reply stores, on the phone, your message, the reply, and its tool calls
(contact values removed). Reports travel only inside the encrypted backup and never go to the
gateway. `scripts/read-feedback.ps1` reads them on a PC with the passphrase.

## Lookups on your PC

`scripts/network-lookup.ps1` reads people from the encrypted backup on a PC: it refuses a public
repo, asks for the passphrase for every lookup, decrypts in memory, prints only what was asked for
(never a contact value), and writes nothing to disk. Whatever it prints goes to whoever runs it,
including an AI coding agent and that agent's provider.

## Keys

The AI key, the GitHub token for the backup, and the passphrase are entered by you, never built into
the app (releases are public), and kept in Android's encrypted storage. A key stored on a phone can
still be taken from a rooted or compromised phone; revoke it if the phone is lost.

## Deleting

Deleting a person deletes their notes and records. Backups keep data until they are replaced, and
their earlier versions stay in the private repo's git history. Taking back the assistant's
permission stops new requests but does not delete what the gateway's operator or Anthropic already
received.
