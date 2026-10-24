package android.net.wifi.rtt

import android.net.wifi.ScanResult
import android.os.WorkSource
import java.util.concurrent.Executor

class RangingRequest {
    class Builder {
        fun addAccessPoints(scans: List<ScanResult>) = this
        fun build() = RangingRequest()
    }
    companion object { fun getMaxPeers() = 10 }
}
data class RangingResult(val status: Int) {
    companion object { const val STATUS_SUCCESS = 0 }
}
open class RangingResultCallback {
    open fun onRangingFailure(code: Int) {}
    open fun onRangingResults(results: MutableList<RangingResult>) {}
}
class WifiRttManager {
    var isAvailable = true
    data class Request(val executor: Executor, val callback: RangingResultCallback)
    val requests = mutableListOf<Request>()
    var cancels = 0
    fun startRanging(source: WorkSource, request: RangingRequest, executor: Executor,
        callback: RangingResultCallback) { requests.add(Request(executor,callback)) }
    fun cancelRanging(source: WorkSource) { cancels++ }
}
