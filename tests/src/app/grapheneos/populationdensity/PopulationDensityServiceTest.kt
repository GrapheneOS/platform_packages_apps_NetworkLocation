package app.grapheneos.populationdensity

import android.content.Context
import android.content.Intent
import android.location.provider.IPopulationDensityProvider
import android.os.Binder
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.rule.ServiceTestRule
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests service scheduling through injection and the installed service binding path. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class PopulationDensityServiceTest {
    @get:Rule val serviceRule = ServiceTestRule()

    private class QueuedExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        val pendingCommandCount: Int
            get() = commands.size

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        /** Runs the next queued command. */
        fun runNext() {
            commands.removeFirst().run()
        }
    }

    private class FakeServiceProvider : PopulationDensityServiceProvider {
        val binder = Binder()
        var prewarmInvocationCount = 0
            private set

        override fun getServiceBinder(): IBinder = binder

        override fun prewarmDataSource() {
            prewarmInvocationCount++
        }
    }

    @Test
    fun onCreateAndBindSchedulePrewarmWithoutRunningItInline() {
        val queuedExecutor = QueuedExecutor()
        val fakeProvider = FakeServiceProvider()
        var factoryContext: Context? = null
        val service =
            PopulationDensityService(
                populationDensityProviderFactory = { context ->
                    factoryContext = context
                    fakeProvider
                },
                initializationExecutor = queuedExecutor,
            )

        service.onCreate()

        assertSame(service, factoryContext)
        assertEquals(1, queuedExecutor.pendingCommandCount)
        assertEquals(0, fakeProvider.prewarmInvocationCount)
        assertSame(fakeProvider.binder, service.onBind(Intent()))
        assertEquals(2, queuedExecutor.pendingCommandCount)
        assertEquals(0, fakeProvider.prewarmInvocationCount)

        queuedExecutor.runNext()
        assertEquals(1, fakeProvider.prewarmInvocationCount)
        assertEquals(1, queuedExecutor.pendingCommandCount)
        queuedExecutor.runNext()
        assertEquals(2, fakeProvider.prewarmInvocationCount)
        assertEquals(0, queuedExecutor.pendingCommandCount)
        service.onDestroy()
    }

    @Test
    fun installedServiceReturnsContainingDatabaseCell() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val serviceIntent = Intent(context, PopulationDensityService::class.java)

        val binder = serviceRule.bindService(serviceIntent)
        val provider = IPopulationDensityProvider.Stub.asInterface(binder)
        val s2CellId = provider.getCoarsenedS2CellId(TEST_QUERY_LATITUDE, TEST_QUERY_LONGITUDE)

        assertContainingDatabaseCell(
            TEST_QUERY_LATITUDE,
            TEST_QUERY_LONGITUDE,
            s2CellId,
            TEST_QUERY_EXPECTED_LEVEL,
        )
    }
}
