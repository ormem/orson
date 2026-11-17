# Orson setup guide

For Claude Code. Turns an old Android phone into Orson, a voice device for [Ormem](https://ormem.app). Ormem stays a hosted service; only the Orson app runs on the phone.

Work through the steps in order. Explain each one to the person in plain language.

## Rules

- A factory reset erases the phone. Get a clear yes first, after the backup is confirmed.
- Never unlock the bootloader or flash anything.
- The person tests the voice. Don't play test audio; read the logs while they speak.
- Don't screenshot the phone while they may be typing a password.
- Never print or save the Ormem key. Don't commit it.
- Remove packages in small batches, and restart and check after each one.

## You need

- A computer with `adb`
- An Android phone, Android 8 or newer, with a USB data cable
- An Ormem API key from app.ormem.app, under Account. The Free plan includes 100 voice calls a month.
- Wi-Fi the phone can reach

## 1. Connect and identify

Ask them to turn on USB debugging: tap Build number 7 times in About phone, then enable it in Developer options. Set USB to Transfer files, and tap Allow on the phone.

```bash
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb shell df -h /data
```

Stop if the phone has no `arm64-v8a` or `armeabi-v7a`, or runs below Android 8.

If `adb devices` is empty, check the cable first: many only charge.

## 2. Back up

Ask where to save it, and check there's enough free space.

```bash
adb pull -a /sdcard/ "<backup folder>/files"
```

Compare the file count with the phone. Android can list a folder twice, so remove duplicates before comparing.

USB can't reach contacts, messages, call history or app data. Ask them to use the maker's backup tool (HiSuite on Huawei, Smart Switch on Samsung) or their Google account. Remind them to back up chat apps such as WhatsApp from inside the app.

Don't continue until they confirm both backups are finished.

## 3. Factory reset

Get their go-ahead. They do this on the phone, in Settings under Reset.

During setup afterwards:

1. Connect to Wi-Fi.
2. **Skip every account.** Android refuses step 4 if any account is on the phone.
3. Turn USB debugging back on.
4. On Huawei, also turn on "Allow ADB debugging in charge only mode". Otherwise USB debugging drops after each restart.

Check that no accounts remain:

```bash
adb shell dumpsys account | head -3
```

## 4. Install Orson

Use the latest release APK, or build it (see Building).

Copy the speech model onto the phone. It's too big to go inside the app. Without it, Orson falls back to a less accurate built-in model.

```bash
./scripts/fetch-models.sh
adb shell mkdir -p /sdcard/Android/data/app.ormem.orson/files/asr/parakeet
adb push models/parakeet/. /sdcard/Android/data/app.ormem.orson/files/asr/parakeet/
```

```bash
adb install orson.apk
adb shell pm grant app.ormem.orson android.permission.WRITE_SECURE_SETTINGS
adb shell dpm set-device-owner app.ormem.orson/.AdminReceiver
adb shell cmd package set-home-activity app.ormem.orson/.MainActivity
```

## 5. Add the Ormem key

Ask for their key and name. Run this so the key isn't saved in shell history:

```bash
adb shell am start -n app.ormem.orson/.MainActivity --es ormem_key "$ORMEM_KEY" --es name "Their name"
```

The main screen should show "Ormem connected" within a few seconds.

## 6. Strip the phone

List what's installed, and sort it into keep and remove:

```bash
adb shell pm list packages -s
adb shell pm list packages -3
```

**Keep:** `android`, `com.android.systemui`, `com.android.phone`, `com.android.settings`, `com.android.shell`, `com.android.keychain`, `com.android.location.fused`, `com.android.managedprovisioning`, `com.android.providers.settings`, `com.android.providers.media`, `com.android.providers.telephony`, `com.android.providers.contacts`, the package installer, the WebView, one keyboard, anything named for audio, Wi-Fi, overlays or display, and the maker's core system service.

**Remove:** Google apps and services, social apps, games, the app store, browser, gallery, camera app, music, mail, calendar, and the maker's extras.

Removing a package only hides it for this user, so it can be restored:

```bash
adb shell pm uninstall -k --user 0 <package>
adb shell pm disable-user --user 0 <package>   # if uninstall is refused, or the package may be needed
```

Save what you removed in a folder outside this repo; it's specific to their phone.

Some makers re-enable packages after a restart. Orson hides the ones in `Kiosk.HIDE` at every start; that list is for Huawei, so adjust it for other makers and rebuild.

## 7. Tune

```bash
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put global stay_on_while_plugged_in 7
```

## 8. Check

Restart, then confirm:

```bash
adb reboot
adb wait-for-device
adb shell dumpsys activity activities | grep -E "mResumedActivity|mLockTaskModeState"
adb shell dumpsys audio | grep -E "rec (start|stop)" | tail -1
```

Orson should be on screen, with the mic recording. Then ask them to say "Orson, my favourite colour is blue", and ask about it afterwards. The log shows how Ormem sorted each request, such as `converse remember`. Read what happened:

```bash
adb logcat -d | grep " Orson "
```

If the wake word misses, suggest Tap to talk and a different wake word in Settings. Rare names are recognised poorly.

## Undo

- Restore a package: `adb shell cmd package install-existing <package>`
- Re-enable a package: `adb shell pm enable <package>`
- Everything: factory reset from recovery mode, using the phone's buttons.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `set-device-owner` fails | An account is still on the phone. Remove it, or reset again. |
| Phone gone from `adb` after restart | Orson Settings, Device, unlock, then set USB to Transfer files. |
| Mic seems dead | Raise Mic boost in Settings, Voice. |
| Ormem unreachable | Check Wi-Fi in Settings, then the key. |
| "No memories yet" | Nothing is saved yet. Tell Orson something first. |

## Building

```bash
./scripts/fetch-models.sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`. Use the release build: the debug build is much slower.
