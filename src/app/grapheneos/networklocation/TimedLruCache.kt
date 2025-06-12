package app.grapheneos.networklocation

import android.annotation.ElapsedRealtimeLong
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.android.internal.annotations.GuardedBy
import com.android.internal.os.BackgroundThread
import java.util.Optional
import kotlin.time.Duration

private const val TAG = "TimedLruCache"

class TimedLruCache<K, V>(maxSize: Int, private val cacheCleanupInterval: Duration) {
    private val lruCache = LruCache<K, Entry<V>>(maxSize)

    private class Entry<V>(val value: V) {
        @ElapsedRealtimeLong
        var lastAccessTime: Long = SystemClock.elapsedRealtime()
            private set

        @GuardedBy("TimedLruCache.lruCache")
        fun updateLastAccessTime() {
            lastAccessTime = SystemClock.elapsedRealtime()
        }
    }

    fun checkCache(key: K): Optional<V>? {
        val cacheEntry = synchronized {
            val res = lruCache.get(key) ?: return@synchronized null
            res.updateLastAccessTime()
            res
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        return Optional.ofNullable<V>(cacheEntry.value) as Optional<V>
    }

    fun <R> synchronized(block: () -> R): R {
        return synchronized(lruCache, block)
    }

    fun put(key: K, value: V) {
        lruCache.put(key, Entry(value))
    }

    init {
        scheduleClean()
    }

    private fun scheduleClean() {
        // note that the time that the device spends in deep sleep is not counted against the delay
        BackgroundThread.getHandler().postDelayed({ clean() }, cacheCleanupInterval.inWholeMilliseconds)
    }

    private fun clean() {
        scheduleClean()
        val minTime = SystemClock.elapsedRealtime() - cacheCleanupInterval.inWholeMilliseconds
        var numRemoved = 0
        synchronized {
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