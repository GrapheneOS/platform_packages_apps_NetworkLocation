package app.grapheneos.networklocation

import app.grapheneos.networklocation.cell.CellPositioningService
import app.grapheneos.networklocation.cell.CellPositioningServiceCache
import app.grapheneos.networklocation.cell.CellTowerPositioningData
import app.grapheneos.networklocation.cell.Identifier
import app.grapheneos.networklocation.cell.Standard
import app.grapheneos.networklocation.wifi.WifiApPositioningData
import app.grapheneos.networklocation.wifi.WifiPositioningService
import app.grapheneos.networklocation.wifi.WifiPositioningServiceCache
import java.io.IOException
import kotlin.system.exitProcess

// No radio scans or network calls. Both adapters exercise the production cache
// classes and Android's actual LruCache, clock, logging and background handler.
private data class Item(val id: Int, val data: PositioningData?)

private fun point(version: Int) = PositioningData(
    latitude = version.toDouble(),
    longitude = -version.toDouble(),
    accuracyMeters = 20 + version,
    altitudeMeters = 100 + version,
    verticalAccuracyMeters = 5 + version,
    rawConfidence = version,
    altitudeReference = AltitudeReference.MEAN_SEA_LEVEL,
)

private class Fixture(cell: Boolean) {
    val calls = mutableListOf<List<Int>>()
    var response = emptyList<Item>()
    var failure: IOException? = null
    private fun fetch(ids: List<Int>): List<Item> {
        calls.add(ids.toList())
        failure?.let { throw it }
        return response
    }

    private fun bssid(id: Int) = "02:00:00:00:00:" + id.toString(16).padStart(2, '0')
    private fun id(bssid: String) = bssid.substringAfterLast(':').toInt(16)
    private fun identifier(id: Int) = Identifier(Standard.LTE, 310, 260, 1, id.toLong())

    private val wifi = if (cell) null else WifiPositioningServiceCache(
        object : WifiPositioningService {
            override fun fetchNearbyApPositioningData(bssids: List<String>) =
                fetch(bssids.map { id(it) }).map { WifiApPositioningData(bssid(it.id), it.data) }
        }
    )
    private val cellular = if (!cell) null else CellPositioningServiceCache(
        object : CellPositioningService {
            override fun fetchNearbyTowerPositioningData(identifiers: List<Identifier>) =
                fetch(identifiers.map { it.cellId.toInt() }).map {
                    CellTowerPositioningData(identifier(it.id), it.data)
                }
        }
    )

    fun get(ids: List<Int>, threshold: Int = 15): List<Item> {
        return wifi?.getPositioningData(ids.map { bssid(it) }, threshold)?.map {
            Item(id(it.bssid), it.positioningData)
        } ?: cellular!!.getPositioningData(ids.map { identifier(it) }, threshold).map {
            Item(it.identifier.cellId.toInt(), it.positioningData)
        }
    }

    fun seed(vararg items: Item) {
        response = items.toList()
        get(items.map { it.id })
        calls.clear()
        response = emptyList()
    }
}

fun main() {
    var passed = 0
    val failures = mutableListOf<String>()
    for (cell in listOf(false, true)) {
        val kind = if (cell) "cell" else "wifi"
        fun test(name: String, body: (Fixture) -> Unit) {
            try {
                body(Fixture(cell))
                println("PASS $kind/$name")
                passed++
            } catch (t: Throwable) {
                failures.add("$kind/$name: ${t.message}")
                println("FAIL ${failures.last()}")
            }
        }

        test("cached positive avoids a request") { f ->
            val old = point(1)
            f.seed(Item(1, old))
            check(f.get(listOf(1)) == listOf(Item(1, old)))
            check(f.calls.isEmpty())
        }
        test("negative cache still suppresses repeated lookups") { f ->
            f.seed(Item(1, null))
            f.response = listOf(Item(1, point(2)))
            check(f.get(listOf(1)).isEmpty())
            check(f.calls.isEmpty())
        }
        test("cached-only mode never sends a request") { f ->
            val old = point(1)
            f.seed(Item(2, old))
            check(f.get(listOf(1, 2), 0) == listOf(Item(2, old)))
            check(f.calls.isEmpty())
        }
        test("enough cached observations avoid a request") { f ->
            val items = (1..15).map { Item(it, point(1)) }
            f.seed(*items.toTypedArray())
            check(f.get((1..16).toList()) == items)
            check(f.calls.isEmpty())
        }
        test("an earlier missing observation still requests later misses") { f ->
            val items = (1..15).map { Item(it, point(1)) }
            f.seed(*items.toTypedArray())
            val extra = listOf(Item(0, point(2)), Item(16, point(3)))
            f.response = extra
            check(f.get((0..16).toList()) == items + extra)
            check(f.calls == listOf(listOf(0, 16)))
        }
        test("unchanged data preserve cached-then-requested order") { f ->
            val old = point(1)
            val new = point(2)
            f.seed(Item(2, old))
            f.response = listOf(Item(1, new), Item(2, old))
            check(f.get(listOf(1, 2)) == listOf(Item(2, old), Item(1, new)))
        }
        test("refreshed cached record is returned immediately") { f ->
            f.seed(Item(2, point(1)))
            val fresh = point(2)
            val requested = point(3)
            f.response = listOf(Item(1, requested), Item(2, fresh))
            val result = f.get(listOf(1, 2))
            check(result == listOf(Item(2, fresh), Item(1, requested))) {
                "current call used the old record although the cache was refreshed"
            }
            check(f.get(listOf(1, 2), 0).toSet() == result.toSet())
        }
        test("a negative cached record can become usable immediately") { f ->
            f.seed(Item(2, null))
            val fresh = point(2)
            val requested = point(3)
            f.response = listOf(Item(1, requested), Item(2, fresh))
            check(f.get(listOf(1, 2)).toSet() == setOf(Item(2, fresh), Item(1, requested))) {
                "newly mapped observed identity was omitted from the current call"
            }
        }
        test("an explicitly invalidated cached record is removed immediately") { f ->
            f.seed(Item(2, point(1)))
            val requested = point(3)
            f.response = listOf(Item(1, requested), Item(2, null))
            check(f.get(listOf(1, 2)) == listOf(Item(1, requested))) {
                "current call retained positioning data explicitly replaced by a negative record"
            }
        }
        test("response omission does not invalidate a cached record") { f ->
            val old = point(1)
            val requested = point(3)
            f.seed(Item(2, old))
            f.response = listOf(Item(1, requested))
            check(f.get(listOf(1, 2)) == listOf(Item(2, old), Item(1, requested)))
        }
        test("unobserved neighbors are cached without entering the result") { f ->
            val requested = point(1)
            val neighbor = point(2)
            f.response = listOf(Item(1, requested), Item(99, neighbor))
            check(f.get(listOf(1)) == listOf(Item(1, requested)))
            check(f.get(listOf(99), 0) == listOf(Item(99, neighbor)))
            check(f.calls.size == 1)
        }
        test("failed request preserves available cached fallback") { f ->
            val old = point(1)
            f.seed(Item(2, old))
            val expected = IOException("controlled service failure")
            f.failure = expected
            val failure = runCatching { f.get(listOf(1, 2)) }.exceptionOrNull()
            check(failure === expected)
            check(f.get(listOf(1, 2), 0) == listOf(Item(2, old)))
            check(f.calls.size == 1)
        }
        test("empty response produces no invented position") { f ->
            check(f.get(listOf(1, 2)).isEmpty())
            check(f.calls == listOf(listOf(1, 2)))
        }
        test("duplicate cached identities do not satisfy the threshold") { f ->
            val old = point(1)
            val requested = point(2)
            f.seed(Item(1, old))
            f.response = listOf(Item(2, requested))
            check(f.get(List(15) { 1 } + 2) == listOf(Item(1, old), Item(2, requested)))
            check(f.calls == listOf(listOf(2)))
        }
        test("duplicate missing identities use one request slot") { f ->
            val a = point(1)
            val b = point(2)
            f.response = listOf(Item(1, a), Item(2, b))
            check(f.get(listOf(1, 1, 2, 1)) == listOf(Item(1, a), Item(2, b)))
            check(f.calls == listOf(listOf(1, 2)))
        }
        test("cached-only results contain each identity once") { f ->
            val old = point(1)
            f.seed(Item(1, old))
            check(f.get(listOf(1, 1), 0) == listOf(Item(1, old)))
        }
    }
    println("Cache checks: $passed passed; ${failures.size} failed")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
