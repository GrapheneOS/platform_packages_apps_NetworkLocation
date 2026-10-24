package android.content

import android.content.pm.PackageManager
import java.util.concurrent.Executor

class Context(
    val services: MutableMap<Class<*>, Any> = mutableMapOf(),
    val packageManager: PackageManager = PackageManager(),
    val mainExecutor: Executor = Executor { it.run() },
) {
    fun <T> getSystemService(type: Class<T>): T? = services[type]?.let(type::cast)
}
