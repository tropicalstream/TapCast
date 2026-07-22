# TapCast 🎙️🥽

A podcast player for the **RayNeo X3 Pro** smart glasses — subscribe, browse
episodes, and listen, all from the face-worn display, with no phone app
required.

Built native-Android, following the same patterns as the rest of the Tap*
suite (binocular SBS input, dwell-to-commit navigation) plus RayNeo's Mercury
SDK for the AR launcher/gesture bridge.

## Features

- **Subscriptions** via iTunes/podcast-index search, or bulk **OPML import**
  (Pocket Casts, Apple Podcasts, Overcast, Castro, AntennaPod all export this
  format) — drop a file in and every show it lists gets added at once.
- **Playback**: resume-where-you-left-off, variable speed, skip ±, sleep
  timer, autoplay-next.
- **Offline downloads** — episodes cache to device storage and play without
  a connection.
- **Voice search**: hold to record on the glasses' mic, transcribed through
  Groq's Whisper API. The Groq key lives on-device only, entered masked in
  Settings — nothing is hardcoded or bundled.
- **Artwork cache** — disk-backed so the show gallery works offline and
  doesn't re-fetch on every scroll.

## Build

Requires the two RayNeo vendor AARs already vendored under `app/libs/`
(Mercury Android SDK + RayNeo IPC SDK). Standard Gradle build from there:

```
./gradlew assembleDebug
```

## Credits

RayNeo Mercury Android SDK and RayNeo IPC SDK — © their respective owners,
used under RayNeo's developer terms for X3 Pro app development.
