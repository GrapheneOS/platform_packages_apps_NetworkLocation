package app.grapheneos.networklocation

import android.app.AppGlobals
import android.content.Context
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_APPLE
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_APPLE_CHINA
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_DISABLED
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_GRAPHENEOS_APPLE_PROXY
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.util.Log
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequest
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationRequest.ALSMeta
import app.grapheneos.networklocation.proto.AppleWpsProtos.ALSLocationResponse
import app.grapheneos.verboseLog
import java.io.DataOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.grapheneos.tls.ModernTLSSocketFactory

private const val TAG = "ApplePs"
private const val EXTRA_VERBOSE_TAG = "ApplePsVV"

class ApplePositioningService {
    val macosMeta: ALSMeta = ALSMeta.newBuilder()
        .setSoftwareBuild("macOS15.4/24E248")
        .setProductId("arm64")
        .build()

    private val tlsSocketFactory = ModernTLSSocketFactory()

    @Throws(IOException::class)
    fun fetch(request: ALSLocationRequest): ALSLocationResponse {
        val url = getServerUrl()

        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = tlsSocketFactory
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty(
                "User-Agent",
                "locationd/2960.0.57 CFNetwork/3826.500.111.1.1 Darwin/24.4.0"
            )
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

                outputStream.writeInt(request.serializedSize)
                request.writeTo(outputStream)
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                throw IOException("non-200 response code: $responseCode")
            }
            val ignoredHeaderSize = 10
            val protoBytes: ByteArray = connection.inputStream.use { inputStream ->
                inputStream.skipNBytes(ignoredHeaderSize.toLong())
                inputStream.readAllBytes()
            }
            val response = ALSLocationResponse.parseFrom(protoBytes)
            verboseLog(TAG) {
                "byte size: ${protoBytes.size + ignoredHeaderSize}"
            }
            if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
                Log.v(EXTRA_VERBOSE_TAG, "response headers: " + connection.headerFields)
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun getServerUrl(): URL {
        val context: Context = AppGlobals.getInitialApplication()
        val setting = NETWORK_LOCATION_SETTING.get(context)
        return when (setting) {
            NETWORK_LOCATION_APPLE_CHINA ->
                "https://gs-loc-cn.apple.com/clls/wloc"
            NETWORK_LOCATION_GRAPHENEOS_APPLE_PROXY ->
                "https://gs-loc.apple.grapheneos.org/clls/wloc"
            NETWORK_LOCATION_APPLE ->
                "https://gs-loc.apple.com/clls/wloc"
            NETWORK_LOCATION_DISABLED ->
                // network location can be disabled by the user at any point
                throw IOException("network location setting became disabled")
            else ->
                throw IllegalStateException("unexpected URL setting: $setting")
        }
    }
}