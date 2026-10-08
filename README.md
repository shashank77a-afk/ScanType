# ScanType – build & release
1. Open this folder in Android Studio (Koala+), let Gradle sync.
2. Run on a device with Google Play services (the document scanner needs it).
3. Release: create a keystore (Build > Generate Signed Bundle), or put keystore.properties
   (storeFile, storePassword, keyAlias, keyPassword) in the project root, then:
   ./gradlew :app:bundleRelease  ->  app/build/outputs/bundle/release/app-release.aab
4. Handwriting accuracy: implement OcrEngine against your own secure backend in Engines.kt.
