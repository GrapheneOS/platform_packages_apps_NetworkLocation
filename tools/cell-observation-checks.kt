package app.grapheneos.networklocation.cell

import android.telephony.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

private const val NOW = 1_000_000L
private fun lte(id: Int = 1234, millis: Long? = NOW - 20): CellInfoLte = CellInfoLte().apply {
    cellIdentity = CellIdentityLte(311, 480, id, 12, 456)
    if (millis != null) setTimeStamp(millis * 1_000_000L)
}

fun main() {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true))
    var passed = 0
    var failed = 0
    fun checkCase(name: String, body: () -> Unit) {
        try { body(); passed++; println("PASS $name") }
        catch (e: Throwable) { failed++; println("FAIL $name: $e") }
    }
    fun select(vararg cells: CellInfo) = selectCellObservations(cells.toList(), NOW)
    checkCase("empty input") { check(select().isEmpty()) }
    checkCase("valid LTE identifier and timestamp preserved") {
        val cell = lte()
        val found = select(cell)
        check(found.keys.single() == Identifier(Standard.LTE, 311, 480, 456, 1234))
        check(found.values.single() === cell)
    }
    checkCase("default unavailable Android timestamp is rejected") {
        val cell = lte(millis = null)
        check(cell.timestampMillis == Long.MAX_VALUE / 1_000_000)
        check(select(cell).isEmpty())
    }
    checkCase("future timestamp is rejected") { check(select(lte(millis = NOW + 1)).isEmpty()) }
    checkCase("negative timestamp is rejected") { check(select(lte(millis = -1)).isEmpty()) }
    checkCase("zero and present timestamp boundaries remain valid") {
        check(select(lte(1, 0), lte(2, NOW)).size == 2)
    }
    checkCase("freshest duplicate retained in either order") {
        val older = lte(millis = NOW - 100)
        val newer = lte(millis = NOW - 1)
        check(select(older, newer).values.single() === newer)
        check(select(newer, older).values.single() === newer)
    }
    checkCase("invalid duplicate cannot replace valid observation") {
        val valid = lte()
        check(select(valid, lte(millis = null)).values.single() === valid)
        check(select(lte(millis = NOW + 100), valid).values.single() === valid)
    }
    checkCase("valid older observations keep their real timestamp") {
        val cell = lte(millis = 123)
        check(select(cell).values.single().timestampMillis == 123L)
    }
    checkCase("unavailable LTE identity is rejected") {
        check(select(lte(CellInfo.UNAVAILABLE)).isEmpty())
        val cell = lte().apply { cellIdentity = CellIdentityLte() }
        check(select(cell).isEmpty())
    }
    checkCase("NR keeps its full 36-bit identity") {
        val cell = CellInfoNr().apply {
            setCellIdentity(CellIdentityNr(1, 567, 123456, intArrayOf(78), "311", "480",
                0x123456789L, null, null, emptyList()))
            setTimeStamp((NOW - 7) * 1_000_000L)
        }
        check(select(cell).keys.single() == Identifier(Standard.NR, 311, 480, 567, 0x123456789L))
    }
    checkCase("unavailable NR identity is rejected") {
        val cell = CellInfoNr().apply { setTimeStamp(NOW * 1_000_000L) }
        check(select(cell).isEmpty())
    }
    checkCase("GSM and WCDMA retain technology separation") {
        val gsm = CellInfoGsm().apply {
            cellIdentity = CellIdentityGsm(12, 3456, 17, 1, "310", "260", null, null, emptyList())
            setTimeStamp(NOW * 1_000_000L)
        }
        val wcdma = CellInfoWcdma().apply {
            cellIdentity = CellIdentityWcdma(12, 3456, 1, 10564, "310", "260", null, null, emptyList(), null)
            setTimeStamp(NOW * 1_000_000L)
        }
        val found = select(gsm, wcdma)
        check(found.keys == setOf(Identifier(Standard.GSM, 310, 260, 12, 3456),
            Identifier(Standard.WCDMA, 310, 260, 12, 3456)))
    }
    checkCase("unsupported CDMA input is rejected") {
        val cell = CellInfoCdma().apply { setTimeStamp(NOW * 1_000_000L) }
        check(select(cell).isEmpty())
    }
    println("passed=$passed failed=$failed")
    exitProcess(if (failed == 0) 0 else 1)
}
