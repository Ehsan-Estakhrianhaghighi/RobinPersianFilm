# RobinPersianFilm v1.1

Android / Fire TV app based on the FarsiPlex catalog.

## v1.1 changes
- Filter generic navigation entries such as Genres from the movie/TV catalog.
- Hide recognizable third-party banners and ad frames inside the playback WebView.
- Preserve the site's required ad-based access/unlock step and video sources.
- Retain the cat launcher icon and the GitHub Actions workflow.

## Build
Upload these project contents (including .github/workflows/android.yml) to GitHub. GitHub Actions builds app-debug.apk.

Ad filtering is best effort; it may not hide every ad, and provider changes may require more adjustments. The project has not been compiled or tested on a device in this environment.
