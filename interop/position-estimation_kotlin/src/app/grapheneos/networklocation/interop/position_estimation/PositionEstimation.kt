package app.grapheneos.networklocation.interop.position_estimation

/**
 * Estimate a position using robust methods to minimize the chance of an inaccurate position while
 * maximizing accuracy.
 */
object PositionEstimation {
    private external fun estimatePosition(measurements: Array<Measurement>): Position?

    init {
        System.loadLibrary("network_location_position_estimation_rust")
    }

    fun main(measurements: Array<Measurement>): Position? {
        return estimatePosition(measurements)
    }
}
