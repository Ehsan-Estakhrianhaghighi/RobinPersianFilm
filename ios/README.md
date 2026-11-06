# RobinPersianFilm for iOS (SwiftUI)

Native iPhone/iPad project for the RobinPersianFilm catalog.

## Implemented

- SwiftUI Home, Movies, Series, Favorites and History, with search and Newest / A–Z sorting
- FarsiPlex and FarsiLand catalogs with FarsiPlex preferred when normalized titles match
- Series episode discovery
- FarsiLand POST-form redirect chain, separately resolving and verifying published 480p, 720p and 1080p MP4 streams
- Native AVPlayer playback, selectable quality, full-screen and playback-position preservation
- FarsiPlex provider WKWebView fallback with published media-source discovery
- Persistent favorites/history using UserDefaults
- HTTPS requests and explicit allowed streaming domains

FarsiPlex playback may require a legitimate provider interaction in WKWebView. The iOS web engine is different from Android's WebView: identical source discovery and uninterrupted autoplay cannot be guaranteed. Signed links expire and must be discovered again from the provider's pages; no signed media URL is embedded in this app.

## Build

Requirements: macOS, current Xcode, and [XcodeGen](https://github.com/yonaskolb/XcodeGen). This project downloads [SwiftSoup](https://github.com/scinfu/SwiftSoup) via Swift Package Manager.

```bash
cd ios
brew install xcodegen
xcodegen generate
open RobinPersianFilm.xcodeproj
```

Select the RobinPersianFilm scheme and an iPhone simulator, then Run. For a physical iPhone, set a unique Bundle Identifier and choose your Apple Developer team in Xcode > Signing & Capabilities. Sign and install through Xcode (or distribute through TestFlight after configuring an Apple Developer Program account).

GitHub Actions: **iOS Build Check** compiles an unsigned iOS Simulator app and uploads a simulator-only zip if successful. That zip is **not** an installable iPhone IPA. Producing a signed IPA requires an Apple signing certificate/provisioning profile and an authorized developer team, none of which are present in this repo.

Android app is kept separate and is not replaced by the iOS project.
