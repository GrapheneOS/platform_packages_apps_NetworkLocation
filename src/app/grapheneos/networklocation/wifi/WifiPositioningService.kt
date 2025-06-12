package app.grapheneos.networklocation.wifi

import app.grapheneos.networklocation.PositioningData
import java.io.IOException

interface WifiPositioningService {
    @Throws(IOException::class)
    fun fetchNearbyApPositioningData(bssids: List<String>): List<WifiApPositioningData>
}

class WifiApPositioningData(
    val bssid: Bssid, // access point BSSID
    val positioningData: PositioningData?,
) {
    override fun toString(): String {
        val pd = positioningData
        return if (pd == null) "$bssid (no positioning data)" else "${bssid}_$pd"
    }
}
