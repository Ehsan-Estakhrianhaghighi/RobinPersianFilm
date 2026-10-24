# RobinPersianFilm v1.8 (Android / Fire TV)

This release retains the v1.7 player navigation, catalog, poster, app icon,
quality/source selection, and nonessential-banner filtering.

## New: optional automatic *start* of FarsiPlex's required access ad

After the viewer explicitly chooses **Play Movie** or **Play Selected Episode**,
RobinPersianFilm checks the visible FarsiPlex access dialog. If its actual
**View a short ad** control is present in the same-origin page DOM, the app
clicks **that specific control once** to begin the site's normal rewarded-ad
flow, rather than making the viewer click it separately. The advertisement
must still play and finish normally for the provider to grant access.

The code does **not** bypass the site's 24-hour unlock, mark ads as watched,
click third-party ad creatives, or automatically follow external ad links.
It rate-limits the attempt so page polling and navigation cannot generate
repeated ad activations.

### Limitations

Google/third-party offerwall controls may live in a cross-origin iframe or
require a trusted physical user gesture. Android WebView's page JavaScript
cannot click through that isolation. If the one-time automated activation
is not permitted, the provider's dialog remains visible for a manual tap.
The update is **experimental** until tested against FarsiPlex on a phone
and Fire Stick. The app has not been fully compiled in this environment.

## GitHub update

If you already installed v1.7, replace:

- `app/src/main/java/com/persianrubinfilm/app/MainActivity.kt`
- `app/build.gradle.kts`

Your `.github/workflows/android.yml` remains unchanged.
Build through GitHub Actions and publish the resulting `app-debug.apk`.
