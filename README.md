# RobinPersianFilm v1.2 — In-app player controls (experimental)

This is a separate Android/Fire TV app from PersianRobinFilm. The application label and cat logo are retained.

## Changes
- Playback remains inside the app, with an in-app player screen instead of opening an external browser.
- When FarsiPlex exposes source tabs or quality links in the *same webpage DOM*, Player 1/2/3 and quality choices appear above the embedded page and can be selected from the app.
- Available options are refreshed after website updates (including after a legitimate unlock).
- Basic banner filtering and top-level redirect protection are retained.
- Existing catalog, series episodes, favorites, and history remain unchanged.

## Important limitations
- This is **not yet native Media3/ExoPlayer playback**. It still embeds the provider's authorized WebView player.
- FarsiPlex's legitimate 'View a short ad' 24-hour unlock step and any required pre-roll ads are not bypassed.
- Some sources/quality controls are inside cross-origin iframe players. Android cannot access those controls through the outer page's JavaScript; select them in the provider when needed.
- FarsiPlex may still require a click on its player after authorization. No automatic playback is guaranteed.
- This source ZIP was validated structurally but NOT compiled or tested on a physical Fire Stick.

## GitHub update
For existing RobinPersianFilm repository, replace `app/src/main/java/com/persianrubinfilm/app/MainActivity.kt` and `app/build.gradle.kts`. Keep `.github/workflows/android.yml` unchanged. Run GitHub Actions and review compiler logs.
