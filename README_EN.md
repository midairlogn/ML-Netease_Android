<div align="center">
<h1>ML-Netease_Android</h1>
An Android music player application that integrates with Netease Cloud Music.<br><br>

**English** | [**中文简体**](README.md)
</div>

## ✨ Features

### 🎵 Netease Cloud Music Integration

- **Song Search**: Search by song name or directly parse by song ID.
- **Playlists & Details**: View song details, album info, and playlists. Supports adding shortcuts and one-click playback.
- **Account Support**: Supports logging in with a `Music_U` cookie to unlock higher audio quality options.

### 🎧 Music Player

- **Full Controls**: Play, pause, previous, and next track.
- **Playback Modes**: Loop All, Loop One, Shuffle, and Sequential playback.
- **Background Playback**: Service-based playback that continues even when the app is in the background.
- **Batch Downloads**: Supports custom download functionality for batch song downloads.
- **Local Audio Support**: Automatically scans local audio files, supports manually adding local files, and plays them directly.
- **Favorites**: Lets you favorite songs inside the app, including both local audio files and Netease Cloud songs.
- **Hearing Protection**: Includes smart hearing protection that automatically pauses playback after continuous listening for a configured duration.

### 🗂️ Data & Settings

- **Data Import & Export**: Supports encrypted export and import of usage data such as settings and home screen shortcuts for backup and migration. The export button is located in the top-right corner of the Settings screen.

### 💬 Lyrics System

- **Synced Lyrics**: Real-time scrolling lyrics on the main player screen.
- **Floating Lyrics Overlay**:
  - Displays lyrics on top of other applications.
  - Customizable font size and colors.
  - Integrated mini-playback controls.
  - Supports Lock Mode (prevent accidental touches) and Expand/Collapse views.

## 📱 Requirements

- **Android Version**: `Android 12.0 / API Level 26.0` or higher.
- **Required Permissions**:
  - "Display over other apps" for Floating Lyrics.
  - "Notifications", "Read device audio", and "Overlay" permissions for full functionality.
- **Network**: Internet connection is required for fetching Netease music.

## 🛠️ Tech Stack

- **Language**: Java
- **Networking**: OkHttp 3
- **UI Components**: AndroidX AppCompat, Material Design, ConstraintLayout
- **Architecture**: MVVM-like structure driven by Managers and Services.

## 🚀 Setup & Installation

Please go to the [Releases](https://github.com/midairlogn/ML-Netease_Android/releases) page to download and install the latest APK.

Alternatively, you can build from source:

1. Clone the repository:
   ```bash
   git clone https://github.com/midairlogn/ML-Netease_Android.git
   ```
2. Open the project in Android Studio.
3. Build and run the application on your Android device or emulator.

## 📖 Usage

> **Note**: For the application to function correctly, please grant permissions for Notifications, Device Audio, and Floating Windows. The app will guide you through this process if permissions are missing at startup.

1. **Search**: Use the search bar on the home screen. You can search by name or directly use a song ID.
2. **Play**: Tap on a song to start playing.
3. **Local Audio**: Automatically scan local audio files on the device, manually add files for playback, or directly open audio files in this app from a file manager.
4. **Favorites & Downloads**: Favorite both local and Netease Cloud songs, and use the custom download feature for batch downloads.
5. **Floating Lyrics**: Enable "Floating Lyrics" in Settings. A floating icon/window will appear; tap to expand for controls and settings.
6. **Settings & Data Migration**: Configure audio quality, smart hearing protection, and your Netease `MUSIC_U` cookie for authenticated access; settings and home shortcuts can also be exported and imported in encrypted form. Backup files use encrypted `.mlns` format.
   > For detailed information on the obtainment of `MUSIC_U`, please refer to [Methods to Obtain `MUSIC_U`](https://github.com/midairlogn/ML-Netease_url/blob/main/MUSIC_U/get-MUSIC_U-EN.md).

## Disclaimer

This tool is for learning and communication purposes only and is not an official application of NetEase Cloud Music. Please support genuine music. When using this tool, please abide by relevant laws and regulations and respect the work of musicians.

## Related Projects

- [ML-Netease_url (Midairlogn)](https://github.com/midairlogn/ML-Netease_url)
- [Netease_url (Suxiaoqinx)](https://github.com/Suxiaoqinx/Netease_url/)

## License

[GNU General Public License v3.0](LICENSE)
