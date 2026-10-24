package app.grapheneos.networklocation.scannerchecks

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.*
import android.net.wifi.rtt.*
import android.os.WorkSource
import android.telephony.*
import app.grapheneos.networklocation.cell.*
import app.grapheneos.networklocation.wifi.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

private class Fixture {
    val cell = TelephonyManager()
    val wifi = WifiScanner()
    val manager = WifiManager()
    val rtt = WifiRttManager()
    val context = Context(mutableMapOf(TelephonyManager::class.java to cell,
        WifiScanner::class.java to wifi, WifiManager::class.java to manager,
        WifiRttManager::class.java to rtt))
    val cellScanner = CellTowerScanner(context)
    val wifiScanner = WifiApScanner(context)
    val ranger = WifiApRanger(context)
    fun close() {
        // Test cleanup also permits running the pre-fix sources without leaked
        // executor threads keeping the host test process alive.
        (cell.requests.map { it.executor } + rtt.requests.map { it.executor })
            .filterIsInstance<ExecutorService>().forEach { it.shutdownNow() }
    }
}

// Include scheduling failures such as RejectedExecutionException, as well as
// errors thrown by the callback body on its execution thread.
private fun deliver(executor: Executor, action: () -> Unit): Throwable? {
    val error = AtomicReference<Throwable?>()
    val latch = CountDownLatch(1)
    try {
        executor.execute {
            try { action() } catch (e: Throwable) { error.set(e) }
            finally { latch.countDown() }
        }
    } catch (e: Throwable) { return e }
    check(latch.await(2, TimeUnit.SECONDS)) { "callback was not dispatched" }
    return error.get()
}

private fun okay(error: Throwable?) { check(error == null) { error.toString() } }
private val source = WorkSource()
private val results = arrayOf(WifiScanner.ScanData(arrayOf(ScanResult("home"),ScanResult("drive_nomap"))))

fun main() = runBlocking {
    var passed = 0
    var failed = 0
    suspend fun test(name: String, action: suspend CoroutineScope.(Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            withTimeout(5000) { coroutineScope { action(fixture) } }
            println("PASS $name")
            passed++
        } catch (e: Throwable) {
            println("FAIL $name: $e")
            failed++
        } finally { fixture.close() }
    }
    test("cell success and repeated request") { f ->
        repeat(2) { index ->
            val job = async(start=CoroutineStart.UNDISPATCHED) { f.cellScanner.scan(source) }
            val req=f.cell.requests.last()
            okay(deliver(req.executor) { req.callback.onCellInfo(mutableListOf(CellInfo(index))) })
            check(job.await()==listOf(CellInfo(index)))
        }
    }
    test("cell error remains a scan failure") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { runCatching { f.cellScanner.scan(source) } }
        val req=f.cell.requests.single()
        okay(deliver(req.executor) { req.callback.onError(3,IllegalStateException("modem")) })
        check(job.await().exceptionOrNull() is CellScanFailedException)
    }
    for (error in listOf(false,true)) test("cell late ${if(error) "error" else "success"} after cancellation") { f ->
        var published=false
        val job=launch(start=CoroutineStart.UNDISPATCHED) { f.cellScanner.scan(source); published=true }
        val req=f.cell.requests.single()
        job.cancelAndJoin()
        okay(deliver(req.executor) {
            if(error) req.callback.onError(3,null) else req.callback.onCellInfo(mutableListOf())
        })
        check(!published)
    }
    test("cell duplicate terminal callbacks") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { f.cellScanner.scan(source) }
        val req=f.cell.requests.single()
        okay(deliver(req.executor) { req.callback.onCellInfo(mutableListOf(CellInfo(1))) })
        check(job.await()==listOf(CellInfo(1)))
        okay(deliver(req.executor) { req.callback.onCellInfo(mutableListOf(CellInfo(2))) })
        okay(deliver(req.executor) { req.callback.onError(3,null) })
    }
    test("cell success after terminal error") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { runCatching { f.cellScanner.scan(source) } }
        val req=f.cell.requests.single()
        okay(deliver(req.executor) { req.callback.onError(3,null) })
        check(job.await().isFailure)
        okay(deliver(req.executor) { req.callback.onCellInfo(mutableListOf()) })
    }
    test("cell cancellation wins before resumed coroutine runs") { f ->
        var published=false
        val job=launch(start=CoroutineStart.UNDISPATCHED) { f.cellScanner.scan(source); published=true }
        val req=f.cell.requests.single()
        okay(deliver(req.executor) { req.callback.onCellInfo(mutableListOf()) })
        job.cancelAndJoin()
        check(!published)
    }
    test("cell missing hardware and powered off") { f ->
        f.context.packageManager.missing.add(PackageManager.FEATURE_TELEPHONY)
        check(runCatching { f.cellScanner.scan(source) }.exceptionOrNull() is CellScannerUnavailableException)
        f.context.packageManager.missing.clear()
        f.cell.radioPowerState=0
        check(runCatching { f.cellScanner.scan(source) }.exceptionOrNull() is CellScannerUnavailableException)
        check(f.cell.requests.isEmpty())
    }
    test("wifi success preserves nomap filtering") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { f.wifiScanner.scan(source) }
        val callback=f.wifi.listeners.single()
        callback.onSuccess()
        callback.onFullResult(ScanResult("not a complete scan"))
        check(!job.isCompleted)
        callback.onResults(results)
        check(job.await()==listOf(ScanResult("home")))
    }
    test("wifi duplicate terminal callbacks") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { f.wifiScanner.scan(source) }
        val callback=f.wifi.listeners.single()
        callback.onResults(results)
        check(job.await()==listOf(ScanResult("home")))
        callback.onResults(results)
        callback.onFailure(-1,"late")
    }
    test("wifi terminal error then success") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { runCatching { f.wifiScanner.scan(source) } }
        val callback=f.wifi.listeners.single()
        callback.onFailure(-1,"failed")
        check(job.await().exceptionOrNull() is WifiScanFailedException)
        callback.onResults(results)
    }
    test("wifi cancellation stops scan and ignores late callbacks") { f ->
        var published=false
        val job=launch(start=CoroutineStart.UNDISPATCHED) { f.wifiScanner.scan(source); published=true }
        val callback=f.wifi.listeners.single()
        job.cancelAndJoin()
        callback.onResults(results)
        callback.onFailure(-1,"late")
        check(f.wifi.stops==1 && !published)
    }
    for (empty in listOf(false,true)) test("wifi ${if(empty) "empty" else "null"} results") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { runCatching { f.wifiScanner.scan(source) } }
        f.wifi.listeners.single().onResults(if(empty) emptyArray() else null)
        check(job.await().exceptionOrNull() is WifiScanFailedException)
    }
    test("wifi disabled without always scanning") { f ->
        f.manager.isWifiEnabled=false
        f.manager.isScanAlwaysAvailable=false
        check(runCatching { f.wifiScanner.scan(source) }.exceptionOrNull() is WifiScannerUnavailableException)
        check(f.wifi.listeners.isEmpty())
    }
    test("rtt success keeps successful ranges") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { f.ranger.range(emptyList(),source) }
        val req=f.rtt.requests.single()
        okay(deliver(req.executor) { req.callback.onRangingResults(mutableListOf(RangingResult(0),RangingResult(1))) })
        check(job.await()==listOf(RangingResult(0)))
    }
    test("rtt duplicate terminal callbacks") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { f.ranger.range(emptyList(),source) }
        val req=f.rtt.requests.single()
        okay(deliver(req.executor) { req.callback.onRangingResults(mutableListOf()) })
        check(job.await().isEmpty())
        okay(deliver(req.executor) { req.callback.onRangingResults(mutableListOf()) })
        okay(deliver(req.executor) { req.callback.onRangingFailure(2) })
    }
    test("rtt terminal error then success") { f ->
        val job=async(start=CoroutineStart.UNDISPATCHED) { runCatching { f.ranger.range(emptyList(),source) } }
        val req=f.rtt.requests.single()
        okay(deliver(req.executor) { req.callback.onRangingFailure(2) })
        check(job.await().exceptionOrNull() is WifiRangerFailedException)
        okay(deliver(req.executor) { req.callback.onRangingResults(mutableListOf()) })
    }
    test("rtt cancellation and late callbacks") { f ->
        var published=false
        val job=launch(start=CoroutineStart.UNDISPATCHED) { f.ranger.range(emptyList(),source); published=true }
        val req=f.rtt.requests.single()
        job.cancelAndJoin()
        okay(deliver(req.executor) { req.callback.onRangingResults(mutableListOf()) })
        okay(deliver(req.executor) { req.callback.onRangingFailure(2) })
        check(f.rtt.cancels==1 && !published)
    }
    test("rtt unavailable") { f ->
        f.rtt.isAvailable=false
        check(runCatching { f.ranger.range(emptyList(),source) }.exceptionOrNull() is WifiRangerUnavailableException)
        check(f.rtt.requests.isEmpty())
    }
    println("passed=$passed failed=$failed")
    check(failed==0)
}
