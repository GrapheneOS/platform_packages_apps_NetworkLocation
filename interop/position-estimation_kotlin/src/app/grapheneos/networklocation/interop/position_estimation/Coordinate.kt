package app.grapheneos.networklocation.interop.position_estimation

data class Coordinate(
    /** whether the value is real (not estimated based on other data) */
    var real: Boolean,
    var value: Double,
    /**
     * Squared uncertainty scale in m², used for residual tests, weighting,
     * and uncertainty probes.
     * Wi-Fi uses six-sigma output scaling and divides the square root by six.
     * Cellular reports the square root directly.
     * See tools/accuracy-semantics.md for input construction and reporting.
     */
    var sixSigmaSquared: Double,
)
