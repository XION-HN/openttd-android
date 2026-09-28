#!/usr/bin/env bash
# 在 Linux x86_64（GitHub Actions）上构建 OpenTTD Android 版：
#   - 交叉编译 SDL2 / FreeType
#   - 打补丁并交叉编译 OpenTTD 为 libmain.so
#   - 把 .so / baseset / lang / 中文字体铺进 android 工程
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$ROOT/work}"
ABI="${ABI:-arm64-v8a}"
API="${API:-24}"
OTTD_VER="${OTTD_VER:-15.3}"
SDL2_REF="${SDL2_REF:-release-2.32.10}"
FT_REF="${FT_REF:-VER-2-13-3}"
OPENGFX_VER="${OPENGFX_VER:-8.0}"
JOBS="${JOBS:-$(nproc)}"

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_SDK_ROOT"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
: "${ANDROID_NDK_HOME:?需要设置 ANDROID_NDK_HOME}"
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
NDK_PREBUILT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
case "$ABI" in
    arm64-v8a)   ANDROID_TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) ANDROID_TRIPLE=arm-linux-androideabi ;;
    x86_64)      ANDROID_TRIPLE=x86_64-linux-android ;;
    *) echo "未知 ABI: $ABI"; exit 1 ;;
esac

mkdir -p "$WORK"

# 交叉编译时必须让 pkg-config 看不到宿主机的库（否则 ICU 会误命中，
# 把 /usr/include + NOTFOUND 库链进 openttd_lib）。
export PKG_CONFIG_LIBDIR="$WORK/empty-pkgconfig"
export PKG_CONFIG_PATH=""
mkdir -p "$PKG_CONFIG_LIBDIR"

echo "==================== 1/5 OpenTTD 源码 + 补丁 ===================="
if [ ! -d "$WORK/OpenTTD/.git" ]; then
    git clone --depth 1 --branch "$OTTD_VER" https://github.com/OpenTTD/OpenTTD.git "$WORK/OpenTTD"
fi
cd "$WORK/OpenTTD"
if git apply --reverse --check "$ROOT/patches/openttd-android-15.3.patch" 2>/dev/null; then
    echo "补丁已应用"
else
    git apply "$ROOT/patches/openttd-android-15.3.patch"
fi

echo "==================== 2/5 宿主工具（strgen 等） ===================="
HOST_TOOLS="$WORK/build/host-tools"
cmake -S "$WORK/OpenTTD" -B "$HOST_TOOLS" -G Ninja -DOPTION_TOOLS_ONLY=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$HOST_TOOLS" -j "$JOBS"

echo "==================== 3/5 SDL2 for Android ===================="
SDL2_SRC="$WORK/SDL2"
SDL2_PREFIX="$WORK/deps/sdl2-$ABI"
if [ ! -d "$SDL2_SRC/.git" ]; then
    git clone --depth 1 --branch "$SDL2_REF" https://github.com/libsdl-org/SDL.git "$SDL2_SRC"
fi
cmake -S "$SDL2_SRC" -B "$WORK/build/sdl2-$ABI" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$SDL2_PREFIX" \
    -DSDL_SHARED=ON -DSDL_STATIC=ON -DSDL_TEST=OFF -DSDL_TESTS=OFF
cmake --build "$WORK/build/sdl2-$ABI" -j "$JOBS"
cmake --install "$WORK/build/sdl2-$ABI"

echo "==================== 4/5 FreeType for Android ===================="
FT_SRC="$WORK/freetype"
FT_PREFIX="$WORK/deps/freetype-$ABI"
if [ ! -d "$FT_SRC/.git" ]; then
    git clone --depth 1 --branch "$FT_REF" https://github.com/freetype/freetype.git "$FT_SRC"
fi
cmake -S "$FT_SRC" -B "$WORK/build/freetype-$ABI" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$FT_PREFIX" \
    -DBUILD_SHARED_LIBS=OFF \
    -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
    -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
cmake --build "$WORK/build/freetype-$ABI" -j "$JOBS"
cmake --install "$WORK/build/freetype-$ABI"

echo "==================== 5/5 OpenTTD 客户端 ===================="
CLIENT_DIR="$WORK/build/client-$ABI"
cmake -S "$WORK/OpenTTD" -B "$CLIENT_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$SDL2_PREFIX;$FT_PREFIX" \
    -DSDL2_DIR="$SDL2_PREFIX/lib/cmake/SDL2" \
    -DFREETYPE_LIBRARY="$FT_PREFIX/lib/libfreetype.a" \
    -DFREETYPE_INCLUDE_DIRS="$FT_PREFIX/include/freetype2" \
    -DOPTION_DEDICATED=OFF -DHOST_BINARY_DIR="$HOST_TOOLS" \
    -DPERSONAL_DIR=".openttd" -DGLOBAL_DIR="(not set)" -DSHARED_DIR="(not set)"
cmake --build "$CLIENT_DIR" --target openttd -j "$JOBS"

echo "==================== 收集产物到 android 工程 ===================="
AND="$ROOT/android/app/src/main"
rm -rf "$AND/jniLibs/$ABI" "$AND/assets/data"
mkdir -p "$AND/jniLibs/$ABI" "$AND/assets/data/baseset" "$AND/assets/data/lang"

cp "$CLIENT_DIR/libmain.so" "$AND/jniLibs/$ABI/libmain.so"
cp "$SDL2_PREFIX/lib/libSDL2.so" "$AND/jniLibs/$ABI/libSDL2.so"
cp "$NDK_PREBUILT/sysroot/usr/lib/$ANDROID_TRIPLE/libc++_shared.so" "$AND/jniLibs/$ABI/libc++_shared.so"
for so in libmain.so libSDL2.so libc++_shared.so; do
    "$NDK_PREBUILT/bin/llvm-strip" --strip-unneeded "$AND/jniLibs/$ABI/$so" 2>/dev/null || true
done

cp -a "$CLIENT_DIR/baseset/." "$AND/assets/data/baseset/"
cp "$ROOT/font/OpenTTD-CJK.otf" "$AND/assets/data/baseset/OpenTTD-CJK.otf"
cp "$CLIENT_DIR/lang/"*.lng "$AND/assets/data/lang/"

# OpenGFX 图形集
if [ ! -f "$WORK/opengfx/opengfx-$OPENGFX_VER/opengfx.obg" ]; then
    mkdir -p "$WORK/opengfx"
    cd "$WORK/opengfx"
    curl -fL -o opengfx.zip "https://cdn.openttd.org/opengfx-releases/$OPENGFX_VER/opengfx-$OPENGFX_VER-all.zip"
    unzip -o -q opengfx.zip
    tar -xf "opengfx-$OPENGFX_VER.tar"
fi
mkdir -p "$AND/assets/data/baseset/OpenGFX"
cp "$WORK/opengfx/opengfx-$OPENGFX_VER/"* "$AND/assets/data/baseset/OpenGFX/"

echo "==================== 完成 ===================="
ls -lh "$AND/jniLibs/$ABI"
du -sh "$AND/assets/data"
