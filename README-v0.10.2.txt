PersianRubinFilm v0.10.2 — fixes episode season grouping for all series.

The source site lists separate Season headings followed by E01/E02/... episode links.
The episode links usually use /watch/...html?key=... and do NOT themselves name the season.
This parser now reads season headings in document order and keeps the exact episode URLs.
When the source does not label a season, episodes appear in a neutral Episodes group.
All existing direct-player, advertisement filtering, virtual mouse, layout and catalog code is unchanged.

GitHub update: replace app/src/main/java/com/persianrubinfilm/app/MainActivity.kt and
app/build.gradle.kts, then commit and build in GitHub Actions.
