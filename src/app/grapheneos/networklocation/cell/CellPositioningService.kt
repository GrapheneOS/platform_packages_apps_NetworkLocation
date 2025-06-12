package app.grapheneos.networklocation.cell

import app.grapheneos.networklocation.PositioningData
import java.io.IOException

interface CellPositioningService {
    @Throws(IOException::class)
    fun fetchNearbyTowerPositioningData(identifiers: List<Identifier>): List<CellTowerPositioningData>
}

enum class Standard {
    NR,
    LTE,
    WCDMA,
    GSM,
}

data class Identifier(
    val standard: Standard,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cellId: Long,
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
