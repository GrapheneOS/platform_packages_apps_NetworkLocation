package app.grapheneos.populationdensity

import android.content.Context
import android.location.provider.PopulationDensityProviderBase
import android.os.Handler
import android.os.IBinder
import android.os.OutcomeReceiver
import android.os.SystemClock
import android.util.Log
import app.grapheneos.verboseLog
import com.android.internal.os.BackgroundThread
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.nanoseconds

private const val TAG = "PopulationDensityProviderImpl"
private const val VERY_VERBOSE_TAG = "PopulationDensityProviderImplVV"

/** Serves coarsening cells from a background-initialized population density data source. */
class PopulationDensityProviderImpl(
    context: Context,
    private val initializationExecutor: Executor = BackgroundThread.getExecutor(),
    private val populationDensityDataSourceFactory: () -> PopulationDensityDataSource = {
        PopulationDensityLocalDataSource(context)
    },
) : PopulationDensityProviderBase(context, TAG),
    PopulationDensityServiceProvider {
    private val initializationWaitTimeoutHandler = Handler.createAsync(context.mainLooper)

    private sealed interface DataSourceState {
        data object NotReady : DataSourceState

        class Initializing : DataSourceState {
            val completion = CompletableFuture<Ready>()
            val waitTimeoutArmed = AtomicBoolean()
        }

        data class Ready(
            val dataSource: PopulationDensityDataSource,
        ) : DataSourceState

        data class Failed(
            val error: Throwable,
        ) : DataSourceState
    }

    private val dataSourceState = AtomicReference<DataSourceState>(DataSourceState.NotReady)

    override fun getServiceBinder(): IBinder? = binder

    /** Initializes the data source for eager background prewarming. */
    override fun prewarmDataSource() {
        val initializingState = beginInitialization() ?: return
        initializeDataSource(initializingState)
    }

    private fun beginInitialization(): DataSourceState.Initializing? {
        while (true) {
            val currentState = dataSourceState.get()
            if (currentState is DataSourceState.Initializing ||
                currentState is DataSourceState.Ready
            ) {
                return null
            }
            val initializingState = DataSourceState.Initializing()
            if (dataSourceState.compareAndSet(currentState, initializingState)) {
                return initializingState
            }
        }
    }

    private fun initializeDataSource(initializingState: DataSourceState.Initializing) {
        try {
            val dataSource = populationDensityDataSourceFactory()
            val readyState = DataSourceState.Ready(dataSource)
            check(dataSourceState.compareAndSet(initializingState, readyState)) {
                "population density data source state changed during initialization"
            }
            initializingState.completion.complete(readyState)
        } catch (exception: IOException) {
            dataSourceState.compareAndSet(initializingState, DataSourceState.Failed(exception))
            Log.e(TAG, "population density data source initialization failed", exception)
            initializingState.completion.completeExceptionally(exception)
        } catch (error: LinkageError) {
            dataSourceState.compareAndSet(initializingState, DataSourceState.Failed(error))
            Log.wtf(TAG, "population density native library initialization failed", error)
            initializingState.completion.completeExceptionally(error)
        } catch (throwable: Throwable) {
            dataSourceState.compareAndSet(initializingState, DataSourceState.NotReady)
            initializingState.completion.completeExceptionally(throwable)
            throw throwable
        }
    }

    override fun onGetDefaultCoarseningLevel(callback: OutcomeReceiver<Int, Throwable>): Unit =
        callback.onError(
            UnsupportedOperationException("default coarsening level is not supported"),
        )

    override fun onGetCoarsenedS2Cells(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        numAdditionalCells: Int,
        callback: OutcomeReceiver<LongArray, Throwable>,
    ) {
        verboseLog(TAG) {
            "onGetCoarsenedS2Cells numAdditionalCells: $numAdditionalCells"
        }

        if (numAdditionalCells < 0) {
            callback.onError(
                IllegalArgumentException("numAdditionalCells must be non-negative"),
            )
            return
        }

        if (latitudeDegrees !in MIN_LATITUDE..MAX_LATITUDE ||
            longitudeDegrees !in MIN_LONGITUDE..MAX_LONGITUDE
        ) {
            callback.onError(IllegalArgumentException("coordinates are out of bounds"))
            return
        }

        queryDataSource(latitudeDegrees, longitudeDegrees, callback)
    }

    private fun queryDataSource(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        callback: OutcomeReceiver<LongArray, Throwable>,
    ) {
        while (true) {
            when (val currentState = dataSourceState.get()) {
                is DataSourceState.Ready -> {
                    queryReadyDataSource(currentState, latitudeDegrees, longitudeDegrees, callback)
                    return
                }

                is DataSourceState.Failed -> {
                    callback.onError(currentState.error)
                    return
                }

                is DataSourceState.Initializing -> {
                    queryWhenInitializationCompletes(
                        currentState,
                        latitudeDegrees,
                        longitudeDegrees,
                        callback,
                    )
                    return
                }

                DataSourceState.NotReady -> {
                    val initializingState = beginInitialization() ?: continue
                    queryWhenInitializationCompletes(
                        initializingState,
                        latitudeDegrees,
                        longitudeDegrees,
                        callback,
                    )
                    scheduleDataSourceInitialization(initializingState)
                    return
                }
            }
        }
    }

    private fun queryWhenInitializationCompletes(
        initializingState: DataSourceState.Initializing,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        callback: OutcomeReceiver<LongArray, Throwable>,
    ) {
        initializingState.completion.whenComplete { _, error ->
            try {
                if (error == null) {
                    queryDataSource(latitudeDegrees, longitudeDegrees, callback)
                } else {
                    callback.onError(error)
                }
            } catch (exception: RuntimeException) {
                Log.wtf(TAG, "population density deferred result delivery failed", exception)
            }
        }
        if (initializingState.waitTimeoutArmed.compareAndSet(false, true)) {
            val timeout =
                Runnable {
                    initializingState.completion.completeExceptionally(
                        TimeoutException("population density initialization timed out"),
                    )
                }
            initializationWaitTimeoutHandler.postDelayed(
                timeout,
                PopulationDensityProviderBase.QUERY_TIMEOUT_MILLIS,
            )
            initializingState.completion.whenComplete { _, _ ->
                initializationWaitTimeoutHandler.removeCallbacks(timeout)
            }
        }
    }

    private fun scheduleDataSourceInitialization(
        initializingState: DataSourceState.Initializing,
    ) {
        try {
            initializationExecutor.execute { initializeDataSource(initializingState) }
        } catch (exception: RejectedExecutionException) {
            dataSourceState.compareAndSet(initializingState, DataSourceState.Failed(exception))
            Log.e(TAG, "population density data source initialization scheduling failed", exception)
            initializingState.completion.completeExceptionally(exception)
        }
    }

    private fun queryReadyDataSource(
        readyState: DataSourceState.Ready,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        callback: OutcomeReceiver<LongArray, Throwable>,
    ) {
        val dataSource = readyState.dataSource
        val isVerbose = Log.isLoggable(TAG, Log.VERBOSE)
        val queryStartElapsedRealtimeNanos =
            if (isVerbose) SystemClock.elapsedRealtimeNanos() else 0L
        val coarsenedS2CellId =
            try {
                dataSource.getCoarseLocationCellId(latitudeDegrees, longitudeDegrees)
            } catch (exception: RuntimeException) {
                dataSourceState.compareAndSet(readyState, DataSourceState.Failed(exception))
                Log.wtf(TAG, "population density query failed", exception)
                callback.onError(exception)
                return
            } catch (error: LinkageError) {
                dataSourceState.compareAndSet(readyState, DataSourceState.Failed(error))
                Log.wtf(TAG, "population density native query failed", error)
                callback.onError(error)
                return
            }

        if (isVerbose) {
            val queryElapsedTime =
                (SystemClock.elapsedRealtimeNanos() - queryStartElapsedRealtimeNanos).nanoseconds
            verboseLog(TAG) {
                "query took ${queryElapsedTime.inWholeMicroseconds} microseconds"
            }
        }

        if (coarsenedS2CellId == S2_CELL_ID_NONE) {
            val exception =
                IllegalStateException(
                    "population density query returned an invalid S2 cell ID",
                )
            dataSourceState.compareAndSet(readyState, DataSourceState.Failed(exception))
            Log.wtf(TAG, "population density database query returned an invalid cell", exception)
            callback.onError(exception)
            return
        }

        val currentState = dataSourceState.get()
        if (currentState !== readyState) {
            val error =
                if (currentState is DataSourceState.Failed) {
                    currentState.error
                } else {
                    IllegalStateException("population density data source changed during query")
                }
            callback.onError(error)
            return
        }

        verboseLog(VERY_VERBOSE_TAG) { "coarsenedS2CellId: $coarsenedS2CellId" }
        callback.onResult(longArrayOf(coarsenedS2CellId))
    }
}
