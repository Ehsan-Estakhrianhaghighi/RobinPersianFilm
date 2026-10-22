PersianRobinFilm v0.10.9 — Complete catalog pagination

What changed
- Corrected series listing endpoint to https://www.diycraftsguide.com/serial.html
- Fetches the source site's numbered movie and series listing pages by following discovered pagination links.
- Deduplicates movies and series by their watch-page URLs; retains poster enrichment, playback and remote-control code.
- Bounds catalog scanning to 80 numbered pages per category with request timeouts.

Only changed app/src/main/java/com/persianrubinfilm/app/MainActivity.kt and app/build.gradle.kts.
Existing .github/workflows/android.yml is unchanged.

Limitations: Source websites can change or use JavaScript-only catalog loading. The update has not been tested on Fire Stick and a successful Android build has not been confirmed. Pagination may not retrieve all entries if the source blocks requests or its listings are JavaScript-rendered.
