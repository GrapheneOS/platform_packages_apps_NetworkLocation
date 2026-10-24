package app.grapheneos.networklocation.wifi

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.WorkSource
import app.grapheneos.verboseLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "WifiApRanger"

/**
 * TODO: WIP Wi-Fi RTT AP ranger, not currently used.
 */
class WifiApRanger(private val context: Context) {

    @Throws(WifiRangerUnavailableException::class, WifiRangerFailedException::class)
    suspend fun range(scanResults: List<ScanResult>, workSource: WorkSource): List<RangingResult> {
        val wifiRttManager = context.getSystemService(WifiRttManager::class.java)
            ?: throw WifiRangerUnavailableException("wifiRttManager is null")

        if (!wifiRttManager.isAvailable) {
            throw WifiRangerUnavailableException("WifiRttManager is unavailable")
        }

        val request = RangingRequest.Builder().addAccessPoints(
            // TODO: sort by highest signal strength
            scanResults.take(RangingRequest.getMaxPeers())
        ).build()

        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean()
            val rangingResultCallback = object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    if (!completed.compareAndSet(false, true)) return
                    // TODO: maybe log with description
                    verboseLog(TAG) { "onRangingFailure: code: $code" }
                    continuation.resumeWithException(WifiRangerFailedException(code))
                }

                override fun onRangingResults(results: MutableList<RangingResult>) {
                    if (!completed.compareAndSet(false, true)) return
                    val filteredResults = results.filter {
                        it.status == RangingResult.STATUS_SUCCESS
                    }
                    verboseLog(TAG) { "onRangingResults, size: ${results.size}, filteredSize: ${filteredResults.size}" }
                    continuation.resume(filteredResults)
                }
            }
            verboseLog(TAG) { "calling startRanging" }
            wifiRttManager.startRanging(
                workSource,
                request,
                context.mainExecutor,
                rangingResultCallback
            )
            continuation.invokeOnCancellation {
                completed.set(true)
                verboseLog(TAG) {"cancelling ranging"}
                wifiRttManager.cancelRanging(workSource)
                verboseLog(TAG) {"canceled ranging"}
            }
        }
    }
}

class WifiRangerUnavailableException(msg: String) : Exception(msg) {
    override fun toString() = "WifiRangerUnavailableException: $message"
}

// TODO: better logging, see WifiApScanner WifiScanFailedException for example
class WifiRangerFailedException(val code: Int) : Exception() {
    override fun toString(): String {
        return "WifiRangerFailedException{code: $code}"
    }
}
