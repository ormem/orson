# Orson

Android app that turns an old phone into an Ormem voice device. Package `app.ormem.orson`, Kotlin, no Google services.

## Setting up a phone

When asked to set up a phone, follow [SETUP.md](SETUP.md) step by step. Explain each step in plain language.

- Get a clear yes before a factory reset, and only after the backup is confirmed.
- Never unlock the bootloader or flash anything.
- The person tests the voice. Don't play test audio; read `adb logcat -d | grep " Orson "` while they speak.
- Never print, log or save the Ormem key, and never commit it.
- Phones differ: check the maker and Android version, and adapt. `Kiosk.HIDE` lists packages hidden on a Huawei phone; edit it for other makers.

## Building

- `./scripts/fetch-models.sh` first: models and the speech library aren't in git.
- Use `gradle assembleRelease`. The debug build is too slow for speech.
- Long builds and model downloads can take minutes; run them where they won't time out.

## Code map

- `MainActivity`: main screen, conversation, sends requests to Ormem `/v1/converse`.
- `Speech`: mic, wake word, end of speech, speech to text.
- `Voice`: text to speech (Piper).
- `Ormem`: API client. `KeyVault`: encrypts the key with the Android Keystore.
- `SettingsActivity`, `PinPad`, `ScanActivity`: settings behind a PIN, QR key scan.
- `Kiosk`: device owner, lock task, hidden packages.
