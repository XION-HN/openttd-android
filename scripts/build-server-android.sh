#!/usr/bin/env bash
# M1：用 NDK 交叉编译 OpenTTD 的 dedicated server（不依赖 SDL2），
# 目的是先验证「OpenTTD 15.3 源码 + NDK」能过编译，把问题暴露出来。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

: "${NDK_TOOLCHAIN_FILE:?没有 NDK，先解决 aarch64 宿主的 NDK 问题}"
: "${OTTD_SRC:?}"

HOST_TOOLS="$OTTD_BUILD/host-tools"
SERVER_DIR="$OTTD_BUILD/server-$NDK_ABI"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"

echo "== 0. 清理/准备 =="
mkdir -p "$OTTD_BUILD"

# OpenTTD 的交叉编译需要先用宿主编译器生成 strgen / settingsgen 等工具
echo "== 1. 构建宿主工具（x86_64/arm64 本机编译）=="
if [ ! -f "$HOST_TOOLS/CMakeCache.txt" ]; then
    cmake -S "$OTTD_SRC" -B "$HOST_TOOLS" -G Ninja \
        -DOPTION_TOOLS_ONLY=ON \
        -DCMAKE_BUILD_TYPE=Release
fi
cmake --build "$HOST_TOOLS" -j "$JOBS"

echo "== 2. 配置 Android dedicated server =="
cmake -S "$OTTD_SRC" -B "$SERVER_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$NDK_ABI" \
    -DANDROID_PLATFORM="$NDK_PLATFORM" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DOPTION_DEDICATED=ON \
    -DOPTION_USE_ASSERTS=OFF \
    -DHOST_BINARY_DIR="$HOST_TOOLS" \
    -DPERSONAL_DIR=".openttd" \
    -DGLOBAL_DIR="(not set)" \
    -DSHARED_DIR="(not set)"

echo "== 3. 编译 =="
cmake --build "$SERVER_DIR" -j "$JOBS"

echo "== 完成，产物： =="
find "$SERVER_DIR" -maxdepth 3 -type f \( -name 'openttd' -o -name '*.so' \) -print
