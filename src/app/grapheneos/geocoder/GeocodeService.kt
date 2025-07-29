package app.grapheneos.geocoder

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

private const val TAG = "GeocodeService"

class GeocodeService : Service() {
    private lateinit var geocodeProvider: GeocodeProviderImpl

    override fun onCreate() {
        Log.d(TAG, "onCreate")
        geocodeProvider = GeocodeProviderImpl(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind: $intent")
        return geocodeProvider.binder
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
    }
}
