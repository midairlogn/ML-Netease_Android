# AGENTS

## Scope
- Single-module Android app only: `settings.gradle` includes just `:app`.
- Main package/namespace/applicationId is `com.midairlogn.mlnetease`.

## Build
- Gradle wrapper scripts are checked in. Use `./gradlew` on Unix/macOS or `gradlew.bat` on Windows; a locally installed `gradle` is not required.
- Toolchain from build files: AGP `9.3.1`, Gradle distribution `9.6.0`, Java 17, `compileSdk 34`, `minSdk 26`, `targetSdk 34`.
- Focused shell commands:
  - `gradlew.bat :app:assembleDebug` on Windows, or `./gradlew :app:assembleDebug` on Unix/macOS
  - `gradlew.bat :app:installDebug` on Windows, or `./gradlew :app:installDebug` on Unix/macOS
  - `gradlew.bat clean` on Windows, or `./gradlew clean` on Unix/macOS
- No repo-specific CI, lint, formatter, or test workflow files are present. There are no checked-in `test` or `androidTest` sources under `app/src`.

## Entry Points
- `MainActivity` is the real app shell: bottom-nav host for `HomeFragment`, `LocalFragment`, `DownloadsFragment`, and `SettingsFragment`, plus the always-visible mini player.
- `PlayerActivity` is the full-screen player and owns the cover/lyrics pager.
- `MusicService` is the foreground playback service, media session owner, notification action handler, and floating-lyrics coordinator.
- `MainApplication` tracks app foreground/background state and restarts queued downloads on process start.

## State And Persistence
- Playback state is centralized in the singleton `MusicPlayerManager`; activities, fragments, floating lyrics, and `MusicService` subscribe to it. Do not introduce parallel playback state.
- App/user settings live in `SharedPreferences` via `SettingsManager`, including `MUSIC_U`, playback mode, floating lyrics, lyric styling, app language, hearing protection, download customization, home shortcuts, and favourites.
- Dynamic launcher shortcuts are regenerated from settings via `AppShortcutController.refresh()`. Home shortcuts and favourites changes should keep launcher shortcuts in sync.

## Network
- `NeteaseApi` intentionally mixes request styles by endpoint. Keep each method's existing headers/cookies/request shape unless you verify the endpoint can change.
- `songUrl()` uses the desktop `eapi` flow with `CryptoUtils` against `https://interface3.music.163.com/eapi/song/enhance/player/url/v1`; this is easy to break by "cleaning up" headers or payload generation.

## Feature Boundaries
- Home screen has two modes: shortcut mode and search-results mode. `HomeFragment` swaps the `RecyclerView` adapter between `HomeShortcutAdapter` and `SongAdapter`; preserve that mode logic when editing the screen.
- The home shortcut list always includes the favourites entry first via `rebuildHomeEntries()`, even when no user shortcuts exist.
- Floating lyrics are controlled from multiple surfaces: settings UI, overlay controls, and media-session/notification custom actions. Changes must stay synchronized across all three.
- Overlay visibility depends on app visibility: `MainApplication` notifies `MusicService`, which updates `FloatingLyricsManager` so the overlay hides while the app itself is foregrounded.
- Downloads are a second foreground-service flow: `SongDownloadService` processes one queued task at a time from `DownloadTaskManager`, and `DownloadsFragment` is only a view/controller over that persisted queue.

## Permissions And Gotchas
- `MainActivity.checkOverlayPermission()` still starts `MusicService` if the user cancels the initial overlay prompt. Do not regress that startup path when touching permission flows.
- Settings import intentionally refuses to re-enable floating lyrics if overlay permission is currently missing (`SettingsManager.importAppDataJson`); preserve that guard.
- `AndroidManifest.xml` enables cleartext traffic (`android:usesCleartextTraffic="true"`). Do not remove it without verifying Netease/network behavior.
- `MainActivity` handles launcher shortcut intents and audio-file `VIEW` intents in `onNewIntent`/startup flow; be careful with changes to launch modes, intent flags, or tab-opening extras.
- Settings version text comes from `BuildConfig.VERSION_NAME`, so `app/build.gradle` remains the source of truth for app versioning.
- README requirement text is stale; trust build config over prose for platform support (`minSdk 26`, `targetSdk 34`).

## Secrets
- Never commit real `MUSIC_U` values or other local secrets. The repo ignores `local.properties`, keystores, and `google-services.json`.
