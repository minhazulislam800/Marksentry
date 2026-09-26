# Sheet App — Ultra-Lightweight WebView Wrapper

A minimal native Android app (plain Java, no AndroidX, no third-party
libraries) that wraps a Google Apps Script web app in a `WebView`. It is
**not** an offline app — it is a thin online wrapper that speeds up loading
of *static* resources while always fetching live data from the server.

## Project structure

```
webview-wrapper/
├── .github/workflows/build-apk.yml     GitHub Actions build (no Android Studio needed)
├── app/
│   ├── build.gradle                    App module config, R8/shrinking, no dependencies
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/webviewwrapper/
│       │   ├── MainActivity.java       WebView, caching, back button, error/retry
│       │   └── SettingsActivity.java   URL settings screen
│       └── res/
│           ├── layout/                 activity_main.xml, activity_settings.xml
│           ├── values/                 strings.xml, colors.xml, themes.xml
│           ├── drawable/ic_launcher.xml (vector icon — no binary PNGs)
│           ├── menu/main_menu.xml
│           └── xml/                    backup/data-extraction rules
├── build.gradle                        Root Gradle config
├── settings.gradle
├── gradle.properties                   AndroidX disabled — pure platform SDK
└── README.md
```

No SQLite, no Room, no WorkManager, no Firebase, no analytics, no ads, no
Kotlin runtime, no AndroidX/AppCompat — every one of those was deliberately
left out to keep the APK small and the codebase simple.

## Default Web App URL

```
https://script.google.com/macros/s/AKfycbwhk7D9TI68hjE15PFQAQ78oCjNvkVkLJ0GNjSa496VXMeRw5JFflRpDbNkNiTEYRLG/exec
```

This is only the *initial* value. It is stored in `SharedPreferences`
(`app_prefs` → `active_url`) the first time the app runs, and can be changed
at any time from **Settings** without rebuilding the app.

## How to build the APK (no Android Studio required)

1. Create a new GitHub repository.
2. Push this entire project folder to it (the `.github/workflows` folder
   must be at the repo root).
3. On GitHub, open the **Actions** tab.
4. Select the **"Build Release APK"** workflow and click **"Run workflow"**
   (it also runs automatically on every push to `main`).
5. When the run finishes, open it and download the **`app-release`**
   artifact — it contains the installable `.apk` file.
6. Transfer the APK to an Android device and install it (you'll need to
   allow "install unknown apps" for whatever app you use to open the file).

The workflow (`.github/workflows/build-apk.yml`) installs a JDK, the Android
SDK command-line tools, and Gradle itself on the runner — you never need
Android Studio, and the repository doesn't need to contain the Gradle
wrapper jar.

> **Note:** the workflow uses the Android SDK command-line tools that
> already ship on GitHub's `ubuntu-latest` runners directly, rather than the
> `android-actions/setup-android` action — that action tries to install a
> legacy `tools` package that Google removed years ago, which makes its own
> setup step fail before your build even starts.

### Building locally instead (optional)

If you do have Android Studio or a local Android SDK + Gradle install:

```
gradle assembleRelease
```

The APK will be at `app/build/outputs/apk/release/app-release.apk`.

## How URL changing works, exactly

1. The active URL always lives in `SharedPreferences` under `active_url`,
   defaulting to the URL above on first run.
2. **Settings screen** (`SettingsActivity`) shows the current `active_url` in
   an editable field with **Save**, **Cancel**, and **Reset to Default**:
   - **Save** validates the text starts with `https://` and matches a
     well-formed URL pattern, then writes it to `active_url` and closes the
     screen. It does **not** touch the WebView directly.
   - **Cancel** discards the edit and closes without saving anything.
   - **Reset to Default** writes the hard-coded default URL back into
     `active_url`.
3. Separately, `MainActivity` keeps a second preference, `last_loaded_url` —
   the URL that was actually loaded into the WebView last time. Every time
   `MainActivity` starts or resumes, it compares `active_url` to
   `last_loaded_url`:
   - **If they match:** nothing special happens; the existing WebView state
     is reused (or, on a fresh process, the URL is loaded normally with
     standard HTTP caching in effect for static resources).
   - **If they differ:** this is a URL change. Before loading anything, the
     app calls `webView.clearCache(true)`, `clearHistory()`,
     `clearFormData()`, `WebStorage.deleteAllData()`, and clears cookies.
     Only then does it load the new URL and update `last_loaded_url` to
     match. This guarantees a freshly deployed Apps Script app is never
     served stale HTML/CSS/JS left over from a previous deployment.
   - This cache reset touches only WebView-managed browser state. It never
     touches `SharedPreferences` or any other app setting — your saved URL
     itself is never wiped by this process.
4. Pressing **Reset to Default** is treated identically to entering any
   other new URL: it goes through the same change-detection and cache-reset
   path described above.

## Exactly what is cached, and what is not

**Cached (standard WebView/HTTP caching only, via `WebSettings.LOAD_DEFAULT`):**
- Static HTML shell, CSS, JavaScript files served by the Apps Script app
  (only to the extent the server's own `Cache-Control`/`ETag` headers permit)
- Images, fonts, and static icons/assets

**Never cached, by design — always fetched live from the server:**
- Google Sheet data of any kind
- Search results, dashboard data, result data
- Any Apps Script response that reflects live application state
- Anything served through `google.script.run` calls

There is no SQLite database, no IndexedDB data cache, no offline queue, no
local JSON snapshot, and no service-worker offline database anywhere in this
project. The only two values persisted by the app itself are `active_url`
and `last_loaded_url`, both plain strings in `SharedPreferences` — neither
holds application/business data.

## Android Back button behavior

`MainActivity.onBackPressed()`:
- If the WebView is showing content and has browsing history
  (`webView.canGoBack()`), Back navigates within the WebView
  (`webView.goBack()`).
- Otherwise, Back falls through to the default Activity behavior, which
  exits the app.

## External links

Any link whose host matches the configured Web App's host — or a Google
auth/redirect host such as `accounts.google.com`, `script.google.com`,
`script.googleusercontent.com`, or `docs.google.com` — stays inside the
WebView. Anything else is handed off to the system browser via
`Intent.ACTION_VIEW`.

## Loading / error UI

- A single `ProgressBar` shows while a page is loading; the WebView is
  hidden until `onPageFinished` fires (no splash screen, no animation).
- If there is no network connection, or the server returns a main-frame
  HTTP error / connection failure, a plain error screen appears with a
  **Retry** button. It never falls back to showing old data to look "fast."

## Security

- The Settings screen only accepts URLs beginning with `https://`; anything
  else is rejected with an inline error message.
- `android:usesCleartextTraffic="false"` blocks plain HTTP at the manifest
  level as well.
- No API keys, tokens, or secrets are embedded anywhere in the app — the
  Apps Script deployment itself is the only "credential," and it is user
  supplied, not baked into source.

## APK size

Achieved by: R8 minification + resource shrinking, no AndroidX/AppCompat, no
Kotlin runtime, no third-party libraries, a vector (not raster) launcher
icon, and zero bundled copies of the Apps Script HTML/CSS/JS. A WebView-only
wrapper like this typically lands in roughly the **1–3 MB** range depending
on the exact Android Gradle Plugin/R8 version used by the runner — the
workflow's "Report APK size" step prints the exact number for your build.
This is a realistic range, not a guarantee: a bare-minimum Android APK has a
small fixed overhead (manifest, resources.arsc, signing block) that can't be
compressed away entirely.

## Testing checklist

Run these after installing a build; check off in your own copy of this file.

| # | Test | Expected result |
|---|------|------------------|
| 1 | First launch | APK installs; default Apps Script URL loads |
| 2 | Live data | Data shown comes from the live server, not any local cache |
| 3 | Static caching | On reload, static resources reuse normal WebView/HTTP cache where headers allow |
| 4 | URL change (A → B) | Old cache cleared; URL B loads; no interference from old assets |
| 5 | URL persistence | Change URL, close app, reopen — new URL is still active |
| 6 | Reset | Reset to Default restores the original URL (and clears cache the same way) |
| 7 | Android Back | Back follows WebView history; exits app only once history is empty |
| 8 | Network failure | Disabling internet and reloading shows the error/Retry screen, not stale data |
| 9 | APK size | Check the workflow's printed `.apk` file size |
| 10 | No unwanted data caching | Confirm no SQLite/IndexedDB/local JSON file holds Sheet data (inspect via `adb shell run-as` if desired) |

## Explicitly out of scope

No login system, no user management, no local database, no offline-first
architecture, no sync, no push notifications, no Firebase, no analytics, no
ads. The Google Apps Script web application remains the single source of
truth; this project is only a native wrapper around it.
