package app.grapheneos.networklocation

enum class AltitudeReference(val captureName: String) {
    UNSPECIFIED("unspecified"),
    MEAN_SEA_LEVEL("mean_sea_level"),
}

class PositioningData(
    val latitude: Double,
    val longitude: Double,
    /** Raw service accuracy scale in meters; its confidence level is not established. */
    val accuracyMeters: Int,
    /** Service height in the explicitly identified reference below. */
    val altitudeMeters: Int?,
    /** Raw service altitude uncertainty; not a verified one-sigma value. */
    val verticalAccuracyMeters: Int?,
    /** Uninterpreted optional service field. It is not known to be a percentage. */
    val rawConfidence: Int? = null,
    val altitudeReference: AltitudeReference = AltitudeReference.UNSPECIFIED,
) {
    val meanSeaLevelAltitudeMeters: Int?
        get() = altitudeMeters.takeIf { altitudeReference == AltitudeReference.MEAN_SEA_LEVEL }

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
