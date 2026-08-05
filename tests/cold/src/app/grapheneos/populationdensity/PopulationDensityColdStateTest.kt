package app.grapheneos.populationdensity

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

private const val STATE_PREFERENCES = "population_density_cold_test_state"
private const val PRIOR_SERVICE_STATE = "prior_service_state"

/** Saves, disables, and exactly restores the provider service around the cold-start test. */
@RunWith(AndroidJUnit4::class)
class PopulationDensityColdStateTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val componentName = ComponentName(context, PopulationDensityService::class.java)
    private val preferences =
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    @Test
    fun disableProviderService() {
        check(!preferences.contains(PRIOR_SERVICE_STATE)) {
            "prior provider service state has not been restored"
        }
        val priorServiceState = context.packageManager.getComponentEnabledSetting(componentName)
        check(preferences.edit().putInt(PRIOR_SERVICE_STATE, priorServiceState).commit()) {
            "failed to persist prior provider service state"
        }
        context.packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    @Test
    fun restoreProviderService() {
        check(preferences.contains(PRIOR_SERVICE_STATE)) {
            "prior provider service state is unavailable"
        }
        restorePriorServiceState()
    }

    @Test
    fun restoreProviderServiceIfNeeded() {
        if (!preferences.contains(PRIOR_SERVICE_STATE)) {
            return
        }
        restorePriorServiceState()
    }

    private fun restorePriorServiceState() {
        val priorServiceState = preferences.getInt(PRIOR_SERVICE_STATE, Int.MIN_VALUE)
        context.packageManager.setComponentEnabledSetting(
            componentName,
            priorServiceState,
            PackageManager.DONT_KILL_APP,
        )
        check(preferences.edit().remove(PRIOR_SERVICE_STATE).commit()) {
            "failed to clear prior provider service state"
        }
    }
}
