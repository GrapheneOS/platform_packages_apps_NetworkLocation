package app.grapheneos.networklocation

import android.location.Location
import android.location.LocationManager
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderRequest
import android.net.wifi.ScanResult
import android.os.SystemClock
import android.telephony.CellInfo
import android.util.Log
import app.grapheneos.networklocation.cell.CellPositioningServiceCache
import app.grapheneos.networklocation.cell.CellScanFailedException
import app.grapheneos.networklocation.cell.CellScannerUnavailableException
import app.grapheneos.networklocation.cell.CellTowerPositioningData
import app.grapheneos.networklocation.cell.CellTowerScanner
import app.grapheneos.networklocation.cell.Identifier
import app.grapheneos.networklocation.cell.selectCellObservations
import app.grapheneos.networklocation.interop.position_estimation.Coordinate
import app.grapheneos.networklocation.interop.position_estimation.Measurement
import app.grapheneos.networklocation.interop.position_estimation.Position
import app.grapheneos.networklocation.interop.position_estimation.PositionEstimation
import app.grapheneos.networklocation.wifi.Bssid
import app.grapheneos.networklocation.wifi.selectWifiObservations
import app.grapheneos.networklocation.wifi.WifiApPositioningData
import app.grapheneos.networklocation.wifi.WifiApRanger
import app.grapheneos.networklocation.wifi.WifiApScanner
import app.grapheneos.networklocation.wifi.WifiPositioningServiceCache
import app.grapheneos.networklocation.wifi.WifiRangerFailedException
import app.grapheneos.networklocation.wifi.WifiRangerUnavailableException
import app.grapheneos.networklocation.wifi.WifiScanFailedException
import app.grapheneos.networklocation.wifi.WifiScannerUnavailableException
import app.grapheneos.verboseLog
import java.io.IOException
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

private const val TAG = "LocationReportingTask"

class LocationReportingTask(
    private val provider: LocationProviderBase,
    private val request: ProviderRequest,
    private val wifiScanner: WifiApScanner,
    private val wifiRanger: WifiApRanger,
    private val wifiService: WifiPositioningServiceCache,
    private val cellScanner: CellTowerScanner,
    private val cellService: CellPositioningServiceCache,
) {
    suspend fun run() {
        val interval = max(1000, request.intervalMillis)
        verboseLog(TAG) { "started, interval: $interval ms" }
        while (true) {
            currentCoroutineContext().ensureActive()
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
            wifiScanner.scan(request.workSource)
        } catch (e: Exception) {
            when (e) {
                is WifiScannerUnavailableException, is WifiScanFailedException -> {

                    // stack trace is intentionally omitted, it doesn't contain useful info
                    Log.d(TAG, e.toString())
                    null
                }

                else -> throw e
            }
        }
        if (scanResults != null) {
            val location = estimateLocationWifi(scanResults)
            // Blocking lookup/native work may have completed after cancellation.
            currentCoroutineContext().ensureActive()
            verboseLog(TAG) { "estimateLocationWifi returned $location" }
            if (location != null) {
                provider.reportLocation(location)
                return
            }
        }
        currentCoroutineContext().ensureActive()
        verboseLog(TAG) { "falling back to cell-tower-based location" }
        val cellInfos = try {
            cellScanner.scan(request.workSource)
        } catch (e: Exception) {
            when (e) {
                is CellScannerUnavailableException, is CellScanFailedException -> {
                    // stack trace is intentionally omitted, it doesn't contain useful info
                    Log.d(TAG, e.toString())
                    return
                }

                else -> throw e
            }
        }
        val location = estimateLocationCell(cellInfos)
        currentCoroutineContext().ensureActive()
        verboseLog(TAG) { "estimateLocationCell returned $location" }
        if (location != null) {
            provider.reportLocation(location)
        }
    }

    private class EstimatedDistance(
        var distance: Double,
        /** Squared RSSI interval half-width, or (6 * RTT standard deviation)². */
        var sixSigmaSquared: Double,
    )

    private class PositionedScanResult(
        val scanResult: ScanResult,
        val positioningData: PositioningData,
        var estimatedDistance: EstimatedDistance?,
    )

    private class PositionedCellInfo(
        val cellInfo: CellInfo,
        val positioningData: PositioningData,
    )

    private fun estimateLocationWifi(scanResults: List<ScanResult>): Location? {
        val observations = selectWifiObservations(scanResults, SystemClock.elapsedRealtimeNanos() / 1000)
        if (observations.isEmpty()) return null
        var bestResults = HashMap<Bssid, PositionedScanResult>()

        val allPositioningData = mutableListOf<WifiApPositioningData>()

        try {
            val bssids = observations.entries.sortedByDescending { it.value.level }.map { it.key }

            // just in case the potential additional requests fail, add only cached ones
            allPositioningData.addAll(wifiService.getPositioningData(bssids, 0))

            // don't make additional requests when we have 15 of the closest results with valid
            // positioning data already
            val onlyCachedThreshold = 15
            val result = wifiService.getPositioningData(bssids, onlyCachedThreshold)
            allPositioningData.clear()
            allPositioningData.addAll(result)
        } catch (e: IOException) {
            Log.d(TAG, "unable to obtain positioning data: $e")
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "", e)
            }
        }

        for (data in allPositioningData) {
            val scanResult = observations[data.bssid]
            if (scanResult != null && data.positioningData != null) {
                bestResults[data.bssid] =
                    PositionedScanResult(scanResult, data.positioningData, null)
            }
        }
        if (bestResults.isEmpty()) {
            return null
        }

        // TODO: Use Wi-Fi RTT to estimate distance for APs that support it.
        val useWifiRtt = false
        if (useWifiRtt) {
            runBlocking {
                try {
                    // TODO: Note that the max results this can return is 10.
                    wifiRanger.range(bestResults.values.map { it.scanResult }, request.workSource)
                        .map { rangingResult ->
                            // TODO: Handle one-sided RTT correctly or stop using it.
                            // use absolute value to counter negative distance values in cases of
                            // close devices
                            val distanceMeters = rangingResult.distanceMm.absoluteValue / 1000.0
                            val sixSigmaSquaredMeters =
                                rangingResult.distanceStdDevMm.div(1000.0).times(6.0).pow(2.0)

                            // verboseLog(TAG) { "$rangingResult, $distanceMeters, $sixSigmaSquaredMeters" }

                            bestResults[rangingResult.macAddress.toString()]?.estimatedDistance =
                                EstimatedDistance(distanceMeters, sixSigmaSquaredMeters)
                        }
                } catch (e: Exception) {
                    when (e) {
                        is WifiRangerUnavailableException, is WifiRangerFailedException -> {
                            // stack trace is intentionally omitted, it doesn't contain useful info
                            Log.d(TAG, e.toString())
                        }

                        else -> throw e
                    }
                }
            }
        }

        // Only use RSSI estimation on results with non-null estimatedDistance (not set by Wi-Fi RTT).
        for (result in bestResults.values.filter { it.estimatedDistance == null }) {
            val (pathLossExponentLower, pathLossExponentHigher) = when (result.scanResult.band) {
                ScanResult.WIFI_BAND_24_GHZ -> Pair(2.0, 7.0)
                ScanResult.WIFI_BAND_5_GHZ -> Pair(2.0, 7.0)
                ScanResult.WIFI_BAND_6_GHZ -> Pair(2.0, 7.0)
                else -> continue
            }
            val rssiAtOneMeter = when (result.scanResult.band) {
                ScanResult.WIFI_BAND_24_GHZ -> -20.0
                ScanResult.WIFI_BAND_5_GHZ -> -35.0
                ScanResult.WIFI_BAND_6_GHZ -> -35.0
                else -> continue
            }
            val (distanceLowerBound, distanceHigherBound) = rssiDistanceBounds(
                result.scanResult.level.toDouble(),
                pathLossExponentLower,
                pathLossExponentHigher,
                rssiAtOneMeter,
            )
            val distance = (distanceHigherBound + distanceLowerBound) / 2.0
            val sixSigmaSquared = max(0.0, distance - distanceLowerBound).pow(2)
            result.estimatedDistance = EstimatedDistance(
                distance,
                sixSigmaSquared,
            )
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
            bestResults.values.map { it.positioningData.longitude }.medianLongitude() ?: return null,
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
            val normalizedEstimatedDistanceSixSigma =
                sqrt(estimatedDistance.sixSigmaSquared) / 3.0
            val xPositionVariance =
                (positioningData.accuracyMeters.toDouble() + normalizedEstimatedDistanceSixSigma).pow(2)
            val yPositionVariance =
                (positioningData.accuracyMeters.toDouble() + normalizedEstimatedDistanceSixSigma).pow(2)
            val zPositionVariance =
                ((positioningData.verticalAccuracyMeters?.toDouble()
                    ?: 0.0) + normalizedEstimatedDistanceSixSigma).pow(2)
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
        val accuracySixSigma = sqrt(max(result.x.sixSigmaSquared, result.y.sixSigmaSquared))
        // Convert from 6-sigma to 1-sigma
        loc.accuracy = accuracySixSigma.div(6.0).toFloat()
        estimatedGeoPoint.altitude?.let { estimatedAltitude ->
            loc.altitude = estimatedAltitude
            val verticalAccuracySixSigma = sqrt(result.z.sixSigmaSquared)
            // Convert from 6-sigma to 1-sigma
            loc.verticalAccuracyMeters = verticalAccuracySixSigma.div(6.0).toFloat()
        }
        return loc
    }

    private fun estimateLocationCell(cellInfos: List<CellInfo>): Location? {
        val bestResults = HashMap<Identifier, PositionedCellInfo>()

        val identifiers = selectCellObservations(cellInfos, SystemClock.elapsedRealtime())

        if (identifiers.isEmpty()) {
            return null
        }

        val allPositioningData = mutableListOf<CellTowerPositioningData>()

        try {
            // just in case the potential additional requests fail, add only cached ones
            allPositioningData.addAll(
                cellService.getPositioningData(
                    identifiers.keys.toList(),
                    0
                )
            )

            // don't make additional requests when we have 8 of the closest results with valid
            // positioning data already
            val onlyCachedThreshold = 8
            val result =
                cellService.getPositioningData(identifiers.keys.toList(), onlyCachedThreshold)
            allPositioningData.clear()
            allPositioningData.addAll(result)
        } catch (e: IOException) {
            Log.d(TAG, "unable to obtain positioning data: $e")
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "", e)
            }
        }

        for (data in allPositioningData) {
            val cellInfo = identifiers[data.identifier]
            if (cellInfo != null && data.positioningData != null) {
                bestResults[data.identifier] = PositionedCellInfo(cellInfo, data.positioningData)
            }
        }

        if (bestResults.isEmpty()) {
            return null
        }

        // use the median coordinates of nearby cells for protection against around 50%
        // or less of them being in a wildly incorrect location
        val refGeoPoint = GeoPoint(
            bestResults.values.map { it.positioningData.latitude }.median() ?: return null,
            bestResults.values.map { it.positioningData.longitude }.medianLongitude() ?: return null,
            bestResults.values.mapNotNull { it.positioningData.altitudeMeters }.let {
                if (it.isNotEmpty()) it.average() else null
            }
        )

        val measurements = bestResults.values.map { result ->
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
            // Treat the raw cell database scale as a coverage/proximity
            // tolerance. Its confidence level is not established.
            val xyPositionVariance = positioningData.accuracyMeters.toDouble().pow(2)
            val zPositionVariance = positioningData.verticalAccuracyMeters?.toDouble()?.pow(2)
            Measurement(
                Position(
                    Coordinate(
                        true,
                        position.x,
                        xyPositionVariance,
                    ),
                    Coordinate(
                        true,
                        position.y,
                        xyPositionVariance,
                    ),
                    Coordinate(
                        position.z != null,
                        position.z ?: 0.0,
                        zPositionVariance ?: 0.0,
                    ),
                ),
                // we can't estimate distance for cell towers
                0.0,
                0.0,
            )
        }

        val time = SystemClock.elapsedRealtime()
        val result = PositionEstimation.main(measurements.toTypedArray())
        verboseLog(TAG) { "estimateLocationCell took ${(SystemClock.elapsedRealtime() - time)} ms" }
        if (result == null) {
            return null
        }

        val loc = Location(LocationManager.NETWORK_PROVIDER)

        loc.elapsedRealtimeNanos =
            bestResults.values.minOf { it.cellInfo.timestampMillis }.milliseconds.inWholeNanoseconds
        val locationAgeMillis =
            SystemClock.elapsedRealtime() - loc.elapsedRealtimeNanos / 1_000_000L
        loc.time = max(0L, System.currentTimeMillis() - locationAgeMillis)

        val enuPoint = Point(result.x.value, result.y.value, result.z.value)
        val estimatedGeoPoint = enuPointToGeoPoint(enuPoint, refGeoPoint)
        loc.longitude = estimatedGeoPoint.longitude
        loc.latitude = estimatedGeoPoint.latitude
        // Cellular reports the square-root scale directly; Wi-Fi applies /6.
        loc.accuracy = sqrt(max(result.x.sixSigmaSquared, result.y.sixSigmaSquared)).toFloat()
        estimatedGeoPoint.altitude?.let { estimatedAltitude ->
            loc.altitude = estimatedAltitude
            loc.verticalAccuracyMeters = sqrt(result.z.sixSigmaSquared).toFloat()
        }
        return loc
    }
}
