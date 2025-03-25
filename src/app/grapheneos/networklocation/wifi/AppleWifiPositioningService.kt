package app.grapheneos.networklocation.wifi

import android.app.AppGlobals
import android.content.Context
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_DISABLED
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_APPLE
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SERVER_GRAPHENEOS_PROXY
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.util.Log
import app.grapheneos.networklocation.proto.AppleWpsProtos
import app.grapheneos.networklocation.verboseLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max
import kotlin.Pair
import org.grapheneos.tls.ModernTLSSocketFactory

private const val TAG = "AppleWps"
private const val EXTRA_VERBOSE_TAG = "AppleWpsVV"

class AppleWifiPositioningService : WifiPositioningService {

    private val tlsSocketFactory = ModernTLSSocketFactory()

    @Throws(IOException::class)
    override fun fetchNearbyApPositioningData(
        bssids: List<String>,
        maxResultsHint: Int,
        withPositioningDataThreshold: Int
    ): List<WifiApPositioningData> {
        val result = HashMap<Bssid, PositioningData?>()

        val requestBssids = bssids.toMutableList()

        while (requestBssids.isNotEmpty()) {
            // the service only allows up to 4 bssids per request
            val currentRequestBssids = requestBssids.take(4)
            requestBssids.removeAll(currentRequestBssids)

            val response = fetchInner(currentRequestBssids, maxResultsHint)

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
    private fun fetchInner(bssids: List<Bssid>, maxResultsHint: Int): AppleWpsProtos.Response {
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
                val header = byteArrayOf(
                    0x00, 0x01,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x01,
                    0x00, 0x00,
                    0x00,
                )

                val body = AppleWpsProtos.Request.newBuilder().run {
                    addAllBssidWrapper(bssids.map {
                        AppleWpsProtos.BssidWrapper.newBuilder()
                            .setBssid(it)
                            .build()
                    })
                    // should be at least 1, otherwise it defaults to around 400
                    setMaxAdditionalResults(max(1, maxResultsHint - bssids.size))
                    build()
                }

                outputStream.write(header)
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
            // the api returns -1 or -500 for unknown altitude
            if ((it == -1L) || (it == -500L)) null else it
        }
        val verticalAccuracyMeters = pd.verticalAccuracyMeters.let {
            // the api returns -1 for unknown vertical accuracy (altitude accuracy)
            if (it == -1L || altitudeMeters == null) null else it
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
