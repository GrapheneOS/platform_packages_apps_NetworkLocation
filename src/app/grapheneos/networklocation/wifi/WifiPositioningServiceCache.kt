package app.grapheneos.networklocation.wifi

import android.annotation.ElapsedRealtimeLong
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.android.internal.annotations.GuardedBy
import com.android.internal.os.BackgroundThread
import java.io.IOException
import java.util.Optional

private const val TAG = "WifiPositioningServiceCache"

typealias Bssid = String

private const val CACHE_CAPACITY = 10_000
// max number of WifiApPositioningData entries that service should return in a single call
private const val MAX_RESPONSE_SIZE = 100
private const val CACHE_CLEANUP_INTERVAL_MILLIS: Long = 15 * 60_000L // 15 minutes

class WifiPositioningServiceCache(private val service: WifiPositioningService) {
    private val lruCache = LruCache<Bssid, Entry>(CACHE_CAPACITY)

    private class Entry(val apPositioningData: WifiApPositioningData) {
        @ElapsedRealtimeLong var lastAccessTime: Long = SystemClock.elapsedRealtime()
            private set

        @GuardedBy("WifiPositioningServiceCache.lruCache")
        fun updateLastAccessTime() {
            lastAccessTime = SystemClock.elapsedRealtime()
        }
    }

    @Throws(IOException::class)
    fun getPositioningData(
        bssids: List<Bssid>,
        onlyCachedThreshold: Int,
    ): List<WifiApPositioningData> {
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        val positioningData = mutableListOf<WifiApPositioningData>()
        val queryBssids = mutableListOf<Bssid>()

        for (bssid in bssids) {
            val cacheEntry = checkCache(bssid)
            if (cacheEntry != null) {
                val res = cacheEntry.orElse(null)
                if (isVerbose) Log.v(
                    TAG,
                    "getPositioningData: cache hit for $bssid: $res"
                )
                if (res != null) {
                    positioningData.add(WifiApPositioningData(bssid, res))
                }
            } else if (positioningData.size < onlyCachedThreshold || queryBssids.isNotEmpty()) {
                queryBssids.add(bssid)
            }
        }

        if (queryBssids.isEmpty()) {
            return positioningData
        }

        if (isVerbose) Log.v(TAG, "querying positioning data for $queryBssids")
        val apInfos: List<WifiApPositioningData> =
            service.fetchNearbyApPositioningData(
                queryBssids,
                MAX_RESPONSE_SIZE,
                onlyCachedThreshold
            )
        if (isVerbose) Log.v(TAG, "service response: $apInfos")

        if (apInfos.size > MAX_RESPONSE_SIZE) {
            Log.w(TAG, "service response size (${apInfos.size}) is greater than MAX_RESPONSE_SIZE")
        }

        synchronized(lruCache) {
            apInfos.forEachIndexed { idx, pd: WifiApPositioningData ->
                lruCache.put(pd.bssid, Entry(pd))
                if (idx == queryBssids.size * MAX_RESPONSE_SIZE) {
                    return@forEachIndexed
                }
            }
            for ((index, bssid) in queryBssids.withIndex()) {
                val cacheEntry = checkCache(bssid)
                if (cacheEntry != null) {
                    val res = cacheEntry.orElse(null)
                    res?.let { positioningData.add(WifiApPositioningData(bssid, it)) }
                } else if (index <= onlyCachedThreshold) {
                    Log.w(
                        TAG,
                        "cache lookup for $bssid failed after service.fetchAccessPointInfo()"
                    )
                }
            }
        }

        return positioningData
    }

    private fun checkCache(bssid: Bssid): Optional<PositioningData>? {
        val cacheEntry = synchronized(lruCache) {
            val res = lruCache.get(bssid) ?: return null
            res.updateLastAccessTime()
            res
        }
        return Optional.ofNullable(cacheEntry.apPositioningData.positioningData)
    }

    init {
        scheduleClean()
    }

    private fun scheduleClean() {
        // note that the time that the device spends in deep sleep is not counted against the delay
        BackgroundThread.getHandler().postDelayed({ clean() }, CACHE_CLEANUP_INTERVAL_MILLIS)
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
