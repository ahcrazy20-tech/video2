#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" "$here/OfflineNativeSmoke.java"
java -Xcheck:jni -Djava.library.path="$1" -cp "$classes" com.ahcrazy.video2.data.offline.OfflineNativeSmoke
