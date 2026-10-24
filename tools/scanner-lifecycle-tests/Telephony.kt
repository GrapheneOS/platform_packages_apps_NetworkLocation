package android.telephony

import android.os.WorkSource
import java.util.concurrent.Executor

data class CellInfo(val id: Int)
class TelephonyManager {
    var radioPowerState = RADIO_POWER_ON
    data class Request(val executor: Executor, val callback: CellInfoCallback)
    val requests = mutableListOf<Request>()
    fun requestCellInfoUpdate(source: WorkSource, executor: Executor, callback: CellInfoCallback) {
        requests.add(Request(executor,callback))
    }
    open class CellInfoCallback {
        open fun onCellInfo(cellInfo: MutableList<CellInfo>) {}
        open fun onError(errorCode: Int, detail: Throwable?) {}
    }
    companion object { const val RADIO_POWER_ON = 1 }
}
