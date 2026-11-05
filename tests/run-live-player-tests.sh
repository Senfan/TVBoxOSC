#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
classes="$(mktemp -d "${TMPDIR:-/tmp}/tvbox-live-tests.XXXXXX")"
trap 'rm -rf "$classes"' EXIT

json_jar="${JSON_JAR:-}"
if [[ -z "$json_jar" ]]; then
    json_jar="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json/20180813" -name 'json-20180813.jar' -print -quit 2>/dev/null || true)"
fi
if [[ ! -f "$json_jar" ]]; then
    printf '%s\n' 'Missing JVM org.json jar. Build the app to populate the Gradle cache, or set JSON_JAR to a local jar.' >&2
    exit 1
fi

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$javac_bin" -encoding UTF-8 -cp "$json_jar" -d "$classes" \
    "$root/tests/live-support/androidx/annotation/NonNull.java" \
    "$root/tests/live-support/com/orhanobut/hawk/Hawk.java" \
    "$root/tests/live-support/com/github/tvbox/osc/util/PlayerHelper.java" \
    "$root/tests/live-support/xyz/doikki/videoplayer/player/VideoView.java" \
    "$root/app/src/main/java/com/github/tvbox/osc/util/HawkConfig.java" \
    "$root/app/src/main/java/com/github/tvbox/osc/bean/LivePlayerManager.java" \
    "$root/tests/LivePlayerManagerTest.java"
"$java_bin" -cp "$classes:$json_jar" com.github.tvbox.osc.bean.LivePlayerManagerTest
