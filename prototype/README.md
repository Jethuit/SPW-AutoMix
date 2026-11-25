# 离线过渡试听与 Demo 工作台

需要 Python 3、NumPy、SciPy，以及 PATH 上可直接运行的 FFmpeg（含 `rubberband` 滤镜）。

## 1. 交互式工作台

在仓库根目录运行 `py -3 prototype/editor_server.py`（默认索引 `E:/music` 下的音频文件，可通过 `--music` 指定目录），打开 <http://127.0.0.1:18763/>。

主要功能：
- **参考曲对与自选曲库**：左侧提供 5 组对齐 Spotify 截图模式的对照曲对（含《夏夜→Inspire》《V→The Rebel Path》《Akane→Moon Flow》《Her Eyes→Duality》《Without Me→Billie Jean》），也可展开“＋ 自选本地曲对”从本地曲库任选两首歌进行分析与混音。
- **自动规划与中点落鼓**：点击顶部 **⚡ 自动推荐**，后台检测 BPM（含非整数速度）、强拍、B 轨主节奏落点与全曲响度差，优先将 B 轨主节奏落点对齐混音中点，支持高达 ±10.5% 的保音高/共振峰节拍匹配并列出推荐依据。
- **三频段彩色波形与节拍磁吸**：蓝（低频）/橙（中频）/白（高频）三频段波形叠加拍点刻度，并显示音量（蓝）、低音 EQ（黄）与半窗滤波（紫红）三条包络；拖动波形切点默认开启**🧲 节拍磁吸**（优先吸附强拍与普通拍点，按住 `Alt` 可自由微调，黄色菱形可磁吸小节线）；支持 `1× / 2×` 缩放、`-1拍 / ±10ms / 吸附拍点` 按钮以及键盘快捷键（`Space` 播放/暂停，`←/→` 微调 10 ms，`Shift+←/→` 移动 1 拍）。
- **快速试听定位与测量**：点击主波形即可跳转播放头到对应时间；传输栏提供“开头 / 交接前 1s / 换低音前 1s”跳转，并在下方实时展示 A/B/混音 LUFS、自动响度补偿、4× 真峰值与限幅器压减量。
- **完整 DSP 参数与预设**：支持 5 种音量包络、4 种 causal LR4 3-band EQ 模式（含 0.25 拍干脆低音切换、三频段交接与快速切低音）、8 种半窗扫频滤波组合（含双高通进出 `融入`）、5 种效果（含回声及三类混响）、A 轨节拍循环、预热补偿后的保音高/共振峰节拍匹配（Rubberband）及归一化至 -14 LUFS 的全曲门控自动响度匹配。

## 2. 合成 DSP 精度校验

运行自动校验脚本验证时间对齐、频段隔离、滤波衰减、响度匹配与峰值限制：

```powershell
py -3 prototype/verify_dsp.py
```

校验项覆盖同速（120 BPM 拍点误差 < 1 ms、单轨静音、低音交换）、Rubberband 变速预热（含 `cue_b = 0.0` 首拍瞬态保留、全段拍差 < 5 ms）、100% 湿声扫频高/低通以及 LR4 中性平坦度、LUFS 对齐、效果尾音和 -1 dBTP 真峰值限制。

## 3. 命令行分析与渲染

分析单曲或生成曲对自动计划：

```powershell
py -3 prototype/analyze.py "E:\music\四季音色 - 夏夜.flac" "E:\music\Capo Productions - Inspire.flac" --bpm-a 100 --bpm-b 100
```

直接从命令行渲染过渡音频（输出 48 kHz 双声道 float32 WAV，可选 `--pcm16` 导出 16-bit PCM）：

```powershell
py -3 prototype/render.py "E:\music\四季音色 - 夏夜.flac" "E:\music\Capo Productions - Inspire.flac" preview/summer-inspire.wav --exit-start 166.818 --entry 14.646 --bpm 100 --bars 4 --context 8 --bass-a -24 --bass-b -24 --loudness-match
```
