package app.grapheneos.populationdensity

import com.android.internal.location.geometry.S2CellIdUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal const val TEST_QUERY_LATITUDE = 40.7128
internal const val TEST_QUERY_LONGITUDE = -74.0060
internal const val TEST_QUERY_EXPECTED_LEVEL = 12

/** Verifies that a database cell is a valid ancestor containing the queried coordinate. */
internal fun assertContainingDatabaseCell(
    latitude: Double,
    longitude: Double,
    s2CellId: Long,
    expectedLevel: Int? = null,
) {
    val level = S2CellIdUtils.getLevel(s2CellId)
    val coordinateDescription = "($latitude, $longitude)"
    assertTrue(
        "invalid S2 level $level for coordinates $coordinateDescription",
        level in 0..MAX_DATABASE_S2_LEVEL,
    )
    if (expectedLevel != null) {
        assertEquals(
            "unexpected S2 level for coordinates $coordinateDescription",
            expectedLevel,
            level,
        )
    }

    val queriedLeafCellId = S2CellIdUtils.fromLatLngDegrees(latitude, longitude)
    assertEquals(
        "returned S2 cell does not contain coordinates $coordinateDescription",
        s2CellId,
        S2CellIdUtils.getParent(queriedLeafCellId, level),
    )
}
