# RobinPersianFilm v1.7 — seamless transition and banner cleanup

A focused update based on v1.6. Keep the existing GitHub Actions workflow.

## Changes

- The intermediate `/play-quick-view/` page stays *behind an app loading screen* while FarsiPlex transitions to the final `/play-hq-player/` page. The old 6.5-second forced reveal has been removed.
- If the provider does not advance automatically after 11 seconds, the app offers an **explicit** "Show provider page to continue" fallback, rather than automatically showing the preview.
- The final player is shown after its document finishes loading and cosmetic ad cleanup has been applied.
- Adds cosmetic filtering for common standalone banner advertisements and top/floating ads, including dynamically inserted banners.
- **The required “View a short ad / Site-wide access for 24 hours” prompt is intentionally not removed or bypassed.** Provider-required pre-roll ads may also remain. They control access to content and may be necessary for playback to work.
- Fixes two JavaScript quoting errors that could prevent in-app Player 1/2/3 and quality choices from being detected or selected.
- Retains the catalogs, episode selection, video host options, cat launcher icon, and Fire Stick support.
- Android versionCode 8, versionName 1.7.0; visible app title remains RobinPersianFilm.

## Apply to your existing GitHub repository

Extract `RobinPersianFilm-v1.7-Changed-Files.zip` and update only:

- `app/src/main/java/com/persianrubinfilm/app/MainActivity.kt`
- `app/build.gradle.kts`

The `.github/workflows/android.yml` file is unchanged. Build the APK with your normal GitHub Actions workflow.

## Validation performed

- Parsed and checked the embedded playback JavaScript with Node.js.
- Passed simulated preview/final-player navigation cases inherited from v1.6.
- Passed a cosmetic-filter test verifying that recognizable standalone ads are hidden while a required unlock dialog and video-player elements remain accessible.
- Verified ZIP archives and source version strings.

**Not performed:** A full Android compilation or an end-to-end phone/Fire Stick playback test. FarsiPlex may change its site structure or require user interaction for the access step.
