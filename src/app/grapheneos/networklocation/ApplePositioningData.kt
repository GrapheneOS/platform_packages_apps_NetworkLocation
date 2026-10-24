package app.grapheneos.networklocation

import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocation

/** Validate raw units before an invalid entry can influence the local origin. */
private fun ALSLocation.hasValidPosition(): Boolean =
    latitude in -9_000_000_000L..9_000_000_000L &&
        longitude in -18_000_000_000L..18_000_000_000L && accuracy >= 0

/** Decode service units without assigning an undocumented confidence level. */
internal fun convertAppleWifiPositioningData(pd: ALSLocation?): PositioningData? {
    // Message presence is distinct from an explicitly supplied (0, 0) location.
    if (pd == null || !pd.hasValidPosition()) return null
    // Requests use WIFI_ALTITUDE_SCALE_10_TO_THE_2. Keep the existing integer
    // meter representation, but do not mistake an absent optional field for 0.
    // MSL is an explicit interpretation supported by replay and Apple's public
    // altitude API, not a published contract for this private database field.
    val altitudeMeters = if (!pd.hasAltitude() || pd.altitude == -100 || pd.altitude == -50000) {
        null
    } else {
        pd.altitude / 100
    }
    val verticalAccuracyMeters = if (!pd.hasVerticalAccuracy() || pd.verticalAccuracy < 0 || altitudeMeters == null) {
        null
    } else {
        pd.verticalAccuracy / 100
    }
    return PositioningData(pd.latitude * 0.000_000_01, pd.longitude * 0.000_000_01,
        pd.accuracy, altitudeMeters, verticalAccuracyMeters,
        if (pd.hasConfidence()) pd.confidence else null, AltitudeReference.MEAN_SEA_LEVEL)
}

internal fun convertAppleCellPositioningData(pd: ALSLocation?): PositioningData? {
    if (pd == null || !pd.hasValidPosition()) return null
    // Cell altitude remains unavailable; horizontal scale and confidence are raw.
    return PositioningData(pd.latitude * 0.000_000_01, pd.longitude * 0.000_000_01,
        pd.accuracy, null, null, if (pd.hasConfidence()) pd.confidence else null)
}
