# Liquid Recorder

**English** | [Español](README.es.md)

Installs on the phone as **Recorder** (*Grabadora* in Spanish).

A free, tracker-free voice recorder with the same liquid-glass interface as the rest of the suite
([Liquid Clock](https://github.com/mrsalvaa/liquid-clock), Calendar, Contacts, Phone, Gallery and
Calc). Settings also has the Solid style.

**Privacy in three lines:** the app has no internet permission. Recordings live in its private
folder and only leave the phone if you share or export them. No ads, telemetry, crash reporting or
Google Play Services.

Licensed under GPL-3.0-or-later.

## Features

- **Best possible quality**: floating-point capture at 44.1 or 48 kHz, mono or stereo, to lossless
  FLAC (default), 16- or 24-bit WAV, 128–320 kbps AAC or 64–256 kbps Opus. Gain, microphone (phone,
  wired, USB, Bluetooth), processing (standard, unprocessed, voice, ambience).
- **While recording**: waveform or spectrogram (FFT, log scale), dBFS meter with peak hold and
  clipping warning, a warning when a call takes the microphone, screen-off recording with a
  notification (pause, marker, stop).
- **Markers**: created instantly and named without pausing; rename or delete them while recording
  continues. When playing: jump between markers, add, rename and share them as text.
- **Playback**: waveform with markers, 0.5×–2× speed, skip silences, lock-screen controls. Opens
  audio and **video** files from the phone (sound only) and can save that audio to the recordings.
- **Editing**: trim, delete a part and split (markers move with the audio), as a copy or replacing
  the original (which goes to the trash).
- **Remove background noise**: makes a clean copy (the original isn't touched), no AI, all on the
  phone: 80 Hz high-pass, automatic noise profile, two-pass spectral subtraction, optional extra
  polish (non-local means) and EBU R128 loudness at −16 LUFS. Progress notification.
- **Organizing**: multi-select, folders, tags, favorites, accent-insensitive search (markers too) and a 30-day trash.
- **Export** to another format through the system file picker; **share** the original.
- **Shortcuts**: "Record" quick settings tile, widget (marker and stop without opening the app) and
  app-icon shortcut.
- **Recovery**: if the phone dies while recording FLAC or WAV, it is recovered up to the last 5
  seconds when the app opens again.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Recording. Asked the first time you tap Record. |
| `POST_NOTIFICATIONS` | Recording and playback notifications. Asked together with the microphone; recording works without it. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Keep recording with the screen off or the app in the background. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep playing in the background, with lock-screen controls. |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep a long task (cleaning, exporting) running with its progress notification if you leave the app. One or the other depending on the Android version. |
| `WAKE_LOCK` | Keep the CPU awake during a recording or playback. |
| `MODIFY_AUDIO_SETTINGS` | Turn on a Bluetooth microphone chosen in Settings. |

No internet, storage or location. `INTERNET` and `ACCESS_NETWORK_STATE` are explicitly removed from
the merged manifest. Outside files are opened through the system picker or "Open with", with a
temporary permission on that file only.

## Dependencies

| Library | License | Use |
|---|---|---|
| Jetpack Compose, AndroidX (core, activity, lifecycle) | Apache-2.0 | UI |
| kotlinx.serialization | Apache-2.0 | Settings and library as JSON |
| [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | Glass effect |
| Media3 ExoPlayer and Session | Apache-2.0 | Playback and notification controls |

FLAC and WAV are written by the app itself (`core/audio`); AAC and Opus use the system encoders
(`MediaCodec`). Without the internet permission, the HTTP code Media3 ships can't connect.

## Download

See the [changelog](CHANGELOG.md) for what's new in each version.

Get the APK from [Releases](../../releases) and install it (Android 12 or later).

Check the file wasn't altered:

```bash
sha256sum -c liquid-recorder-v1.2.0.apk.sha256
```

SHA-256 of `liquid-recorder-v1.2.0.apk`:

```
4c5ea410557da260b74ba5d888b3bce1d6ad9a55445f23a65842fed23904007a
```

And that it's signed with the project key (certificate SHA-256 fingerprint):

```
22:90:B6:A3:5D:46:53:AD:7E:54:3B:3D:DE:B7:01:66:1F:A9:80:7B:E8:EC:CF:22:57:D2:D6:66:5A:42:AA:59
```

```bash
apksigner verify --print-certs liquid-recorder-v1.2.0.apk
```

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test assembleDebug
```

To check real smoothness on the phone, use `assembleProfile` (optimized like the release, installs as
the test build): Compose debug builds are several times slower.

`:core:test` uses the system's `flac`, `metaflac` and `ffprobe`, when present, to validate the
generated files (including the FLAC MD5).

Debug and profile install as `dev.liquidrecorder.debug`, "Recorder (test)".
