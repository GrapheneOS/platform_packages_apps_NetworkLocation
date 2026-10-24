package android.net.wifi

import android.os.WorkSource

class WifiSsid(val bytes: ByteArray)
data class ScanResult(val name: String?) {
    fun getWifiSsid(): WifiSsid? = name?.let { WifiSsid(it.toByteArray()) }
}
class WifiManager {
    var isWifiEnabled = true
    var isScanAlwaysAvailable = true
}
class WifiScanner {
    class ScanSettings {
        var band = 0
        var rnrSetting = 0
        var hideFromAppOps = false
    }
    class ScanData(val results: Array<ScanResult>)
    interface ScanListener {
        fun onSuccess()
        fun onFailure(reason: Int, description: String?)
        fun onResults(results: Array<ScanData>?)
        fun onPeriodChanged(periodInMs: Int)
        fun onFullResult(fullScanResult: ScanResult?)
    }
    val listeners = mutableListOf<ScanListener>()
    var stops = 0
    fun startScan(settings: ScanSettings, listener: ScanListener, source: WorkSource) {
        listeners.add(listener)
    }
    fun stopScan(listener: ScanListener) { stops++ }
    companion object {
        const val WIFI_BAND_BOTH = 3
        const val WIFI_RNR_ENABLED = 1
        const val REASON_UNSPECIFIED = -1
    }
}
