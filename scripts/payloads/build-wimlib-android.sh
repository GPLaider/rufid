#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

need git
need make

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME must point to an installed Android NDK." >&2
  exit 1
fi

src="$PAYLOAD_ROOT/sources/wimlib"
wrapper_src="$PROJECT_ROOT/app/src/main/cpp/rufid_wim_jni.c"
if [[ ! -d "$src/.git" ]]; then
  "$SCRIPT_DIR/fetch-sources.sh"
fi
if [[ ! -f "$wrapper_src" ]]; then
  echo "Missing Rufid wimlib JNI wrapper: $wrapper_src" >&2
  exit 1
fi

api="${ANDROID_API:-24}"
toolchain=
clang_suffix=
tool_suffix=
host_tags=()
if [[ -n "${ANDROID_NDK_HOST_TAG:-}" ]]; then
  host_tags+=("$ANDROID_NDK_HOST_TAG")
fi
host_tags+=("linux-x86_64" "windows-x86_64" "darwin-x86_64" "darwin-aarch64")
for host_tag in "${host_tags[@]}"; do
  candidate="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
  if [[ -d "$candidate" ]]; then
    toolchain="$candidate"
    if [[ "$host_tag" == windows-* ]]; then
      clang_suffix=".cmd"
      tool_suffix=".exe"
    fi
    break
  fi
done
if [[ -z "$toolchain" ]]; then
  echo "Unsupported NDK host path under: $ANDROID_NDK_HOME/toolchains/llvm/prebuilt" >&2
  exit 1
fi

ndk_tool() {
  local name="$1"
  local suffix="${2:-$tool_suffix}"
  local path="$toolchain/$name$suffix"
  if [[ -x "$path" || -f "$path" ]]; then
    printf '%s\n' "$path"
    return 0
  fi
  echo "Missing NDK tool: $path" >&2
  exit 1
}

abis=(
  "arm64-v8a:aarch64-linux-android:aarch64-linux-android"
  "armeabi-v7a:arm-linux-androideabi:armv7a-linux-androideabi"
  "x86:i686-linux-android:i686-linux-android"
  "x86_64:x86_64-linux-android:x86_64-linux-android"
)

git -C "$src" checkout --detach "$WIMLIB_REF"
if [[ ! -x "$src/configure" ]]; then
  (cd "$src" && ./bootstrap)
fi

if ! grep -q '__ANDROID__' "$src/include/wimlib/glob.h"; then
  glob_header="$src/include/wimlib/glob.h"
  tmp_glob_header="$glob_header.tmp"
  awk '
    BEGIN {
      inserted = 0
    }
    /^#ifndef _WIN32$/ && !inserted {
      print "#ifdef __ANDROID__"
      print "#  include <stddef.h>"
      print ""
      print "typedef struct {"
      print "\tsize_t gl_pathc;"
      print "\tchar **gl_pathv;"
      print "\tsize_t gl_offs;"
      print "} glob_t;"
      print ""
      print "#  define GLOB_ERR\t0x1"
      print "#  define GLOB_NOSORT\t0x2"
      print ""
      print "#  define GLOB_NOSPACE\t1"
      print "#  define GLOB_ABORTED\t2"
      print "#  define GLOB_NOMATCH\t3"
      print ""
      print "static inline int"
      print "glob(const char *pattern, int flags,"
      print "     int (*errfunc)(const char *epath, int eerrno),"
      print "     glob_t *pglob)"
      print "{"
      print "\t(void)pattern; (void)flags; (void)errfunc; (void)pglob;"
      print "\treturn GLOB_NOMATCH;"
      print "}"
      print ""
      print "static inline void"
      print "globfree(glob_t *pglob) { (void)pglob; }"
      print ""
      print "#elif !defined(_WIN32)"
      inserted = 1
      next
    }
    { print }
  ' "$glob_header" > "$tmp_glob_header"
  mv "$tmp_glob_header" "$glob_header"
fi

unix_apply="$src/src/unix_apply.c"
if ! grep -q 'RUFID_ANDROID_SKIP_FUTIMES' "$unix_apply"; then
  tmp_unix_apply="$unix_apply.tmp"
  awk '
    /struct timeval times\[2\];/ && !inserted {
      print "#ifndef __ANDROID__"
      print "\t\t/* RUFID_ANDROID_SKIP_FUTIMES */"
      inserted = 1
    }
    inserted && !closed && /return WIMLIB_ERR_SET_TIMESTAMPS;/ {
      print
      print "#else"
      print "\t\treturn WIMLIB_ERR_SET_TIMESTAMPS;"
      print "#endif"
      closed = 1
      next
    }
    { print }
  ' "$unix_apply" > "$tmp_unix_apply"
  mv "$tmp_unix_apply" "$unix_apply"
fi

for entry in "${abis[@]}"; do
  IFS=: read -r abi host clang_prefix <<<"$entry"
  build_dir="$PAYLOAD_ROOT/build/wimlib-$abi"
  out_dir="$PAYLOAD_ROOT/out/jniLibs/$abi"
  cc="$(ndk_tool "${clang_prefix}${api}-clang" "$clang_suffix")"
  ar="$(ndk_tool "llvm-ar")"
  ranlib="$(ndk_tool "llvm-ranlib")"
  strip="$(ndk_tool "llvm-strip")"
  rm -rf "$build_dir"
  mkdir -p "$build_dir" "$out_dir"
  (
    cd "$build_dir"
    CC="$cc" \
    AR="$ar" \
    RANLIB="$ranlib" \
    STRIP="$strip" \
      "$src/configure" \
        --host="$host" \
        --enable-shared \
        --disable-static \
        --disable-dependency-tracking \
        --without-fuse \
        --without-ntfs-3g \
        --without-libcrypto \
        --without-xml
    make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
  )
  lib="$(find "$build_dir" -type f -name 'libwim.so*' | head -n 1)"
  if [[ -z "$lib" ]]; then
    echo "Could not find built libwim.so for $abi" >&2
    exit 1
  fi
  cp "$lib" "$out_dir/libwimutils.so"
  "$strip" "$out_dir/libwimutils.so" || true
  write_sha256_sidecar "$out_dir/libwimutils.so"

  "$cc" \
    -shared \
    -fPIC \
    -O2 \
    -I"$src/include" \
    "$wrapper_src" \
    -ldl \
    -Wl,-soname,librufidwim.so \
    -o "$out_dir/librufidwim.so"
  "$strip" "$out_dir/librufidwim.so" || true
  write_sha256_sidecar "$out_dir/librufidwim.so"
done

cat > "$PAYLOAD_ROOT/out/jniLibs/wimlib.source.txt" <<EOF
Source: $WIMLIB_REPO
Ref: $WIMLIB_REF
Android API: $api
Output: jniLibs/<abi>/libwimutils.so and jniLibs/<abi>/librufidwim.so
Configure excludes ntfs-3g, fuse, libcrypto, and XML support for a smaller Android library.
EOF

echo "Built Android wimlib shared libraries under $PAYLOAD_ROOT/out/jniLibs"
