#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)

# Give CI a small actionable annotation even when the zipped job log is inaccessible.
if [[ "${GITHUB_ACTIONS:-}" == true && "${VIDEO2_NATIVE_DIAGNOSTICS:-}" != 1 ]]; then
  log=$(mktemp)
  trap 'rm -f "$log"' EXIT
  if VIDEO2_NATIVE_DIAGNOSTICS=1 bash "$0" "$@" 2>&1 | tee "$log"; then exit 0; fi
  detail=$( { grep -aiEA3 'CMake Error|fatal error:|error:|failed to compile|^FAILED:' "$log" || tail -16 "$log"; } | cut -c1-300 | tail -12)
  detail=${detail:0:3400}
  detail=${detail//%/%25}; detail=${detail//$'\r'/%0D}; detail=${detail//$'\n'/%0A}
  printf '::error title=Video2 native build::%s\n' "$detail"
  exit 1
fi

# Usage: build-offline.sh android <work-dir> <jniLibs-dir> <Android SDK> [assets-dir]
#        build-offline.sh host    <work-dir> <lib-dir>     <JDK JNI include dir>
mode=$1
work=$2
out=$3
case "$mode" in
  android)
    sdk=$4
    cmake="$sdk/cmake/3.31.5/bin/cmake"
    ndk="$sdk/ndk/27.3.13750724"
    toolchain="$ndk/build/cmake/android.toolchain.cmake"
    if [[ ! -x "$cmake" || ! -f "$toolchain" ]] && [[ "${GITHUB_ACTIONS:-}" == true ]]; then
      sdkmanager "cmake;3.31.5" "ndk;27.3.13750724"
    fi
    if [[ ! -x "$cmake" || ! -f "$toolchain" ]]; then
      echo 'error: Install Android SDK packages cmake;3.31.5 and ndk;27.3.13750724 for the Video2 offline engines.' >&2
      exit 1
    fi
    args=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$sdk/cmake/3.31.5/bin/ninja"
          "-DCMAKE_TOOLCHAIN_FILE=$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
          -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
    out="$out/arm64-v8a"
    ;;
  host)
    cmake="${CMAKE:-cmake}"
    args=(-G Ninja "-DVIDEO2_JNI_HEADERS=$4;$4/linux")
    ;;
  *) echo "error: Unknown native build mode: $mode" >&2; exit 1 ;;
esac
jobs=2
if [[ "${GITHUB_ACTIONS:-}" == true ]]; then jobs=4; fi
mkdir -p "$work" "$out"
work=$(cd "$work" && pwd)
out=$(cd "$out" && pwd)

verified_source() {
  local archive="$work/$1.tgz" source=$2 url=$3 hash=$4
  if [[ ! -f "$source/.verified" ]]; then
    if [[ ! -f "$archive" ]] || ! printf '%s  %s\n' "$hash" "$archive" | sha256sum --check --status; then
      curl -fsSL --retry 3 --connect-timeout 15 "$url" -o "$archive"
    fi
    printf '%s  %s\n' "$hash" "$archive" | sha256sum --check
    mkdir -p "$source"
    tar -xzf "$archive" -C "$source" --strip-components=1
    touch "$source/.verified"
  fi
}

vulkan_tools() {
  glslc="$ndk/shader-tools/linux-x86_64/glslc"
  if [[ ! -x "$glslc" ]]; then glslc=$(command -v glslc || true); fi
  if [[ ! -x "$glslc" ]]; then echo 'error: A host glslc (from the NDK shader tools or Vulkan SDK) is required.' >&2; exit 1; fi
  local vk=v1.4.341 spv=vulkan-sdk-1.4.341.0
  vulkan_headers="$work/Vulkan-Headers-$vk"
  spirv_source="$work/SPIRV-Headers-$spv"
  spirv_prefix="$work/spirv-headers-install"
  verified_source vulkan-headers "$vulkan_headers" "https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/$vk" 8876aad926b2e72bdefddc34885472d5332e2d20aa3ed57313466f0bc982cf3f
  verified_source spirv-headers "$spirv_source" "https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/refs/tags/$spv" cab0a654c4917e16367483296b44cdb1d614e3120c721beafcd37e3a8580486c
  "$cmake" -S "$spirv_source" -B "$work/spirv-headers-build" -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DSPIRV_HEADERS_ENABLE_INSTALL=ON
  "$cmake" --install "$work/spirv-headers-build" --prefix "$spirv_prefix"
}

build() {
  local engine=$1 repo=$2 rev=$3 hash=$4 variant=${5:-$1}
  local source="$work/$engine-$rev"
  verified_source "$engine" "$source" "https://codeload.github.com/ggml-org/$repo/tar.gz/$rev" "$hash"
  local variant_args=()
  if [[ "$engine" == whisper ]]; then
    variant_args+=("-DVIDEO2_WHISPER_FP16=$([[ "$variant" == whisper_fp16 ]] && echo ON || echo OFF)")
    variant_args+=("-DVIDEO2_WHISPER_FP16_DOTPROD=$([[ "$variant" == whisper_fp16_dotprod ]] && echo ON || echo OFF)")
    if [[ "$variant" == whisper_vulkan ]]; then
      vulkan_tools
      # Unlike the API 26 CPU variants, this links Vulkan 1.1 core loader symbols.
      # Keep in sync with OfflineModels.supportsVulkan. Never load this library below 29.
      local vulkan_api=29
      variant_args+=("-DANDROID_PLATFORM=android-$vulkan_api" -DVIDEO2_WHISPER_VULKAN=ON "-DVulkan_GLSLC_EXECUTABLE=$glslc"
                     "-DVulkan_INCLUDE_DIR=$vulkan_headers/include"
                     "-DVulkan_LIBRARY=$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/$vulkan_api/libvulkan.so"
                     "-DVIDEO2_SPIRV_INCLUDE=$spirv_source/include"
                     "-DSPIRV-Headers_DIR=$spirv_prefix/share/cmake/SPIRV-Headers")
    else
      variant_args+=(-DVIDEO2_WHISPER_VULKAN=OFF)
    fi
  else
    variant_args+=("-DVIDEO2_HYMT_DOTPROD=$([[ "$variant" == hymt_dotprod ]] && echo ON || echo OFF)")
  fi
  "$cmake" -S "$root/native/$engine" -B "$work/$variant-build" "${args[@]}" \
    -DCMAKE_BUILD_TYPE=Release "-DVIDEO2_SOURCES=$source" "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$out" \
    "${variant_args[@]}"
  "$cmake" --build "$work/$variant-build" --target "video2_$variant" --parallel "${VIDEO2_NATIVE_JOBS:-$jobs}"
}

build_foxlet() {
  # Foxlet v0.5.0 is an ARM-optimized build of Mozilla's Bergamot/Marian engine.
  # Build only the native batch runtime; Kadr owns model files and job lifetimes.
  foxlet_rev=0eff2a15032362115848a496ce1df8ed9fc68650
  foxlet="$work/foxlet-$foxlet_rev"
  verified_source foxlet "$foxlet" "https://codeload.github.com/yinvoke/foxlet-translate/tar.gz/$foxlet_rev" \
    0f4a0f1bca29ed60c877895f2092bbcb9f6c383565ee8d175feb6ef66e9e582b
  archive_patch="$root/native/foxlet/archive-build.patch"
  if ! patch --dry-run --batch -s -R -p1 -d "$foxlet" < "$archive_patch" >/dev/null 2>&1; then
    patch --batch -p1 -d "$foxlet" --fuzz=0 < "$archive_patch"
  fi
  "$cmake" -S "$foxlet" -B "$work/foxlet-build" "${args[@]}" -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_ARCH=armv8-a -DCOMPILE_TESTS=OFF -DGIT_SUBMODULE=OFF \
    "-DVIDEO2_FOXLET_REVISION=$foxlet_rev" "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$out"
  "$cmake" --build "$work/foxlet-build" --target foxlet --parallel "${VIDEO2_NATIVE_JOBS:-$jobs}"
  notices="$1/licenses"
  mkdir -p "$notices"
  cp "$archive_patch" "$notices/foxlet-archive-build.patch"
  python3 - "$foxlet" "$notices" "$foxlet_rev" <<'PYNOTICE'
import pathlib, runpy, sys
source, out = map(pathlib.Path, sys.argv[1:3])
text = runpy.run_path(str(source / 'tools/distribution/package_notices.py'))['notices']()
(out / 'foxlet-THIRD-PARTY.txt').write_text(text, encoding='utf-8')
(out / 'foxlet-NOTICE.txt').write_bytes((source / 'NOTICE').read_bytes())
(out / 'foxlet-SOURCE.txt').write_text(
    'MPL-covered Foxlet/Bergamot source, with the archive build patch in this directory:\n'
    f'https://github.com/yinvoke/foxlet-translate/tree/{sys.argv[3]}\n', encoding='utf-8')
PYNOTICE
}

if [[ "$mode" == android ]]; then build_foxlet "${5:-$work/assets}"; fi

# whisper.cpp v1.9.5 and llama.cpp b12503: separate static ggml builds, no servers/tools.
whisper=(whisper whisper.cpp d1be6fde11ac6e0407606b4e42fe72d34add8037 645445da884df6dbd52b30f82d7be7c01d1767c938714e477e786b88d529e9f4)
build "${whisper[@]}"
if [[ "$mode" == android ]]; then
  build "${whisper[@]}" whisper_fp16
  build "${whisper[@]}" whisper_fp16_dotprod
  build "${whisper[@]}" whisper_vulkan
fi
hymt=(hymt llama.cpp 4577932fab595eaa8b27ad71734d5de1f3c07d8c cb0058aa0d883a1f8b3704b85158776dd0c67fbd1e6922a361ec1f74d82f9381)
build "${hymt[@]}"
if [[ "$mode" == android ]]; then build "${hymt[@]}" hymt_dotprod; fi

if [[ "$mode" == android ]]; then
  # Android 15+ devices can use 16 KiB pages; reject a misaligned engine before packaging.
  readelf="$sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
  for so in "$out/libvideo2_whisper.so" "$out/libvideo2_whisper_fp16.so" "$out/libvideo2_whisper_fp16_dotprod.so" "$out/libvideo2_whisper_vulkan.so" "$out/libvideo2_hymt.so" "$out/libvideo2_hymt_dotprod.so" "$out/libfoxlet.so"; do
    loads=$("$readelf" -lW "$so")
    # Pass numeric comparison to bash: awk implementations differ on coercing hexadecimal text.
    mapfile -t alignments < <(awk '$1 == "LOAD" { print $NF }' <<< "$loads")
    aligned=${#alignments[@]}
    for align in "${alignments[@]}"; do
      if ((align < 16384)); then aligned=0; fi
    done
    if ((aligned == 0)); then
      echo "error: ${so##*/} does not have 16 KiB-aligned LOAD segments" >&2
      printf '%s\n' "$loads" >&2
      exit 1
    fi
  done
fi
