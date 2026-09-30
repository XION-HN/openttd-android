# OpenTTD 15.3 Android 移植

把 OpenTTD 15.3 移植到 Android（arm64-v8a），基于 **NDK + SDL2 + FreeType + Gradle**。
官方 OpenTTD 源码本身不含 Android 构建系统，本仓库提供：

- `patches/openttd-android-15.3.patch`：对 OpenTTD 15.3 源码的移植补丁
  - bionic 缺头文件修复（`src/ini.cpp`）
  - Android 下把 `openttd` 目标改成 `libmain.so`（导出 `SDL_main`，供 SDLActivity 调用）
  - native 崩溃捕获（写 sdcard 日志）
- `android/`：SDLActivity 包装的 Gradle 工程
  - 首次启动解压 assets 到私有目录
  - 自动配置中文界面 + 内置 CJK 字体
  - Java / native 崩溃日志写到 `Download/OpenTTD/`
- `font/OpenTTD-CJK.otf`：从 Noto Sans CJK SC 子集化的中文字体（约 1.7MB，覆盖 OpenTTD 中文翻译全部字符）
- `scripts/ci-build.sh`：CI/本地一键构建（SDL2 → FreeType → OpenTTD → 铺资源）
- `.github/workflows/android.yml`：GitHub Actions 自动构建；打 `v*` tag 会自动发布 Release

## 构建

### GitHub Actions
- 手动触发 `Android Build`，或 push 一个 `v*` tag；
- 产物：`OpenTTD-15.3-android-arm64.apk`（debug 签名）。

### 本地（Linux x86_64）
```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/27.2.12479018
bash scripts/ci-build.sh
cd android && ./gradlew assembleDebug
```

## 当前状态
- ✅ 能进主菜单
- ✅ OpenGFX 8.0 图形集内置
- ✅ 中文界面（FreeType + CJK 字体，12929 字符：GB2312 全字 + 中文符号/全角/箭头等）
- ✅ 内置 Chinese True Town Names（zbx1425）并设为新游戏默认地名
- ✅ 存档/配置在 /sdcard/OpenTTD（需「所有文件访问权限」）
- ✅ 联网内容下载：已加 INTERNET 权限；无 libcurl 时自动走 OpenTTD 的 TCP fallback 下载通道
- ⏳ 触摸操作 / 缩放手势 仍在打磨

## 许可
OpenTTD 本体为 GPLv2。`font/OpenTTD-CJK.otf` 来自 Noto Sans CJK（SIL OFL 1.1）。
