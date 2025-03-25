package app.grapheneos.networklocation.wifi

import android.app.AppGlobals
import android.content.Context
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_DISABLED
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_APPLE
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.os.SystemClock
import android.util.Log
import app.grapheneos.networklocation.proto.AppleWpsProtos
import app.grapheneos.networklocation.proto.AppleWpsProtos.DeviceInfo
import app.grapheneos.networklocation.verboseLog
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

private const val THROTTLED_ADDITIONAL_RESULTS = 8
private const val MAX_ADDITIONAL_RESULTS = 100
private val THROTTLE_COOLDOWN = 10.seconds
private const val THROTTLE_TRIGGER_RESULT_COUNT = 17

class AppleWifiPositioningService : WifiPositioningService {

    private val tlsSocketFactory = ModernTLSSocketFactory()

    private var throttleTriggeredElapsedRealtime = -THROTTLE_COOLDOWN

    @Throws(IOException::class)
    override fun fetchNearbyApPositioningData(
        bssids: List<String>,
        withPositioningDataThreshold: Int
    ): List<WifiApPositioningData> {
        val result = HashMap<Bssid, PositioningData?>()

        val requestBssids = bssids.toMutableList()

        while (requestBssids.isNotEmpty()) {
            // the service only allows up to 4 bssids per request
            val currentRequestBssids = requestBssids.take(4)
            requestBssids.removeAll(currentRequestBssids)

            val response = fetchInner(
                currentRequestBssids,
                if (SystemClock.elapsedRealtime().milliseconds - throttleTriggeredElapsedRealtime >= THROTTLE_COOLDOWN) {
                    MAX_ADDITIONAL_RESULTS
                } else {
                    THROTTLED_ADDITIONAL_RESULTS
                }
            )

            if (response.accessPointCount >= THROTTLE_TRIGGER_RESULT_COUNT) {
                verboseLog(TAG) { "response AP count (${response.accessPointCount}) triggered throttle" }
                throttleTriggeredElapsedRealtime = SystemClock.elapsedRealtime().milliseconds
            }

            for (ap in response.accessPointList) {
                val apBssid = normalizeBssid(ap.bssid)
                if (apBssid == null) {
                    Log.w(TAG, "invalid bssid ${ap.bssid}")
                    continue
                }
                requestBssids.remove(apBssid)
                result.putIfAbsent(apBssid, convertPositioningData(ap.positioningData))
            }
            for (bssid in currentRequestBssids) {
                if (!result.any { it.key == bssid }) {
                    Log.d(
                        TAG,
                        "server didn't return positioning data for one of the requested bssids $bssid"
                    )
                    result.putIfAbsent(bssid, null)
                }
            }

            if (bssids.count { result[it] != null } >= withPositioningDataThreshold) {
                break
            }
        }

        return result.map { WifiApPositioningData(it.key, it.value) }
    }

    @Throws(IOException::class)
    private fun fetchInner(bssids: List<Bssid>, maxAdditionalResults: Int): AppleWpsProtos.Response {
        val (url, enforceModernTls) = getServerUrl()

        verboseLog(TAG) {"request bssids: $bssids"}

        val connection = url.openConnection() as HttpsURLConnection
        try {
            if (enforceModernTls) {
                connection.sslSocketFactory = tlsSocketFactory
            }
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "_/_")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "locationd/2956.0.6 CFNetwork/3826.400.120 Darwin/24.3.0")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true

            connection.outputStream.use { outputStream ->
                val locale = "en-US_US"
                val identifier = "com.apple.locationd"
                val version = "15.3.2.24D81"

                outputStream.write(byteArrayOf(0x00, 0x01, 0x00))
                outputStream.write(locale.length)
                outputStream.write(locale.toByteArray())
                outputStream.write(0x00)
                outputStream.write(identifier.length)
                outputStream.write(identifier.toByteArray())
                outputStream.write(0x00)
                outputStream.write(version.length)
                outputStream.write(version.toByteArray())
                outputStream.write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00))

                val body = AppleWpsProtos.Request.newBuilder().run {
                    addAllBssidWrapper(bssids.map {
                        AppleWpsProtos.BssidWrapper.newBuilder()
                            .setBssid(it)
                            .build()
                    })
                    // should be at least 1, otherwise it defaults to around 400
                    setMaxAdditionalResults(max(1, maxAdditionalResults))
                    addAllUnknown31(listOf(
                        // seems to control what frequency nearby APs it returns
                        // 1 means all?, while 2 means 5 (and 6? unconfirmed.) only.
                        1,
                        // unknown
                        2,
                    ))
                    setUnknown32(2)
                    setDeviceInfo(
                        DeviceInfo.newBuilder()
                            .setOsVersion("macOS15.3.2/24D81")
                            .setArch("arm64")
                            .build()
                    )

                    build()
                }

                body.writeDelimitedTo(outputStream)
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("non-200 response code: $responseCode")
            }
            val ignoredHeaderSize = 10
            val protoBytes: ByteArray = connection.inputStream.use { inputStream ->
                inputStream.skip(ignoredHeaderSize.toLong())
                inputStream.readAllBytes()
            }
            val response = AppleWpsProtos.Response.parseFrom(protoBytes)
            verboseLog(TAG) {
                "response AP list size: ${response.accessPointCount}, " +
                        "byte size: ${protoBytes.size + ignoredHeaderSize}"
            }
            if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
                Log.v(EXTRA_VERBOSE_TAG, "response headers: " + connection.headerFields)
                response.accessPointList.forEachIndexed { i, ap ->
                    Log.v(EXTRA_VERBOSE_TAG, "response[$i]: bssid: ${ap.bssid}, " +
                            "positioning data: ${convertPositioningData(ap.positioningData)}")
                }
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun convertPositioningData(pd: AppleWpsProtos.PositioningData): PositioningData? {
        if (pd.latitude == -18000000000) {
            return null
        }
        val latitude = pd.latitude * 0.000_000_01;
        val longitude = pd.longitude * 0.000_000_01
        val altitudeMeters = pd.altitudeMeters.let {
            // the api returns -100 or -50000 for unknown altitude
            if ((it == -100L) || (it == -50000L)) null else it / 100
        }
        val verticalAccuracyMeters = pd.verticalAccuracyMeters.let {
            // the api returns -100 for unknown vertical accuracy (altitude accuracy)
            if (it == -100L || altitudeMeters == null) null else it / 100
        }
        return PositioningData(latitude, longitude, pd.accuracyMeters, altitudeMeters, verticalAccuracyMeters)
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
