# OpenTTD 15.3 Android 移植

把 OpenTTD 15.3 移植到 Android（arm64-v8a），基于 **NDK + SDL2 + FreeType + Gradle**。
官方 OpenTTD 源码本身不含 Android 构建系统，本仓库提供：

- `patches/0004-sdl2-mixer-music-driver.patch`：SDL2_mixer 音乐驱动（Ogg/MP3/FLAC）
  - 就是旧版 `openttd_ogg_mod/patches/src/music/sdl2_mixer_m.cpp` 的原码，驱动名 `sdl2mixer`
  - 需要交叉编译 SDL2_mixer（STB Vorbis 内置解码），CI 脚本已包含
- `patches/0003-music-driver-android.patch`：Android MediaPlayer 音乐驱动（备用）
  - 新增 `MusicDriver_Android`（`src/music/music_driver_android.cpp/.h`），把音乐文件交给 Java 的 `org.openttd.sdl.AndroidMusicPlayer` 播放
  - 播放的是音乐集里的真实音频文件（OGG/MP3/WAV/FLAC）；MIDI 无法用 MediaPlayer 播放
  - 驱动名 `android`，`MainActivity` 会把 `misc.musicdriver` 设成它
  - 注意：Android 的 SDL 音频后端只允许一个默认输出设备，SDL2_mixer 会和音效驱动抢设备，
    因此默认用 MediaPlayer；两个驱动在打不开设备时都是非致命降级（静音），不会闪退
- `patches/0002-content-search-hidden-newgrf.patch`：内容列表补全补丁
  - BaNaNaS 会按客户端上报的版本号过滤 `compatibility`，被标成旧版本兼容的模组（FIRS 1-4、FISH 1、Iron Horse 1/2、2cc Trainset 等）服务端根本不下发
  - 该补丁额外用 `0.0.1`/`1.2.0` 各请求一轮列表再合并去重，使这些模组能被搜到
  - 同时让「搜寻内容」也匹配模组描述文本
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
- ✅ 自定义 BGM 导入：用 OpenTTD「打开」或分享一个音乐模组 `.tar.gz` 即自动解到 `baseset/`
  - 包内路径需含 `openttd_ogg_mod/baseset/<音乐集名>/...`，导入后重启游戏生效
  - 配合 `AndroidMusicPlayer` 可播放 OGG/MP3 音乐集
- ✅ 内置 OpenMSX 音乐集（BGM）：音乐窗口可直接选曲，不再是「无音乐可用」
- ✅ 联网内容下载：已加 INTERNET 权限；无 libcurl 时自动走 OpenTTD 的 TCP fallback 下载通道
- ✅ 内容搜索补全：旧版模组（FIRS 1-4 等）不再因兼容性标记被 BaNaNaS 隐藏
- ✅ 触控 v1：轻点左键、拖动平移、长按右键、双指缩放、返回键=Esc、顶部左/右键切换
- ⏳ 放置 ✓/✗ 确认按钮（下一版）

## 许可
OpenTTD 本体为 GPLv2。`font/OpenTTD-CJK.otf` 来自 Noto Sans CJK（SIL OFL 1.1）。
