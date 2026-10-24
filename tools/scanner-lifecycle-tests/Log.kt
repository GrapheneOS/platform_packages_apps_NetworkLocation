package android.util

object Log {
    const val VERBOSE = 2
    fun isLoggable(tag: String, level: Int) = false
    fun v(tag: String, message: String) = 0
    fun w(tag: String, message: String) = 0
    fun e(tag: String, message: String) = 0
}
class SparseArray<T> {
    private val items = mutableMapOf<Int,T>()
    fun put(key: Int, value: T) { items[key] = value }
    fun get(key: Int): T? = items[key]
}
