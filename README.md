# SPW AutoMix

SPW AutoMix 是面向 Salt Player for Windows 的转场插件。它分析本地音频，推荐切点和混音方案，并提供双轨波形编辑器供试听和调整。

## 安装

从 GitHub Releases 下载 `SPW-AutoMix-v0.1.0.spmod`。正式包内置 FFmpeg，支持离线分析和试听。兼容性检查使用 Windows x64 上的 SPW 1.19.3。 [GitHub Releases](https://github.com/Jethuit/SPW-AutoMix/releases)

1. 在 SPW 创意工坊导入安装包，授予曲库读取和类变换权限，再启用插件。
2. 从左侧导航栏打开 **AutoMix**。双击右侧歌曲可以直接播放，点击歌曲间的转场可以编辑和试听。
3. 如需让方案在日常播放中自动应用，在 SPW 播放设置中开启 **智能过渡**。已保存的曲对设置优先。

## 功能

- 双轨波形、拍点和强拍、BPM、调名、Camelot 编号及响度。
- 原速转场推荐、拍点磁吸、自定义音量和效果曲线，以及响度匹配试听。
- 浏览和调整播放队列与歌单，扫描分析结果或生成新的混音歌单。

操作说明和本版注意事项见 [插件文档](plugin/README.md)及 [v0.1.0 发布说明](docs/releases/v0.1.0.md)。
