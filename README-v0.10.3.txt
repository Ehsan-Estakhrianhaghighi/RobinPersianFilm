PersianRubinFilm v0.10.3 — Fire TV detail scrolling and full-screen playback

- Movie/series details now use a vertically scrollable container for remote navigation.
- Embedded video preview has an explicit height rather than occupying an unscrollable remainder.
- Fire TV Play automatically opens the safe embedded player in a full-screen in-app view after the embed is detected.
- Back exits full-screen to return to the episode/movie detail page; a full-screen button is available for phone and TV.
- Remote virtual mouse, existing episode extraction, ad-host blocking, and existing source-player isolation are preserved.

Update on GitHub: replace app/src/main/java/com/persianrubinfilm/app/MainActivity.kt and app/build.gradle.kts.
Build verification: GitHub Actions (full Android build was not run in this environment).
