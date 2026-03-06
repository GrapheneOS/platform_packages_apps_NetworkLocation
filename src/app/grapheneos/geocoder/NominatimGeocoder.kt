package app.grapheneos.geocoder

import android.app.AppGlobals
import android.content.Context
import android.ext.settings.GeocoderSettings.GEOCODER_DISABLED
import android.ext.settings.GeocoderSettings.GEOCODER_SERVER_GRAPHENEOS
import android.ext.settings.GeocoderSettings.GEOCODER_SERVER_OPENSTREETMAP
import android.ext.settings.GeocoderSettings.GEOCODER_SETTING
import android.location.Address
import android.os.Bundle
import android.util.Log
import androidx.core.os.toPersistableBundle
import app.grapheneos.verboseLog
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.grapheneos.tls.ModernTLSSocketFactory

private const val TAG = "NominatimGeocoder"
private const val EXTRA_VERBOSE_TAG = "NominatimGeocoderVV"

/** Version which should get incremented when request behavior changes */
private const val USER_AGENT_VERSION = 1

class NominatimGeocoder : Geocoder {
    private val tlsSocketFactory = ModernTLSSocketFactory()

    @Throws(IOException::class)
    override fun forwardGeocode(
        locationName: String,
        lowerLeftLatitude: Double,
        lowerLeftLongitude: Double,
        upperRightLatitude: Double,
        upperRightLongitude: Double,
        maxResults: Int,
        preferredLocale: Locale
    ): List<Address> {
        val urlSuffix =
            "/search?format=geocodejson&q=${
                URLEncoder.encode(
                    locationName,
                    StandardCharsets.UTF_8
                )
            }&limit=$maxResults&addressdetails=1&extratags=1".let {
                // add bounding box parameter if it's set for this request
                if (lowerLeftLatitude == 0.0 && lowerLeftLongitude == 0.0 && upperRightLatitude == 0.0 && upperRightLongitude == 0.0) {
                    it
                } else {
                    "$it&viewbox=$lowerLeftLongitude,$lowerLeftLatitude,$upperRightLongitude,$upperRightLatitude&bounded=1"
                }
            }
        return fetch(urlSuffix, maxResults, preferredLocale)
    }

    @Throws(IOException::class)
    override fun reverseGeocode(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        preferredLocale: Locale,
    ): List<Address> {
        val urlSuffix =
            // Nominatim doesn't support returning more than 1 reverse geocoding result
            "/reverse?format=geocodejson&lat=${latitude}&lon=${longitude}&zoom=18&addressdetails=1&extratags=1"
        return fetch(urlSuffix, maxResults, preferredLocale)
    }

    @Throws(IOException::class)
    private fun fetch(
        urlSuffix: String,
        expectedMaxResults: Int,
        preferredLocale: Locale
    ): List<Address> {
        val (baseUrl, enforceModernTls) = getServerBaseUrl()
        val url = URL("$baseUrl$urlSuffix")

        val connection = url.openConnection() as HttpsURLConnection
        val responseBytes = try {
            if (enforceModernTls) {
                connection.sslSocketFactory = tlsSocketFactory
            }
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Language", preferredLocale.toLanguageTag())
            connection.setRequestProperty(
                "User-Agent",
                "GrapheneOS geocoder $USER_AGENT_VERSION"
            )
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                throw IOException("non-200 response code: $responseCode")
            }
            connection.inputStream.use { inputStream ->
                inputStream.readAllBytes()
            }
        } finally {
            connection.disconnect()
        }

        val responseString = responseBytes.decodeToString()

        // occurs when reverse geocode coordinates are in the middle of the ocean, presumably
        // because there were no results
        if (responseString == "{\"error\":\"Unable to geocode\"}") {
            return emptyList()
        }

        val response = try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<GeocodeJson>(responseString)
        } catch (e: SerializationException) {
            throw IOException("failed to deserialize response", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("deserialized response was not of expected type", e)
        }
        verboseLog(TAG) {
            "byte size: ${responseBytes.size}"
        }
        if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
            Log.v(EXTRA_VERBOSE_TAG, "response headers: " + connection.headerFields)
        }
        val responseFeaturesSize = response.features?.size ?: 0
        verboseLog(TAG) {
            "response features size: $responseFeaturesSize"
        }

        if (responseFeaturesSize > expectedMaxResults) {
            Log.w(
                TAG,
                "response features size ($responseFeaturesSize) was more than expected ($expectedMaxResults), truncating"
            )
        }
        val result = mutableListOf<Address>()
        for (feature in response.features?.take(expectedMaxResults) ?: emptyList()) {
            val extra = feature.properties.geocoding.extra?.toMutableMap()
            // we don't know which locale was actually used, so we just assume it was the one
            // we requested
            val address = Address(preferredLocale)
            address.latitude = feature.geometry.coordinates[1]
            address.longitude = feature.geometry.coordinates[0]
            with(feature.properties.geocoding) {
                address.postalCode = this.postcode
                address.featureName = this.name
                address.countryName = this.country
                address.countryCode = this.countryCode
                address.adminArea = this.state
                address.subAdminArea = this.county
                address.locality = this.city ?: this.locality
                address.subLocality = this.district
                address.thoroughfare = this.street
                address.subThoroughfare = this.houseNumber
                val addressLine0 = StringBuilder().run {
                    address.subThoroughfare?.let { this.append("$it ") }
                    address.thoroughfare?.let { this.append(it) }
                    address.subLocality?.let { this.append(", $it") }
                    address.locality?.let { this.append(", $it") }
                    address.subAdminArea?.let { this.append(", $it") }
                    address.adminArea?.let { this.append(", $it") }
                    address.postalCode?.let {
                        if (address.adminArea == null) {
                            this.append(",")
                        }
                        this.append(" $it")
                    }
                    address.countryName?.let { this.append(", $it") }
                }?.removePrefix(", ")
                addressLine0?.let { address.setAddressLine(0, it.toString()) }
            }
            address.url = extra?.remove("website")
            address.phone = extra?.remove("phone")
            address.extras = extra?.let { Bundle(it.toPersistableBundle()) }
            result.add(address)
        }
        if (Log.isLoggable(EXTRA_VERBOSE_TAG, Log.VERBOSE)) {
            result.forEachIndexed { i, address ->
                Log.v(
                    EXTRA_VERBOSE_TAG, "address[$i]: $address"
                )
            }
        }
        return result
    }

    @Throws(IOException::class)
    private fun getServerBaseUrl(): Pair<URL, Boolean> {
        val context: Context = AppGlobals.getInitialApplication()
        val setting = GEOCODER_SETTING.get(context)
        return when (setting) {
            GEOCODER_SERVER_GRAPHENEOS -> Pair(URL("https://nominatim.grapheneos.org"), true)

            GEOCODER_SERVER_OPENSTREETMAP -> Pair(URL("https://nominatim.openstreetmap.org"), true)

            GEOCODER_DISABLED ->
                // geocoder can be disabled by the user at any point
                throw IOException("geocoder setting became disabled")

            else -> throw IllegalStateException("unexpected URL setting: $setting")
        }
    }
}
