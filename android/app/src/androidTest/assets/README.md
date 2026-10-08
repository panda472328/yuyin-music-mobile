# 受控音视频夹具

`controlled-video-with-audio.mp4` 是本项目自行生成的 90 秒、64×64、2 fps 纯色视频及低音量 220 Hz 测试音，不含第三方音乐或视频。H.264 baseline 视频轨道和 AAC 单声道音频轨道用于验证真正的音视频流在 WebView 中的后台策略；将 WAV 放在 `<video>` 中只能覆盖音频流。每 2 秒包含一个关键帧，文件约 195 KB。

可使用 FFmpeg 重新生成：

```sh
ffmpeg -f lavfi -i 'color=c=0x66806f:size=64x64:rate=2:duration=90' \
  -f lavfi -i 'sine=frequency=220:sample_rate=16000:duration=90' \
  -c:v libx264 -preset veryslow -profile:v baseline -pix_fmt yuv420p \
  -g 4 -keyint_min 4 -sc_threshold 0 \
  -c:a aac -b:a 16k -ar 16000 -ac 1 -af volume=0.25 \
  -movflags +faststart -shortest controlled-video-with-audio.mp4
```

仅 `androidTest` 的独立测试 APK 包含此目录；正式 release 应用不包含测试宿主、媒体夹具或注入入口。`ControlledPlaybackTest` 通过官方视频页的受控 base URL 加载内存数据，拦截所有 HTTP 请求，不访问或下载 Bilibili 音视频。
