#!/usr/bin/env bash
# M2：为 Android 交叉编译 SDL2，安装到 deps/sdl2-<abi>，供 OpenTTD 的 find_package(SDL2) 使用。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

: "${NDK_TOOLCHAIN_FILE:?没有 NDK}"

SDL2_REPO="${SDL2_REPO:-https://github.com/libsdl-org/SDL.git}"
SDL2_REF="${SDL2_REF:-release-2.32.10}"        # 若不存在可改 release-2.30.x / SDL2
SDL2_SRC="${OTTD_SDL2_SRC:-$OTTD_DEPS/SDL2}"
SDL2_BUILD="${SDL2_BUILD:-$OTTD_BUILD/sdl2-$NDK_ABI}"
SDL2_PREFIX="${SDL2_PREFIX:-$OTTD_DEPS/sdl2-$NDK_ABI}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"

mkdir -p "$OTTD_DEPS"

if [ ! -d "$SDL2_SRC/.git" ]; then
    echo "== 克隆 SDL2 ($SDL2_REF) =="
    git clone --depth 1 --branch "$SDL2_REF" "$SDL2_REPO" "$SDL2_SRC"
else
    echo "== 复用已有 SDL2 源码：$SDL2_SRC =="
fi

echo "== 配置 SDL2 for Android ($NDK_ABI, $NDK_PLATFORM) =="
cmake -S "$SDL2_SRC" -B "$SDL2_BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$NDK_ABI" \
    -DANDROID_PLATFORM="$NDK_PLATFORM" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$SDL2_PREFIX" \
    -DSDL_SHARED=ON \
    -DSDL_STATIC=ON \
    -DSDL_TEST=OFF \
    -DSDL_TESTS=OFF

echo "== 编译并安装 =="
cmake --build "$SDL2_BUILD" -j "$JOBS"
cmake --install "$SDL2_BUILD"

echo "== SDL2 安装完成：$SDL2_PREFIX =="
ls "$SDL2_PREFIX/lib" 2>/dev/null || true
