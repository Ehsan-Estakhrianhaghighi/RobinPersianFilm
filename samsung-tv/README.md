# RobinPersianFilm — Samsung Smart TV (Tizen)

This is a **new Samsung TV project**, separate from the working Android/Fire Stick APK.

## Target

Samsung **UN50TU690TFXZC** (TU690T, 50-inch 4K, Tizen). A TV app is a signed **.wgt**, not an Android APK.

## Status

**Development preview; not yet a tested or signed Samsung app.** Source is committed and GitHub Actions validates JavaScript, Python and XML. The CI source ZIP **is not an installable .wgt**. Video playback, real FarsiPlex titles, device remote navigation and HTTPS/LAN access need on-device validation.

## Architecture

- `index.html`, `style.css`, `app.js`, `config.xml` — Tizen Web App.
- Samsung AVPlay displays signed MP4 streams; a standard video element is used when testing in a desktop browser.
- `server/server.py` — **separate companion server** serving JSON catalog and resolving current provider-published signed video links. A Tizen Web App cannot directly run Android Kotlin/Jsoup logic.
- Android application, versioning and APK workflow are untouched.

> Note: The companion is a developer/testing implementation rather than a production hosting service. It runs on a computer on your LAN, or a trusted HTTPS server. Do not expose it directly to the public Internet: it has no authentication and it makes requests to third-party providers.

## Quick local server setup

On a PC/Mac with Python 3.10+:

```sh
cd samsung-tv/server
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python server.py
```

Check `http://localhost:8787/api/health` and then your computer's LAN IPv4 address. TV and computer should be connected to the same home network. Allow incoming TCP port 8787 on your computer's firewall **only from trusted LAN devices**. In the TV app's **Server settings** enter `http://YOUR_COMPUTER_LAN_IP:8787`.

The companion fetches actual site HTML and follows provider-published forms, but sites can change and some FarsiPlex titles require JavaScript/browser session state. Do not expect 100% catalog or playback parity before TV testing.

## Package and install on the Samsung TV

1. Install **Tizen Studio** and its **TV Extension** on a PC, including the TV emulator/developer tools.
2. In Tizen Studio, import `samsung-tv` as an existing Tizen TV Web Application (or create a TV Web App and copy the four root frontend files).
3. Create a **Samsung certificate profile** with the Tizen certificate manager, including the TV's device DUID where required.
4. On the TV, enable **Developer Mode** in the Apps interface and enter the computer's IP as instructed by Samsung. Restart TV if prompted.
5. With TV and PC on the same LAN, connect using **Device Manager**, then build/sign the project and **Run As → Tizen Web Application** on the TV.
6. Enter the companion server's URL in **Server settings** in the app.

**A GitHub-generated source ZIP is not a signed/installable WGT.** We intentionally do not upload signing certificates or private keys to GitHub.

## API contract

- `GET /api/health` → service readiness
- `GET /api/catalog` → `{movies: [...], series: [...]}`
- `GET /api/episodes?url=...` → `{episodes: [...]}`
- `GET /api/resolve?url=...` → `{sources:[{url,quality},...]}`

The resolver validates hostnames and only returns media that responds to a request. FarsiPlex is preferred for catalog duplicates. No signed token is fabricated or reused across titles.

## Next validation

Test a FarsiLand movie and series plus one FarsiPlex title. Verify remote D-pad, AVPlay playback, seek, quality switch, and images. The TV's model code is sufficient; do **not** post its serial number publicly.

Samsung reference: https://developer.samsung.com/smarttv/develop/getting-started/quick-start-guide.html
