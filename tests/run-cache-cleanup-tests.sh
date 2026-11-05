#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/tvbox-cache-cleanup-tests.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
java_home="${JAVA_HOME:-/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home}"
mkdir -p "$temporary/classes"
"$java_home/bin/javac" --release 17 -encoding UTF-8 -d "$temporary/classes" \
    "$root/app/src/main/java/com/github/tvbox/osc/util/CacheCleanup.java" \
    "$root/tests/CacheCleanupTest.java"
"$java_home/bin/java" -cp "$temporary/classes" com.github.tvbox.osc.util.CacheCleanupTest "$temporary/data"
