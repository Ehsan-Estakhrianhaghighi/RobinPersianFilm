PersianRubinFilm v0.10 — Direct video-player and safer Play fix

- Extracts an allowlisted HTTPS video embed from the selected film/episode page.
- Opens the player directly, rather than loading the scam-ad-filled source website.
- Adds an autoplay hint and allows WebView media playback without an extra Android gesture.
- Prevents top-level navigation to other hosts from the player; blocks known ad requests.
- Displays retry/error feedback when no supported player is available.
- Keeps v0.9 catalog, posters, series selection, watch history, and TV virtual cursor.
- Player-provider advertisements cannot always be removed safely.

GitHub replacement files:
 app/src/main/java/com/persianrubinfilm/app/MainActivity.kt
 app/build.gradle.kts

Not yet tested on Fire TV hardware or compiled with full Android toolchain.
