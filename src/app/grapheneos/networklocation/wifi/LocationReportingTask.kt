package app.grapheneos.networklocation.wifi

import android.location.Location
import android.location.LocationManager
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderRequest
import android.net.wifi.ScanResult
import android.os.SystemClock
import android.util.Log
import app.grapheneos.networklocation.EstimatedPosition
import app.grapheneos.networklocation.GeoPoint
import app.grapheneos.networklocation.MAX_MEASUREMENTS_FOR_RANSAC_TRILATERATION
import app.grapheneos.networklocation.Measurement
import app.grapheneos.networklocation.Point
import app.grapheneos.networklocation.enuPointToGeoPoint
import app.grapheneos.networklocation.estimatePosition
import app.grapheneos.networklocation.geoPointToEnuPoint
import app.grapheneos.networklocation.median
import app.grapheneos.networklocation.verboseLog
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Duration.Companion.microseconds

private const val TAG = "LocationReportingTask"

class LocationReportingTask(private val provider: LocationProviderBase,
                            private val request: ProviderRequest,
                            private val scanner: WifiApScanner,
                            private val service: WifiPositioningServiceCache,
) {
    suspend fun run() {
        val interval = max(1000, request.intervalMillis)
        verboseLog(TAG) {"started, interval: $interval ms"}
        while (true) {
            val start = SystemClock.elapsedRealtime()
            step()
            val stepDuration = SystemClock.elapsedRealtime() - start
            if (stepDuration < interval) {
                val sleepDuration = interval - stepDuration
                verboseLog(TAG) {"sleeping for $sleepDuration ms"}
                delay(sleepDuration)
            } else {
                verboseLog(TAG) {"step took longer than interval ($interval ms): $stepDuration ms"}
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
        verboseLog(TAG) {"estimateLocation returned $location"}
        if (location != null) {
            provider.reportLocation(location)
        }
    }

    private class PositionedScanResult(
        val scanResult: ScanResult,
        val positioningData: PositioningData,
    )

    private fun estimateLocation(scanResults: List<ScanResult>): Location? {
        val bestResults = mutableListOf<PositionedScanResult>()
        for (scanResult in scanResults.sortedByDescending { it.level }) {
            val bssid: Bssid = scanResult.BSSID

            val positioningData = try {
                // don't make additional network request when there's at least 5 results already
                val onlyCached = bestResults.size >= 5
                service.getPositioningData(bssid, onlyCached)
            } catch (e: IOException) {
                Log.d(TAG, "unable to obtain positioning data: $e")
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "", e)
                }
                continue
            }
            if (positioningData == null) {
                continue
            }
            bestResults.add(PositionedScanResult(scanResult, positioningData))
            if (bestResults.size == MAX_MEASUREMENTS_FOR_RANSAC_TRILATERATION) {
                break
            }
        }
        if (bestResults.isEmpty()) {
            return null
        }

        // use the median coordinates of nearby APs for protection against around 50%
        // or less of them being in a wildly incorrect location
        val refGeoPoint = GeoPoint(
            bestResults.map { it.positioningData.latitude }.median(),
            bestResults.map { it.positioningData.longitude }.median(),
            bestResults.mapNotNull { it.positioningData.altitudeMeters }.let {
                if (it.isNotEmpty()) it.average() else null
            }
        )

        val measurements = bestResults.map { result ->
            val positioningData = result.positioningData
            // convert position to Cartesian coordinates
            val position = geoPointToEnuPoint(
                GeoPoint(
                    positioningData.latitude,
                    positioningData.longitude,
                    positioningData.altitudeMeters?.toDouble()
                ),
                refGeoPoint
            )
            val xyPositionVariance = positioningData.accuracyMeters.toDouble().pow(2)
            val zPositionVariance = positioningData.verticalAccuracyMeters?.toDouble()
            val rssi = result.scanResult.level.toDouble()
            Measurement(position, xyPositionVariance, zPositionVariance, rssi)
        }

        val time = SystemClock.elapsedRealtime()
        val result: EstimatedPosition? = estimatePosition(measurements,
            // accuracy should be at the 68th percentile confidence level
            0.68)
        verboseLog(TAG) {"estimateLocation took ${(SystemClock.elapsedRealtime() - time)} ms"}
        if (result == null) {
            return null
        }

        val loc = Location(LocationManager.NETWORK_PROVIDER)

        loc.elapsedRealtimeNanos = bestResults.minOf { it.scanResult.timestamp }.microseconds.inWholeNanoseconds
        val locationAgeMillis = SystemClock.elapsedRealtime() - loc.elapsedRealtimeNanos / 1_000_000L
        loc.time = max(0L, System.currentTimeMillis() - locationAgeMillis)

        val enuPoint = Point(result.position.x, result.position.y, result.position.z)
        val estimatedGeoPoint = enuPointToGeoPoint(enuPoint, refGeoPoint)
        loc.longitude = estimatedGeoPoint.longitude
        loc.latitude = estimatedGeoPoint.latitude
        loc.accuracy = result.xzAccuracyRadius.toFloat()
        result.position.z?.let { loc.altitude = it }
        result.zAccuracyRadius?.let { loc.verticalAccuracyMeters = it.toFloat() }
        return loc
    }
}
