# RobinPersianFilm — Android / Fire TV (initial release)

This is a **separate** Kotlin/Jetpack Compose Android application for browsing FarsiPlex's public movie and TV-show catalog. It preserves the **same cat launcher icon** from the existing PersianRobinFilm app, but uses a **new Android application ID**: `com.robinpersianfilm.app`. Installing it does not replace PersianRobinFilm.

## Features

- Movie catalog: `https://farsiplex.com/movie/`
- Series catalog: `https://farsiplex.com/tvshow/`
- Search, selectable series episodes, history, favorites, responsive phone/TV grid
- FarsiPlex playback page in a WebView, with native full-screen requests supported
- Provider's own playback-source selection, quality options, and required unlock flow remain accessible. The app does not bypass an ad-gated unlock or attempt to extract restricted/signed streams.
- Basic protection against unrelated *top-level* browser redirects; embedded video/ads that the provider requires may still appear.

## Build through GitHub Actions

1. Create a NEW GitHub repository (suggested name: `RobinPersianFilm`).
2. Upload all project files and folders to the repository ROOT, including `.github/workflows/android.yml`. Do not place an extra parent directory around them.
3. Commit to `main`.
4. Go to **Actions → Build Android APK**. After a successful build, open the workflow run and download the artifact **RobinPersianFilm-APK**.
5. Extract `app-debug.apk`, install it on a phone/Fire Stick, or upload the APK as a release asset.

The project retains the same GitHub Actions build workflow as the earlier app, with only the artifact name updated. This workspace has not run the complete Android Gradle build; GitHub Actions is the first compile test.

## Playback architecture and current limitations

This first release uses FarsiPlex's authorized **website playback flow**. The user's browser test found a `.mp4` request, but that alone does not prove a stable third-party stream endpoint or permission to play it in a native player. A native Media3 player could be added after testing an authorized stream and the provider's access requirements.

- The provider may require watching a 30-second advertisement to unlock a video.
- The provider's in-page source and quality menus are preserved rather than reimplemented, so Fire Stick D-pad accessibility of those third-party controls is not guaranteed.
- On some series, season information is not discoverable from listing markup; these may default to Season 1 until the specific site's HTML is better mapped.
- The catalog is read from public HTML; website redesigns can break the parser.
- The app does not download videos in advance.

## Important technical files

- `app/src/main/java/com/persianrubinfilm/app/MainActivity.kt` — app UI, catalog parser, player
- `app/src/main/AndroidManifest.xml` — launcher name, TV launch mode, internet permission
- `app/build.gradle.kts` — independent app ID and SDK/dependencies
- `.github/workflows/android.yml` — APK build and artifact

The existing Kotlin source namespace/path is intentionally retained to minimize migration risk. The installed application ID is different.
