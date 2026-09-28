# MPEG-TS 原始录像直通验证契约

本文件是录制播放器中 `オリジナル (MPEG-TS)` 模式的实现与验收契约。

## 范围与不变量

- 仅当录像已完成、容器为 `MPEG-TS`，且用户选择
  `StreamQuality.ORIGINAL_MPEG_TS_VALUE` 时启用本管线。HLS、MMT/TLV 与普通转码质量不受影响。
- 原始视频流不限制 MPEG-2、H.264 或 HEVC 编码；实际播放仍取决于设备的 Media3 解码器能力。
- 播放器必须继续使用原始下载 URL；服务器 API 与公开协议不得改变。
- MPEG-2、H.264、HEVC 等原始视频 elementary stream 必须直通，不得转码或替换。输出 PMT 必须继续公开
  对应视频、主/副音频和 ID3 metadata track，音轨切换必须使用 Media3 的真实 track groups。
- 原始 MPEG-TS 必须统一经过既有 tsreadex `servicefilter + id3conv`，使用录像的
  `serviceId` 及参数 `-a 13 -b 5 -c 5 -u 1 -d 9`，完成单服务筛选、音轨整理，
  并将 ARIB STD-B24 字幕与文字叠加转换为标准 ID3 PRIV metadata。
- `id3conv` 输出的每个 ID3 PES 必须设置 `data_alignment_indicator`，使 Media3 `Id3Reader`
  接受完整 ID3 sample 并将其交给现有 metadata 回调；不得用自定义 payload reader 取代该链路。
- ID3 PRIV owner 为 `aribb24.js`。private data 的 `data_identifier=0x80` 只进入普通字幕解码器，
  `data_identifier=0x81` 只进入文字叠加解码器；非法、截断或其他 private data 不得误送。
- 隐藏普通字幕时，仍必须向普通字幕解码器送入管理包以维持语言列表；文字叠加不受普通字幕
  开关影响，始终按独立解码器处理。
- 保留 HTTP 请求头、Range seek、追赶播放的已知文件长度更新和播放器现有的自定义 SeekMap。
  非零 Range 请求不得接受静默回退的 HTTP 200；重新打开后必须重新发现 PAT/PMT 并继续输出 metadata。
- 用户可见质量名称固定为 `オリジナル (MPEG-TS)`；内部值仍为 `original-mpegts-hwdi`。
- 当当前录像不提供 Original 时，播放器可为本次播放选择可用质量，但不得因此覆盖用户保存的 Original 偏好。
  设置项标题仍是功能开关文案。BML 数据广播不属于本次 MPEG-TS 直通范围，播放器布局不得扩大或重排。

## 字幕展示与切换

- ID3 必须从 `9 + PES_header_data_length` 开始，不得插入五个零字节；保留 PTS 单调化及 `0x84`。
- 确认画质切换时保留当前可见字幕最多 5 秒，以单调时钟计时；暂停、缓冲及没有新字幕均不得延长。
  丢弃旧时间轴队列、重置解码器，以来源代次拒绝旧播放器和异步解码结果；新流对应字幕或清屏提前替换。
  普通字幕和文字叠加独立处理，连续切换不得延长同一旧画面的期限。
- 关闭普通字幕、换节目、退出及主动 seek 直接清理相关展示；正常播放尊重广播持续时间和清屏。
  字幕覆盖区域保持固定，空、过渡、正常、清屏状态只改变区域内绘制，不移动邻接控件或焦点。
- 录像及 SMB 入口必须在宿主中取得并显式传入非空 ViewModel；播放器不得依赖复杂 Composable
  默认参数求值来初始化必需依赖。实机已记录 `2026-09-06 22:05:25` 默认参数路径空 ViewModel 崩溃，
  必须从同一录像入口重新进入成功后才继续字幕验收。

## 回归与实机验收

- JVM：管线选择、HTTP Range、ID3 `0x80`/`0x81` 分流、非法/截断 payload、字幕关闭、
  语言管理包以及播放器 fence/reset。
- 用可控时钟覆盖五秒过渡、暂停／缓冲／无新字幕、提前替换、连续切换、旧代次迟到、两层独立清理。
- Native：构造 PAT/PMT、MPEG-2 与 HEVC 视频、双音轨、字幕、文字叠加与非字幕 private stream；
  验证视频 stream type/负载、双音轨、精确 PES payload 起点、`0x84`、PRIV owner/PTS/原始 payload，
  以及重新打开后的 PAT/PMT 发现。不得搜索 ID3 跳过前缀。
- 将实际 native 输出送入实际 Media3 提取链验证 metadata；只检查模拟 ID3 字节不足以验收。
- Android Debug 与 Release 构建必须通过。
- Release 保留数据原地安装电视 `172.24.0.219:5555`，核对 APK SHA-256；当前录像入口验证
  “转码有字幕 → MPEG-2 → 转码”、过渡到期、MPEG-2 硬解、主副音轨、字幕语言、文字叠加、seek、暂停恢复及重开。
- 只有电视画面与对应链路证据均通过，才认定修复完成。本地测试和构建不能替代实机验收。

## 字幕 owner 标识

- `app/src/main/assets/aribb24.js` 已由 `libaribcaption` 原生解码替代并移除。
- ID3 PRIV owner 字符串 `aribb24.js` 仍是字幕数据的既有格式标识，不表示 JS 资源或运行时仍存在。

## 实机连续播放失败（2026-09-06）

- 用户在 MPEG-2 模式报告严重卡顿。22:10:45 与 22:11:05 实机发生
  `AdtsReader.readSample → SampleQueue.commitSample: IllegalArgumentException`，触发 SourceError、
  缓冲及解码器重建；22:11:02、22:11:04 已收到 MPEG-2 ID3 字幕并绘制，但这不代表播放验收通过。
- 必须修复音频 sample 提交异常，并在同一录像连续播放验证不再反复重建或缓冲；
  不能通过禁用音轨、吞掉断言或延迟自动重试掩盖故障。字幕定时处理不能引入不必要的高频 UI 工作。
- 已定位本地 Media3 `0001` 补丁在 PTS 未定时跳过 sampleData，但跨 PES 后按完整大小提交 metadata。
  必须保留完整帧字节，仅在整帧结束仍无 PTS 时抑制 metadata；回归覆盖跨 PES 中途获得 PTS。

## 修复部署与性能观察（2026-09-06 22:25—22:29）

- 本地 Media3 `1.11.0-komorebi6` 已重新发布；AAC 跨 PES 回归与其余 JVM 测试共 172 项全部通过，
  Debug／Release 构建通过。覆盖安装后电视与本地 APK SHA-256 均为
  `b646c8374ce6dd7373d6d1f75d564cc1cd73fc7334e81424ed16dc6297034879`。
- 用户反馈“好像可以了”并要求监控性能。22:25:54 从 1080p-60fps 切入 MPEG-2，22:26:02 ready，
  后续收到字幕与广播清屏；截图可见黄色 ARIB 字幕。观察期间没有再出现 SampleQueue 异常或播放器错误。
- 活跃播放七次 CPU 采样为 156/124/142/136/171/168/148%（四核合计 400%），
  仍有较高负载，不能据此宣称性能已优化。初次 PSS 220065 KiB、RSS 317300 KiB。
- SurfaceView 采样 126 个有效展示时间戳，跨度 4170.93 ms，间隔中位 33.41 ms、最大 52.29 ms，
  三个间隔超过 50 ms；这是约四秒的呈现采样，不能等同于整段解码掉帧率。
- 应用启动以来 UI gfxinfo 为 6516 帧、1066 janky（16.36%），包含进场、切换及交互，
  不是视频 SurfaceView 的掉帧率。22:26:36 与 22:27:29 有缓冲并伴随位置跳变，未出现 SourceError，
  仅凭现有日志不能判定为自动卡顿或主动 seek。22:28 后 media_session 明确处于 PAUSED，
  后续线程低负载不能作为连续播放性能证据。完整切换、seek 与双音轨实机矩阵仍未验收完成。
- 22:29 恢复播放后，20.39 秒 `/proc/20543/task/*/stat` 差分（已核对 CLK_TCK=100）
  合计约 116.29% CPU；主线程 28.10%、RenderThread 23.15%、ExoPlayer 播放线程 15.94%、
  Loader 5.49%。第二次 PSS 201108 KiB（含 SwapPss 51258 KiB），没有单调增长证据；
  该短窗口不能证明无内存泄漏。原始诊断命令与完整输出保留在本次会话。
