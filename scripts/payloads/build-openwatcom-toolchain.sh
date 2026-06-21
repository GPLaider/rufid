#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

need git
need make
need gcc

src_dir="$PAYLOAD_ROOT/sources/open-watcom-v2"
report_dir="$PAYLOAD_ROOT/out/source-provenance/toolchains/openwatcom"
env_file="$report_dir/env.sh"
tool_root="$report_dir/root"
wrapper_dir="$tool_root/binl"

clone_pinned "$OPENWATCOM_REPO" "$OPENWATCOM_REF" "$src_dir"

has_openwatcom_component_build() {
  [[ -x "$src_dir/build/binbuild/bwcc" ]] &&
    [[ -x "$src_dir/build/binbuild/bwcl" ]] &&
    [[ -x "$src_dir/build/binbuild/bwlink" ]] &&
    [[ -f "$src_dir/bld/hdr/dos/h/stdlib.h" || -f "$src_dir/rel/h/stdlib.h" ]] &&
    [[ -f "$src_dir/bld/clib/_dos/library/msdos.286/mm/clibm.lib" ||
      -f "$src_dir/bld/clib/library/msdos.086/mm/clibm.lib" ||
      -f "$src_dir/rel/lib286/dos/clibm.lib" ]] &&
    [[ -f "$src_dir/bld/clib/_dos/library/msdos.286/ms/clibs.lib" ||
      -f "$src_dir/bld/clib/library/msdos.086/ms/clibs.lib" ||
      -f "$src_dir/rel/lib286/dos/clibs.lib" ]]
}

if ! has_openwatcom_component_build; then
  (
    cd "$src_dir"
    export OWROOT="$src_dir"
    export OWTOOLS="${RUFID_OPENWATCOM_BOOTSTRAP_TOOLS:-GCC}"
    export OWDOCBUILD=0
    export OWDISTRBUILD=0
    export OWOBJDIR="${RUFID_OPENWATCOM_OBJDIR:-binbuild}"
    # build.sh only loads cmnvars.sh when OWROOT is unset. Rufid sets OWROOT
    # explicitly to keep the source tree pinned, so load the version/toolchain
    # variables here before invoking the OpenWatcom bootstrap.
    # shellcheck disable=SC1091
    . ./cmnvars.sh
    build_rc=0
    ./build.sh "${RUFID_OPENWATCOM_BUILD_TARGET:-build}" || build_rc=$?
    if (( build_rc != 0 )) && ! has_openwatcom_component_build; then
      if [[ -f "$src_dir/build/$OWOBJDIR/bootx.log" ]]; then
        tail -200 "$src_dir/build/$OWOBJDIR/bootx.log" >&2
      fi
      if [[ -f "$src_dir/build/$OWOBJDIR/boot.log" ]]; then
        tail -200 "$src_dir/build/$OWOBJDIR/boot.log" >&2
      fi
      exit "$build_rc"
    fi
  )
fi

bin_dir=""
for candidate in "$src_dir/rel/binl64" "$src_dir/rel/binl" "$src_dir/rel/bin" "$src_dir/build/binbuild"; do
  if [[ -x "$candidate/wmake" ]]; then
    bin_dir="$candidate"
    if [[ -x "$candidate/wcc" && -x "$candidate/wcl" && -x "$candidate/wlink" ]]; then
      break
    fi
  fi
done

if [[ -x "$src_dir/build/binbuild/bwcc" ]]; then
  rm -rf "$tool_root"
  mkdir -p "$wrapper_dir" "$tool_root/lib286/dos" "$tool_root/lib"
  init_file="$wrapper_dir/bwlink-rufid.lnk"
  awk '
    /^system begin dos$/ { print "system begin com"; in_dos=1; next }
    in_dos && /^end$/ { print; exit }
    in_dos {
      sub(/^    format dos \\^$/, "    format dos com")
      print
    }
  ' "$src_dir/build/bwlink.lnk" > "$init_file"
  cat "$src_dir/build/bwlink.lnk" >> "$init_file"
  if [[ -d "$src_dir/rel/h" ]]; then
    ln -sf "$src_dir/rel/h" "$tool_root/h"
  else
    ln -sf "$src_dir/bld/hdr/dos/h" "$tool_root/h"
  fi
  for candidate in \
    "$src_dir/rel/lib286/dos/clibm.lib" \
    "$src_dir/bld/clib/_dos/library/msdos.286/mm/clibm.lib" \
    "$src_dir/bld/clib/library/msdos.086/mm/clibm.lib"; do
    if [[ -f "$candidate" ]]; then
      ln -sf "$candidate" "$tool_root/lib286/dos/clibm.lib"
      break
    fi
  done
  for candidate in \
    "$src_dir/rel/lib286/dos/clibs.lib" \
    "$src_dir/bld/clib/_dos/library/msdos.286/ms/clibs.lib" \
    "$src_dir/bld/clib/library/msdos.086/ms/clibs.lib"; do
    if [[ -f "$candidate" ]]; then
      ln -sf "$candidate" "$tool_root/lib286/dos/clibs.lib"
      break
    fi
  done
  ln -sf "$src_dir/build/binbuild/wmake" "$wrapper_dir/wmake"
  ln -sf "$src_dir/build/binbuild/bwcc" "$wrapper_dir/wcc"
  ln -sf "$src_dir/build/binbuild/bwcl" "$wrapper_dir/wcl"
  ln -sf "$src_dir/build/binbuild/bwasm" "$wrapper_dir/wasm"
  ln -sf "$src_dir/build/binbuild/bwlib" "$wrapper_dir/wlib"
  ln -sf "$src_dir/build/binbuild/bwrc" "$wrapper_dir/wrc"
  ln -sf "$src_dir/build/binbuild/bowcc" "$wrapper_dir/owcc"
  ln -sf "$src_dir/build/binbuild/bwcc" "$wrapper_dir/bwcc"
  ln -sf "$src_dir/build/binbuild/bwcl" "$wrapper_dir/bwcl"
  ln -sf "$src_dir/build/binbuild/bwasm" "$wrapper_dir/bwasm"
  ln -sf "$src_dir/build/binbuild/bwlib" "$wrapper_dir/bwlib"
  ln -sf "$src_dir/build/binbuild/bwrc" "$wrapper_dir/bwrc"
  ln -sf "$src_dir/build/binbuild/bowcc" "$wrapper_dir/bowcc"
  rm -f "$wrapper_dir/wlink" "$wrapper_dir/bwlink"
  cat > "$wrapper_dir/wlink" <<EOF
#!/usr/bin/env bash
exec "$src_dir/build/binbuild/bwlink" "@$init_file" "\$@"
EOF
  chmod +x "$wrapper_dir/wlink"
  cp "$wrapper_dir/wlink" "$wrapper_dir/bwlink"
  bin_dir="$wrapper_dir"
fi

mkdir -p "$report_dir"
cat > "$report_dir/OPENWATCOM_SOURCE_BUILD.txt" <<EOF
OpenWatcom source build
Repository: $OPENWATCOM_REPO
Ref: $OPENWATCOM_REF
Tag: $OPENWATCOM_TAG
Source directory: $src_dir
Detected binary directory: ${bin_dir:-none}
Required FreeDOS tools: wmake, wcc, wcl, wlink
EOF

if [[ -z "$bin_dir" || ! -x "$bin_dir/wmake" || ! -x "$bin_dir/wcc" || ! -x "$bin_dir/wcl" || ! -x "$bin_dir/wlink" ]]; then
  echo "OpenWatcom source build did not produce the required FreeDOS toolchain." >&2
  echo "See $report_dir/OPENWATCOM_SOURCE_BUILD.txt" >&2
  exit 1
fi

cat > "$env_file" <<EOF
export RUFID_OPENWATCOM_HOME='$tool_root'
export WATCOM='$tool_root'
export OWROOT='$src_dir'
export PATH='$bin_dir':\$PATH
EOF

echo "Built OpenWatcom toolchain from source: $bin_dir"
