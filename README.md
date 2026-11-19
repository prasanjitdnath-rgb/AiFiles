# AI File Manager

A polished Android file manager and gallery with on-device AI features, smart cleanup, and a private vault.

Built with **Kotlin + Jetpack Compose + Material 3**, Room, Coil, ML Kit, Media3, and BiometricPrompt.

## Highlights

### Files
- Storage overview with usage card and category shortcuts
- Recent files, folder browser with breadcrumbs, sorting, and hidden files
- Multi-select with copy, cut, paste, rename, delete, zip, share, and move to vault
- Built-in audio/video player with playback speed controls

### Gallery
- Date-grouped grid, folder albums, and AI-generated smart albums
- Fullscreen viewer with pinch zoom, double-tap zoom, and photo details
- Multi-select, sharing, deletion, AI labels, OCR text, and smart rename

### AI tools
- On-device image labeling, Latin OCR, perceptual hashing, and duplicate detection
- Natural-language search such as `screenshots last week`, `bills`, `videos today`
- Cleanup suggestions for similar photos, large files, old screenshots, and APKs
- Optional cloud OCR via your own Gemini API key for handwriting and Bengali text

### Private vault
- Move files into app-private storage, hidden from Gallery and other apps
- Unlock with fingerprint, face, or device PIN
- Auto-locks when the app goes to the background

## Privacy

By default, indexing, OCR, and image analysis run entirely on your device. Cloud OCR is opt-in and only sends an image when you use that feature. Your API key is stored in app-private preferences.

The vault is sandboxed, not encrypted at rest. It is not a substitute for encrypted storage if you need protection against physical device compromise.

## Build

Requirements:
- Android Studio Koala or newer
- JDK 17
- Android SDK 35 installed

Open the project in Android Studio and run the `app` configuration, or build from the command line:

```bash
gradle assembleDebug
```

GitHub Actions builds the debug APK on push and pull request.

minSdk 34, targetSdk 34.

## Permissions

The app requests:
- Media permissions (images, video, audio)
- All files access via `MANAGE_EXTERNAL_STORAGE` for full file management
- Biometric authentication for the private vault
- Internet only for the optional cloud OCR feature

Google Play requires a declaration for `MANAGE_EXTERNAL_STORAGE`.

## Notes

Indexing runs while the app is open. For production background scanning, move `Indexer` to WorkManager.

## License

MIT
