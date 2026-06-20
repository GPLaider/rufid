# F-Droid Source Build Policy

Rufid's F-Droid rule is stricter than the local developer payload rule:
payloads should be built from source in the F-Droid build when that is
technically feasible.

## Rules

- Do not use payloads extracted from opaque APKs.
- Do not commit generated payload binaries.
- Prefer source-built payloads over verified binary redistribution.
- Treat verified official binary distributions as temporary audit inputs, not as
  the final F-Droid answer, when a practical source build path exists.
- If a payload needs a toolchain, pin and build that toolchain from source or
  use packages available to the F-Droid buildserver.
- Keep build scripts honest: an unfinished strict source path must fail with a
  clear report instead of silently packaging a binary fallback.

## FreeDOS

FreeDOS is handled as a strict source-build target for F-Droid review.

The official FreeDOS 1.4 LiteUSB archive remains useful because it carries the
package set and corresponding `SOURCE/` entries. Rufid may use it to extract and
audit package sources. The F-Droid FreeDOS payload is assembled from built
artifacts rather than copied from `FD14LITE.img`.

Strict FreeDOS source build requirements:

- expand the `SOURCES.ZIP` archives for the selected package set
- build or provide a source-built 16-bit DOS toolchain
- build at least the boot kernel, FreeCOM, and required boot/install tools
- assemble a FAT boot image from those source-built artifacts
- record the build manifest and SHA-256 hashes

The current strict path expands the nested package source archives, builds the
FreeDOS kernel, FreeCOM, SYS, and FAT boot sector with a pinned OpenWatcom v2
toolchain, then assembles a minimal FAT16 image from those source-built
artifacts. `FD14LITE.img` is used only to recover the package/source set.
