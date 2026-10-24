package app.grapheneos.networklocation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

private const val TAG = "ReportingCancellationChecks"
private object SystemClock {
    fun elapsedRealtimeNanos() = System.nanoTime()
}
private object Log {
    fun d(tag: String, text: String) {}
}
private fun verboseLog(tag: String, text: () -> String) {}
private object WifiLocationCapture {
    fun failure(id: String?, stage: String, error: Exception) {}
}
private class WifiScannerUnavailableException : Exception()
private class WifiScanFailedException : Exception()
private class CellScannerUnavailableException : Exception()
private class CellScanFailedException : Exception()

private class Gate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun block() {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "lookup was not released" }
    }
}

private class Fixture(
    val wifiFix: Boolean,
    val cellFix: Boolean = true,
    val wifiScanFails: Boolean = false,
    val cellScanFails: Boolean = false,
    val wifiLookupGate: Gate? = null,
    val cellLookupGate: Gate? = null,
) {
    private val request = object { val workSource = 0 }
    private val provider = object {
        val reports = mutableListOf<String>()
        fun reportLocation(location: String) { reports.add(location) }
    }
    private val wifiScanner = object {
        suspend fun scan(workSource: Int): List<Int> {
            if (wifiScanFails) throw WifiScanFailedException()
            return listOf(1)
        }
    }
    private val cellScanner = object {
        var calls = 0
        suspend fun scan(workSource: Int): List<Int> {
            calls++
            if (cellScanFails) throw CellScanFailedException()
            return listOf(1)
        }
    }
    val reports: List<String> get() = provider.reports
    val cellScanCalls: Int get() = cellScanner.calls
    private fun estimateLocationWifi(results: List<Int>, start: Long = 0, end: Long = 0): String? {
        wifiLookupGate?.block()
        return if (wifiFix) "wifi" else null
    }
    private fun estimateLocationCell(results: List<Int>): String? {
        cellLookupGate?.block()
        return if (cellFix) "cell" else null
    }
    suspend fun runStep() = step()

    // INSERT PRODUCTION STEP HERE
}

fun main() = runBlocking {
    // Active requests keep successful Wi-Fi, cellular fallback and scan failures.
    for ((fixture, expected) in listOf(
        Fixture(wifiFix = true) to listOf("wifi"),
        Fixture(wifiFix = false) to listOf("cell"),
        Fixture(wifiFix = false, cellFix = false) to emptyList(),
        Fixture(wifiFix = false, wifiScanFails = true) to listOf("cell"),
        Fixture(wifiFix = false, cellScanFails = true) to emptyList(),
    )) {
        fixture.runStep()
        check(fixture.reports == expected)
    }
    println("PASS five active request and fallback controls")
    var failures = 0
    for (stage in listOf("wifi_success", "wifi_no_fix", "cell_success")) {
        val gate = Gate()
        val fixture = if (stage == "cell_success") Fixture(false, cellLookupGate = gate)
            else Fixture(stage == "wifi_success", wifiLookupGate = gate)
        val work = CoroutineScope(Dispatchers.IO).launch { fixture.runStep() }
        try {
            check(gate.entered.await(5, TimeUnit.SECONDS)) { "lookup did not start" }
            work.cancel()
            gate.release.countDown()
            withTimeout(5000) { work.join() }
            val cellCallsExpected = if (stage == "cell_success") 1 else 0
            val passed = fixture.reports.isEmpty() && fixture.cellScanCalls == cellCallsExpected
            if (!passed) failures++
            println("${if (passed) "PASS" else "FAIL"} cancelled $stage: reports=${fixture.reports}, cell_scans=${fixture.cellScanCalls}")
        } finally {
            gate.release.countDown()
            work.cancelAndJoin()
        }
    }
    check(failures == 0) { "$failures cancelled steps continued reporting or started fallback" }
}
