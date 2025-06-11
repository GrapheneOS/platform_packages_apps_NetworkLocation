package app.grapheneos.networklocation

import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class Throttle(private val cooldown: Duration) {
    private var lastTriggerElapsedRealtime = -cooldown

    fun isThrottled() = elapsedRealtime() - lastTriggerElapsedRealtime < cooldown

    private fun elapsedRealtime() = SystemClock.elapsedRealtime().milliseconds

    fun trigger() {
        lastTriggerElapsedRealtime = elapsedRealtime()
    }
}