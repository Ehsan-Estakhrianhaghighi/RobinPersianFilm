PersianRubinFilm v0.10.4 - Fire TV playback controls and scrolling

Changes from 0.10.3:
- D-pad Up/Down scrolls the details page when an embedded player WebView has focus.
- Adds explicit Top and Video/Bottom scroll actions for Fire TV.
- Adds focusable Play/Pause, Mouse, and Exit controls to full-screen playback.
- Remote media Play/Pause attempts to toggle an HTML5 video.
- Movie catalog, series episode extraction, and existing ad-host filtering unchanged.

Notes:
- Some third-party video hosts encapsulate their video in cross-origin frames;
  Android cannot directly pause those from the parent document. Use MENU to
  activate the remote virtual cursor and click the host's visible controls.
- No full Android build was run locally. Validate on GitHub Actions / Fire TV.
