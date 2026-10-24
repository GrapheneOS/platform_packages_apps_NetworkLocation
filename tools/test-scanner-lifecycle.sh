#!/bin/bash
set -euo pipefail
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_root=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)
source_dir=${1:-"$script_dir/../src/app/grapheneos/networklocation"}
output_dir=${2:-"$android_root/out/network-location-scanner-tests"}
mkdir -p "$output_dir"
JAVA_HOME="$android_root/prebuilts/jdk/jdk21/linux-x86" \
    "$android_root/external/kotlinc/bin/kotlinc" \
    "$script_dir"/scanner-lifecycle-tests/*.kt \
    "$script_dir/../src/app/grapheneos/Utils.kt" \
    "$source_dir/cell/CellTowerScanner.kt" "$source_dir/wifi/WifiApScanner.kt" \
    "$source_dir/wifi/WifiApRanger.kt" \
    -cp "$android_root/external/kotlinc/lib/kotlinx-coroutines-core-jvm.jar" \
    -nowarn -include-runtime -d "$output_dir/tests.jar"
"$android_root/prebuilts/jdk/jdk21/linux-x86/bin/java" \
    -cp "$output_dir/tests.jar:$android_root/external/kotlinc/lib/kotlinx-coroutines-core-jvm.jar" \
    app.grapheneos.networklocation.scannerchecks.ChecksKt
