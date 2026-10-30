# Development notes

For people who want to build, change or debug NestCast TV. Users: see the [README](../README.md).

## Requirements

- Android Studio (or just the Android SDK) with **SDK Platform 36** and a **JDK 17** (Android Studio includes one).
- The app: Kotlin 2.1, Jetpack Compose, AndroidX Media3 1.5.1 (ExoPlayer), Coil for posters, ZXing for the Support QR code. `minSdk` 23, `compileSdk`/`targetSdk` 36. Only `armeabi-v7a` and `arm64-v8a` are built (TV devices are ARM; it keeps the FFmpeg native libraries small).
- Audio the TV can't decode itself (E-AC-3, AC-3, DTS, TrueHD...) is handled by the Jellyfin FFmpeg extension for Media3 (`org.jellyfin.media3:media3-ffmpeg-decoder`), which is GPL-3.0. That is why the app is GPL-3.0.

## Building

Open the folder in Android Studio and let Gradle sync, or use the wrapper:

```powershell
.\gradlew.bat assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease    # app\build\outputs\apk\release\app-release.apk
.\gradlew.bat bundlePlay         # app\build\outputs\bundle\play\app-play.aab
```

Build types:

| Type | Signed with | Purpose |
| --- | --- | --- |
| `debug` | debug key | Development; slow on a TV dongle (not minified). |
| `release` | local debug key | Optimised build for sideloading onto your own TV. Installs over the previous release build. |
| `sideload` | sideload key | The APK published on GitHub Releases (Support screen included). Needs `keystore-sideload.properties` and `keystore/nestcast-sideload.jks`. |
| `play` | upload key | The Google Play bundle. Hides the Support (donation) screen (`BuildConfig.SHOW_SUPPORT = false`). |

The `play` type reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and the keystore in `keystore/`. Both are git-ignored; never commit them. Bump `versionCode` in `app/build.gradle.kts` for every Play upload.

### Publishing the GitHub APK

Set `versionName` (and raise `versionCode`) in `app/build.gradle.kts`, commit, then push a matching tag:

```powershell
git tag v0.9.2
git push origin v0.9.2
```

`.github/workflows/release.yml` checks that the tag matches `versionName`, builds `assembleSideload` and attaches `NestCast-TV-v0.9.2.apk` to the release. It signs with the repository secrets `SIDELOAD_KEYSTORE_BASE64`, `SIDELOAD_STORE_PASSWORD`, `SIDELOAD_KEY_ALIAS` and `SIDELOAD_KEY_PASSWORD`. Keep a backup of `keystore/nestcast-sideload.jks` and its passwords: if the key is lost, installed copies can no longer be updated by the next APK. The same key must be used for every release.

## Installing on a TV

Turn on Developer options and Wireless debugging on the TV, then:

```powershell
adb pair TV_IP:PAIRING_PORT          # once, with the pairing code shown on the TV
adb connect TV_IP:DEBUG_PORT         # the port changes whenever wireless debugging restarts
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am start -n com.nestcast.tv/com.castbox.tv.MainActivity
```

The Android package (`applicationId`) is `com.nestcast.tv`; the Kotlin namespace is still `com.castbox.tv`. `adb exec-out screencap -p > shot.png` takes a screenshot (it can't capture the video layer).

## How it talks to the server

The TV is a client of NestCast Server's HTTP API. The server is in [castbox-media-server](https://github.com/cosmintoncean/castbox-media-server); its code is the reference for the contract. The endpoints this app uses:

- `GET /api/health`: name and the PC's permanent `serverId`.
- `GET /api/library`: the movie/show files (`id`, `title`, `fileName`, `extension`, `sizeBytes`, `url`, `rootName`, `relativeFolder`). Each `url` points at `GET /api/media/{id}`, which supports range requests.
- `GET /api/personal/roots`, `/api/personal/browse`, `/api/personal/{id}`, `/api/personal/{id}/thumb`: personal pictures and videos, browsed one folder at a time.
- `GET /api/medialog` and `POST /api/watched`: the MediaLog account behind a PC, and finished-video reports (events are saved on the TV and resent until the PC accepts them).
- `/api/screens/...`: the screen sharing handshake (request, wait for the PC's approval, then the stream).

Server URLs are entered on the TV (`http://<PC address>:<port>`, default port 5078). The server builds each video's `url` from its own LAN address, so a PC must not be reached through `localhost`. There is no authentication.

## Layout

- `app/src/main/java/com/castbox/tv/MainActivity.kt`: activity, library, folder tree, playback screen.
- `NextEpisode.kt`: finds the next episode in a folder and decides when the credits window starts.
- `WatchStore.kt`, `WatchReporter.kt`: resume positions and watched state; reporting finished videos to the PC.
- `PersonalScreens.kt`, `CastScreens.kt`, `SupportScreen.kt`, `WhoIsWatching.kt`, `PosterRepo.kt`: the other screens and the TVmaze poster lookup.
- `branding/`: icon and Play Store artwork sources.
- `pc-server/`: an old standalone test server from before the Windows app existed. It is not supported; use NestCast Server.

## Testing

There are no automated tests in this repository yet; changes are checked by building and running on a TV. The server repository has the unit tests for the API side.
