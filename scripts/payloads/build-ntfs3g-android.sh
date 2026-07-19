#!/usr/bin/env bash
# Cross-build NTFS-3G mkntfs + Rufid stream tool for Android (4 ABIs).
# No FUSE. No prebuilt third-party runtime. Static libntfs-3g into stream PIE.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

# Host tool preflight (never "need tar"/"need ta" - historical CRLF corruption risk).
need git
need make
need autoreconf
need autoconf
need automake
need libtool

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME must point to an Android NDK (F-Droid metadata ndk field, e.g. r29)." >&2
  exit 1
fi
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ANDROID_NDK_HOME is not a directory: $ANDROID_NDK_HOME" >&2
  exit 1
fi
export ANDROID_NDK_HOME

src="$PAYLOAD_ROOT/sources/ntfs-3g"
stream_src="$PROJECT_ROOT/app/src/main/cpp/rufid_ntfs_stream.c"
if [[ ! -d "$src/.git" ]]; then
  "$SCRIPT_DIR/fetch-sources.sh"
fi
if [[ ! -f "$stream_src" ]]; then
  echo "Missing stream tool source: $stream_src" >&2
  exit 1
fi

git -C "$src" fetch --tags --force origin 2>/dev/null || true
git -C "$src" checkout --detach "$NTFS3G_REF"
actual="$(git -C "$src" rev-parse HEAD)"
if [[ "$actual" != "$NTFS3G_REF" ]]; then
  echo "NTFS-3G HEAD mismatch: expected $NTFS3G_REF got $actual" >&2
  exit 1
fi

# Fresh ntfs-3g git has no generated configure. autoreconf needs AM_PATH_LIBGCRYPT
# even when configure later uses --disable-crypto (macro is expanded at m4 time).
# Debian/Ubuntu: libgcrypt20-dev (+ libgpg-error-dev). F-Droid sudo installs these.
ensure_ntfs3g_autotools() {
  local m4_found=0
  local search_dirs=()
  if [[ -n "${ACLOCAL_PATH:-}" ]]; then
    IFS=':' read -r -a search_dirs <<<"$ACLOCAL_PATH"
  fi
  search_dirs+=(
    /usr/share/aclocal
    /usr/local/share/aclocal
    /opt/homebrew/share/aclocal
  )
  for dir in "${search_dirs[@]}"; do
    if [[ -f "$dir/libgcrypt.m4" ]]; then
      m4_found=1
      break
    fi
  done
  if [[ "$m4_found" -ne 1 ]]; then
    cat >&2 <<'EOF'
NTFS-3G autogen requires AM_PATH_LIBGCRYPT from libgcrypt.m4.
Install host packages (Debian/Ubuntu/F-Droid sudo):
  libgcrypt20-dev libgpg-error-dev
(plus autoconf automake libtool already required for autoreconf)
Crypto stays disabled at configure (--disable-crypto); the m4 file is only
needed to generate ./configure from git. Do not rely on a pre-generated configure.
EOF
    exit 1
  fi
}

if [[ ! -x "$src/configure" ]]; then
  ensure_ntfs3g_autotools
  (cd "$src" && bash ./autogen.sh)
fi
if [[ ! -x "$src/configure" ]]; then
  echo "NTFS-3G configure missing after autogen.sh" >&2
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

# Allow only Bionic system libs on packaged executables.
enforce_elf_contract() {
  local f="$1"
  local label="$2"
  local type_line needed flags_line
  type_line="$("$readelf_bin" -h "$f" | awk -F: '/Type:/ {gsub(/^[ \t]+/,"",$2); print $2; exit}')"
  case "$type_line" in
    *DYN*) ;;
    *)
      echo "$label is not PIE (Type must be DYN): $f type=[$type_line]" >&2
      exit 1
      ;;
  esac
  # Prefer explicit DF_1_PIE when present; DYN alone is accepted as Android NDK PIE.
  flags_line="$("$readelf_bin" -d "$f" 2>/dev/null | awk '/FLAGS_1|Flags:/ {print}' || true)"
  if [[ -n "$flags_line" ]] && ! grep -Eqi 'PIE|POSITION_INDEPENDENT' <<<"$flags_line"; then
    # Some NDK outputs only mark DYN without FLAGS_1; do not fail solely on missing FLAGS_1.
    :
  fi
  needed="$("$readelf_bin" -d "$f" 2>/dev/null | awk '/NEEDED/ {print $NF}' | tr -d '[]' || true)"
  while IFS= read -r lib; do
    [[ -z "$lib" ]] && continue
    case "$lib" in
      libc.so*|libdl.so*|libm.so*|ld-android.so*|liblog.so*) ;;
      *)
        echo "Unexpected NEEDED on $label: $lib ($f)" >&2
        exit 1
        ;;
    esac
  done <<<"$needed"
}

abis=(
  "arm64-v8a:aarch64-linux-android:aarch64-linux-android"
  "armeabi-v7a:arm-linux-androideabi:armv7a-linux-androideabi"
  "x86:i686-linux-android:i686-linux-android"
  "x86_64:x86_64-linux-android:x86_64-linux-android"
)

strip_bin="$(ndk_tool llvm-strip)"
readelf_bin="$(ndk_tool llvm-readelf)"

for entry in "${abis[@]}"; do
  IFS=':' read -r abi triple clang_triple <<<"$entry"
  build_dir="$PAYLOAD_ROOT/build/ntfs3g-$abi"
  out_dir="$PAYLOAD_ROOT/out/jniLibs/$abi"
  mkdir -p "$out_dir" "$build_dir"

  cc="$(ndk_tool "${clang_triple}${api}-clang" "$clang_suffix")"
  cxx="$(ndk_tool "${clang_triple}${api}-clang++" "$clang_suffix")"
  ar="$(ndk_tool llvm-ar)"
  ranlib="$(ndk_tool llvm-ranlib)"

  echo "=== Building NTFS-3G for $abi (API $api) ==="
  rm -rf "$build_dir"
  mkdir -p "$build_dir"
  pushd "$build_dir" >/dev/null

  export CC="$cc"
  export CXX="$cxx"
  export AR="$ar"
  export RANLIB="$ranlib"
  # API level comes from the NDK clang triple (*-android${api}-clang), not -D__ANDROID_API__.
  # Manual -D__ANDROID_API__ redefines the NDK builtin and is forbidden.
  export CFLAGS="-O2 -fPIE -fPIC -DANDROID"
  export LDFLAGS="-pie -fPIE -Wl,--gc-sections"
  export LIBS="-lc -ldl"
  # No FUSE driver; keep ntfsprogs (mkntfs) + libntfs-3g.
  # Do not pass unknown configure switches (e.g. legacy gnomevfs flags).
  "$src/configure" \
    --host="$triple" \
    --prefix="$build_dir/install" \
    --disable-shared \
    --enable-static \
    --disable-ntfs-3g \
    --disable-crypto \
    --disable-quarantined \
    --without-uuid \
    --without-hd \
    ac_cv_func_utimensat=no \
    ac_cv_func_futimens=no \
    2>&1 | tee "$build_dir/configure.log"

  make -j"$(nproc 2>/dev/null || echo 2)" -C libntfs-3g 2>&1 | tee "$build_dir/make-lib.log"
  make -j"$(nproc 2>/dev/null || echo 2)" -C ntfsprogs mkntfs 2>&1 | tee "$build_dir/make-mkntfs.log"

  mkntfs_bin=
  for cand in ntfsprogs/mkntfs ntfsprogs/.libs/mkntfs; do
    if [[ -f "$cand" ]]; then
      mkntfs_bin="$cand"
      break
    fi
  done
  if [[ -z "$mkntfs_bin" ]]; then
    echo "mkntfs binary not found for $abi" >&2
    exit 1
  fi

  libntfs=
  for cand in libntfs-3g/.libs/libntfs-3g.a libntfs-3g/libntfs-3g.a; do
    if [[ -f "$cand" ]]; then
      libntfs="$cand"
      break
    fi
  done
  if [[ -z "$libntfs" ]]; then
    echo "libntfs-3g.a not found for $abi" >&2
    exit 1
  fi

  stream_out="$out_dir/librufidntfsstream.so"
  read -r -a cflags <<<"$CFLAGS"
  read -r -a ldflags <<<"$LDFLAGS"
  "$cc" "${cflags[@]}" "${ldflags[@]}" \
    -I"$src/include/ntfs-3g" \
    -I"$build_dir/include" \
    -I"$src/include" \
    -o "$stream_out" \
    "$stream_src" \
    "$libntfs" \
    -lc -ldl
  "$strip_bin" "$stream_out"

  mk_out="$out_dir/librufidmkntfs.so"
  cp -f "$mkntfs_bin" "$mk_out"
  "$strip_bin" "$mk_out"
  chmod 755 "$mk_out" "$stream_out"

  write_sha256_sidecar "$mk_out"
  write_sha256_sidecar "$stream_out"

  {
    echo "ABI=$abi"
    echo "mkntfs=$mk_out"
    echo "stream=$stream_out"
    "$readelf_bin" -d "$mk_out" 2>/dev/null | grep -E 'NEEDED|FLAGS_1|Flags' || true
    "$readelf_bin" -d "$stream_out" 2>/dev/null | grep -E 'NEEDED|FLAGS_1|Flags' || true
    "$readelf_bin" -h "$mk_out" | head -20
    "$readelf_bin" -h "$stream_out" | head -20
  } | tee "$out_dir/ntfs3g-elf-$abi.txt"

  enforce_elf_contract "$mk_out" "librufidmkntfs.so"
  enforce_elf_contract "$stream_out" "librufidntfsstream.so"

  popd >/dev/null
done

cat > "$PAYLOAD_ROOT/out/jniLibs/ntfs3g.source.txt" <<EOF
Upstream: $NTFS3G_REPO
Tag: $NTFS3G_TAG
Commit: $NTFS3G_REF
Verified HEAD: $(git -C "$src" rev-parse HEAD)
NDK: $ANDROID_NDK_HOME
API: $api
Build script: scripts/payloads/build-ntfs3g-android.sh
Stream source: app/src/main/cpp/rufid_ntfs_stream.c
Outputs: jniLibs/<abi>/librufidmkntfs.so , jniLibs/<abi>/librufidntfsstream.so
Notes: No FUSE. No prebuilt third-party runtime. mkntfs + stream linked to static libntfs-3g.
Host autogen: libgcrypt20-dev + libgpg-error-dev for AM_PATH_LIBGCRYPT.
EOF

echo "Built NTFS-3G Android tools under $PAYLOAD_ROOT/out/jniLibs"
