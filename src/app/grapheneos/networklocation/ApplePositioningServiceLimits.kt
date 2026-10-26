package app.grapheneos.networklocation

internal object ApplePositioningServiceLimits {
    const val MAX_REQUEST_APS = 40
    const val MAX_ADDITIONAL_APS = 100
    const val THROTTLED_ADDITIONAL_APS = 8
    const val MAX_REQUEST_TOWERS = 12
    const val MAX_ADDITIONAL_TOWERS = 25
    const val THROTTLED_ADDITIONAL_TOWERS = 4
    const val BASELINE_ADDITIONAL_TOWERS = 19

    const val RESPONSE_HEADER_BYTES = 10
    // Includes the header. ApplePositioningResponseSizeTest checks the budget against
    // the known protobuf fields and a conservative model of the requested counts.
    const val MAX_RESPONSE_BYTES = 1024 * 1024
}
