package app.grapheneos.networklocation.wifi

import android.location.Location
import android.location.LocationManager
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderRequest
import android.net.wifi.ScanResult
import android.os.SystemClock
import android.util.Log
import app.grapheneos.networklocation.GeoPoint
import app.grapheneos.networklocation.Point
import app.grapheneos.networklocation.enuPointToGeoPoint
import app.grapheneos.networklocation.geoPointToEnuPoint
import app.grapheneos.networklocation.interop.position_estimation.Coordinate
import app.grapheneos.networklocation.interop.position_estimation.Measurement
import app.grapheneos.networklocation.interop.position_estimation.Position
import app.grapheneos.networklocation.interop.position_estimation.PositionEstimation
import app.grapheneos.networklocation.median
import app.grapheneos.networklocation.rssiToDistance
import app.grapheneos.networklocation.verboseLog
import java.io.IOException
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private const val TAG = "LocationReportingTask"

class LocationReportingTask(
    private val provider: LocationProviderBase,
    private val request: ProviderRequest,
    private val scanner: WifiApScanner,
    private val ranger: WifiApRanger,
    private val service: WifiPositioningServiceCache,
) {
    suspend fun run() {
        val interval = max(1000, request.intervalMillis)
        verboseLog(TAG) { "started, interval: $interval ms" }
        while (true) {
            val start = SystemClock.elapsedRealtime()
            step()
            val stepDuration = SystemClock.elapsedRealtime() - start
            if (stepDuration < interval) {
                val sleepDuration = interval - stepDuration
                verboseLog(TAG) { "sleeping for $sleepDuration ms" }
                delay(sleepDuration)
            } else {
                verboseLog(TAG) { "step took longer than interval ($interval ms): $stepDuration ms" }
            }
        }
    }

    private suspend fun step() {
        val scanResults = try {
            scanner.scan(request.workSource)
        } catch (e: Exception) {
            when (e) {
                is WifiScannerUnavailableException, is WifiScanFailedException -> {
                    // stack trace is intentionally omitted, it doesn't contain useful info
                    Log.d(TAG, e.toString())
                    return
                }

                else -> throw e
            }
        }
        val location = estimateLocation(scanResults)
        verboseLog(TAG) { "estimateLocation returned $location" }
        if (location != null) {
            provider.reportLocation(location)
        }
    }

    private class EstimatedDistance(
        var distance: Double,
        /** variance (1-sigma²) */
        var variance: Double,
    )

    private class PositionedScanResult(
        val scanResult: ScanResult,
        val positioningData: PositioningData,
        var estimatedDistance: EstimatedDistance?,
    )

    private fun estimateLocation(scanResults: List<ScanResult>): Location? {
        var bestResults = HashMap<Bssid, PositionedScanResult>()

        val allPositioningData = mutableListOf<WifiApPositioningData>()

        try {
            val bssids = scanResults.sortedByDescending { it.level }.map { it.BSSID }

            // just in case the potential additional requests fail, add only cached ones
            allPositioningData.addAll(service.getPositioningData(bssids, 0))

            // don't make additional requests when we have 15 of the closest results with valid
            // positioning data already
            val onlyCachedThreshold = 15
            val result = service.getPositioningData(bssids, onlyCachedThreshold)
            allPositioningData.clear()
            allPositioningData.addAll(result)
        } catch (e: IOException) {
            Log.d(TAG, "unable to obtain positioning data: $e")
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "", e)
            }
        }

        for (data in allPositioningData) {
            val scanResult = scanResults.find { it.BSSID == data.bssid }
            if (scanResult != null && data.positioningData != null) {
                bestResults[data.bssid] =
                    PositionedScanResult(scanResult, data.positioningData, null)
            }
        }
        if (bestResults.isEmpty()) {
            return null
        }

        runBlocking {
            // TODO: use Wi-Fi RTT to estimate distance with RSSI as a fallback
//            try {
//                // TODO: maybe handle the fact that the max results this can return is 10
//                ranger.range(bestResults.values.map { it.scanResult }, request.workSource)
//                    .map { rangingResult ->
//                        // TODO: handle one-sided RTT correctly
//                        // use absolute value to counter negative distance values in cases of
//                        // close devices
//                        val distanceMeters = rangingResult.distanceMm.absoluteValue / 1000.0
//
//                        bestResults[rangingResult.macAddress.toString()]?.estimatedDistance =
//                            distanceMeters
//                    }
//            } catch (e: Exception) {
//                when (e) {
//                    is WifiRangerUnavailableException, is WifiRangerFailedException -> {
//                        // stack trace is intentionally omitted, it doesn't contain useful info
//                        Log.d(TAG, e.toString())
//                    }
//
//                    else -> throw e
//                }
//                verboseLog(TAG) { "falling back to RSSI for estimating distance" }
                for (result in bestResults.values) {
                    val pathLossExponent = when (result.scanResult.band) {
                        ScanResult.WIFI_BAND_24_GHZ -> 4.0
                        ScanResult.WIFI_BAND_5_GHZ -> 3.75
                        ScanResult.WIFI_BAND_6_GHZ -> 3.75
                        else -> continue
                    }
                    val rssiAtOneMeter = when (result.scanResult.band) {
                        ScanResult.WIFI_BAND_24_GHZ -> -20.0
                        ScanResult.WIFI_BAND_5_GHZ -> -35.0
                        ScanResult.WIFI_BAND_6_GHZ -> -35.0
                        else -> continue
                    }
                    result.estimatedDistance = EstimatedDistance(
                        rssiToDistance(
                            result.scanResult.level.toDouble(),
                            pathLossExponent,
                            rssiAtOneMeter,
                        ),
                        max(0.0, rssiAtOneMeter - result.scanResult.level.toDouble()).pow(2)
                    )
                }
//            }
        }

        bestResults =
            bestResults.filterValues {
                it.estimatedDistance != null
            } as HashMap<Bssid, PositionedScanResult>

        if (bestResults.isEmpty()) {
            return null
        }

        // use the median coordinates of nearby APs for protection against around 50%
        // or less of them being in a wildly incorrect location
        val refGeoPoint = GeoPoint(
            bestResults.values.map { it.positioningData.latitude }.median() ?: return null,
            bestResults.values.map { it.positioningData.longitude }.median() ?: return null,
            bestResults.values.mapNotNull { it.positioningData.altitudeMeters }.let {
                if (it.isNotEmpty()) it.average() else null
            }
        )

        val measurements = bestResults.values.map { result ->
            val positioningData = result.positioningData
            val estimatedDistance = result.estimatedDistance!!
            // convert position to Cartesian coordinates
            val position = geoPointToEnuPoint(
                GeoPoint(
                    positioningData.latitude,
                    positioningData.longitude,
                    positioningData.altitudeMeters?.toDouble()
                ),
                refGeoPoint
            )
            // sqrt and divide by 3.0 so we can spread it out over all 3 dimensions equally
            val normalizedEstimatedDistanceStandardDeviation =
                sqrt(estimatedDistance.variance) / 3.0
            val xPositionVariance =
                (positioningData.accuracyMeters.toDouble() + normalizedEstimatedDistanceStandardDeviation).pow(2)
            val yPositionVariance =
                (positioningData.accuracyMeters.toDouble() + normalizedEstimatedDistanceStandardDeviation).pow(2)
            val zPositionVariance =
                ((positioningData.verticalAccuracyMeters?.toDouble()
                    ?: 0.0) + normalizedEstimatedDistanceStandardDeviation).pow(2)
            Measurement(
                Position(
                    Coordinate(
                        true,
                        position.x,
                        xPositionVariance,
                    ),
                    Coordinate(
                        true,
                        position.y,
                        yPositionVariance,
                    ),
                    Coordinate(
                        position.z != null,
                        position.z ?: 0.0,
                        zPositionVariance,
                    ),
                ),
                estimatedDistance.distance,
                0.0,
            )
        }

        val time = SystemClock.elapsedRealtime()
        val result = PositionEstimation.main(measurements.toTypedArray())
        verboseLog(TAG) { "estimateLocation took ${(SystemClock.elapsedRealtime() - time)} ms" }
        if (result == null) {
            return null
        }

        val loc = Location(LocationManager.NETWORK_PROVIDER)

        loc.elapsedRealtimeNanos =
            bestResults.values.minOf { it.scanResult.timestamp }.microseconds.inWholeNanoseconds
        val locationAgeMillis =
            SystemClock.elapsedRealtime() - loc.elapsedRealtimeNanos / 1_000_000L
        loc.time = max(0L, System.currentTimeMillis() - locationAgeMillis)

        val enuPoint = Point(result.x.value, result.y.value, result.z.value)
        val estimatedGeoPoint = enuPointToGeoPoint(enuPoint, refGeoPoint)
        loc.longitude = estimatedGeoPoint.longitude
        loc.latitude = estimatedGeoPoint.latitude
        loc.accuracy = ((sqrt(result.x.variance) + sqrt(result.y.variance)) / 2.0).toFloat()
        estimatedGeoPoint.altitude?.let { estimatedAltitude ->
            loc.altitude = estimatedAltitude
            loc.verticalAccuracyMeters = sqrt(result.z.variance).toFloat()
        }
        return loc
    }
}
