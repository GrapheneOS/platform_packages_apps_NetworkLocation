package app.grapheneos.networklocation.wifi

import java.io.IOException

interface WifiPositioningService {
    @Throws(IOException::class)
    fun fetchNearbyApPositioningData(
        bssids: List<String>,
        maxResultsHint: Int,
        withPositioningDataThreshold: Int
    ): List<WifiApPositioningData>
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

class PositioningData(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Long,
    val altitudeMeters: Long?,
    val verticalAccuracyMeters: Long?,
) {
    override fun toString(): String {
        return StringBuilder().run {
            append('{'); append(latitude); append(','); append(longitude)
            append('±'); append(accuracyMeters); append('m')
            if (altitudeMeters != null) {
                append(" altitude:"); append(altitudeMeters)
                if (verticalAccuracyMeters != null) {
                    append('±'); append(verticalAccuracyMeters)
                }
                append('m')
            }
            append('}')
            toString()
        }
    }
}
