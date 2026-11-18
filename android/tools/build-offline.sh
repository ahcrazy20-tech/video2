#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)

# Usage: build-offline.sh android <work-dir> <jniLibs-dir> <Android SDK>
#        build-offline.sh host    <work-dir> <lib-dir>     <JDK JNI include dir>
mode=$1
work=$2
out=$3
case "$mode" in
  android)
    sdk=$4
    cmake="$sdk/cmake/3.31.5/bin/cmake"
    toolchain="$sdk/ndk/27.3.13750724/build/cmake/android.toolchain.cmake"
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

build() {
  local engine=$1 repo=$2 rev=$3 hash=$4 variant=${5:-$1}
  local archive="$work/$engine.tgz" source="$work/$engine-$rev"
  if [[ ! -f "$source/.verified" ]]; then
    if [[ ! -f "$archive" ]] || ! printf '%s  %s\n' "$hash" "$archive" | sha256sum --check --status; then
      curl -fsSL --retry 3 --connect-timeout 15 "https://codeload.github.com/ggml-org/$repo/tar.gz/$rev" -o "$archive"
    fi
    printf '%s  %s\n' "$hash" "$archive" | sha256sum --check
    mkdir -p "$source"
    tar -xzf "$archive" -C "$source" --strip-components=1
    touch "$source/.verified"
  fi
  local variant_args=()
  if [[ "$engine" == whisper ]]; then
    variant_args+=("-DVIDEO2_WHISPER_FP16=$([[ "$variant" == whisper_fp16 ]] && echo ON || echo OFF)")
  else
    variant_args+=("-DVIDEO2_HYMT_DOTPROD=$([[ "$variant" == hymt_dotprod ]] && echo ON || echo OFF)")
  fi
  "$cmake" -S "$root/native/$engine" -B "$work/$variant-build" "${args[@]}" \
    -DCMAKE_BUILD_TYPE=Release "-DVIDEO2_SOURCES=$source" "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$out" \
    "${variant_args[@]}"
  "$cmake" --build "$work/$variant-build" --target "video2_$variant" --parallel "${VIDEO2_NATIVE_JOBS:-$jobs}"
}

# whisper.cpp v1.9.5 and llama.cpp b12503: separate static ggml builds, no servers/tools.
whisper=(whisper whisper.cpp d1be6fde11ac6e0407606b4e42fe72d34add8037 645445da884df6dbd52b30f82d7be7c01d1767c938714e477e786b88d529e9f4)
build "${whisper[@]}"
if [[ "$mode" == android ]]; then build "${whisper[@]}" whisper_fp16; fi
hymt=(hymt llama.cpp 4577932fab595eaa8b27ad71734d5de1f3c07d8c cb0058aa0d883a1f8b3704b85158776dd0c67fbd1e6922a361ec1f74d82f9381)
build "${hymt[@]}"
if [[ "$mode" == android ]]; then build "${hymt[@]}" hymt_dotprod; fi

if [[ "$mode" == android ]]; then
  # Android 15+ devices can use 16 KiB pages; reject a misaligned engine before packaging.
  readelf="$sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
  for so in "$out/libvideo2_whisper.so" "$out/libvideo2_whisper_fp16.so" "$out/libvideo2_hymt.so" "$out/libvideo2_hymt_dotprod.so"; do
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
