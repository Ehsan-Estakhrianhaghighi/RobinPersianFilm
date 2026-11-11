# RobinPersianFilm for Apple TV and iPhone

Native SwiftUI + AVKit implementation, with separate **tvOS** and **iOS** targets sharing one Swift codebase. The Android project is unaffected.

## Run from your Mac

1. Install the latest supported **Xcode** from the Mac App Store. Open it once to install its components.
2. Install [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen` (or install it by another method).
3. Clone this repository, then run:
   ```sh
   cd RobinPersianFilm/Apple
   xcodegen generate
   open RobinPersianFilmApple.xcodeproj
   ```
4. In Xcode, choose the `RobinPersianFilmTV` scheme for Apple TV or `RobinPersianFilmIOS` for iPhone. Select **Signing & Capabilities > Team** and your Apple Account (Personal Team is sufficient for local testing, subject to Apple's provisioning limits).
5. For Apple TV: connect the Mac and Apple TV to the same local network. On Apple TV, enable pairing under **Settings > Remotes and Devices > Remote App and Devices**. In Xcode choose **Window > Devices and Simulators**, select Apple TV and pair using its displayed code. Choose the paired Apple TV in the scheme destination and press **Run**.
6. For iPhone: connect or pair the iPhone, enable Developer Mode if requested, select it as the destination and press **Run**.

Xcode builds a separate **tvOS** application for Apple TV; an iOS IPA cannot simply be installed on Apple TV. You don't need App Store distribution to run a development build.

## First milestone

- Home, Movies, Series with Newest / A–Z sorting and title search.
- Merge FarsiPlex and FarsiLand catalog data, preferring FarsiPlex for duplicate titles.
- Series episode listing, posters, favorites and recent history.
- Native AVPlayer transport controls on Apple TV and iPhone.
- Source/quality selection for provider-published, verifiable MP4 links.
- FarsiLand HTML POST-form resolver for provider-issued URLs where available.

**Limitations:** Catalog HTML and signed media links may change. Some FarsiPlex content may require browser-only steps not supported on tvOS; the app reports this rather than bypassing access gates. The project has not been compiled on a Mac in this environment. Stream access is subject to the provider's authorization and rights.

## Troubleshooting

If Xcode reports signing errors, choose your personal Team for each target and change the two bundle identifiers to values unique to your Apple account.

If an episode says "No supported stream", use a source for which you have playback permission and provide its published direct stream URL or website request log for debugging. Do not store expiring signed URLs in the repository.
