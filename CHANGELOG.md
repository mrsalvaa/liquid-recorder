# Changelog

## Unreleased

- Multi-select: the options at the bottom go in two rows, with bigger text.
- Remove background noise: changing the intensity (or any dropdown inside a window) closed the whole window. Now the list opens on top and goes back to the window.

## 1.2.1

- New app icons. The default (Liquid) icon has a sober graphite background instead of the cyan and pink glow, and its accent is now the same red across the suite. The Material icon is a solid color with a smaller white drawing, without the light-blue frame.
- New Samsung-style icon.
- Settings: "Accent color" is now just "Accent".

## 1.2.0

First release.

- Records to FLAC (default, lossless), 16- or 24-bit WAV, AAC (M4A) or Opus (OGG), at 44.1 or 48 kHz, mono or stereo.
- Floating-point capture, -6 to +24 dB gain, choice of microphone (phone, wired, USB or Bluetooth) and processing (standard, unprocessed, voice or ambience).
- While recording: waveform or spectrogram, dB level meter with peak hold and clipping warning, and a warning when another app (a call) takes the microphone.
- Markers while recording: creating one opens a window to name it without pausing; they can be renamed or deleted while recording continues.
- Keeps recording with the screen off; notification with pause, marker and stop.
- If the phone dies in the middle of a FLAC or WAV recording, it is recovered the next time the app opens.
- Playback with waveform, markers, 0.5× to 2× speed, skip silences and lock-screen controls.
- Opens audio and video files from the phone (only the sound of videos is played) and can save that audio to the recordings.
- Trim, delete a part and split, as a copy or replacing the original.
- Folders, tags, favorites, accent-insensitive search and a 30-day trash.
- Share, export to another format and share markers as text.
- "Record" quick settings tile, home-screen widget and app-icon shortcut.
- Settings use dropdowns (format, quality, sample rate, gain, skip and speed), like Contacts.
- Recording controls in a floating glass bar; the spectrogram is free, edge to edge.
- Slower, continuous waveform and spectrogram (they glide at the display's refresh rate).
- Animated back and forward buttons; "[▶" and "◀]" to set the selection when editing.
- Spanish texts in neutral Spanish. "Trim and split" is now "Edit"; thinner meter.
- Remove background noise: a clean copy of one or more recordings with high-pass, automatic noise profile, two-pass spectral subtraction, optional extra polish and loudness to −16 LUFS. No AI, all on the phone, with a progress notification.
- Multi-select in Recordings (long press): favorite, move, tags, share, export to a folder and delete.
- "Discard" when a recording ends; the now-playing pill shows "2:11 / 3:12".
- Trimming and deleting a part apply directly (the original goes to the trash); only splitting asks. Fixed: replacing the original showed an error.
- Free-standing waveform like the spectrogram, a record bar that stretches without clipping, and screens that reach the bottom edge.
- Liquid Glass or Solid, theme, background, accent color and icon, like the rest of the suite.
