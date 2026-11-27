#!/usr/bin/env bash
# Builds the FFmpeg decoder libraries that the NDI SDK loads at runtime to decode
# NDI|HX (H.264 / H.265 video, AAC audio):
#   app/src/main/jniLibs/<abi>/libavutil-ndi.so    (SONAME libavutil-ndi.so.59)
#   app/src/main/jniLibs/<abi>/libavcodec-ndi.so   (SONAME libavcodec-ndi.so.61)
#
# libndi.so looks those libraries up by their versioned SONAME. An APK can only
# carry files ending in ".so", so the app loads them by file name first and the
# Android linker then matches libndi's dlopen() against the SONAME.
#
# Usage (Linux, macOS or Git Bash on Windows):
#   tools/build-ffmpeg-android.sh <path/to/ffmpeg-7.1.5.tar.xz> [work-dir]
# Environment:
#   ANDROID_NDK   NDK r27 directory (required)
#   ABIS          default "arm64-v8a armeabi-v7a x86 x86_64"
#   JOBS          default 6
#
# FFmpeg is built as LGPL v2.1+ (no --enable-gpl, no --enable-nonfree).
set -euo pipefail

FFMPEG_VERSION=7.1.5
FFMPEG_SHA256=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
API=26

TARBALL=${1:?usage: $0 <ffmpeg-$FFMPEG_VERSION.tar.xz> [work-dir]}
REPO=$(cd "$(dirname "$0")/.." && pwd)
WORK=${2:-$REPO/build/ffmpeg}
NDK=${ANDROID_NDK:?set ANDROID_NDK to the NDK directory}
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86 x86_64"}
JOBS=${JOBS:-6}

echo "$FFMPEG_SHA256  $TARBALL" | sha256sum -c -

case "$(uname -s)" in
    Linux*)  HOST_TAG=linux-x86_64;  EXE= ;;
    Darwin*) HOST_TAG=darwin-x86_64; EXE= ;;
    *)       HOST_TAG=windows-x86_64; EXE=.exe ; NDK=$(cygpath -m "$NDK") ;;
esac
BIN=$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin
MAKE=$(command -v make || echo "$NDK/prebuilt/$HOST_TAG/bin/make$EXE")

mkdir -p "$WORK"

for ABI in $ABIS; do
    EXTRA=()
    case $ABI in
        arm64-v8a)   ARCH=aarch64; TRIPLE=aarch64-linux-android ;;
        armeabi-v7a) ARCH=arm;     TRIPLE=armv7a-linux-androideabi; EXTRA=(--cpu=armv7-a) ;;
        # x86 assembly needs nasm and produces text relocations on 32-bit Android.
        x86)         ARCH=x86;     TRIPLE=i686-linux-android;   EXTRA=(--disable-asm) ;;
        x86_64)      ARCH=x86_64;  TRIPLE=x86_64-linux-android; EXTRA=(--disable-x86asm) ;;
        *) echo "unknown ABI $ABI" >&2; exit 1 ;;
    esac
    CC="$BIN/clang$EXE --target=$TRIPLE$API"

    echo "=== $ABI"
    # One source tree per ABI, built in place: an out-of-tree build writes
    # /c/... paths into the Makefile, which a native Windows make cannot read.
    SRC=$WORK/$ABI/ffmpeg-$FFMPEG_VERSION
    rm -rf "${WORK:?}/$ABI"
    mkdir -p "$WORK/$ABI"
    tar -xf "$TARBALL" -C "$WORK/$ABI"
    (
        cd "$SRC"
        mkdir tmp
        TMPDIR=tmp ./configure \
            --target-os=android --arch="$ARCH" --enable-cross-compile \
            --cc="$CC" --cxx="$BIN/clang++$EXE --target=$TRIPLE$API" \
            --host-cc="$CC" \
            --ar="$BIN/llvm-ar$EXE" --nm="$BIN/llvm-nm$EXE" \
            --ranlib="$BIN/llvm-ranlib$EXE" --strip="$BIN/llvm-strip$EXE" \
            --build-suffix=-ndi \
            --enable-shared --disable-static --enable-pic \
            --disable-autodetect --enable-pthreads \
            --disable-programs --disable-doc --disable-network \
            --disable-avdevice --disable-avformat --disable-avfilter \
            --disable-swscale --disable-swresample --disable-postproc \
            --disable-everything \
            --enable-decoder=h264,hevc,aac,aac_latm \
            --enable-parser=h264,hevc,aac,aac_latm \
            --extra-ldflags="-Wl,-z,max-page-size=16384" \
            "${EXTRA[@]}" > configure.out || { tail -30 ffbuild/config.log; exit 1; }
        grep -E '^License' configure.out

        # Android builds drop the version from the SONAME; libndi needs it.
        sed -i 's/-soname,$(SLIBNAME) /-soname,$(SLIBNAME_WITH_MAJOR) /' ffbuild/config.mak
        grep -q 'soname,$(SLIBNAME_WITH_MAJOR) ' ffbuild/config.mak

        "$MAKE" -j"$JOBS" > make.out
    )

    OUT=$REPO/app/src/main/jniLibs/$ABI
    for LIB in avutil avcodec; do
        cp "$SRC/lib$LIB/lib$LIB-ndi.so" "$OUT/"
        "$BIN/llvm-strip$EXE" --strip-unneeded "$OUT/lib$LIB-ndi.so"
    done
    "$BIN/llvm-readelf$EXE" -d "$OUT/libavcodec-ndi.so" "$OUT/libavutil-ndi.so" | grep -E 'SONAME|NEEDED'
done
echo "FFmpeg $FFMPEG_VERSION decoder libraries written to app/src/main/jniLibs"
