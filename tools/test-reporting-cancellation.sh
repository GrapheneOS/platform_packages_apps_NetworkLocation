#!/bin/bash
set -euo pipefail
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_root=$(CDPATH= cd -- "$script_dir/../../../.." && pwd)
source_file=${1:-"$script_dir/../src/app/grapheneos/networklocation/LocationReportingTask.kt"}
output_dir=${2:-"$android_root/out/network-location-reporting-cancellation-tests"}
mkdir -p "$output_dir"
# Exercise the real step body, substituting only scanners, lookups and Android
# callbacks with deterministic gates. Fail if the source boundary changes.
awk '
    NR == FNR {
        if ($0 ~ /^    private suspend fun step\(\)/) capturing = 1
        if ($0 ~ /^    private class EstimatedDistance\(/) capturing = 0
        if (capturing) body = body $0 ORS
        next
    }
    $0 == "    // INSERT PRODUCTION STEP HERE" {
        if (body == "") exit 2
        printf "%s", body
        replacements++
        next
    }
    { print }
    END { if (replacements != 1) exit 3 }
' "$source_file" "$script_dir/reporting-cancellation-checks.kt" > "$output_dir/Checks.kt"
JAVA_HOME="$android_root/prebuilts/jdk/jdk21/linux-x86" \
    "$android_root/external/kotlinc/bin/kotlinc" "$output_dir/Checks.kt" \
    -cp "$android_root/external/kotlinc/lib/kotlinx-coroutines-core-jvm.jar" \
    -nowarn -include-runtime -d "$output_dir/tests.jar"
"$android_root/prebuilts/jdk/jdk21/linux-x86/bin/java" \
    -cp "$output_dir/tests.jar:$android_root/external/kotlinc/lib/kotlinx-coroutines-core-jvm.jar" \
    app.grapheneos.networklocation.ChecksKt
