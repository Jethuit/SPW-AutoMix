# SPW AutoMix 打包约定

- **本机开发与测试**使用轻量安装包。从仓库根目录运行 `py -3 tools/build_plugin.py --system-runtime`，复用本机已有的 FFmpeg。
- **正式发布**使用离线安装包。从仓库根目录运行 `py -3 tools/build_plugin.py`，随包提供 FFmpeg。目标电脑导入后应能离线使用。
- 两种安装包都输出到 `build/libs/`。开发构建使用独立的 `dev` 版本号，轻量版带 `.lite` 后缀。包内版本号与文件名一致。正式发布时使用未发布过的正式版本号，已有安装包、发布附件和 Git 标签保持原样。
