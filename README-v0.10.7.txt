PersianRubinFilm v0.10.7 — Fire TV full-screen playback keys

Changes from v0.10.6:
- OK at the center and the physical Play/Pause key now share one pause/resume path.
- Detect HTML5 video elements in open shadow roots and accessible same-origin frames.
- Fallback: send a native Space key press to the focused WebView for provider players such as VK Video.
- The D-pad cursor still clicks the provider's native controls after you move it.
- No extra on-screen buttons. Series parsing and detail-page focus unchanged.

This is a candidate fix, NOT verified on a physical Fire TV or compiled in CI.
Only MainActivity.kt and app/build.gradle.kts differ from v0.10.6.
