package app.grapheneos.networklocation

import kotlin.math.abs

fun main() {
    for (intercept in listOf(-20.0, -35.0)) {
        for (rssi in -127..0) {
            val (lower, upper) = rssiDistanceBounds(rssi.toDouble(), 2.0, 7.0, intercept)
            check(lower > 0.0 && lower <= upper)
            check(upper.isFinite())
            if (rssi <= intercept) {
                check(lower == rssiToDistance(rssi.toDouble(), 7.0, intercept))
                check(upper == rssiToDistance(rssi.toDouble(), 2.0, intercept))
            } else {
                check(lower < upper && upper < 1.0)
            }
        }
    }
    check(normalizeLongitude(12.3456789) == 12.3456789)
    check(normalizeLongitude(180.1) < -179.89)
    check(abs(normalizeLongitude(-180.1) - 179.9) < 1e-10)
    check(emptyList<Double>().medianLongitude() == null)
    check(abs(listOf(0.0, 179.99, -179.99, 179.98, -179.98).medianLongitude()!!) > 179.9)
    val origin = GeoPoint(35.0, 179.9999, null)
    val observed = GeoPoint(35.0, -179.9999, null)
    val point = geoPointToEnuPoint(observed, origin)
    check(abs(point.x) < 20.0)
    val restored = enuPointToGeoPoint(point, origin)
    check(abs(restored.longitude - observed.longitude) < 1e-9)
    check(abs(restored.latitude - observed.latitude) < 1e-9)
    check(restored.altitude == null)
    println("Coordinate checks passed: ordered RSSI bounds, dateline origin, round trip, normalization, unknown altitude")
}
