#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
classes="$(mktemp -d "${TMPDIR:-/tmp}/tvbox-retry-tests.XXXXXX")"
trap 'rm -rf "$classes"' EXIT

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$javac_bin" -encoding UTF-8 -d "$classes" \
    "$root/app/src/main/java/com/github/tvbox/osc/player/PlaybackRetryPolicy.java" \
    "$root/app/src/main/java/com/github/tvbox/osc/player/PlaybackSuccessTracker.java" \
    "$root/tests/PlaybackRetryPolicyTest.java" \
    "$root/tests/PlaybackSuccessTrackerTest.java" \
    "$root/tests/PlaybackPersistenceGuardTest.java"
"$java_bin" -cp "$classes" PlaybackRetryPolicyTest
"$java_bin" -cp "$classes" PlaybackSuccessTrackerTest
"$java_bin" -cp "$classes" PlaybackPersistenceGuardTest "$root"
