package app.grapheneos.networklocation.interop.position_estimation

data class Coordinate(
    /** whether the value is real (not estimated based on other data) */
    var real: Boolean,
    var value: Double,
    /** 6-sigma² */
    var sixSigmaSquared: Double,
)
