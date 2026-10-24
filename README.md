# RobinPersianFilm v1.10 — Clean final-player screen

This update is based on v1.9. The final FarsiPlex player page can show the site-wide navigation row (hamburger menu, logo, search) and an unrelated article below the video sources. That website chrome is not part of the playback controls.

## Changes

- Hides FarsiPlex's website **menu, logo, and search** when `/play-hq-player/` is loaded inside the app, including site headers implemented with non-standard HTML elements.
- Hides the unrelated article below the video-source/quality list, when identifiable.
- Reapplies the player-page cleanup if the website injects or redraws these elements, with throttling to avoid unnecessary work on Fire TV.
- **Does not remove** the actual video surface, Player 1/2/3, available 480p/720p/1080p source links, or the website's required 24-hour unlock step.
- Keeps v1.9's existing navigation, unlock handling, search, movies, episodes, and app branding. No changes to `.github/workflows/android.yml`.

## Update the existing GitHub project

Replace exactly:

- `app/src/main/java/com/persianrubinfilm/app/MainActivity.kt`
- `app/build.gradle.kts`

Commit, confirm that GitHub Actions succeeds, then install the updated APK. This update changes version metadata to `1.10.0` without adding a visible version number to the title.

## Verification and limitations

The cleanup JavaScript passed Node syntax validation and mock-DOM checks that it hides sample website navigation and unrelated article elements **without hiding** simulated player/quality controls or the unlock dialog. The ZIPs were checked for integrity. A full Android build and a real-device playback test were **not** possible in this environment. FarsiPlex may change its layout later, so additional selectors could be necessary.
