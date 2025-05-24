package app.grapheneos.networklocation

import android.content.Context
import android.ext.settings.NetworkLocationSettings
import android.ext.settings.NetworkLocationSettings.NETWORK_LOCATION_SETTING
import android.location.provider.LocationProviderBase
import android.location.provider.ProviderProperties
import android.location.provider.ProviderRequest
import android.os.Bundle
import android.util.Log
import app.grapheneos.networklocation.wifi.AppleWifiPositioningService
import app.grapheneos.networklocation.wifi.WifiApRanger
import app.grapheneos.networklocation.wifi.WifiApScanner
import app.grapheneos.networklocation.wifi.WifiPositioningServiceCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "LocationProviderImpl"

// Reuse cache across NetworkLocationProvider instances to reduce latency and network usage. Note
// that the cache is periodically cleaned up to prevent recording long location history, see
// WifiPositioningServiceCache.scheduleClean()
private val wifiPositioningServiceCache by lazy {
    WifiPositioningServiceCache(AppleWifiPositioningService())
}

class LocationProviderImpl(private val context: Context)
        : LocationProviderBase(context, TAG, createProviderProperties()) {

    private var locationReportingJob: Job? = null

    override fun onSetRequest(request: ProviderRequest) {
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        if (isVerbose) Log.v(TAG, "onSetRequest: $request")

        synchronized(this) {
            val prevJob = locationReportingJob
            if (prevJob != null) {
                if (isVerbose) Log.v(TAG, "cancelling previous locationReportingJob")
                runBlocking {
                    prevJob.cancelAndJoin()
                }
                if (isVerbose) Log.v(TAG, "canceled previous locationReportingJob")
                locationReportingJob = null
            }

            if (!request.isActive) {
                return
            }

            val task = LocationReportingTask(
                this,
                request,
                WifiApScanner(context),
                WifiApRanger(context),
                wifiPositioningServiceCache,
            )
            locationReportingJob = CoroutineScope(Dispatchers.IO).launch {
                task.run()
            }
        }
    }

    override fun onFlush(callback: OnFlushCompleteCallback) {
        verboseLog(TAG) {"onFlush"}
        // Location batching is not supported by this location provider. Location batching is meant to
        // be used by providers that can estimate location even when the application processor (AP)
        // is suspended.
        callback.onFlushComplete()
    }

    override fun onSendExtraCommand(command: String, extras: Bundle?) {
        Log.d(TAG, "onSendExtraCommand: $command, extras: $extras")
    }

    fun updateIsAllowedState() {
        val setting = NETWORK_LOCATION_SETTING.get(context)
        val isEnabled = setting != NetworkLocationSettings.NETWORK_LOCATION_DISABLED
        setAllowed(isEnabled)
    }
}

// make sure to manually keep in sync with Google's network location provider properties
// for app compatibility
private fun createProviderProperties(): ProviderProperties {
    return ProviderProperties.Builder().run {
        setPowerUsage(ProviderProperties.POWER_USAGE_MEDIUM)
        setAccuracy(ProviderProperties.ACCURACY_FINE)
        setHasNetworkRequirement(true)
        setHasAltitudeSupport(true)
        build()
    }
}
