package app.grapheneos.networklocation.cell

import android.content.Context
import android.content.pm.PackageManager
import android.os.WorkSource
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import app.grapheneos.verboseLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.jvm.Throws
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "CellTowerScanner"

class CellTowerScanner(private val context: Context) {
    @Throws(CellScannerUnavailableException::class, CellScanFailedException::class)
    suspend fun scan(workSource: WorkSource): List<CellInfo> {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            throw CellScannerUnavailableException("device does not support FEATURE_TELEPHONY")
        }

        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            ?: throw CellScannerUnavailableException("telephonyManager is null")

        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)) {
            if (telephonyManager.radioPowerState != TelephonyManager.RADIO_POWER_ON) {
                throw CellScannerUnavailableException("cellular radio power isn't on")
            }
        } else {
            throw CellScannerUnavailableException("device does not support FEATURE_TELEPHONY_RADIO_ACCESS")
        }

        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean()
            val callback = object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (!completed.compareAndSet(false, true)) return
                    verboseLog(TAG) { "onCellInfo: size: ${cellInfo.size}" }
                    continuation.resume(cellInfo)
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (!completed.compareAndSet(false, true)) return
                    verboseLog(TAG) { "onError: errorCode: $errorCode, detail: $detail" }
                    continuation.resumeWithException(CellScanFailedException(errorCode, detail))
                }
            }
            verboseLog(TAG) { "calling requestCellInfoUpdate" }
            telephonyManager.requestCellInfoUpdate(
                workSource,
                context.mainExecutor,
                callback,
            )
            continuation.invokeOnCancellation {
                // Telephony has no cancellation API for this request. Its late
                // callback must still have a live executor, but cannot finish
                // the cancelled continuation or a later request.
                completed.set(true)
                verboseLog(TAG) { "cancelled scan; ignoring late callbacks" }
            }
        }
    }
}

class CellScannerUnavailableException(msg: String) : Exception(msg) {
    override fun toString() = "CellScannerUnavailableException: $message"
}

class CellScanFailedException(private val errorCode: Int, private val detail: Throwable?) : Exception() {
    override fun toString(): String {
        return "CellScanFailedException{errorCode: $errorCode, detail: $detail}"
    }
}
