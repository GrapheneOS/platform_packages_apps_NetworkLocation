package app.grapheneos.populationdensity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.provider.IPopulationDensityProvider
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import kotlin.time.Duration.Companion.nanoseconds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "PopulationDensityColdStartTest"

/** Enables, binds, and queries the installed provider service in a dedicated cold process. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class PopulationDensityColdStartTest {
    @get:Rule val serviceRule = ServiceTestRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun coldServiceInitializationAndFirstQuerySucceed() {
        val startElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val componentName = ComponentName(context, PopulationDensityService::class.java)
        context.packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        val serviceIntent = Intent(context, PopulationDensityService::class.java)
        val binder = serviceRule.bindService(serviceIntent)
        val provider = IPopulationDensityProvider.Stub.asInterface(binder)
        val s2CellId = provider.getCoarsenedS2CellId(TEST_QUERY_LATITUDE, TEST_QUERY_LONGITUDE)
        val elapsedTime =
            (SystemClock.elapsedRealtimeNanos() - startElapsedRealtimeNanos).nanoseconds

        Log.i(
            TAG,
            "cold service initialization and first query took " +
                "${elapsedTime.inWholeMilliseconds} ms",
        )
        assertContainingDatabaseCell(
            TEST_QUERY_LATITUDE,
            TEST_QUERY_LONGITUDE,
            s2CellId,
            TEST_QUERY_EXPECTED_LEVEL,
        )
    }
}
