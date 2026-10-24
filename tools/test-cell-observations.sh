#!/bin/bash
set -euo pipefail
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_root=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)
device_serial=${1:?Usage: test-cell-observations.sh SERIAL [SOURCE_FILE] [OUTPUT_DIRECTORY]}
source_file=${2:-"$script_dir/../src/app/grapheneos/networklocation/cell/CellObservations.kt"}
output_dir=${3:-"$android_root/out/network-location-cell-observation-tests"}
device_adb=${ADB:-adb}
jdk_dir="$android_root/prebuilts/jdk/jdk21/linux-x86"
framework_jar="$android_root/out/soong/.intermediates/frameworks/base/framework-all/android_common/combined/framework-all.jar"
mkdir -p "$output_dir"
JAVA_HOME="$jdk_dir" "$android_root/external/kotlinc/bin/kotlinc" \
    "$source_file" "$script_dir/cell-observation-checks.kt" \
    "$script_dir/../src/app/grapheneos/networklocation/cell/CellPositioningService.kt" \
    "$script_dir/../src/app/grapheneos/networklocation/PositioningData.kt" \
    -cp "$framework_jar" -jvm-target 21 -nowarn -include-runtime -d "$output_dir/checks.jar"
"$jdk_dir/bin/java" -cp "$android_root/prebuilts/r8/r8.jar" com.android.tools.r8.D8 \
    --min-api 37 --lib "$android_root/prebuilts/sdk/37.0/system/android.jar" \
    --classpath "$framework_jar" --output "$output_dir/checks.zip" "$output_dir/checks.jar"
remote_dir=$("$device_adb" -s "$device_serial" shell mktemp -d /data/local/tmp/cell-observations.XXXXXXXX | tr -d '\r')
[[ $remote_dir =~ ^/data/local/tmp/cell-observations\.[a-zA-Z0-9]+$ ]]
cleanup() {
    result=$?
    trap - EXIT
    "$device_adb" -s "$device_serial" shell rm -rf "$remote_dir" || result=1
    exit "$result"
}
trap cleanup EXIT
"$device_adb" -s "$device_serial" push "$output_dir/checks.zip" "$remote_dir/checks.zip" > "$output_dir/push.log" 2>&1
timeout 30s "$device_adb" -s "$device_serial" shell \
    "timeout 25s env CLASSPATH='$remote_dir/checks.zip' app_process /system/bin app.grapheneos.networklocation.cell.Cell_observation_checksKt < /dev/null"
