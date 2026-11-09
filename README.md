# ScrollShot 滚动截屏

自动滚动截屏 + 自动拼接长图工具，支持多级嵌套容器滚动。

## 目标版本
- compileSdk 36，minSdk 35，targetSdk 36
- 可安装运行于 Android 15 / 16 / 17 及更新版本。
- 说明：构建时 Google 官方仓库中尚未提供 Android 37（API 37）平台包，
  因此使用 API 36 编译；API 36 的应用在 Android 17 上可正常运行，
  且满足 Google Play 自 2026 年 8 月 31 日起对新应用 target API 36+ 的要求。
  待平台包可用后，只需把 compileSdk / targetSdk 改为 37 即可重新构建。

## 功能
1. 跨应用自动滚动截屏：无障碍服务 + 录屏截取（MediaProjection）。
2. 丝滑滚动：慢速手势滑动（650ms），小步多帧，逐帧拼接。
3. 多级容器：遍历无障碍树，按面积从小到大（最里层优先）依次尝试滚动，
   里层滚到底后自动滚外层；每个节点先竖向、再横向尝试。
4. 自动拼接：按手势距离预估重叠，再用灰度模板匹配在预估附近搜索最佳重叠，
   去掉重复段；顶部状态栏和底部导航栏在截取时裁掉，悬浮按钮在每帧前隐藏。
5. 手动拼接：选多张已有截图，按重叠自动拼成长图。
6. 保存与分享：保存到相册 Pictures/ScrollShot（PNG），可预览、分享。

## 权限与限制（验收前必读）
- 只需授权一次：无障碍服务和悬浮窗开启后会一直保留，App 会跳过已授权项，不重复弹窗；通知为可选，不影响截屏。
- 录屏授权是 Android 系统的硬性规则：每次启动截屏服务时系统会问一次，App 无法永久绕过。为减少打扰，v1.2 起服务在一次截屏结束后不会退出，悬浮窗会回到“已就绪”状态，可再次点“开始”继续截，无需重新授权；只有点悬浮窗“退出”才会真正结束服务、下次再问。
- Android 15+ 侧载安装后，开启无障碍可能需要先在系统设置里放开“受限设置”，
  请按系统提示操作。
- 防截屏页面（部分银行、DRM 视频等）系统本身不允许截取，会提示失败，
  不会拼黑图。
- 截取上限：单次最多 40 帧，拼合高度上限 40000px，超出会提示/降采样。

## 使用方法
1. 打开 App，完成 1-3 步权限。
2. 点“开始滚动截屏”，同意录屏后 App 退到后台，出现悬浮按钮。
3. 切换到目标页面，点悬浮窗“开始”，工具开始滚动截取。
4. 点悬浮窗“停止”或通知里的“停止”，拼好的长图自动保存并打开预览。

## 构建
首选手动构建（不依赖 Gradle Daemon，适合本机环境）：
```
export ANDROID_HOME=/path/to/Android/sdk
./build-material.sh
```
产物：`build/manual/ScrollShot-1.0.0-debug.apk`（调试签名，可直接安装）。

也可以在 Android Studio 中打开本项目构建，或用命令行：
```
export ANDROID_HOME=/path/to/Android/sdk
gradle :app:assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 目录结构
- app/src/main/java/com/scrollshot/MainActivity.java — 首页、权限检查、手动拼接
- app/src/main/java/com/scrollshot/CaptureService.java — 前台服务、录屏、悬浮窗、截取循环、保存
- app/src/main/java/com/scrollshot/ScrollAccessibilityService.java — 多级容器滚动（无障碍手势）
- app/src/main/java/com/scrollshot/Stitcher.java — 重叠匹配拼接算法
- app/src/main/java/com/scrollshot/ResultActivity.java — 长图预览与分享
- app/src/main/java/com/scrollshot/CaptureSession.java — 服务间共享状态
