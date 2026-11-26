# 当前采集基线

## 代码与设备

代码迁入自 `../scripts/CaptureSDKDemo`，即已适配 capturelib-0.1.1 的官方示例。
初次迁入时采集代码保持原样。当前 Android 2D 界面已按 Operator 的深色双栏布局重做；保留原有 SDK 配置与预览、录像、拍照调用路径。
AAR 包含的 native 库不提供完整相机驱动或系统拍摄服务源码。

设备为 PICO 4 Ultra Enterprise，Android 型号 A9210，已测固件 5.15.8。
四段基线录像的文件名和帧数分别为：

- DemoPicture-1795677911470.mp4：827 帧，13.783333 秒。
- DemoPicture-1795678086750.mp4：1101 帧，18.350000 秒。
- DemoPicture-1795678417615.mp4：653 帧，10.883333 秒，设备重启后。
- DemoPicture-1795678570440.mp4：1131 帧，18.850000 秒。

四段文件逐帧 PTS 均约 16.667 ms；未观察到时间戳倒退或超过 25 ms 的间隔。
原文件保留在仓库外的 `../data/debug/demo011_user_record_20261126/` 和 `../data/debug/demo011_reboot_20261126/`，不提交录像数据。

## 参数

- SDK：capturelib-0.1.1.aar。
- SHA-256：`9e5a15f39709b94cb781068167d406da229c4fb37e821606efe802bf5c6cb267`。
- 请求视频帧率：60 FPS。
- 配置视频尺寸：2048×768，MV-HEVC 关闭。
- 配置码率：10×1024×1024 bit/s。
- 默认未勾选“反畸变图像”，启用 `output-camera-raw-data`。
- MCTF、EIS、MFNR：沿用官方示例，开启。更改这些参数后需要重新验收。
- 当前界面名称 Synapath PICO，版本 0.1.1-ui；包名保持 com.synapath.capturesdkdemo011。

## 数据路径和已知边界

`startPreview` 送图到 SurfaceView，`startRecord` 直接交由 SDK 生成文件。
开始录像前停止 SDK 预览；当前界面在停止录像的 CONFIGURED 回调后自动恢复预览。
RAW 模式不保证传感器内存直出。手册指出仍有一次渲染合成与编码。
文件 PTS 的 60 FPS 不证明每帧对应一次独立曝光。

保留官方示例的状态回调和调用结构作为对照基线，尚未将其改造成生产级状态机。
示例存在回调中同步 configure、GlobalScope 生命周期、权限拒绝后初始化等需后续处理的点。
标定查询使用固定的空间视频尺寸与 FOV，不能直接视为 RAW 画面的有效标定。

本次只迁移代码与构建入口，不修改头显固件、显示模式、休眠或透视设置。

## Android 2D 界面验收

0.1.1-ui 版本在设备完成安装、导航切换、录像起停和相机内外参读取。
新录像 DemoPicture-1795681542972.mp4 为 2048×768，642 帧、10.7 秒；
逐帧 PTS 实测约 60 FPS，无倒退或超过 25 ms 的间隔。
本地证据见 `artifacts/ui-validation/`（Git 忽略）。

## 录像预览互斥验证

在当前 A9210 / capturelib-0.1.1 环境中，保持 startPreview 活跃后调用
startRecord 返回 0，未进入录制状态；先 startRecord 成功后再 startPreview，
后者同样返回 0。两种调用顺序的日志在本地 artifacts/concurrent-preview/。
因此当前版本保留 SDK 直接录像路径，录像中遮住旧预览并显示计时提示，
停止录像后自动恢复预览。未声称支持并行实时预览。

相机标定页使用分组数值卡片。空间视频内参以 SDK 参数索引展示，
外参以每四个元素一行呈现，不推定未确认的坐标变换语义。
显示保留五位小数，JSON 导出保留 SDK 返回的 double 精度，
路径为应用 external files 下的 camera-calibration.json。

## 预览缓冲区尺寸

0.1.1-ui.3 将 SurfaceHolder 缓冲区固定为 1280×480，startPreview
传入同一尺寸；界面仍以 8:3 比例缩放。此前窗口布局可产生 813×305
等奇数尺寸，存在双目渲染边缘对齐风险。该修改用于排查用户报告的
右侧闪烁竖线，不能仅凭尺寸变更认定现象已消失。录像配置保持
2048×768、请求 60 FPS。
