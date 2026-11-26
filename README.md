# SPW AutoMix

> 给 Salt Player for Windows（SPW）加上 DJ 式的歌曲转场：自动找切点、对拍、交换低音，也能在双轨波形上手动微调并试听。

**[⬇ 下载 v0.1.0（.spmod）](https://github.com/Jethuit/SPW-AutoMix/releases/download/v0.1.0/SPW-AutoMix-v0.1.0.spmod)** · [全部版本](https://github.com/Jethuit/SPW-AutoMix/releases) · [发布说明](docs/releases/v0.1.0.md)

| 项目 | 说明 |
| --- | --- |
| 系统 | Windows x64 |
| 宿主 | Salt Player for Windows，已在 **1.19.3** 上验证 |
| 依赖 | 无需额外安装，安装包内置 FFmpeg，可离线使用 |
| 音频 | 本地曲库歌曲，单曲首次分析上限 15 分钟 |

## 它能做什么

- **自动转场**：分析拍点、小节、段落和响度，为相邻两首歌推荐退出点、进入点和过渡方式。
- **接管实际切歌**：开启 SPW 的「智能过渡」后，正常播放时自动使用 AutoMix 的方案，不用每次手动操作。
- **双轨编辑器**：上下两条波形分别是退出曲和进入曲，显示 BPM、调性（含 Camelot 编号，如 `C♯m · 12A`）、小节首拍和拍点。拖动波形即可改切点。
- **一键试听**：试听会把两首歌响度匹配到 −14 LUFS，听到的就是切歌时的效果。
- **歌单编排**：扫描整张歌单，挑出能接得顺的歌，或把整张歌单按衔接顺序重排，另存为新歌单。

## 安装

1. 下载 `.spmod` 文件，在 SPW 的「创意工坊」中导入。
2. 授予两项权限后启用插件：
   - **曲库读取**：读取本地歌曲和歌单。
   - **类变换**：让 SPW 切歌时采用 AutoMix 的方案。
3. **在 SPW 播放设置中开启「智能过渡」**。不开启的话只能在编辑器里试听，正常切歌不会使用 AutoMix 的方案。
4. 从 SPW 左侧栏点击 **AutoMix** 进入，或在插件配置页点「打开 AutoMix」。

> [!IMPORTANT]
> **必须开启 SPW 的「智能过渡」**，AutoMix 才会接管实际切歌。可以在插件配置页的「播放接入状态」确认是否已接管。

## 快速上手

### 1. 听一次自动转场

1. 在右侧「队列」或「歌单」里，点两首歌之间的转场按钮；正在播放时，也可以点右上角「当前转场」。
2. 预设选「自动」，点两条波形中间的播放按钮试听。

### 2. 按自己的口味调整

- **拖动波形**：上方 A 轨调整退出点，下方 B 轨调整进入点。开启「拍点磁吸」后会自动对齐小节首拍或拍点。
- **调参数**：过渡长度（2 / 4 / 8 小节）、音量曲线、均衡器（低音交接）、滤波、效果。鼠标悬停在选项上时，波形会先预览曲线，点击后才生效。
- 满意后点右上角 **保存**。保存只作用于这一对歌曲的这个方向（A → B）。

### 3. 让正常播放也用上 AutoMix

> [!IMPORTANT]
> 在 SPW 播放设置中开启 **智能过渡**（安装时已提到）。未开启时，保存的方案和自动推荐都只能在编辑器中试听。

开启后，切歌时按以下优先级选用方案：

1. 你手动保存过的曲对方案；
2. AutoMix 在后台为下一首准备好的自动推荐；
3. 推荐未就绪或已错过切点时，回退为原速匹配。

> 某一对歌曲想交给 SPW 自己处理时，选择「沿用 SPW」即可。

## 歌单编排

在右侧「歌单」中选择一张歌单，点击「生成混音」：

| 选项 | 效果 |
| --- | --- |
| 仅扫描 | 分析并缓存 BPM、调性等结果，之后打开更快 |
| 挑歌并新建 | 只保留适合衔接的歌，按推荐顺序另存为「精选」歌单 |
| 全单重排并新建 | 保留全部歌曲，按推荐顺序另存为新歌单，并提示较弱的衔接数 |

原歌单不会被修改。列表中还支持：双击播放、拖动左侧手柄排序、单击歌曲选择相邻转场。

## 常见问题

**切歌时没有用上 AutoMix？**
确认已授予「类变换」权限，并在 SPW 播放设置里开启了智能过渡。插件配置页的「播放接入状态」可以查看当前是否已接管。如果切歌前已经错过设定的切点，把进度条往回拖一点即可触发。

**显示「调性未知」？**
调性识别置信度不足时不会强行给出结果，不影响转场。

**有的歌被扫描跳过了？**
首次分析只支持 15 分钟以内的音频，超长曲目会跳过；之前已有的有效分析缓存仍会复用。

**分析结果存在哪里？**
插件配置目录下的 `analysis/`。删除后会在下次使用时重新分析。

## 从源码构建

需要 Python 3。在仓库根目录运行：

```powershell
# 正式版：内置 FFmpeg，可离线使用
py -3 tools/build_plugin.py

# 轻量版：使用本机 FFmpeg，适合开发调试
py -3 tools/build_plugin.py --system-runtime
```

安装包输出到 `build/libs/`，轻量版文件名带 `.lite` 后缀。轻量版会从 SPW 进程的 `PATH` 中查找 FFmpeg，也可以用环境变量 `AUTOMIX_FFMPEG` 指定路径（修改后需重启 SPW）。版本号约定见 [AGENT.md](AGENT.md)。

## 项目结构

```text
plugin/src/        插件源码（Kotlin）
  Dsp*.kt          音频解码、拍点 / 调性 / 响度分析、转场规划与试听渲染
  Host*.kt         与 SPW 的对接：播放、队列、歌单、导航、主题
  Editor.kt        AutoMix 编辑器界面
plugin/resources/  插件配置页与许可文件
tools/             构建脚本
tests/             自动选点测试
docs/              需求、技术设计、验证计划与发布说明
```

更多文档：[产品需求](docs/REQUIREMENTS.md) · [技术设计](docs/AUTOMIX_DESIGN.md) · [首轮验证计划](docs/FIRST_VALIDATION.md) · [插件详细说明与更新历史](plugin/README.md)

## 致谢

界面接入方式参考了 [SPW Listen Stats](plugin/resources/META-INF/LICENSE-SPW-Listen-Stats)，部分结构规则源自 [SPW-Lyrics](plugin/resources/META-INF/LICENSE-SPW-Lyrics)，两者均为 MIT 许可，许可文件随安装包提供。

## 许可证

- AutoMix 自有代码采用 [MIT 许可证](LICENSE)。
- 第三方代码保留各自的许可声明。 [SPW Listen Stats](plugin/resources/META-INF/LICENSE-SPW-Listen-Stats) · [SPW-Lyrics](plugin/resources/META-INF/LICENSE-SPW-Lyrics) · [Compose 索引工具](tools/LICENSE-RestoreComposeIndex)
- 离线包内的 FFmpeg 在独立进程中运行，随包附有 GPLv3 许可和构建说明。 [FFmpeg 构建](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2) · [FFmpeg 源码](https://github.com/FFmpeg/FFmpeg/tree/946fcce07b)
