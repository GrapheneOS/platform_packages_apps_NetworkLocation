package app.grapheneos.populationdensity

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.android.internal.os.BackgroundThread
import java.util.concurrent.Executor

private const val TAG = "PopulationDensityService"

/** Supplies the provider lifecycle operations used by [PopulationDensityService]. */
interface PopulationDensityServiceProvider {
    /** Returns the Binder published by the service. */
    fun getServiceBinder(): IBinder?

    /** Attempts to initialize the provider's data source on a background thread. */
    fun prewarmDataSource()
}

/** Hosts the population density provider and schedules best-effort eager initialization. */
class PopulationDensityService(
    private val populationDensityProviderFactory: (Context) -> PopulationDensityServiceProvider =
        { context -> PopulationDensityProviderImpl(context.applicationContext) },
    private val initializationExecutor: Executor = BackgroundThread.getExecutor(),
) : Service() {
    private lateinit var populationDensityProvider: PopulationDensityServiceProvider

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        populationDensityProvider = populationDensityProviderFactory(this)
        schedulePrewarm()
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind: $intent")
        schedulePrewarm()
        return populationDensityProvider.getServiceBinder()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun schedulePrewarm() {
        initializationExecutor.execute(populationDensityProvider::prewarmDataSource)
    }
}
