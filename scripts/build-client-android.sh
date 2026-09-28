#!/usr/bin/env bash
# M3：用 NDK 交叉编译 OpenTTD 客户端（SDL2 版本）。
# 注意：这一步先编译 openttd_lib（全部游戏逻辑），验证 Android 下能否过编译；
#       真正的 APK 入口 libmain.so 在 M4 里通过补丁把 add_executable 改成 add_library 实现。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

: "${NDK_TOOLCHAIN_FILE:?没有 NDK}"
: "${OTTD_SRC:?}"

SDL2_PREFIX="${SDL2_PREFIX:-$OTTD_DEPS/sdl2-$NDK_ABI}"
FT_PREFIX="${FT_PREFIX:-$OTTD_DEPS/freetype-$NDK_ABI}"
HOST_TOOLS="$OTTD_BUILD/host-tools"
CLIENT_DIR="${CLIENT_DIR:-$OTTD_BUILD/client-$NDK_ABI}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"
TARGET="${TARGET:-openttd_lib}"

if [ ! -d "$SDL2_PREFIX" ]; then
    echo "找不到 SDL2：$SDL2_PREFIX，请先跑 scripts/build-sdl2-android.sh" >&2
    exit 1
fi

if [ ! -f "$HOST_TOOLS/CMakeCache.txt" ]; then
    echo "== 构建宿主工具 =="
    cmake -S "$OTTD_SRC" -B "$HOST_TOOLS" -G Ninja -DOPTION_TOOLS_ONLY=ON -DCMAKE_BUILD_TYPE=Release
    cmake --build "$HOST_TOOLS" -j "$JOBS"
fi

echo "== 配置 OpenTTD 客户端 for Android ($NDK_ABI) =="
cmake -S "$OTTD_SRC" -B "$CLIENT_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$NDK_ABI" \
    -DANDROID_PLATFORM="$NDK_PLATFORM" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$SDL2_PREFIX;$FT_PREFIX" \
    -DSDL2_DIR="$SDL2_PREFIX/lib/cmake/SDL2" \
    -DFREETYPE_LIBRARY="$FT_PREFIX/lib/libfreetype.a" \
    -DFREETYPE_INCLUDE_DIRS="$FT_PREFIX/include/freetype2" \
    -DOPTION_DEDICATED=OFF \
    -DHOST_BINARY_DIR="$HOST_TOOLS" \
    -DPERSONAL_DIR=".openttd" \
    -DGLOBAL_DIR="(not set)" \
    -DSHARED_DIR="(not set)"

echo "== 编译目标：$TARGET =="
cmake --build "$CLIENT_DIR" --target "$TARGET" -j "$JOBS"

echo "== 完成。产物在：$CLIENT_DIR =="
