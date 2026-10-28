# Burrow

Chai-style character chat on your Android phone, with the model running on your own Windows PC in LM Studio, so your chats stay between your phone and your PC

There are two parts, the host is a small tray app for Windows that sits between your phone and LM Studio, and the app is the Android side where your characters and chats live

This project is AI assisted, I made the calls on how it works and what it does and the code was written with help from AI

## Security and privacy

- Your phone talks to your PC over Tailscale with TLS on top, and the PC's certificate gets pinned when you pair, so if anything else tries to pretend to be your PC the app refuses it
- Everything the app saves on your phone is encrypted with a key that lives in the phone's hardware, and none of it goes into cloud backups or device transfers
- The host only listens on your PC's Tailscale address, never on your local network or the internet
- Before handing anything to LM Studio the host checks that whatever is on port 1234 is running from LM Studio's install folder, which stops another app taking the port by accident but won't stop something already running as you
- Paired phones are stored on the PC as token hashes, encrypted to your Windows account
- If LM Studio is set to serve on your local network the host stops using it and tells you to turn that off, since LM Studio's API has no password

Heads up, LM Studio can save full requests in its logs, which means your chats sitting on disk in plain text, so turn off sensitive data logging and verbose logging in its Developer settings and check them again after LM Studio updates

## What you need

- A Windows PC with LM Studio installed normally (default folder) and at least one model downloaded
- An Android phone on Android 9 or newer
- Tailscale on both, same account
- Visual Studio 2022 or newer with the C++ desktop workload to build the host, it comes with CMake and Ninja
- JDK 21 + the Android SDK to build the app

## Building the host

Run `host\build.cmd`, it finds Visual Studio on its own and drops `Burrow.exe` into `host\build`

## Building the app

From `android` run `gradlew assembleDebug` for a debug build, that one is for development and testing only since anyone with USB debugging on your phone can read its data, if your Java is newer than 21 point Gradle at JDK 21 with `JAVA_HOME` or `org.gradle.java.home` in your user `gradle.properties`

For the build you actually use, make your own signing key and put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in `android\keystore.properties`, and never commit the key or that file since whoever has them can push updates your phone will trust

No `keystore.properties` means `gradlew assembleRelease` still builds, the APK just comes out unsigned

## Pairing

Open LM Studio and turn on its local API in the Developer tab, then hit Pair phone in the host's tray menu, you'll get a one-time code and your PC's Tailscale address, type both into the app and that's it, from then on it only trusts that PC's certificate

Models load from the tray or from the Models sheet in the app (that's also where you pick the context window), nothing loads until you pick one

## Security tests

The tests in `android\app\src\androidTest` check the stuff that would hurt most if it broke: plaintext ending up in stored data, tampered files getting accepted, the key not being hardware-backed, an impostor certificate getting through and cleartext being allowed

They run on the debug package and need the host running at whatever address you pass in, and they replace the debug build's chats, characters, profile and pairing with test data, so never run them on a debug install you care about

```
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.hostAddress=100.x.y.z"
```

The hardware key one will fail on an emulator, emulators don't have secure hardware

## Where things are stored

On the PC

| What | Where |
|---|---|
| Paired phones | `%LOCALAPPDATA%\Burrow\devices.bin` |
| TLS certificate | Your Windows account's Personal certificate store |
| Certificate key | `BurrowHostKey` in the Microsoft Software Key Storage Provider |
| Settings and start with Windows | `HKCU\Software\Burrow` and the `Run` key |

On the phone, all inside the app's private storage

| What | Where |
|---|---|
| Chats and characters | `chats.pb` and `characters.pb` |
| Profile and pairing | `profile.pb` and `pairing.pb` |
| Pictures | `pictures` folder |
| Encryption key | Shared preferences, wrapped by a key in the Android Keystore |

## Repository structure

- `host` is the Windows tray host, written in C++ against Win32 and Schannel
- `android` is the Android app, Kotlin and Jetpack Compose, with the security tests in `app\src\androidTest`

## Licence

AGPL-3.0, see `LICENSE`
