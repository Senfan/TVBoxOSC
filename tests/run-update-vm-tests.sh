#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/tvbox-update-vm-tests.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Compile the real VM and networking code offline, with minimal test-only Android substitutes.
# No Gradle, emulator, existing regression script, production keystore or downloads are used.
java_home="${JAVA_HOME:-/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home}"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
find_jar() {
    local jar
    jar="$(find "$cache" -type f -name "$1" -print -quit)"
    if [[ -z "$jar" ]]; then
        printf 'Missing cached dependency: %s\n' "$1" >&2
        return 1
    fi
    printf '%s' "$jar"
}

if [[ -n "${TVBOX_UPDATE_TEST_CLASSPATH:-}" ]]; then
    classpath="$TVBOX_UPDATE_TEST_CLASSPATH"
    printf 'Explicit update regression classpath: %s\n' "$classpath"
else
# Legacy baseline is retained for comparisons; upgraded runs pass an explicit classpath.
gson="$(find_jar 'gson-2.10.1.jar')"
okhttp="$(find_jar 'okhttp-3.12.11.jar')"
okio="$(find_jar 'okio-jvm-3.3.0.jar')"
kotlin=""
for version in 1.9.25 1.9.0 1.7.10; do
    kotlin="$(find "$cache" -type f -name "kotlin-stdlib-$version.jar" -print -quit)"
    [[ -z "$kotlin" ]] || break
done
if [[ -z "$kotlin" ]]; then
    printf 'Missing cached Kotlin stdlib; no dependencies downloaded.\n' >&2
    exit 1
fi
classpath="$gson:$okhttp:$okio:$kotlin"
fi
classes="$temporary/classes"
mkdir -p "$classes" "$temporary/vm"
stubs=()
while IFS= read -r file; do stubs+=("$file"); done < <(find "$root/tests/update-vm-stubs" -type f -name '*.java' | sort)
source="$root/app/src/main/java/com/github/tvbox/osc/update"
"$java_home/bin/javac" --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp "$classpath" -d "$classes" \
    "${stubs[@]}" \
    "$source/UpdateUrlPolicy.java" "$source/UpdateArtifact.java" "$source/UpdateManifest.java" \
    "$source/UpdateIntegrity.java" "$source/UpdateClient.java" "$source/UpdateState.java" "$source/UpdateViewModel.java" \
    "$source/UpdatePreferences.java" "$source/UpdateCleanup.java" "$source/UpdateCleanupReceiver.java" \
    "$root/tests/UpdateViewModelTest.java" "$root/tests/UpdateCleanupTest.java" "$root/tests/UpdateCoreTest.java" "$root/tests/UpdateNetworkTest.java"

status=0
"$java_home/bin/java" --add-modules jdk.httpserver -cp "$classes:$classpath" \
    com.github.tvbox.osc.update.UpdateViewModelTest "$temporary/vm" || status=1
"$java_home/bin/java" --add-modules jdk.httpserver -cp "$classes:$classpath" \
    com.github.tvbox.osc.update.UpdateCleanupTest "$temporary/cleanup" || status=1
"$java_home/bin/java" --add-modules jdk.httpserver -cp "$classes:$classpath" \
    com.github.tvbox.osc.update.UpdateCoreTest "$temporary/core" || status=1
"$java_home/bin/java" --add-modules jdk.httpserver -cp "$classes:$classpath" \
    com.github.tvbox.osc.update.UpdateNetworkTest --timeout-policies || status=1
printf 'Real VM, cleanup, preferences and receiver; JVM Android boundaries only, not real PackageManager/installer acceptance.\n'
exit "$status"
