PersianRubinFilm v0.10.5 — Fire TV fullscreen D-pad interaction

Full-screen now shows only the source video, without custom button overlays.
Fire TV remote:
- OK/Select: first press attempts HTML5 play/pause; on external providers, taps center to show their built-in controls.
- Arrows: after first OK, move visible cursor over the provider player controls.
- OK again: click provider control at cursor.
- MENU: return cursor to center/reset selection.
- Back: exit full-screen player.

LIMITATION: Cross-origin player providers do not expose all settings or play/pause
controls to Android WebView. Some require one tap to reveal controls, and availability
depends on the provider. The app cannot guarantee native focus-based navigation.

No android.yml change.
