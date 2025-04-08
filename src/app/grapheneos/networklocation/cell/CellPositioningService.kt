package app.grapheneos.networklocation.cell

import java.io.IOException

interface CellPositioningService {
    @Throws(IOException::class)
    fun fetchNearbyTowerPositioningData(identifiers: List<Identifier>): List<CellTowerPositioningData>
}

enum class Technology {
    NR,
    LTE,
    GSM,
}

data class Identifier(
    val technology: Technology,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cellId: Int,
)

class CellTowerPositioningData(
    val identifier: Identifier,
    val positioningData: PositioningData?,
) {
    override fun toString(): String {
        val pd = positioningData
        return if (pd == null) "$identifier (no positioning data)" else "${identifier}_$pd"
    }
}

class PositioningData(
    val latitude: Double,
    val longitude: Double,
    val rangeMeters: Int,
    val altitudeMeters: Int?,
    val verticalAccuracyMeters: Int?,
) {
    override fun toString(): String {
        return StringBuilder().run {
            append('{'); append(latitude); append(','); append(longitude)
            append('±'); append(rangeMeters); append('m')
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
