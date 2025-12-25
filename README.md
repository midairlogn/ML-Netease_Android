# ML-Netease_Android

An Android music player application that integrates with Netease Cloud Music, featuring a custom floating lyrics overlay.

## Features

- **Netease Cloud Music Integration**:
  - Search for songs.
  - Access song details, album info, and playlists.
  - Support for user login cookies (Music_U).
  - Selectable audio quality.

- **Music Player**:
  - Full playback controls (Play, Pause, Prev, Next).
  - Playback modes: Loop All, Loop One, Shuffle, Order.
  - Background playback service.

- **Lyrics Support**:
  - Synchronized lyrics display in the main player.
  - **Floating Lyrics Overlay**: A customizable floating window that displays lyrics over other applications.
    - Adjustable font size and text colors.
    - Mini playback controls.
    - Lock mode to prevent accidental touches.
    - Expand/Collapse view.

- **Playlist Management**:
  - View and play songs from Netease playlists.

## Requirements

- Android 7.0 (Nougat) or higher (API Level 24+).
- Internet connection.
- "Display over other apps" permission (for Floating Lyrics).

## Tech Stack

- **Language**: Java
- **Networking**: OkHttp 3
- **UI Components**: AndroidX AppCompat, Material Design, ConstraintLayout
- **Architecture**: MVVM-like structure with Managers and Services.

## Setup & Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/midairlogn/ML-Netease_Android.git
   ```
2. Open the project in Android Studio.
3. Build and run the application on your Android device or emulator.

## Usage

1. **Search**: Use the search bar on the home screen to find songs.
2. **Play**: Tap on a song to start playing.
3. **Floating Lyrics**:
   - Enable "Floating Lyrics" in Settings.
   - Grant the required permission when prompted.
   - A floating icon/window will appear. Tap to expand for controls and settings.
4. **Settings**: Configure audio quality and input your Netease `MUSIC_U` cookie for authenticated access.

## Disclaimer

This application is an unofficial client for learning and personal use. It is not affiliated with, associated with, or endorsed by Netease Cloud Music. All content and data are property of their respective owners.

## Related Projects

- [ML-Netease_url (Midairlogn)](https://github.com/midairlogn/ML-Netease_url)
- [Netease_url (Suxiaoqinx)](https://github.com/Suxiaoqinx/Netease_url/)

## License

[GNU General Public License v3.0](LICENSE)
