<div align="center">
<h1>ML-Netease_Android</h1>
一个轻量级的 Android 网易云音乐播放器。<br><br>

**中文简体** | [**English**](README_EN.md)
</div>

## ✨ 主要功能

### 🎵 网易云音乐集成

- **歌曲搜索**：支持搜索并播放网易云音乐曲库。支持通过名称搜索，或直接使用歌曲 ID 解析。
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
- **必要权限**：
  - 悬浮歌词需“显示在其他应用上层”权限。
  - 软件运行需“发送通知”、“读取设备音频”及“悬浮窗”权限。
- **网络连接**：需要较为稳定的网络环境。

## 技术栈

-   **语言**: Java
-   **网络**: OkHttp 3
-   **UI 组件**: AndroidX AppCompat, Material Design, ConstraintLayout
-   **架构**: 采用类 MVVM 结构，包含 Manager (管理器) 和 Service (服务)。

## 设置与安装

请前往 [Release](https://github.com/midairlogn/ML-Netease_Android/releases) 页面下载最新版本的 APK 安装包进行安装。

当然，你也可以选择从源代码直接构建：

1.  克隆仓库:
    ```bash
    git clone https://github.com/midairlogn/ML-Netease_Android.git
    ```
2.  在 Android Studio 中打开项目。
3.  在您的 Android 设备或模拟器上构建并运行应用程序。

## 使用方法

> 为了软件的正常运行，请授予该软件发送通知消息、读取设备上音频、悬浮窗的权限，在软件启动时候如果检测到权限未开启，软件也会引导你开启相应的权限。

1.  **搜索**: 在主界面使用搜索栏查找歌曲。支持输入名称搜索，也可直接输入歌曲 ID 解析。
2.  **播放**: 点击歌曲即可开始播放。
3.  **悬浮歌词**: 在“设置”中启用“悬浮歌词”功能。开启后将出现一个悬浮图标/窗口，点击即可展开查看控制选项和设置。
4.  **设置**: 配置音频质量，并输入您的网易 `MUSIC_U` cookie 以进行认证访问。
    > 关于 `MUSIC_U` 的获取，请参考 [获取 `MUSIC_U` 的方法](https://github.com/midairlogn/ML-Netease_url/blob/main/MUSIC_U/get-MUSIC_U.md).

## ⚠️ 免责声明

本项目仅供学习和个人使用，非网易云音乐官方应用。所有内容和数据版权归原作者所有。

## 致谢相关项目

- [ML-Netease_url (Midairlogn)](https://github.com/midairlogn/ML-Netease_url)
- [Netease_url (Suxiaoqinx)](https://github.com/Suxiaoqinx/Netease_url/)

## 许可证

[GNU General Public License v3.0](LICENSE)