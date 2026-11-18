#!/usr/bin/env bash
set -euo pipefail
libs=$1
shift
here=$(cd "$(dirname "$0")" && pwd)
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" "$here/OfflineNativeSmoke.java"
java -Xcheck:jni -Djava.library.path="$libs" -cp "$classes" com.ahcrazy.video2.data.offline.OfflineNativeSmoke "$@"
