package app.grapheneos.networklocation.cell

import android.annotation.ElapsedRealtimeLong
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.android.internal.annotations.GuardedBy
import com.android.internal.os.BackgroundThread
import java.io.IOException
import java.util.Optional

private const val TAG = "CellPositioningServiceCache"

private const val CACHE_CAPACITY = 5_000
private const val CACHE_CLEANUP_INTERVAL_MILLIS: Long = 15 * 60_000L // 15 minutes

class CellPositioningServiceCache(private val service: CellPositioningService) {
    private val lruCache = LruCache<Identifier, Entry>(CACHE_CAPACITY)

    private class Entry(val towerPositioningData: CellTowerPositioningData) {
        @ElapsedRealtimeLong
        var lastAccessTime: Long = SystemClock.elapsedRealtime()
            private set

        @GuardedBy("CellPositioningServiceCache.lruCache")
        fun updateLastAccessTime() {
            lastAccessTime = SystemClock.elapsedRealtime()
        }
    }

    @Throws(IOException::class)
    fun getPositioningData(
        identifiers: List<Identifier>,
        onlyCachedThreshold: Int,
    ): List<CellTowerPositioningData> {
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        val positioningData = mutableListOf<CellTowerPositioningData>()
        val queryIdentifiers = mutableListOf<Identifier>()

        for (identifier in identifiers) {
            val cacheEntry = checkCache(identifier)
            if (cacheEntry != null) {
                val res = cacheEntry.orElse(null)
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

        synchronized(lruCache) {
            for (pd in towerInfos) {
                lruCache.put(pd.identifier, Entry(pd))
            }
            for ((index, identifier) in queryIdentifiers.withIndex()) {
                val cacheEntry = checkCache(identifier)
                if (cacheEntry != null) {
                    val res = cacheEntry.orElse(null)
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

    private fun checkCache(identifier: Identifier): Optional<PositioningData>? {
        val cacheEntry = synchronized(lruCache) {
            val res = lruCache.get(identifier) ?: return null
            res.updateLastAccessTime()
            res
        }
        return Optional.ofNullable(cacheEntry.towerPositioningData.positioningData)
    }

    init {
        scheduleClean()
    }

    private fun scheduleClean() {
        // note that the time that the device spends in deep sleep is not counted against the delay
        BackgroundThread.getHandler().postDelayed({ clean() },
            CACHE_CLEANUP_INTERVAL_MILLIS
        )
    }

    private fun clean() {
        scheduleClean()
        val minTime = SystemClock.elapsedRealtime() - CACHE_CLEANUP_INTERVAL_MILLIS
        var numRemoved = 0
        synchronized(lruCache) {
            lruCache.snapshot().entries.forEach {
                if (it.value.lastAccessTime < minTime) {
                    if (lruCache.remove(it.key) != null) {
                        ++numRemoved
                    }
                }
            }
        }
        Log.d(TAG, "clean() removed $numRemoved items")
    }
}