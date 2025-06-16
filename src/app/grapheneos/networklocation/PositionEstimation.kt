package app.grapheneos.networklocation

import kotlin.math.cos
import kotlin.math.pow

/**
 * WGS84 semi-major axis of the Earth in meters.
 * This is the Earth's equatorial radius as defined by the WGS84 ellipsoid.
 */
private const val EARTH_RADIUS = 6378137.0

data class GeoPoint(val latitude: Double, val longitude: Double, val altitude: Double?)

data class Point(val x: Double, val y: Double, val z: Double?)

/**
 * Estimate the distance in meters from the access point using the Log-Distance Path Loss Model.
 */
fun rssiToDistance(
    rssi: Double,
    pathLossExponent: Double,
    rssiAtOneMeter: Double,
): Double {
    return 10.0.pow((rssiAtOneMeter - rssi) / (10 * pathLossExponent))
}

fun List<Double>.median(): Double? {
    val sortedList = this.sorted()
    val size = sortedList.size
    if (size == 0) {
        return null
    }
    return if (size % 2 == 0) {
        (sortedList[size / 2 - 1] + sortedList[size / 2]) / 2.0
    } else {
        sortedList[size / 2]
    }
}

/**
 * Converts the GeoPoint to an ENU (East-North-Up) point relative to the given reference GeoPoint.
 * This method uses a simple Equirectangular approximation which assumes that the Earth is flat in
 * the vicinity of the reference point. While this approximation works reasonably well for short
 * distances, its accuracy diminishes over longer distances due to Earth’s curvature.
 *
 * TODO: Consider using a more accurate implementation in the future, which may be crucial if we
 *  ever locate using longer range technology than Wi-Fi and cellular.
 */
fun geoPointToEnuPoint(geoPoint: GeoPoint, refGeoPoint: GeoPoint): Point {
    val dLat = Math.toRadians(geoPoint.latitude - refGeoPoint.latitude)
    val dLon = Math.toRadians(geoPoint.longitude - refGeoPoint.longitude)
    val latRad = Math.toRadians(refGeoPoint.latitude)

    val x = EARTH_RADIUS * dLon * cos(latRad)
    val y = EARTH_RADIUS * dLat
    val z = refGeoPoint.altitude?.let { geoPoint.altitude?.minus(it) }

    return Point(x, y, z)
}

fun enuPointToGeoPoint(enuPoint: Point, refGeoPoint: GeoPoint): GeoPoint {
    val latRad = Math.toRadians(refGeoPoint.latitude)
    val dLat = enuPoint.y / EARTH_RADIUS
    val dLon = enuPoint.x / (EARTH_RADIUS * cos(latRad))

    val lat = refGeoPoint.latitude + Math.toDegrees(dLat)
    val lon = refGeoPoint.longitude + Math.toDegrees(dLon)
    val alt = enuPoint.z?.let { refGeoPoint.altitude?.plus(it) }

    return GeoPoint(lat, lon, alt)
}
