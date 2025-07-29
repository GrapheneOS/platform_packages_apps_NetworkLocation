package app.grapheneos.networklocation.wifi

import android.util.Log
import app.grapheneos.networklocation.ApplePositioningService
import app.grapheneos.networklocation.PositioningData
import app.grapheneos.networklocation.Throttle
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocation
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequest
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationResponse
import app.grapheneos.networklocation.proto.AppleWpsProtos.WirelessAP
import app.grapheneos.verboseLog
import java.io.IOException
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

private const val TAG = "AppleWps"
private const val EXTRA_VERBOSE_TAG = "AppleWpsVV"

private const val MAX_REQUEST_NETWORKS = 40
private const val THROTTLED_ADDITIONAL_RESULTS = 8
private const val MAX_ADDITIONAL_RESULTS = 100
private const val THROTTLE_TRIGGER_RESULT_COUNT = 17

class AppleWifiPositioningService : WifiPositioningService {
    private val applePs = ApplePositioningService()

    private var throttle = Throttle(10.seconds)

    @Throws(IOException::class)
    override fun fetchNearbyApPositioningData(bssids: List<String>): List<WifiApPositioningData> {
        val requestBssids = bssids.take(MAX_REQUEST_NETWORKS)
        val result = HashMap<Bssid, PositioningData?>()

        val response = fetchInner(
            requestBssids,
            if (throttle.isThrottled()) {
                THROTTLED_ADDITIONAL_RESULTS
            } else {
                MAX_ADDITIONAL_RESULTS
            }
        )

        if (response.wirelessApsCount >= THROTTLE_TRIGGER_RESULT_COUNT) {
            verboseLog(TAG) { "response AP count (${response.wirelessApsCount}) triggered throttle" }
            throttle.trigger()
        }

        for (ap in response.wirelessApsList) {
            val apBssid = normalizeBssid(ap.macId)
            if (apBssid == null) {
                Log.w(TAG, "invalid bssid ${ap.macId}")
                continue
            }
            result.putIfAbsent(apBssid, convertPositioningData(ap.location))
        }
        for (bssid in requestBssids) {
            result.putIfAbsent(bssid, null)
        }

        return result.map { WifiApPositioningData(it.key, it.value) }
    }

    @Throws(IOException::class)
    private fun fetchInner(bssids: List<Bssid>, maxAdditionalResults: Int): ALSLocationResponse {
        verboseLog(TAG) {"request bssids: $bssids"}

        val request = ALSLocationRequest.newBuilder().run {
            addAllWirelessAps(bssids.map {
                WirelessAP.newBuilder()
                    .setMacId(it)
                    .build()
            })
            // should be at least 1, otherwise it defaults to around 400
            setNumberOfSurroundingWifis(max(1, maxAdditionalResults))
            val wifiBands = listOf(
                ALSLocationRequest.WifiBand.K2DOT4GHZ,
                ALSLocationRequest.WifiBand.K5GHZ,
            )
            addAllSurroundingWifiBands(wifiBands)
            setWifiAltitudeScale(ALSLocationRequest.WifiAltitudeScale.KWIFI_ALTITUDE_SCALE_10_TO_THE_2)
            setMeta(applePs.macosMeta)

            build()
        }

        val response = applePs.fetch(request)
        verboseLog(TAG) {
            "response AP list size: ${response.wirelessApsCount}"
        }
        if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
            response.wirelessApsList.forEachIndexed { i, ap ->
                Log.v(
                    EXTRA_VERBOSE_TAG, "response[$i]: bssid: ${ap.macId}, " +
                            "positioning data: ${convertPositioningData(ap.location)}"
                )
            }
        }
        return response
    }

    private fun convertPositioningData(pd: ALSLocation): PositioningData? {
        if (pd.latitude == -18000000000) {
            return null
        }
        val latitude = pd.latitude * 0.000_000_01;
        val longitude = pd.longitude * 0.000_000_01
        val altitudeMeters = pd.altitude.let {
            // the api returns -100 or -50000 for unknown altitude
            if ((it == -100) || (it == -50000)) null else it / 100
        }
        val verticalAccuracyMeters = pd.verticalAccuracy.let {
            // the api returns -100 for unknown vertical accuracy (altitude accuracy)
            if (it == -100 || altitudeMeters == null) null else it / 100
        }
        return PositioningData(latitude, longitude, pd.accuracy, altitudeMeters, verticalAccuracyMeters)
    }
}

// AppleWps doesn't include leading zeros in BSSID octets
private fun normalizeBssid(s: String): Bssid? {
    val octets = s.split(":")
    if (octets.size != 6) {
        return null
    }
    val b = StringBuilder()
    for (i in 0 until 6) {
        if (i != 0) {
            b.append(':')
        }
        val octet = octets[i]
        val len = octet.length
        if (len == 1) {
            b.append('0')
        } else if (len != 2) {
            return null
        }
        b.append(octet)
    }
    return if (b.length == s.length) s else b.toString()
}
