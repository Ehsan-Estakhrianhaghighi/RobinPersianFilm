# RobinPersianFilm v1.6 — preview-to-player navigation fix

This is a targeted update to v1.5.

## Changes

- Fixed the playback automation stopping at `/play-quick-view/`, which is an intermediate preview and not the final player.
- On either a movie/episode page or the intermediate quick-view, the app now tries to follow a same-origin link to the final `/play-hq-player/` page through the original page control, preserving the website's selected-title/session context.
- Added recognition of visible graphical Play overlays that don't contain the word "Play" as ordinary text.
- Shows a short “Opening the video player…” screen during automatic navigation; if the site cannot be advanced programmatically, reveals its page instead of trapping the user.
- Continues to show the required FarsiPlex access-unlock dialog when applicable. It does not bypass access controls or required advertisements.
- Preserves the catalogs, movie/episode selections, quality/player choices, launcher icon, and Fire TV support from v1.5.
- Updated internal versionName to `1.6.0` (the visible app title has no version number).

## Installation

Replace only:

- `app/src/main/java/com/persianrubinfilm/app/MainActivity.kt`
- `app/build.gradle.kts`

Your existing `.github/workflows/android.yml` is unchanged. Build APK via GitHub Actions.

## Validation limits

The JavaScript route and click selection passed local simulated tests (including /play-quick-view/ and unlock detection). A full Android build and real FarsiPlex playback test were not available in this environment. Some provider actions may reject programmatic clicks or require a real user gesture.
