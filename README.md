# No Shorts

YouTube for Android without Shorts. The app opens YouTube's mobile site (m.youtube.com) in a full-screen WebView and removes every Shorts surface:

- Shorts shelves on Home, Subscriptions, Search and under the player
- Individual Shorts tiles mixed into feeds and search results
- The **Shorts** tab in the bottom bar, the **Shorts** search filter chip and the **Shorts** tab on channel pages
- Any Shorts link (tapped, shared or opened from another app) plays in the **normal player** (`/watch?v=…`), so you never land in the swipe feed

It's built to feel like a native app:

- **Seamless theme**: the status and navigation bars follow YouTube's own light/dark setting
- **Splash screen** held until the first page paints, so there's no blank white flash
- **Picture-in-picture**: swipe home while a video plays and it keeps playing in a floating window (muted feed previews don't trigger this)
- **Fullscreen** rotates to landscape for normal videos and stays upright for vertical ones
- Screen stays on while a video plays; video pauses when you leave the app
- No blue tap flashes or accidental text selection; "open the app" promo sheets hidden
- A native loading bar, plus an offline screen with **Retry**
- Links to other sites open in your browser

Sign-in, subscriptions, comments and the back button work as they do on the site. You can open YouTube links in No Shorts, or share a link to it from another app.

## Get the APK

### Option A: GitHub (no setup needed)
1. Create an empty GitHub repo and push this folder to its `main` branch.
2. Open **Actions → Build APK**. A build runs on every push; you can also start one with **Run workflow**.
3. When it finishes, download **NoShorts-apk** from the run page, unzip it, and install the APK on your phone (allow "Install unknown apps" when asked).

### Option B: Android Studio
Open the folder, wait for the Gradle sync, then use **Run ▶** with your phone plugged in, or **Build → Build APK(s)**.

Command line (needs JDK 17+ and the Android SDK): `./gradlew assembleRelease`, which writes the APK to `app/build/outputs/apk/release/`.

## How it works

| File | Role |
|---|---|
| `app/src/main/assets/noshorts.js` | Injected into every page. CSS hides known Shorts elements, and a MutationObserver pass catches anything added later, including items labelled "Shorts" and cards that link to `/shorts/`. It also turns Shorts navigation into `/watch?v=ID`. |
| `MainActivity.kt` | WebView host: injection, link routing, fullscreen video, edge-to-edge layout, back button. |
| `Urls.kt` | Decides which URLs stay in the app and rewrites Shorts and youtu.be links. |

**If YouTube changes its markup** and a Shorts shelf starts showing again, add its tag name to `HIDE`, `ITEMS` or `SHELVES` in `noshorts.js`. That's usually a one-line fix.

## Notes
- This is for personal use. It isn't suitable for the Play Store: Google Play rejects apps that wrap another company's website or use its brand, and modifying YouTube's service conflicts with YouTube's terms.
- Videos pause when you leave the app, the same as on the mobile site.
- If Google ever blocks sign-in inside the app, you can still browse and watch while signed out.
