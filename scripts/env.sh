#!/usr/bin/env bash
# 统一环境变量。用法： source scripts/env.sh
# 适配 aarch64 主机（本机是 arm64 手机）与 x86_64 主机。

set -a 2>/dev/null || true

# ---------- 基本路径 ----------
export OTTD_SRC="${OTTD_SRC:-/root/src/openttd}"
export OTTD_ANDROID_HOME="${OTTD_ANDROID_HOME:-/root/src/ottd-android}"
export OTTD_BUILD="${OTTD_BUILD:-$OTTD_ANDROID_HOME/build}"

# ---------- Android SDK ----------
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
[ -d "$ANDROID_SDK_ROOT" ] || export ANDROID_SDK_ROOT=/usr/lib/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"

# ---------- 目标 ABI / API ----------
export NDK_ABI="${NDK_ABI:-arm64-v8a}"
export NDK_API="${NDK_API:-24}"
export NDK_PLATFORM="${NDK_PLATFORM:-android-${NDK_API}}"
# 传给 CMake / Gradle 的架构名
export OTTD_ABI="$NDK_ABI"

# ---------- 找 NDK ----------
find_ndk() {
    local c
    for c in \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "$ANDROID_SDK_ROOT/ndk/"* \
        /root/ndk/* \
        /opt/ndk/* \
        "$HOME"/termux-ndk/* \
        "$HOME"/android-ndk-*/
    do
        [ -n "$c" ] && [ -f "$c/build/cmake/android.toolchain.cmake" ] && { echo "$c"; return 0; }
    done
    return 1
}

if ANDROID_NDK_HOME="$(find_ndk)"; then
    export ANDROID_NDK_HOME
    export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    export NDK_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
else
    echo "[env.sh] 警告：没找到 Android NDK（需要有 build/cmake/android.toolchain.cmake）" >&2
    unset NDK_TOOLCHAIN_FILE
fi

# ---------- 主机架构检查 ----------
HOST_ARCH="$(uname -m 2>/dev/null || echo unknown)"
export OTTD_HOST_ARCH="$HOST_ARCH"
if [ "$HOST_ARCH" = "aarch64" ] || [ "$HOST_ARCH" = "arm64" ]; then
    # 官方 NDK 只带 linux-x86_64 预编译 clang；aarch64 原生 NDK 目录名可能是
    # linux-aarch64（Termux NDK）或 linux-arm64（HomuHomu833 自定义 NDK）。
    if [ -n "$ANDROID_NDK_HOME" ] \
        && [ ! -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-aarch64/bin/clang" ] \
        && [ ! -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-arm64/bin/clang" ]; then
        echo "[env.sh] 注意：主机是 $HOST_ARCH，但该 NDK 没有 aarch64 宿主工具链。" >&2
        echo "         需要 aarch64 原生 NDK（Termux NDK / HomuHomu833 NDK），或用 qemu-x86_64 跑官方 NDK。" >&2
    fi
fi

# ---------- 常用子目录 ----------
export OTTD_DEPS="${OTTD_DEPS:-$OTTD_ANDROID_HOME/deps}"
export OTTD_SDL2_SRC="${OTTD_SDL2_SRC:-$OTTD_DEPS/SDL2}"

set +a 2>/dev/null || true
