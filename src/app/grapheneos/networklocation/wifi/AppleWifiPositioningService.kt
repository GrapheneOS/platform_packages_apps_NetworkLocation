package app.grapheneos.networklocation.wifi

import android.app.AppGlobals
import android.content.Context
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_DISABLED
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_APPLE
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.os.SystemClock
import android.util.Log
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocation
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequestResponse
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequestResponse.ALSMeta
import app.grapheneos.networklocation.proto.AppleWpsProtos.WirelessAP
import app.grapheneos.networklocation.verboseLog
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.grapheneos.tls.ModernTLSSocketFactory

private const val TAG = "AppleWps"
private const val EXTRA_VERBOSE_TAG = "AppleWpsVV"

private const val MAX_REQUEST_NETWORKS = 40
private const val THROTTLED_ADDITIONAL_RESULTS = 8
private const val MAX_ADDITIONAL_RESULTS = 100
private val THROTTLE_COOLDOWN = 10.seconds
private const val THROTTLE_TRIGGER_RESULT_COUNT = 17

class AppleWifiPositioningService : WifiPositioningService {

    private val tlsSocketFactory = ModernTLSSocketFactory()

    private var throttleTriggeredElapsedRealtime = -THROTTLE_COOLDOWN

    @Throws(IOException::class)
    override fun fetchNearbyApPositioningData(bssids: List<String>): List<WifiApPositioningData> {
        val requestBssids = bssids.take(MAX_REQUEST_NETWORKS)
        val result = HashMap<Bssid, PositioningData?>()

        val response = fetchInner(
            requestBssids,
            if (SystemClock.elapsedRealtime().milliseconds - throttleTriggeredElapsedRealtime >= THROTTLE_COOLDOWN) {
                MAX_ADDITIONAL_RESULTS
            } else {
                THROTTLED_ADDITIONAL_RESULTS
            }
        )

        if (response.wirelessApsCount >= THROTTLE_TRIGGER_RESULT_COUNT) {
            verboseLog(TAG) { "response AP count (${response.wirelessApsCount}) triggered throttle" }
            throttleTriggeredElapsedRealtime = SystemClock.elapsedRealtime().milliseconds
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
    private fun fetchInner(bssids: List<Bssid>, maxAdditionalResults: Int): ALSLocationRequestResponse {
        val (url, enforceModernTls) = getServerUrl()

        verboseLog(TAG) {"request bssids: $bssids"}

        val connection = url.openConnection() as HttpsURLConnection
        try {
            if (enforceModernTls) {
                connection.sslSocketFactory = tlsSocketFactory
            }
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "locationd/2960.0.57 CFNetwork/3826.500.111.1.1 Darwin/24.4.0")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true

            DataOutputStream(connection.outputStream).use { outputStream ->
                val locale = "en-US_US".toByteArray()
                val identifier = "com.apple.locationd".toByteArray()
                val version = "15.4.24E248".toByteArray()
                val requestCode = 1

                outputStream.writeShort(1) // hardcoded
                outputStream.writeShort(locale.size)
                outputStream.write(locale)
                outputStream.writeShort(identifier.size)
                outputStream.write(identifier)
                outputStream.writeShort(version.size)
                outputStream.write(version)
                outputStream.writeInt(requestCode)

                val protobufData = ALSLocationRequestResponse.newBuilder().run {
                    addAllWirelessAps(bssids.map {
                        WirelessAP.newBuilder()
                            .setMacId(it)
                            .build()
                    })
                    // should be at least 1, otherwise it defaults to around 400
                    setNumberOfSurroundingWifis(max(1, maxAdditionalResults))
                    val wifiBands = listOf(
                        ALSLocationRequestResponse.WifiBand.K2DOT4GHZ,
                        ALSLocationRequestResponse.WifiBand.K5GHZ,
                    )
                    addAllSurroundingWifiBands(wifiBands)
                    setWifiAltitudeScale(ALSLocationRequestResponse.WifiAltitudeScale.KWIFI_ALTITUDE_SCALE_10_TO_THE_2)
                    setMeta(
                        ALSMeta.newBuilder()
                            .setSoftwareBuild("macOS15.4/24E248")
                            .setProductId("arm64")
                            .build()
                    )

                    build()
                }

                outputStream.writeInt(protobufData.getSerializedSize())
                protobufData.writeTo(outputStream)
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("non-200 response code: $responseCode")
            }
            val ignoredHeaderSize = 10
            val protoBytes: ByteArray = connection.inputStream.use { inputStream ->
                inputStream.skipNBytes(ignoredHeaderSize.toLong())
                inputStream.readAllBytes()
            }
            val response = ALSLocationRequestResponse.parseFrom(protoBytes)
            verboseLog(TAG) {
                "response AP list size: ${response.wirelessApsCount}, " +
                        "byte size: ${protoBytes.size + ignoredHeaderSize}"
            }
            if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
                Log.v(EXTRA_VERBOSE_TAG, "response headers: " + connection.headerFields)
                response.wirelessApsList.forEachIndexed { i, ap ->
                    Log.v(EXTRA_VERBOSE_TAG, "response[$i]: bssid: ${ap.macId}, " +
                            "positioning data: ${convertPositioningData(ap.location)}")
                }
            }
            return response
        } finally {
            connection.disconnect()
        }
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

    @Throws(IOException::class)
    private fun getServerUrl(): Pair<URL, Boolean> {
        val context: Context = AppGlobals.getInitialApplication()
        val setting = NETWORK_LOCATION_SETTING.get(context)
        return when (setting) {
            NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY ->
                Pair(URL("https://gs-loc.apple.grapheneos.org/clls/wloc"), true)
            NETWORK_LOCATION_SERVER_APPLE ->
                Pair(URL("https://gs-loc.apple.com/clls/wloc"), false)
            NETWORK_LOCATION_DISABLED ->
                // network location can be disabled by the user at any point
                throw IOException("network location setting became disabled")
            else ->
                throw IllegalStateException("unexpected URL setting: $setting")
        }
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
