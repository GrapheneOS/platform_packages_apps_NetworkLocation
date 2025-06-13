package app.grapheneos.networklocation.cell

import android.util.Log
import app.grapheneos.networklocation.TimedLruCache
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

private const val TAG = "CellPositioningServiceCache"

class CellPositioningServiceCache(private val service: CellPositioningService) {
    private val timedLruCache =
        TimedLruCache<Identifier, CellTowerPositioningData>(1_000, 15.minutes)

    @Throws(IOException::class)
    fun getPositioningData(
        identifiers: List<Identifier>,
        onlyCachedThreshold: Int,
    ): List<CellTowerPositioningData> {
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        val positioningData = mutableListOf<CellTowerPositioningData>()
        val queryIdentifiers = mutableListOf<Identifier>()

        for (identifier in identifiers) {
            val cacheEntry = timedLruCache.checkCache(identifier)
            if (cacheEntry != null) {
                val res = cacheEntry.orElse(null).positioningData
                if (isVerbose) Log.v(
                    TAG,
                    "getPositioningData: cache hit for $identifier: $res"
                )
                if (res != null) {
                    positioningData.add(CellTowerPositioningData(identifier, res))
                }
            } else if (positioningData.size < onlyCachedThreshold || queryIdentifiers.isNotEmpty()) {
                queryIdentifiers.add(identifier)
            }
        }

        if (queryIdentifiers.isEmpty()) {
            return positioningData
        }

        if (isVerbose) Log.v(TAG, "querying positioning data for $queryIdentifiers")
        val towerInfos: List<CellTowerPositioningData> =
            service.fetchNearbyTowerPositioningData(queryIdentifiers)
        if (isVerbose) Log.v(TAG, "service response: $towerInfos")

        timedLruCache.synchronized {
            for (pd in towerInfos) {
                timedLruCache.put(pd.identifier, pd)
            }
            for ((index, identifier) in queryIdentifiers.withIndex()) {
                val cacheEntry = timedLruCache.checkCache(identifier)
                if (cacheEntry != null) {
                    val res = cacheEntry.orElse(null).positioningData
                    res?.let { positioningData.add(CellTowerPositioningData(identifier, it)) }
                } else if (index <= onlyCachedThreshold) {
                    Log.w(
                        TAG,
                        "cache lookup for $identifier failed after service.fetchNearbyTowerPositioningData()"
                    )
                }
            }
        }

        return positioningData
    }
}