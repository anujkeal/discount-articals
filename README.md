# BF Discount Scanner

Offline Android barcode scanner for Black Friday discounts. Kotlin, Jetpack Compose, CameraX and bundled Google ML Kit barcode scanning.

## Build
Open in Android Studio or run `gradle assembleDebug`. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds on every push to `main` and uploads the APK as `bf-discount-scanner-debug`.

## Update discount data
Replace `app/src/main/assets/discounts.json` with the current export, then rebuild. To extract it directly from the original HTML:

```bash
python tools/extract_data.py "BF Discount Scanner.html"
gradle assembleDebug
```

The JSON format matches the HTML `const D` object (`codes` and `rows`). The app loads it once at startup and uses in-memory hash maps and sorted prefix indexes. No runtime network or database access is used.

Camera permission is required only for scanning; manual prefix search works without it.
