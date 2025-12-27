<div align="center">
<h1>ML-Netease_Android</h1>
一个轻量级的 Android 网易云音乐播放器。<br><br>

**中文简体** | [**English**](README_EN.md)
</div>



## ✨ 主要功能

### 🎵 网易云音乐集成

- **搜索歌曲**：支持搜索并播放网易云音乐曲库。
- **歌单与详情**：查看歌曲详情、专辑信息及播放列表。
- **账号支持**：支持设置 `Music_U` Cookie 进行登录，解锁更高音质选项。

### 🎧 音乐播放器

- **完整控制**：支持播放、暂停、上一曲、下一曲。
- **播放模式**：支持列表循环、单曲循环、随机播放、顺序播放。
- **后台播放**：支持后台服务运行，切出应用仍可继续听歌。

### 💬 歌词系统

- **同步歌词**：应用内主界面显示同步滚动歌词。
- **悬浮歌词 (Floating Lyrics)**：
  - 支持在其他应用上层显示歌词。
  - 可调节字体大小和颜色。
  - 提供迷你播放控制栏。
  - 支持锁定模式（防误触）和折叠/展开视图。

## 📱 系统要求

- **Android 版本**：`Android 12.0 / API Level 24.0` 及以上。
- **权限**：悬浮歌词功能需要授予“显示在其他应用上层”权限。
- **网络**：需要网络连接。

## 技术栈

-   **语言**: Java
-   **网络**: OkHttp 3
-   **UI 组件**: AndroidX AppCompat, Material Design, ConstraintLayout
-   **架构**: 采用类 MVVM 结构，包含 Manager (管理器) 和 Service (服务)。

## 设置与安装

1.  克隆仓库:
    ```bash
    git clone https://github.com/midairlogn/ML-Netease_Android.git
    ```
2.  在 Android Studio 中打开项目。
3.  在您的 Android 设备或模拟器上构建并运行应用程序。

## 使用方法

1.  **搜索**: 在主屏幕使用搜索栏查找歌曲。
2.  **播放**: 点击歌曲即可开始播放。
3.  **悬浮歌词**:
    -   在“设置”中启用“悬浮歌词”功能。
    -   在提示时授予所需的权限。
    -   将出现一个悬浮图标/窗口。点击即可展开，显示控制选项和设置。
4.  **设置**: 配置音频质量，并输入您的网易 `MUSIC_U` cookie 以进行认证访问。

## ⚠️ 免责声明

本项目仅供学习和个人使用，非网易云音乐官方应用。所有内容和数据版权归原作者所有。

## 致谢相关项目

- [ML-Netease_url (Midairlogn)](https://github.com/midairlogn/ML-Netease_url)
- [Netease_url (Suxiaoqinx)](https://github.com/Suxiaoqinx/Netease_url/)

## 许可证

[GNU General Public License v3.0](LICENSE)