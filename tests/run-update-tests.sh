#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/tvbox-update-tests.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Offline standalone compilation: do not invoke Gradle or download any dependency.
java_home="${JAVA_HOME:-/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home}"
java_bin="$java_home/bin/java"
javac_bin="$java_home/bin/javac"
keytool_bin="$java_home/bin/keytool"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

find_jar() {
    local name="$1" jar
    jar="$(find "$cache" -type f -name "$name" -print -quit)"
    if [[ -z "$jar" ]]; then
        printf 'Missing cached dependency: %s (searched %s)\n' "$name" "$cache" >&2
        return 1
    fi
    printf '%s' "$jar"
}

gson="$(find_jar 'gson-2.10.1.jar')"
okhttp="$(find_jar 'okhttp-3.12.11.jar')"
okio="$(find_jar 'okio-jvm-3.3.0.jar')"
# Prefer a modern available stdlib; exclude source/common/JDK adapter jars.
kotlin=""
for version in 1.9.25 1.9.0 1.7.10; do
    kotlin="$(find "$cache" -type f -name "kotlin-stdlib-$version.jar" -print -quit)"
    [[ -z "$kotlin" ]] || break
done
if [[ -z "$kotlin" ]]; then
    printf 'Missing cached kotlin-stdlib (1.9.25, 1.9.0 or 1.7.10). No dependencies downloaded.\n' >&2
    exit 1
fi
classpath="$gson:$okhttp:$okio:$kotlin"
classes="$temporary/classes"
mkdir -p "$classes" "$temporary/tls" "$temporary/core" "$temporary/network"
printf 'Standalone update tests, JDK: %s\n' "$java_home"
printf 'Cached dependencies:\n  %s\n  %s\n  %s\n  %s\n' "$gson" "$okhttp" "$okio" "$kotlin"

"$javac_bin" --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp "$classpath" -d "$classes" \
    "$root/app/src/main/java/com/github/tvbox/osc/update/UpdateUrlPolicy.java" \
    "$root/app/src/main/java/com/github/tvbox/osc/update/UpdateArtifact.java" \
    "$root/app/src/main/java/com/github/tvbox/osc/update/UpdateManifest.java" \
    "$root/app/src/main/java/com/github/tvbox/osc/update/UpdateIntegrity.java" \
    "$root/app/src/main/java/com/github/tvbox/osc/update/UpdateClient.java" \
    "$root/tests/UpdateCoreTest.java" \
    "$root/tests/UpdateNetworkTest.java"

# Never use a production key: generate a short-lived, test-only loopback certificate each run.
"$keytool_bin" -genkeypair -alias update-test -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=TVBox ephemeral update regression test' -ext 'SAN=IP:127.0.0.1,DNS:localhost' \
    -storetype PKCS12 -keystore "$temporary/tls/test-only.p12" \
    -storepass update-test-only -keypass update-test-only -noprompt \
    >"$temporary/tls/keytool.log" 2>&1 || {
        printf 'Failed to generate ephemeral test certificate.\n' >&2
        exit 1
    }

status=0
"$java_bin" --add-modules jdk.httpserver -cp "$classes:$classpath" \
    com.github.tvbox.osc.update.UpdateCoreTest "$temporary/core" || status=1
"$java_bin" --add-modules jdk.httpserver -cp "$classes:$classpath" \
    com.github.tvbox.osc.update.UpdateNetworkTest "$temporary/network" "$temporary/tls/test-only.p12" || status=1
printf 'These pure Java tests do not replace Android lifecycle, PackageManager or system installer acceptance.\n'
exit "$status"
