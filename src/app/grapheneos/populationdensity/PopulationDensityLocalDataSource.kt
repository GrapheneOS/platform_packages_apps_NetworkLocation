package app.grapheneos.populationdensity

import android.content.Context
import android.content.res.Resources
import app.grapheneos.networklocation.R
import com.android.internal.location.geometry.S2CellIdUtils
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Queries the packaged population density database through a process-global native engine. */
class PopulationDensityLocalDataSource(
    context: Context,
) : PopulationDensityDataSource {
    init {
        ensureInitialized(context)
    }

    override fun getCoarseLocationCellId(
        latitude: Double,
        longitude: Double,
    ): Long {
        require(latitude in MIN_LATITUDE..MAX_LATITUDE) {
            "latitude must be in range [$MIN_LATITUDE, $MAX_LATITUDE]"
        }
        require(longitude in MIN_LONGITUDE..MAX_LONGITUDE) {
            "longitude must be in range [$MIN_LONGITUDE, $MAX_LONGITUDE]"
        }

        val s2CellId = S2CellIdUtils.fromLatLngDegrees(latitude, longitude)
        val coarsenedS2CellId = nativeQuery(s2CellId)
        check(coarsenedS2CellId != S2_CELL_ID_NONE) {
            "population density query returned an invalid S2 cell ID"
        }
        return coarsenedS2CellId
    }

    companion object {
        private val initialized = AtomicBoolean(false)
        private val initializationLock = Any()

        init {
            System.loadLibrary("network_location_population_density_rust")
        }

        /** Initializes the process-global query engine exactly once. */
        private fun ensureInitialized(context: Context) {
            if (initialized.get()) {
                return
            }
            synchronized(initializationLock) {
                if (initialized.get()) {
                    return
                }

                val assetFileDescriptor =
                    try {
                        context.resources.openRawResourceFd(R.raw.population_density_database)
                    } catch (exception: Resources.NotFoundException) {
                        throw IOException(
                            "unable to open uncompressed population density database resource",
                            exception,
                        )
                    }
                assetFileDescriptor.use {
                    val offset = assetFileDescriptor.startOffset
                    val length = assetFileDescriptor.length
                    if (offset < 0 || length <= 0) {
                        throw IOException(
                            "invalid population density database range: offset $offset, " +
                                "length $length",
                        )
                    }

                    val fileDescriptor = assetFileDescriptor.parcelFileDescriptor.fd
                    if (!nativeInit(fileDescriptor, offset, length)) {
                        // A JNI exception normally supersedes this defensive fallback.
                        throw IOException("failed to initialize native query engine")
                    }
                    initialized.set(true)
                }
            }
        }

        @JvmStatic
        private external fun nativeInit(
            fileDescriptor: Int,
            offset: Long,
            length: Long,
        ): Boolean

        @JvmStatic
        private external fun nativeQuery(s2CellId: Long): Long
    }
}
