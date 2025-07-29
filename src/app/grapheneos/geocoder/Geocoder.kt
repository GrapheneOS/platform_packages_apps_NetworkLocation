package app.grapheneos.geocoder

import android.location.Address
import java.util.Locale

interface Geocoder {
    fun forwardGeocode(
        locationName: String,
        lowerLeftLatitude: Double,
        lowerLeftLongitude: Double,
        upperRightLatitude: Double,
        upperRightLongitude: Double,
        maxResults: Int,
        preferredLocale: Locale,
    ): List<Address>

    fun reverseGeocode(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        preferredLocale: Locale,
    ): List<Address>
}
