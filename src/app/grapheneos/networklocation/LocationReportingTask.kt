package app.grapheneos.networklocation

import android.location.Location
import android.location.LocationManager
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderRequest
import android.net.wifi.ScanResult
import android.os.SystemClock
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.util.Log
import app.grapheneos.networklocation.cell.CellPositioningServiceCache
import app.grapheneos.networklocation.cell.CellScanFailedException
import app.grapheneos.networklocation.cell.CellScannerUnavailableException
import app.grapheneos.networklocation.cell.CellTowerPositioningData
import app.grapheneos.networklocation.cell.CellTowerScanner
import app.grapheneos.networklocation.cell.Identifier
import app.grapheneos.networklocation.cell.Technology
import app.grapheneos.networklocation.interop.position_estimation.Coordinate
import app.grapheneos.networklocation.interop.position_estimation.Measurement
import app.grapheneos.networklocation.interop.position_estimation.Position
import app.grapheneos.networklocation.interop.position_estimation.PositionEstimation
import app.grapheneos.networklocation.wifi.Bssid
import app.grapheneos.networklocation.wifi.WifiApPositioningData
import app.grapheneos.networklocation.wifi.WifiApRanger
import app.grapheneos.networklocation.wifi.WifiApScanner
import app.grapheneos.networklocation.wifi.WifiPositioningServiceCache
import app.grapheneos.networklocation.wifi.WifiScanFailedException
import app.grapheneos.networklocation.wifi.WifiScannerUnavailableException
import java.io.IOException
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
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
            verboseLog(TAG) { "estimateLocationWifi returned $location" }
            if (location != null) {
                provider.reportLocation(location)
                return
            }
        }
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
        val location = estimateLocationCellTowers(cellInfos)
        verboseLog(TAG) { "estimateLocationCellTowers returned $location" }
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
        val positioningData: app.grapheneos.networklocation.wifi.PositioningData,
        var estimatedDistance: EstimatedDistance?,
    )

    private class PositionedCellInfo(
        val cellInfo: CellInfo,
        val positioningData: app.grapheneos.networklocation.cell.PositioningData,
        var estimatedDistance: EstimatedDistance?,
    )

    private fun estimateLocationCellTowers(cellInfos: List<CellInfo>): Location? {
        val bestResults = HashMap<Identifier, PositionedCellInfo>()

        val identifiers = cellInfos.mapNotNull {
            val mcc = it.cellIdentity.mccString?.toIntOrNull() ?: return@mapNotNull null
            val mnc = it.cellIdentity.mncString?.toIntOrNull() ?: return@mapNotNull null

            val identifier = when (it.cellIdentity) {
                // TODO: support NR (5G) (also needs to be supported in the service)
                is CellIdentityLte -> {
                    val identity = it.cellIdentity as CellIdentityLte

                    Identifier(
                        Technology.LTE,
                        mcc,
                        mnc,
                        identity.tac.let { tac ->
                            if (tac != CellInfo.UNAVAILABLE) {
                                tac
                            } else {
                                return@mapNotNull null
                            }
                        },
                        identity.ci.let { cellId ->
                            if (cellId != CellInfo.UNAVAILABLE) {
                                cellId
                            } else {
                                return@mapNotNull null
                            }
                        }
                    )
                }

                is CellIdentityGsm -> {
                    val identity = it.cellIdentity as CellIdentityGsm

                    Identifier(
                        Technology.GSM,
                        mcc,
                        mnc,
                        identity.lac.let { lac ->
                            if (lac != CellInfo.UNAVAILABLE) {
                                lac
                            } else {
                                return@mapNotNull null
                            }
                        },
                        identity.cid.let { cellId ->
                            if (cellId != CellInfo.UNAVAILABLE) {
                                cellId
                            } else {
                                return@mapNotNull null
                            }
                        }
                    )
                }

                else -> return@mapNotNull null
            }

            Pair(identifier, it)
        }.toMap()

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
                bestResults[data.identifier] =
                    PositionedCellInfo(cellInfo, data.positioningData, null)
            }
        }
        if (bestResults.isEmpty()) {
            return null
        }

        for (result in bestResults.values) {
            // we have range instead of accuracy, so we need to use 0 distance with range set to xy
            // variance
            result.estimatedDistance = EstimatedDistance(
                0.0,
                0.0,
            )
        }

        // use the median coordinates of nearby towers for protection against around 50%
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
            // we have range instead of accuracy, so we set range to xy variance
            val xyPositionVariance =
                positioningData.rangeMeters.toDouble().pow(2) + estimatedDistance.variance
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
                estimatedDistance.distance,
                0.0,
            )
        }

        val time = SystemClock.elapsedRealtime()
        val result = PositionEstimation.main(measurements.toTypedArray())
        verboseLog(TAG) { "estimateLocationCellTowers took ${(SystemClock.elapsedRealtime() - time)} ms" }
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
        loc.accuracy = ((sqrt(result.x.variance) + sqrt(result.y.variance)) / 2.0).toFloat()
        estimatedGeoPoint.altitude?.let { estimatedAltitude ->
            loc.altitude = estimatedAltitude
            loc.verticalAccuracyMeters = sqrt(result.z.variance).toFloat()
        }
        return loc
    }

    private fun estimateLocationWifi(scanResults: List<ScanResult>): Location? {
        var bestResults = HashMap<Bssid, PositionedScanResult>()

        val allPositioningData = mutableListOf<WifiApPositioningData>()

        try {
            val bssids = scanResults.sortedByDescending { it.level }.map { it.BSSID }

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
            val xyPositionVariance =
                positioningData.accuracyMeters.toDouble().pow(2) + estimatedDistance.variance
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
                estimatedDistance.distance,
                0.0,
            )
        }

        val time = SystemClock.elapsedRealtime()
        val result = PositionEstimation.main(measurements.toTypedArray())
        verboseLog(TAG) { "estimateLocationWifi took ${(SystemClock.elapsedRealtime() - time)} ms" }
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
