# My Reels 🎬

A tiny, fully offline Android app that turns your downloaded Instagram reels into
your own personal shorts feed. No internet, no ads, no algorithm — just the videos
sitting in your phone's `Movies/Instagram` folder.

Built for the Samsung Galaxy S23+ but works on any Android 10+ device.

## Features

- **Random feed** — opens straight into a random reel, fullscreen
- **Never repeats** — remembers every reel you've watched, permanently, even after
  closing the app or rebooting. When you've seen them all, it tells you and starts
  a fresh round
- **Swipe up** for the next reel, **swipe down** to go back to previous ones
  (YouTube Shorts style)
- **Tap to pause** — while paused you get a play button and a mute/unmute toggle
- **Red seek bar** at the bottom — drag it to jump to any point in the video
- **Auto-advance** — when a reel ends, the next random one plays
- **Picks up new downloads** automatically every time you open the app
- Under 100 KB. One Java file. Zero dependencies. Zero tracking.

## How it works

The app queries Android's MediaStore for every video whose path starts with
`Movies/Instagram`, filters out the ones you've already watched (stored as IDs in
SharedPreferences), shuffles the rest, and plays them one after another in a
fullscreen VideoView.

## Getting the APK

Every push to this repo automatically builds the APK via GitHub Actions:

1. Go to the **Actions** tab
2. Open the latest green **Build APK** run
3. Scroll to the bottom → **Artifacts** → download **MyReels-APK**
4. Extract the zip → install `app-debug.apk` on your phone
   (allow "install unknown apps" when prompted)

## Usage

| Action | Result |
|---|---|
| Open app | Plays a random unwatched reel |
| Swipe up | Next random reel |
| Swipe down | Previous reel |
| Tap screen | Pause / resume |
| Tap 🔊 (while paused) | Mute / unmute |
| Drag red bar | Seek within the video |
| Reel ends | Next one plays automatically |

## Requirements

- Android 10 or newer
- Videos located in `Internal storage > Movies > Instagram`
- Video access permission (asked on first launch)

## Project structure

```
app/src/main/java/com/myreels/app/MainActivity.java   ← the entire app
app/src/main/AndroidManifest.xml                      ← permissions & app name
app/src/main/res/                                     ← theme, colors, icon
.github/workflows/build.yml                           ← auto-builds the APK
```

## Customizing

- **App name**: change `android:label` in `AndroidManifest.xml`
- **Different folder**: change `"Movies/Instagram%"` in `MainActivity.java`
- **Reset watched history**: clear the app's storage in
  Settings > Apps > My Reels > Storage > Clear data
