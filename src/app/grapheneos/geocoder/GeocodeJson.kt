package app.grapheneos.geocoder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * see https://github.com/geocoders/geocodejson-spec/tree/master/draft for spec
 */
@Serializable
// https://github.com/Kotlin/kotlinx.serialization/issues/993
@Suppress("PROVIDED_RUNTIME_TOO_LOW")
data class GeocodeJson(
    val type: String,
    val geocoding: Geocoding,
    val features: List<Feature>? = null
) {
    @Serializable
    data class Geocoding(
        val version: String,
        val licence: String? = null,
        val attribution: String? = null,
        val query: String? = null,
    )

    @Serializable
    data class Feature(
        val properties: Properties,
        val type: String,
        val geometry: Geometry,
    ) {
        @Serializable
        data class Properties(
            val geocoding: Geocoding,
        ) {
            @Serializable
            data class Geocoding(
                val type: String,
                val accuracy: Int? = null,
                val label: String? = null,
                val name: String? = null,
                @SerialName("housenumber")
                val houseNumber: String? = null,
                val street: String? = null,
                val locality: String? = null,
                val postcode: String? = null,
                val city: String? = null,
                val district: String? = null,
                val county: String? = null,
                val state: String? = null,
                val country: String? = null,
                @SerialName("country_code")
                val countryCode: String? = null,
                val admin: Map<String, String>? = null,
                val geohash: String? = null,
                val extra: Map<String, String>? = null,
            )
        }

        @Serializable
        data class Geometry(
            val coordinates: List<Double>,
            val type: String,
        )
    }
}
