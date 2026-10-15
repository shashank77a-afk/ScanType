# ScanType – build & release
1. Open this folder in Android Studio (Koala+), let Gradle sync.
2. Run on a device with Google Play services (the document scanner needs it).
3. Release: create a keystore (Build > Generate Signed Bundle), or put keystore.properties
   (storeFile, storePassword, keyAlias, keyPassword) in the project root, then:
   ./gradlew :app:bundleRelease  ->  app/build/outputs/bundle/release/app-release.aab
4. Handwriting accuracy: implement OcrEngine against your own secure backend in Engines.kt.


## Cloudflare Worker backend

The production Worker `scantype-api` is deployed separately from this Android repository. Keep its source in a dedicated backend repository and deploy it from version-controlled source. Do not commit secrets; configure `GEMINI_API_KEY` as a Cloudflare secret binding.

The currently active Worker version is managed directly in Cloudflare. Do not redeploy from this Android repository without syncing the backend source. Gemini model is controlled by the `GEMINI_MODEL` variable; `GEMINI_API_KEY` must remain a Cloudflare secret binding.
