#!/bin/bash
# Run controlled lookup/cache checks using the real Android cache support.
set -euo pipefail
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$test_dir/../../../.." && pwd)
serial=${1:?Usage: test-positioning-cache.sh SERIAL [SOURCE_DIRECTORY] [OUTPUT_DIRECTORY]}
source_dir=${2:-"$test_dir/../src/app/grapheneos/networklocation"}
output_dir=${3:-"$platform_dir/out/network-location-cache-tests"}
device_adb=${ADB:-adb}
jdk_dir="$platform_dir/prebuilts/jdk/jdk21/linux-x86"
framework_jar="$platform_dir/out/soong/.intermediates/frameworks/base/framework-all/android_common/combined/framework-all.jar"
mkdir -p "$output_dir"

JAVA_HOME="$jdk_dir" "$platform_dir/external/kotlinc/bin/kotlinc" \
    "$test_dir/positioning-cache-checks.kt" \
    "$source_dir/TimedLruCache.kt" "$source_dir/PositioningData.kt" \
    "$source_dir/wifi/WifiPositioningService.kt" "$source_dir/wifi/WifiPositioningServiceCache.kt" \
    "$source_dir/cell/CellPositioningService.kt" "$source_dir/cell/CellPositioningServiceCache.kt" \
    -cp "$framework_jar" -jvm-target 21 -include-runtime -d "$output_dir/checks.jar"
"$jdk_dir/bin/java" -cp "$platform_dir/prebuilts/r8/r8.jar" com.android.tools.r8.D8 \
    --min-api 37 --lib "$platform_dir/prebuilts/sdk/37.0/system/android.jar" \
    --classpath "$framework_jar" --output "$output_dir/checks.zip" "$output_dir/checks.jar"

remote_dir=$("$device_adb" -s "$serial" shell mktemp -d /data/local/tmp/positioning-cache.XXXXXXXX | tr -d '\r')
[[ $remote_dir =~ ^/data/local/tmp/positioning-cache\.[a-zA-Z0-9]+$ ]]
trap 'status=$?; trap - EXIT; "$device_adb" -s "$serial" shell rm -rf "$remote_dir" || status=1; exit "$status"' EXIT
"$device_adb" -s "$serial" push "$output_dir/checks.zip" "$remote_dir/checks.zip" > "$output_dir/push.log" 2>&1
timeout 30s "$device_adb" -s "$serial" shell \
    "timeout 25s env CLASSPATH='$remote_dir/checks.zip' app_process /system/bin app.grapheneos.networklocation.Positioning_cache_checksKt < /dev/null"
