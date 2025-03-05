package app.grapheneos.networklocation.interop.position_estimation

data class Coordinate(
    /** whether the value is real (not estimated based on other data) */
    var real: Boolean,
    var value: Double,
    /** variance (1-sigma²) */
    var variance: Double,
)
