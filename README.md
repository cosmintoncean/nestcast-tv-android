# CastBox TV — Android TV MVP

This replaces the browser-to-Chromecast sender approach with a native Android TV app. The TV app browses the CastBox server's library and plays selected files directly from the Windows PC using AndroidX Media3/ExoPlayer. No phone, Chrome, or PC desktop interaction is needed during playback.

## Current MVP features

- Android TV launcher app navigable with the TV remote
- Configure the media server's LAN URL in the app
- Load `/api/library`, search and list movies
- Play selected HTTP media URLs using Media3/ExoPlayer
- Works with the existing CastBox .NET server API

This is a source project, not a prebuilt/verified APK. It needs to be built in Android Studio. I could not run an Android Gradle build in this environment.

## 1. Fix the Windows server URL

On the PC hosting the media, open `pc-server/appsettings.json` (or your existing server's `appsettings.json`) and set `MediaBaseUrl` to that PC's LAN IPv4 address, for example:

```json
"MediaBaseUrl": "http://192.168.1.50:5078"
```

Keep your existing `MediaFolders` values. Windows paths in JSON need doubled backslashes, for example `"D:\\Movies & Shows"`.

Start the server so it listens on the LAN:

```powershell
dotnet run --urls "http://0.0.0.0:5078"
```

On the PC, check `http://localhost:5078/api/library`. On another device on the same Wi-Fi, check `http://192.168.1.50:5078/api/library` using your actual IP. If it doesn't load from another device, allow TCP port 5078 through Windows Firewall on your private home network.

**Important:** `MediaBaseUrl` cannot be `localhost` or `127.0.0.1`; the TV would then try to stream from itself, not the PC.

## 2. Build the Android TV app

1. Install Android Studio with Android SDK Platform 35 and a JDK 17 runtime (Android Studio includes a compatible JDK).
2. Open this folder (`castbox-tv-android`) as an existing project.
3. Let Gradle sync and download dependencies.
4. Use **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. The APK should be at `app/build/outputs/apk/debug/app-debug.apk`.

If Android Studio requests an Android Gradle Plugin or SDK update, use the versions it recommends only if needed and keep Kotlin/Compose plugin versions aligned.

## 3. Install on Android TV

Enable Developer options and USB/network debugging on the TV, then install the debug APK from a computer with Android platform tools:

```powershell
adb connect TV_IP_ADDRESS:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Some TV builds use a pairing step/port shown in Developer options. Alternatively, copy the APK to the TV and install it with a trusted file manager. Android may require allowing that installer to install unknown apps.

## 4. First run

1. Open **CastBox TV** on the TV.
2. In Server settings, enter the PC's LAN address, e.g. `http://192.168.1.50:5078`.
3. Choose **Save and connect**.
4. Select a movie with the remote.
5. Use the TV remote's directional/OK buttons to control the Media3 player; use the on-screen Library button to return.

The server address is saved on the TV. Press Refresh after adding/removing files or when a PC comes back online.

## Current limitations

- The app supports multiple servers (see "Multi-PC library" below), but there is no automatic discovery; server URLs are entered manually.
- It uses a basic text list, not posters or TMDB metadata yet.
- Playback depends on the codecs supported by the Android TV device. Many MKV files work when their internal video/audio codecs are supported, but an MKV container itself does not guarantee compatibility. Transcoding is not implemented.
- The server's current media URLs are built from `MediaBaseUrl`; make sure it is the host PC's LAN IP/hostname.
- The existing server has no authentication and should only be used on a trusted home network. Do not port-forward it to the internet.


## Branding
The CastBox launcher icon is configured in `app/src/main/res/mipmap-nodpi/castbox_icon.png`. The original generated artwork is in `branding/castbox-icon-original.png`.


## Multi-PC library (Android TV 0.3.0)

The TV app now supports multiple Windows media servers:
1. Open CastBox on the TV and use **Your media PCs**.
2. Add a friendly name and the full server URL for each PC, e.g. `http://192.168.1.25:5078`.
3. Select **Connect to library**. CastBox queries each server, combines successful results, and plays each movie from its source server.
4. Use **Refresh** to reload all configured servers, or **Change sources** to add/remove PCs.

Server URLs are saved locally on the TV. The old single-server preference is migrated into a first source automatically.

Notes:
- Both PCs must be reachable on the same LAN and have their Windows firewall rules configured.
- A server that is offline is reported as unavailable while reachable servers remain usable.
- Duplicate detection is currently by exact media URL. The same movie stored on two PCs may appear twice.
- `.\gradlew.bat assembleDebug` built successfully on 2026-10-23 (JDK from Android Studio, SDK Platform 35). It has not been tested on a Chromecast/Android TV device.
- `pc-server/` is an older standalone ASP.NET test server that predates the Windows app in `castbox-media-server`; the Windows app is the supported server.
