package app.grapheneos.populationdensity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

private const val FUZZ_ITERATION_COUNT = 2_000

/** Exercises the packaged database through the installed APK and JNI library. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class PopulationDensityLocalDataSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataSource by lazy { PopulationDensityLocalDataSource(context) }

    @Test
    fun knownLocationsReturnExpectedDensityLevels() {
        val locations =
            arrayOf(
                Triple(40.7128, -74.0060, 12),
                Triple(27.9881, 86.9250, 10),
                Triple(23.4162, 25.6628, 5),
                Triple(0.0, -150.0, 2),
                Triple(43.4799, -110.7624, 12),
                Triple(38.5733, -109.5498, 12),
                Triple(71.2906, -156.7887, 12),
                Triple(36.3013, -116.4146, 7),
            )

        for ((latitude, longitude, expectedLevel) in locations) {
            val s2CellId = dataSource.getCoarseLocationCellId(latitude, longitude)
            assertContainingDatabaseCell(latitude, longitude, s2CellId, expectedLevel)
        }
    }

    @Test
    fun geographicBoundariesReturnValidLevels() {
        val boundaries =
            arrayOf(
                90.0 to 0.0,
                -90.0 to 0.0,
                0.0 to 0.0,
                0.0 to 180.0,
                0.0 to -180.0,
            )

        for ((latitude, longitude) in boundaries) {
            val s2CellId = dataSource.getCoarseLocationCellId(latitude, longitude)
            assertContainingDatabaseCell(latitude, longitude, s2CellId)
        }
    }

    @Test
    fun deterministicGlobalQueriesReturnValidLevels() {
        val random = Random(0)

        repeat(FUZZ_ITERATION_COUNT) {
            val latitude = random.nextDouble(MIN_LATITUDE, MAX_LATITUDE)
            val longitude = random.nextDouble(MIN_LONGITUDE, MAX_LONGITUDE)
            val s2CellId = dataSource.getCoarseLocationCellId(latitude, longitude)
            assertContainingDatabaseCell(latitude, longitude, s2CellId)
        }
    }

    @Test
    fun outOfRangeCoordinatesAreRejected() {
        val invalidCoordinates =
            arrayOf(
                90.5 to 0.0,
                -90.5 to 0.0,
                0.0 to 180.5,
                0.0 to -180.5,
                Double.NaN to 0.0,
                0.0 to Double.NaN,
            )

        for ((latitude, longitude) in invalidCoordinates) {
            assertThrows(IllegalArgumentException::class.java) {
                dataSource.getCoarseLocationCellId(latitude, longitude)
            }
        }
    }
}
