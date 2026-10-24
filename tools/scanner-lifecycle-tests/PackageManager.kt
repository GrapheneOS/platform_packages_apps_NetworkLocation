package android.content.pm

class PackageManager {
    val missing = mutableSetOf<String>()
    fun hasSystemFeature(feature: String) = feature !in missing
    companion object {
        const val FEATURE_TELEPHONY = "telephony"
        const val FEATURE_TELEPHONY_RADIO_ACCESS = "radio"
    }
}
