package app.grapheneos.geocoder

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeocodeJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(coordinates: String): GeocodeJson {
        return json.decodeFromString<GeocodeJson>(
            """{
                "type": "FeatureCollection",
                "geocoding": { "version": "0.1.0" },
                "features": [{
                    "type": "Feature",
                    "properties": { "geocoding": { "type": "city" } },
                    "geometry": { "type": "Point", "coordinates": $coordinates }
                }]
            }"""
        )
    }

    @Test
    fun rejectsMissingCoordinates() {
        for (coordinates in listOf("[]", "[0]")) {
            assertThrows(IllegalArgumentException::class.java) { decode(coordinates) }
        }
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        for (coordinates in listOf("[-180.1, 0]", "[180.1, 0]", "[0, -90.1]", "[0, 90.1]")) {
            assertThrows(IllegalArgumentException::class.java) { decode(coordinates) }
        }
    }

    @Test
    fun rejectsNonFiniteCoordinates() {
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            for (coordinates in listOf(listOf(value, 0.0), listOf(0.0, value))) {
                assertThrows(IllegalArgumentException::class.java) {
                    GeocodeJson.Feature.Geometry(coordinates, "Point")
                }
            }
        }
    }

    @Test
    fun rejectsNonFiniteJsonCoordinates() {
        for (value in listOf("NaN", "Infinity", "-Infinity", "1e309")) {
            for (coordinates in listOf("[$value, 0]", "[0, $value]")) {
                assertThrows(IllegalArgumentException::class.java) { decode(coordinates) }
            }
        }
    }

    @Test
    fun acceptsBoundaryCoordinates() {
        for (longitude in listOf(-180.0, 0.0, 180.0)) {
            for (latitude in listOf(-90.0, 0.0, 90.0)) {
                val result = decode("[$longitude, $latitude]")
                assertEquals(listOf(longitude, latitude), result.features!!.single().geometry.coordinates)
            }
        }
    }

    @Test
    fun acceptsOptionalAltitude() {
        val result = decode("[12.5, 48.5, 120]")
        assertEquals(listOf(12.5, 48.5, 120.0), result.features!!.single().geometry.coordinates)
    }

    @Test
    fun acceptsEmptyFeatureCollection() {
        val result = json.decodeFromString<GeocodeJson>(
            """{"type":"FeatureCollection","geocoding":{"version":"0.1.0"},"features":[]}"""
        )
        assertEquals(emptyList<GeocodeJson.Feature>(), result.features)
    }
}
