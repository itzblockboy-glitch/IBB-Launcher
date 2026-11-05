#!/usr/bin/env bash
# Downloads the prebuilt third-party native libraries the execution layer needs.
#
# Why download instead of committing: these are ~24 MB per ABI of binaries produced by other
# projects (LWJGL-on-Android, gl4es, Mesa/OSMesa, OpenAL, freetype, JNA, Android's unpack200).
# They are not part of this repository's source and change only when the upstream bridge is
# re-pinned, so they live outside git and are fetched by the build (CI and locally).
#
# Usage: tools/fetch_native_libs.sh <destination-dir>
set -euo pipefail

DEST="${1:-app/src/main/jniLibs}"
PIN="b12ad048157b3aa255d078c235dd4571e1900309"   # PojavLauncher commit the bridge is pinned to
REPO="PojavLauncherTeam/PojavLauncher"
BASE="https://raw.githubusercontent.com/$REPO/$PIN/app_pojavlauncher/src/main/jniLibs"

# LWJGL native back-ends + the libraries they dlopen at runtime.
LIBS=(
  liblwjgl.so liblwjgl_opengl.so liblwjgl_stb.so liblwjgl_tinyfd.so liblwjgl_nanovg.so
  libgl4es_114.so libfreetype.so libjnidispatch.so libopenal.so libunpack200.so
)

mkdir -p "$DEST"
for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$DEST/$abi"
  for lib in "${LIBS[@]}"; do
    target="$DEST/$abi/$lib"
    if [ -s "$target" ]; then continue; fi
    echo "fetch $abi/$lib"
    curl -fsSL --retry 3 -o "$target.part" "$BASE/$abi/$lib"
    mv "$target.part" "$target"
  done
done

# OSMesa (Zink / Vulkan renderer) is much bigger and only needed by that renderer, so it is
# fetched too but kept optional: a build without it simply falls back to gl4es.
for abi in arm64-v8a armeabi-v7a x86_64; do
  target="$DEST/$abi/libOSMesa.so"
  if [ -s "$target" ]; then continue; fi
  echo "fetch $abi/libOSMesa.so (Zink)"
  if ! curl -fsSL --retry 3 -o "$target.part" "$BASE/$abi/libOSMesa.so"; then
    rm -f "$target.part"
    echo "warning: no OSMesa for $abi; Zink will be unavailable" >&2
    continue
  fi
  mv "$target.part" "$target"
done

echo "native libraries ready in $DEST"
