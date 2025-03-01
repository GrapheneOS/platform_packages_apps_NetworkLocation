package app.grapheneos.networklocation.wifi

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiScanner
import android.net.wifi.WifiScanner.ScanListener
import android.os.WorkSource
import android.util.Log
import android.util.SparseArray
import app.grapheneos.networklocation.verboseLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "WifiApScanner"

class WifiApScanner(private val context: Context) {

    @Throws(WifiScannerUnavailableException::class, WifiScanFailedException::class)
    suspend fun scan(workSource: WorkSource): List<ScanResult> {
        val wifiManager = context.getSystemService(WifiManager::class.java)
            ?: throw WifiScannerUnavailableException("wifiManager is null")

        if (!wifiManager.isWifiEnabled) {
            @Suppress("DEPRECATION") // isScanAlwaysAvailable is not deprecated for platform apps
            if (!wifiManager.isScanAlwaysAvailable) {
                throw WifiScannerUnavailableException("Wifi is disabled and always-on scanning is not available")
            }
        }

        // WifiManager.isWifiScannerSupported() is intentionally not checked. That method has
        // misleadining name and documentation: it checks whether the default Wi-Fi adapter supports
        // background scanning, not whether android.net.wifi.WifiScanner APIs are supported.

        val wifiScanner = context.getSystemService(WifiScanner::class.java)
            ?: throw WifiScannerUnavailableException("wifiScanner is null")

        val scanSettings = WifiScanner.ScanSettings().apply {
            // type is WifiScanner.SCAN_TYPE_LOW_LATENCY by default
            band = WifiScanner.WIFI_BAND_BOTH
            rnrSetting = WifiScanner.WIFI_RNR_NOT_NEEDED
            hideFromAppOps = true
        }

        return suspendCancellableCoroutine { continuation ->
            val scanListener = object : ScanListener {
                override fun onSuccess() {
                    verboseLog(TAG) {"onSuccess"}
                }

                override fun onFailure(reason: Int, description: String?) {
                    verboseLog(TAG) {"onFailure: reason: $reason, description: $description"}
                    continuation.resumeWithException(WifiScanFailedException(reason, description))
                }

                override fun onResults(results: Array<WifiScanner.ScanData>?) {
                    if (results == null) {
                        continuation.resumeWithException(WifiScanFailedException(WifiScanner.REASON_UNSPECIFIED, "results is null"))
                        return
                    }
                    if (results.isEmpty()) {
                        continuation.resumeWithException(WifiScanFailedException(WifiScanner.REASON_UNSPECIFIED, "results is empty"))
                        return
                    }
                    if (results.size != 1) {
                        // this is is a single-shot scan
                        Log.w(TAG, "onResults: unexpected array size: ${results.size}")
                    }
                    val resultList: List<ScanResult> = results[0].results.toList()
                    val filteredList = resultList.filter {
                        it.getWifiSsid()?.bytes?.decodeToString()?.endsWith("_nomap") == false
                    }
                    verboseLog(TAG) {"onResults, size: ${resultList.size}, filteredSize: ${filteredList.size}"}
                    continuation.resume(filteredList)
                }

                @Deprecated("Deprecated in Java")
                override fun onPeriodChanged(periodInMs: Int) {
                    Log.e(TAG, "unexpected onPeriodChanged: $periodInMs")
                }

                override fun onFullResult(fullScanResult: ScanResult?) {
                    verboseLog(TAG) {"onFullResult: $fullScanResult"}
                }
            }
            verboseLog(TAG) {"calling startScan"}
            wifiScanner.startScan(scanSettings, scanListener, workSource)
            continuation.invokeOnCancellation {
                verboseLog(TAG) {"cancelling scan"}
                wifiScanner.stopScan(scanListener)
                verboseLog(TAG) {"canceled scan"}
            }
        }
    }
}

class WifiScannerUnavailableException(msg: String) : Exception(msg) {
    override fun toString() = "WifiScannerUnavailableException: $message"
}

class WifiScanFailedException(
    /** @see android.net.wifi.WifiScanner.ScanStatusCode */
    val reason: Int,
    val description: String?) : Exception(description) {

    override fun toString(): String {
        return "WifiScanFailedException{reason: ${reasonStrings.get(reason) ?: reason.toString()}, description: $description}"
    }

    companion object {
        private val reasonStrings: SparseArray<String> by lazy {
            val res = SparseArray<String>()
            WifiScanner::class.java.declaredFields.forEach {
                val name = it.name
                if (name.startsWith("REASON_") && it.type == Int::class.javaPrimitiveType) {
                    res.put(it.getInt(null), name)
                }
            }
            res
        }
    }
}
