package app.grapheneos.networklocation.wifi

import android.util.Log
import app.grapheneos.networklocation.TimedLruCache
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

private const val TAG = "WifiPositioningServiceCache"

typealias Bssid = String

class WifiPositioningServiceCache(private val service: WifiPositioningService) {
    private val timedLruCache = TimedLruCache<Bssid, WifiApPositioningData>(10_000, 15.minutes)

    @Throws(IOException::class)
    fun getPositioningData(
        bssids: List<Bssid>,
        onlyCachedThreshold: Int,
    ): List<WifiApPositioningData> {
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        val positioningData = mutableListOf<WifiApPositioningData>()
        val queryBssids = mutableListOf<Bssid>()

        for (bssid in bssids) {
            val cacheEntry = timedLruCache.checkCache(bssid)
            if (cacheEntry != null) {
                val res = cacheEntry.orElse(null).positioningData
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
            service.fetchNearbyApPositioningData(queryBssids)
        if (isVerbose) Log.v(TAG, "service response: $apInfos")

        timedLruCache.synchronized {
            for (pd in apInfos) {
                timedLruCache.put(pd.bssid, pd)
            }
            for ((index, bssid) in queryBssids.withIndex()) {
                val cacheEntry = timedLruCache.checkCache(bssid)
                if (cacheEntry != null) {
                    val res = cacheEntry.orElse(null).positioningData
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
}
