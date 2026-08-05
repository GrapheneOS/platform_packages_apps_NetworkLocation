package app.grapheneos.populationdensity

import android.content.Context
import android.os.OutcomeReceiver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val ARBITRARY_CELL_ID = 0x0fed_cba9_8765_4321L
private const val VALID_LATITUDE = 40.7128
private const val VALID_LONGITUDE = -74.0060
private const val ASYNC_TEST_TIMEOUT_SECONDS = 5L

/** Tests provider readiness, failure handling, and Binder-facing query behavior. */
@RunWith(AndroidJUnit4::class)
@SmallTest
class PopulationDensityProviderImplTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class QueuedExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        val pendingCommandCount: Int
            get() = commands.size

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runNext() {
            commands.removeFirst().run()
        }
    }

    private class RecordingCallback<ResultT : Any> : OutcomeReceiver<ResultT, Throwable> {
        private val invocation = CountDownLatch(1)

        var invocationCount = 0
            private set
        var result: ResultT? = null
            private set
        var error: Throwable? = null
            private set

        override fun onResult(result: ResultT) {
            invocationCount++
            this.result = result
            invocation.countDown()
        }

        override fun onError(error: Throwable) {
            invocationCount++
            this.error = error
            invocation.countDown()
        }

        fun awaitInvocation(): Boolean =
            invocation.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun defaultCoarseningLevelReportsError() {
        val provider = PopulationDensityProviderImpl(context)
        val callback = RecordingCallback<Int>()

        provider.onGetDefaultCoarseningLevel(callback)

        assertTrue(callback.error is UnsupportedOperationException)
        assertNull(callback.result)
        assertEquals(1, callback.invocationCount)
    }

    @Test
    fun notReadyQueriesShareBackgroundInitialization() {
        val initializationExecutor = QueuedExecutor()
        var factoryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context, initializationExecutor) {
                factoryInvocationCount++
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }
        val firstCallback = RecordingCallback<LongArray>()
        val secondCallback = RecordingCallback<LongArray>()

        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, firstCallback)
        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, secondCallback)

        assertEquals(0, firstCallback.invocationCount)
        assertEquals(0, secondCallback.invocationCount)
        assertEquals(0, factoryInvocationCount)
        assertEquals(1, initializationExecutor.pendingCommandCount)

        initializationExecutor.runNext()

        assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), firstCallback.result)
        assertNull(firstCallback.error)
        assertEquals(1, firstCallback.invocationCount)
        assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), secondCallback.result)
        assertNull(secondCallback.error)
        assertEquals(1, secondCallback.invocationCount)
        assertEquals(1, factoryInvocationCount)
    }

    @Test
    fun initializationWaitTimesOutWithoutCancellingInitialization() {
        val initializationExecutor = QueuedExecutor()
        var factoryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context, initializationExecutor) {
                factoryInvocationCount++
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }
        val firstCallback = RecordingCallback<LongArray>()

        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, firstCallback)

        assertTrue(firstCallback.awaitInvocation())
        assertTrue(firstCallback.error is TimeoutException)
        assertNull(firstCallback.result)
        assertEquals(1, firstCallback.invocationCount)
        assertEquals(0, factoryInvocationCount)

        val secondCallback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, secondCallback)
        assertSame(firstCallback.error, secondCallback.error)
        assertNull(secondCallback.result)
        assertEquals(1, secondCallback.invocationCount)

        initializationExecutor.runNext()
        assertEquals(1, firstCallback.invocationCount)
        assertEquals(1, secondCallback.invocationCount)
        assertEquals(1, factoryInvocationCount)

        val successfulCallback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(
            VALID_LATITUDE,
            VALID_LONGITUDE,
            0,
            successfulCallback,
        )
        assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), successfulCallback.result)
        assertNull(successfulCallback.error)
        assertEquals(1, successfulCallback.invocationCount)
    }

    @Test
    fun initializingQueryCompletesAfterPrewarmWithoutInvokingFactoryAgain() {
        val factoryStarted = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        var factoryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context) {
                factoryInvocationCount++
                factoryStarted.countDown()
                check(
                    releaseFactory.await(
                        ASYNC_TEST_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    ),
                )
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }
        val initializationExecutor = Executors.newSingleThreadExecutor()

        try {
            initializationExecutor.execute(provider::prewarmDataSource)
            assertTrue(
                factoryStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            provider.prewarmDataSource()
            assertEquals(1, factoryInvocationCount)

            val callback = RecordingCallback<LongArray>()
            provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)
            assertEquals(0, callback.invocationCount)
            assertEquals(1, factoryInvocationCount)

            releaseFactory.countDown()
            assertTrue(callback.awaitInvocation())
            assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), callback.result)
            assertNull(callback.error)
            assertEquals(1, callback.invocationCount)
        } finally {
            releaseFactory.countDown()
            initializationExecutor.shutdown()
            assertTrue(
                initializationExecutor.awaitTermination(
                    ASYNC_TEST_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                ),
            )
        }
    }

    @Test
    fun initializingQueryReportsInitializationFailure() {
        val factoryStarted = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val initializationFailure = IOException("database loading failed")
        val provider =
            PopulationDensityProviderImpl(context) {
                factoryStarted.countDown()
                check(
                    releaseFactory.await(
                        ASYNC_TEST_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    ),
                )
                throw initializationFailure
            }
        val initializationExecutor = Executors.newSingleThreadExecutor()

        try {
            initializationExecutor.execute(provider::prewarmDataSource)
            assertTrue(
                factoryStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            val callback = RecordingCallback<LongArray>()
            provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)
            assertEquals(0, callback.invocationCount)

            releaseFactory.countDown()
            assertTrue(callback.awaitInvocation())
            assertSame(initializationFailure, callback.error)
            assertNull(callback.result)
            assertEquals(1, callback.invocationCount)
        } finally {
            releaseFactory.countDown()
            initializationExecutor.shutdown()
            assertTrue(
                initializationExecutor.awaitTermination(
                    ASYNC_TEST_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                ),
            )
        }
    }

    @Test
    fun deferredQueryReportsRuntimeFailure() {
        val initializationExecutor = QueuedExecutor()
        val queryFailure = IllegalArgumentException("unexpected query failure")
        val provider =
            PopulationDensityProviderImpl(context, initializationExecutor) {
                PopulationDensityDataSource { _, _ -> throw queryFailure }
            }
        val callback = RecordingCallback<LongArray>()

        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)
        assertEquals(0, callback.invocationCount)

        initializationExecutor.runNext()

        assertSame(queryFailure, callback.error)
        assertNull(callback.result)
        assertEquals(1, callback.invocationCount)
    }

    @Test
    fun deferredResultCallbackFailureDoesNotInvokeErrorCallback() {
        val initializationExecutor = QueuedExecutor()
        val provider =
            PopulationDensityProviderImpl(context, initializationExecutor) {
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }
        val callback =
            object : OutcomeReceiver<LongArray, Throwable> {
                var resultInvocationCount = 0
                var errorInvocationCount = 0
                var result: LongArray? = null

                override fun onResult(result: LongArray) {
                    resultInvocationCount++
                    this.result = result
                    throw IllegalStateException("callback failed")
                }

                override fun onError(error: Throwable) {
                    errorInvocationCount++
                }
            }

        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)
        initializationExecutor.runNext()

        assertEquals(1, callback.resultInvocationCount)
        assertEquals(0, callback.errorInvocationCount)
        assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), callback.result)
    }

    @Test
    fun notReadyQueryReportsInitializationSchedulingFailure() {
        val schedulingFailure = RejectedExecutionException("executor rejected initialization")
        var factoryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context, Executor { throw schedulingFailure }) {
                factoryInvocationCount++
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }
        val callback = RecordingCallback<LongArray>()

        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)

        assertSame(schedulingFailure, callback.error)
        assertNull(callback.result)
        assertEquals(1, callback.invocationCount)
        assertEquals(0, factoryInvocationCount)
    }

    @Test
    fun failedQueryDoesNotRetryUntilBackgroundPrewarm() {
        val initializationFailure = IOException("database loading failed")
        var factoryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context) {
                factoryInvocationCount++
                if (factoryInvocationCount == 1) {
                    throw initializationFailure
                }
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }

        provider.prewarmDataSource()
        repeat(2) {
            val callback = RecordingCallback<LongArray>()
            provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)
            assertSame(initializationFailure, callback.error)
            assertNull(callback.result)
            assertEquals(1, callback.invocationCount)
        }
        assertEquals(1, factoryInvocationCount)

        provider.prewarmDataSource()
        val successfulCallback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(
            VALID_LATITUDE,
            VALID_LONGITUDE,
            0,
            successfulCallback,
        )
        assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), successfulCallback.result)
        assertNull(successfulCallback.error)
        assertEquals(2, factoryInvocationCount)
    }

    @Test
    fun linkageFailureIsReportedWithoutQueryRetry() {
        val linkageFailure = UnsatisfiedLinkError("native library loading failed")
        var factoryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context) {
                factoryInvocationCount++
                throw linkageFailure
            }

        provider.prewarmDataSource()
        val callback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)

        assertSame(linkageFailure, callback.error)
        assertNull(callback.result)
        assertEquals(1, callback.invocationCount)
        assertEquals(1, factoryInvocationCount)
    }

    @Test
    fun unexpectedInitializationFailureIsReportedAndAllowsLaterQueryToRetry() {
        val initializationFailure = IllegalArgumentException("unexpected factory failure")
        var executorFailure: Throwable? = null
        val initializationExecutor =
            Executor { command ->
                try {
                    command.run()
                } catch (throwable: Throwable) {
                    executorFailure = throwable
                }
            }
        var factoryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context, initializationExecutor) {
                factoryInvocationCount++
                if (factoryInvocationCount == 1) {
                    throw initializationFailure
                }
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }

        val failedCallback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, failedCallback)
        assertSame(initializationFailure, executorFailure)
        assertSame(initializationFailure, failedCallback.error)
        assertNull(failedCallback.result)
        assertEquals(1, failedCallback.invocationCount)
        assertEquals(1, factoryInvocationCount)

        val successfulCallback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, successfulCallback)

        assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), successfulCallback.result)
        assertNull(successfulCallback.error)
        assertEquals(1, successfulCallback.invocationCount)
        assertEquals(2, factoryInvocationCount)
    }

    @Test
    fun validCoordinatesReturnPrewarmedCell() {
        var factoryInvocationCount = 0
        var queryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context) {
                factoryInvocationCount++
                PopulationDensityDataSource { latitude, longitude ->
                    queryInvocationCount++
                    assertEquals(VALID_LATITUDE, latitude, 0.0)
                    assertEquals(VALID_LONGITUDE, longitude, 0.0)
                    ARBITRARY_CELL_ID
                }
            }
        provider.prewarmDataSource()
        provider.prewarmDataSource()
        val callback = RecordingCallback<LongArray>()

        provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)

        assertArrayEquals(longArrayOf(ARBITRARY_CELL_ID), callback.result)
        assertNull(callback.error)
        assertEquals(1, callback.invocationCount)
        assertEquals(1, factoryInvocationCount)
        assertEquals(1, queryInvocationCount)
    }

    @Test
    fun invalidCoordinatesAreRejectedBeforeReadinessCheck() {
        var factoryInvoked = false
        val provider =
            PopulationDensityProviderImpl(context) {
                factoryInvoked = true
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }
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
            val callback = RecordingCallback<LongArray>()
            provider.onGetCoarsenedS2Cells(latitude, longitude, 0, callback)
            assertTrue(callback.error is IllegalArgumentException)
            assertNull(callback.result)
            assertEquals(1, callback.invocationCount)
        }
        assertFalse(factoryInvoked)
    }

    @Test
    fun negativeAdditionalCellCountIsRejectedBeforeReadinessCheck() {
        var factoryInvoked = false
        val provider =
            PopulationDensityProviderImpl(context) {
                factoryInvoked = true
                PopulationDensityDataSource { _, _ -> ARBITRARY_CELL_ID }
            }
        val callback = RecordingCallback<LongArray>()

        provider.onGetCoarsenedS2Cells(
            VALID_LATITUDE,
            VALID_LONGITUDE,
            -1,
            callback,
        )

        assertTrue(callback.error is IllegalArgumentException)
        assertNull(callback.result)
        assertEquals(1, callback.invocationCount)
        assertFalse(factoryInvoked)
    }

    @Test
    fun runtimeQueryFailureTransitionsProviderToFailed() {
        val queryFailure = IllegalArgumentException("unexpected query failure")
        var queryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context) {
                PopulationDensityDataSource { _, _ ->
                    queryInvocationCount++
                    throw queryFailure
                }
            }
        provider.prewarmDataSource()

        repeat(2) {
            val callback = RecordingCallback<LongArray>()
            provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)
            assertSame(queryFailure, callback.error)
            assertNull(callback.result)
            assertEquals(1, callback.invocationCount)
        }
        assertEquals(1, queryInvocationCount)
    }

    @Test
    fun queryLinkageFailureTransitionsProviderToFailed() {
        val queryFailure = UnsatisfiedLinkError("native query failed")
        var queryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context) {
                PopulationDensityDataSource { _, _ ->
                    queryInvocationCount++
                    throw queryFailure
                }
            }
        provider.prewarmDataSource()

        repeat(2) {
            val callback = RecordingCallback<LongArray>()
            provider.onGetCoarsenedS2Cells(VALID_LATITUDE, VALID_LONGITUDE, 0, callback)
            assertSame(queryFailure, callback.error)
            assertNull(callback.result)
            assertEquals(1, callback.invocationCount)
        }
        assertEquals(1, queryInvocationCount)
    }

    @Test
    fun concurrentSuccessFailsClosedAfterAnotherQueryFails() {
        val firstQueryStarted = CountDownLatch(1)
        val secondQueryStarted = CountDownLatch(1)
        val releaseFirstQuery = CountDownLatch(1)
        val releaseSecondQuery = CountDownLatch(1)
        val queryInvocationCount = AtomicInteger()
        val queryFailure = IllegalStateException("native query failed")
        val provider =
            PopulationDensityProviderImpl(context) {
                PopulationDensityDataSource { _, _ ->
                    when (queryInvocationCount.incrementAndGet()) {
                        1 -> {
                            firstQueryStarted.countDown()
                            check(
                                releaseFirstQuery.await(
                                    ASYNC_TEST_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS,
                                ),
                            )
                            throw queryFailure
                        }

                        2 -> {
                            secondQueryStarted.countDown()
                            check(
                                releaseSecondQuery.await(
                                    ASYNC_TEST_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS,
                                ),
                            )
                            ARBITRARY_CELL_ID
                        }

                        else -> {
                            error("unexpected query invocation")
                        }
                    }
                }
            }
        provider.prewarmDataSource()
        val queryExecutor = Executors.newFixedThreadPool(2)
        val firstCallback = RecordingCallback<LongArray>()
        val secondCallback = RecordingCallback<LongArray>()

        try {
            val firstQuery =
                queryExecutor.submit {
                    provider.onGetCoarsenedS2Cells(
                        VALID_LATITUDE,
                        VALID_LONGITUDE,
                        0,
                        firstCallback,
                    )
                }
            assertTrue(
                firstQueryStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val secondQuery =
                queryExecutor.submit {
                    provider.onGetCoarsenedS2Cells(
                        VALID_LATITUDE,
                        VALID_LONGITUDE,
                        0,
                        secondCallback,
                    )
                }
            assertTrue(
                secondQueryStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )

            releaseFirstQuery.countDown()
            firstQuery.get(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            releaseSecondQuery.countDown()
            secondQuery.get(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertSame(queryFailure, firstCallback.error)
            assertNull(firstCallback.result)
            assertEquals(1, firstCallback.invocationCount)
            assertSame(queryFailure, secondCallback.error)
            assertNull(secondCallback.result)
            assertEquals(1, secondCallback.invocationCount)
            assertEquals(2, queryInvocationCount.get())
        } finally {
            releaseFirstQuery.countDown()
            releaseSecondQuery.countDown()
            queryExecutor.shutdownNow()
            assertTrue(
                queryExecutor.awaitTermination(
                    ASYNC_TEST_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                ),
            )
        }
    }

    @Test
    fun invalidCellTransitionsProviderToFailed() {
        var queryInvocationCount = 0
        val provider =
            PopulationDensityProviderImpl(context) {
                PopulationDensityDataSource { _, _ ->
                    queryInvocationCount++
                    S2_CELL_ID_NONE
                }
            }
        provider.prewarmDataSource()

        val firstCallback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(
            VALID_LATITUDE,
            VALID_LONGITUDE,
            0,
            firstCallback,
        )
        assertTrue(firstCallback.error is IllegalStateException)
        assertNull(firstCallback.result)
        assertEquals(1, firstCallback.invocationCount)

        val secondCallback = RecordingCallback<LongArray>()
        provider.onGetCoarsenedS2Cells(
            VALID_LATITUDE,
            VALID_LONGITUDE,
            0,
            secondCallback,
        )
        assertSame(firstCallback.error, secondCallback.error)
        assertNull(secondCallback.result)
        assertEquals(1, secondCallback.invocationCount)
        assertEquals(1, queryInvocationCount)
    }
}
