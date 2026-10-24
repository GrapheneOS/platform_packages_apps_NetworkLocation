package app.grapheneos.networklocation.wifi

import android.net.wifi.ScanResult

/** Select usable RSSI observations before lookup, origin selection and tracker timing. */
internal fun selectWifiObservations(
    scanResults: List<ScanResult>,
    elapsedRealtimeMicros: Long,
): Map<Bssid, ScanResult> {
    val observations = LinkedHashMap<Bssid, ScanResult>()
    for (scanResult in scanResults) {
        val bssid = scanResult.BSSID ?: continue
        if (bssid.isEmpty() || scanResult.timestamp !in 1L..elapsedRealtimeMicros) continue
        // Match the native RSSI model. Rejected signals must not consume query
        // slots, influence the coordinate origin or set the estimate's time.
        if (scanResult.level !in -127..0) continue
        when (scanResult.band) {
            ScanResult.WIFI_BAND_24_GHZ, ScanResult.WIFI_BAND_5_GHZ,
            ScanResult.WIFI_BAND_6_GHZ -> {}
            else -> continue
        }
        val previous = observations[bssid]
        if (previous == null || scanResult.timestamp > previous.timestamp ||
            (scanResult.timestamp == previous.timestamp && scanResult.level > previous.level)) {
            observations[bssid] = scanResult
        }
    }
    // Keep valid old observations at their real age. The tracker separately
    // rejects repeated/older scan times; no new age cutoff is imposed here.
    return observations
}
