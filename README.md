# Synapath PICO

PICO Enterprise 数据采集平台。目录按 Operator 的职责划分组织，当前头显端代码来自已适配 capturelib-0.1.1 的官方 CaptureSDKDemo，是原生 Android/Kotlin 应用。

- `xr/`：头显端 Android 工程，入口为 `app/src/main/java/com/picoxr/capturesdkdemo/MainActivity.kt`。
- `scripts/`：Docker 构建、设备安装、SDK 校验及视频 PTS 分析。
- `docker/`：Java 17、Android SDK 35 开发环境。
- `docs/`：采集配置与数据约定。
- `third_party/pico/`：本地供应商 AAR，二进制不纳入 Git。
- `python/`：后续数据处理模块预留位置。
- `web/`：后续采集数据回看模块预留位置。

## 构建

将供应商提供的 `capturelib-0.1.1.aar` 放到 `third_party/pico/`。本机已配置该文件，构建前会校验 SHA-256。

```sh
make build
```

首次构建自动创建 Docker 镜像。APK 输出：`artifacts/SynapathPico-CaptureSDK-0.1.1-debug.apk`。
也可在 Android Studio 中打开 `xr/`，使用 Java 17 与 Android SDK 35 构建。

```sh
make install
adb shell am start -n com.synapath.capturesdkdemo011/com.picoxr.capturesdkdemo.MainActivity
```

Mac 的 ADB 负责 USB 安装；Docker 负责编译。多设备时安装可设置 `PICO_SERIAL`。
安装后应用名为 `Synapath PICO`，界面采用 Operator 风格的 Android 2D 双栏面板，包名保持 `com.synapath.capturesdkdemo011`。

## 检查录像

录像由 SDK 的 `startRecord` 生成，位于设备 `/sdcard/Movies/DemoPicture-*.mp4`。
使用 Mac 上的 ADB 拉取文件后，通过本机 ffprobe 检查逐帧时间戳：

```sh
python3 scripts/analyze-video.py /path/to/video.mp4
```

四段已测录像的文件输出均为 60 FPS，包括重启后的测试。该结果不等同于物理相机曝光频率保证，详情见 [采集基线](docs/capture-baseline.md)。
