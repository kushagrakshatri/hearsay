# Hearsay privacy policy

_Last updated: November 16, 2026_

Hearsay is a voice dictation app built so your words stay on your phone.

## What stays on your phone

- **Your voice.** Audio is recorded only while you hold or tap the mic. It stays in memory, is turned into text on the phone, and is then thrown away. It is never saved to storage and never sent anywhere.
- **Your text.** Transcription and cleanup run on-device. Hearsay keeps no history of what you dictate.
- **Your dictionary and snippets.** They're stored in Hearsay's private app storage. This includes words Hearsay learns when you fix a name.

## What the floating mic reads

The floating mic uses Android's accessibility service, and only to do these things:

- It reads the text box you're typing in (its app, text and cursor) so it can put your words at the cursor and pick a writing style for that app.
- After it inserts text, it watches that text box for a short time. If you fix a misheard name, it can add that word to your dictionary. Only the word is saved.
- If **Spell names on screen** is on (the default), it reads the visible text in the app when you stop talking and picks out the names, so it spells them the same way. It uses them for that one dictation and saves nothing. You can turn this off in Settings → Floating mic.
- It never reads password fields, and it doesn't perform actions you didn't ask for.

## Network use

- **Model downloads.** On first run, Hearsay downloads the speech and cleanup models you pick from their public hosts (Hugging Face and GitHub). These are plain file downloads, checked against fixed SHA-256 hashes.
- **Optional: ChatGPT or your own API key.** Off by default. If you sign in with ChatGPT or add your own OpenAI-compatible key, the transcript, plus any names picked up from the screen for that dictation, goes to that provider (audio never does, unless you also turn on cloud speech with your own key) for cleanup and voice edits. That provider's privacy policy then applies. Sign-in tokens and keys stay in Hearsay's private storage, and signing out revokes them. The F-Droid build has no cloud features at all.

## Testing builds: diagnostics sent to the developer

Testing builds (the builds published on GitHub while Hearsay is being tested) can send diagnostics to the developer through Sentry (sentry.io). This is controlled by **Settings → Testing → Send test logs to developer (testing builds)**, which is on by default in those builds. While it is on, Hearsay sends:

- **Crash reports**, including app freezes (ANRs) and crashes in the on-device speech and language models, with the device model, Android version and app version.
- **Engine problems Hearsay recovered from**, such as a model that couldn't start on the graphics chip, a speech engine falling back to another, or a model download that failed its checksum.
- **For each dictation:** its timings (the "Last dictation" and "Last cleanup" numbers), which models and engines ran, the writing style and the app you dictated into, and **the dictated text: the words as heard and the text Hearsay typed.** If you undo to the words as heard or edit the inserted text soon after, the changed text is sent too.

Audio is never sent. No account, name, email or IP address is attached (Sentry's default personal-data collection is off). Turn the switch off and nothing more is sent.

Public builds (Google Play and F-Droid) are built with telemetry compiled out: they contain no Sentry code and send none of this.

## No tracking

Apart from the testing-build diagnostics above, Hearsay has no servers, analytics, ads, crash reporting or tracking.

## Children

Public builds don't collect personal information from anyone, including children. Testing builds send the diagnostics described above only while their switch is on.

## Contact

Open an issue at https://github.com/kushagrakshatri/hearsay/issues.
