# My Reels

A tiny offline Android app that plays random reels from **Internal storage > Movies > Instagram**, never repeating one you've already watched (even after closing the app).

- Opens straight into a random reel, fullscreen, sound on
- When a reel ends → next random reel automatically
- Tap the screen → skip to the next random reel
- Watched reels are remembered forever; when you've seen them all, it tells you and starts a fresh round
- New downloads into Movies/Instagram are picked up automatically next time you open the app

## Easiest way to get the APK (no software needed, ~5 min)

1. Create a free account at github.com (if you don't have one)
2. Make a **new repository** (any name, can be private)
3. Upload everything in this folder to the repo (drag and drop on the GitHub website works — make sure the `.github` folder is included)
4. Go to the **Actions** tab → the "Build APK" workflow runs automatically (~3 min)
5. Open the finished run → download the **MyReels-APK** artifact → it's a zip containing `app-debug.apk`
6. Send the APK to your phone, tap it, allow "install unknown apps" when Samsung asks, install
7. Open My Reels → tap **Allow** when it asks for video access → enjoy

## Alternative: build on a PC

Install Android Studio, open this folder, press Run with your S23+ plugged in (USB debugging on).
