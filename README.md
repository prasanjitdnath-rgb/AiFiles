# AI Files — File Manager + Gallery for Android 14+

Kotlin · Jetpack Compose · Material 3 (dynamic color) · Room · Coil · ML Kit · Media3 · BiometricPrompt

## Features
**Files** – storage card, category shortcuts, Recent, folder browser with breadcrumbs, sort, hidden files,
multi-select (copy / cut / paste / rename / delete / zip / share / move to vault).
**Gallery** – date-grouped grid, folder albums, AI smart albums, zoom viewer, multi-select, info sheet
(AI labels, OCR text, smart rename).
**Player** – built-in Media3 player for video and audio, with 0.5x–2x speed.
**Private vault** – move files into app-private storage, hidden from Gallery and other apps, unlocked with
fingerprint / device PIN, auto-locks when the app is backgrounded. (Sandboxed, not encrypted at rest.)
**AI (on-device)** – image labels, Latin OCR, perceptual hashing, natural-language search
("screenshots last week", "bills", "videos today"), similar-photo finder, large file / old screenshot / APK cleaner.
**Cloud OCR (opt-in)** – Bengali / handwriting via the Gemini API with your own key (Settings card in the AI tab).
Images are sent to Google only when you use it; the key is stored in app-private preferences.

## Build
Open in Android Studio (Koala or newer) and run, or `gradle assembleDebug` (JDK 17).
GitHub Actions (`.github/workflows/build.yml`) builds a debug APK on every push.
minSdk 34, targetSdk 34.

## Notes
* Needs All files access (`MANAGE_EXTERNAL_STORAGE`); Google Play requires a declaration for this permission.
* Indexing runs while the app is open; move `Indexer` to WorkManager for background scans.

## License
MIT
