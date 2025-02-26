package app.grapheneos.networklocation

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.commons.math3.exception.ConvergenceException
import org.apache.commons.math3.exception.TooManyEvaluationsException
import org.apache.commons.math3.exception.TooManyIterationsException
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.RealVector
import org.apache.commons.math3.linear.SingularMatrixException
import org.apache.commons.math3.util.Combinations
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * WGS84 semi-major axis of the Earth in meters.
 * This is the Earth's equatorial radius as defined by the WGS84 ellipsoid.
 */
private const val EARTH_RADIUS = 6378137.0

/**
 * Assumed variance in dBm squared.
 */
private const val RSSI_VARIANCE = 4.0

data class GeoPoint(val latitude: Double, val longitude: Double, val altitude: Double?)

data class Point(val x: Double, val y: Double, val z: Double?)

data class Measurement(
    val position: Point,
    val xyPositionVariance: Double, // variance of the xy position (in meters squared)
    val zPositionVariance: Double?, // variance of the z position (in meters squared)
    val rssi: Double
)

private class MeasurementExt(val measurement: Measurement, val pathLossExponent: Double)

private data class TrilaterationResult(
    val estimatedPosition: Point,
    val xyAccuracyRadius: Double, // confidence radius (in meters)
    val zAccuracyRadius: Double? // confidence radius (in meters)
)

/**
 * Estimate the distance in meters from the access point using the Log-Distance Path Loss Model.
 */
private fun rssiToDistance(
    rssi: Double,
    pathLossExponent: Double,
    rssiAtOneMeter: Double = -40.0
): Double {
    return 10.0.pow((rssiAtOneMeter - rssi) / (10 * pathLossExponent))
}

private fun rssiVarianceToDistanceVariance(rssi: Double, pathLossExponent: Double): Double {
    val d = rssiToDistance(rssi, pathLossExponent)
    val factor = (ln(10.0) / (10 * pathLossExponent)) * d
    return factor * factor * RSSI_VARIANCE
}

private fun totalMeasurementVariance(measurement: Measurement, pathLossExponent: Double): Double {
    val distanceVariance = rssiVarianceToDistanceVariance(measurement.rssi, pathLossExponent)
    return distanceVariance + measurement.xyPositionVariance
}

private fun computeMeasurementWeights(
    measurements: List<MeasurementExt>
): DoubleArray {
    return measurements.map { entry ->
        val variance = totalMeasurementVariance(entry.measurement, entry.pathLossExponent)
        1.0 / variance
    }.toDoubleArray()
}

fun List<Double>.median(): Double {
    val sortedList = this.sorted()
    val size = sortedList.size
    return if (size % 2 == 0) {
        (sortedList[size / 2 - 1] + sortedList[size / 2]) / 2.0
    } else {
        sortedList[size / 2]
    }
}

/**
 * Computes the chi-squared value for two degrees of freedom.
 */
private fun getChiSquaredValue(confidenceLevel: Double): Double {
    require(confidenceLevel > 0.0 && confidenceLevel < 1.0) { "Confidence level must be between 0 and 1 (exclusive)." }

    return -2.0 * ln(1.0 - confidenceLevel)
}

private fun nonlinearLeastSquaresTrilateration(
    measurements: List<MeasurementExt>,
    initialGuess: Point,
    maxIterations: Int = 1000,
    confidenceLevel: Double = 0.95
): TrilaterationResult? {
    if (measurements.size < 2) {
        return null // not enough measurements
    }

    val observedDistances =
        measurements.map { rssiToDistance(it.measurement.rssi, it.pathLossExponent) }.toDoubleArray()
    val positions =
        measurements.map { doubleArrayOf(it.measurement.position.x, it.measurement.position.y) }
            .toTypedArray()
    val weights = computeMeasurementWeights(measurements)

    // create weight matrix from weights array
    val weightMatrix = Array2DRowRealMatrix(weights.size, weights.size)
    for (i in weights.indices) {
        weightMatrix.setEntry(i, i, weights[i])
    }

    val model = TrilaterationFunction(positions)

    val initialPoint = doubleArrayOf(initialGuess.x, initialGuess.y)

    val optimizer = LevenbergMarquardtOptimizer()

    val problem = LeastSquaresBuilder()
        .start(initialPoint)
        .model(model)
        .target(observedDistances)
        .weight(weightMatrix)
        .lazyEvaluation(false)
        .maxIterations(maxIterations)
        .maxEvaluations(maxIterations)
        .build()

    val optimum = try {
        optimizer.optimize(problem)
    } catch (e: ConvergenceException) {
        return null
    } catch (e: TooManyIterationsException) {
        return null
    } catch (e: TooManyEvaluationsException) {
        return null
    }

    val estimatedParameters = optimum.point.toArray()
    val x = estimatedParameters[0]
    val y = estimatedParameters[1]

    // estimate accuracy radius (confidence interval)
    val covariances = try {
        optimum.getCovariances(1e-12).data
    } catch (e: SingularMatrixException) {
        return null
    }
    val sigmaX2 = covariances[0][0]
    val sigmaY2 = covariances[1][1]
    val sigmaPos = sqrt(sigmaX2 + sigmaY2)
    val chiSquaredValue = getChiSquaredValue(confidenceLevel)
    val xyAccuracyRadius = sigmaPos * sqrt(chiSquaredValue)

    // TODO: calculate z and zAccuracyRadius by taking it into account for trilateration
    val z = measurements.mapNotNull { it.measurement.position.z }.let {
        if (it.isNotEmpty()) it.average() else null
    }
    val zAccuracyRadius = measurements.mapNotNull { it.measurement.zPositionVariance }.let {
        if (it.isNotEmpty()) it.average() else null
    }

    return TrilaterationResult(
        estimatedPosition = Point(x, y, z),
        xyAccuracyRadius = xyAccuracyRadius,
        zAccuracyRadius = zAccuracyRadius
    )
}

/**
 * Trilateration function for Apache Commons Math.
 */
private class TrilaterationFunction(
    private val positions: Array<DoubleArray>
) : MultivariateJacobianFunction {
    override fun value(point: RealVector): org.apache.commons.math3.util.Pair<RealVector, org.apache.commons.math3.linear.RealMatrix> {
        val x = point.toArray()
        val numMeasurements = positions.size

        val values = DoubleArray(numMeasurements)
        val jacobian = Array(numMeasurements) { DoubleArray(2) }

        for (i in 0 until numMeasurements) {
            val dx = x[0] - positions[i][0]
            val dy = x[1] - positions[i][1]
            val distance = sqrt((dx * dx) + (dy * dy)) + 1e-12 // avoid division by zero

            values[i] = distance

            jacobian[i][0] = dx / distance
            jacobian[i][1] = dy / distance
        }

        val valueVector = ArrayRealVector(values)
        val jacobianMatrix = Array2DRowRealMatrix(jacobian)

        return org.apache.commons.math3.util.Pair(valueVector, jacobianMatrix)
    }
}

private data class RansacTrilaterationResult(
    val trilaterationResult: TrilaterationResult,
    val inliersSize: Int
)

// 84 distinct samples at 3 measurements per sample
const val MAX_MEASUREMENTS_FOR_RANSAC_TRILATERATION = 9

/**
 * Use Random sample consensus (RANSAC) with nonlinear least squares trilateration to determine the
 * position.
 */
private fun ransacTrilateration(
    measurements: List<MeasurementExt>,
    minInliers: Int = 3,
    confidenceLevel: Double = 0.95
): RansacTrilaterationResult? {
    val numMeasurements = measurements.size
    require(numMeasurements <= MAX_MEASUREMENTS_FOR_RANSAC_TRILATERATION)
    require(numMeasurements >= minInliers)

    var bestInliers: List<MeasurementExt> = emptyList()
    var bestResult: TrilaterationResult? = null

    val sampleSize = min(numMeasurements, 3)

    for (combination in Combinations(numMeasurements, sampleSize)) {
        val sample = combination.map { measurements[it] }

        // use geometric median of sample positions as initial guess
        val initialGuess = geometricMedian(sample.map { it.measurement.position })

        // estimate position using the sample
        val result =
            nonlinearLeastSquaresTrilateration(sample, initialGuess) ?: continue

        val estimatedPosition = result.estimatedPosition

        // determine inliers
        val inliers = measurements.filter { entry ->
            val dx = estimatedPosition.x - entry.measurement.position.x
            val dy = estimatedPosition.y - entry.measurement.position.y
            val estimatedDistance = sqrt((dx * dx) + (dy * dy))
            val measuredDistance = rssiToDistance(entry.measurement.rssi, entry.pathLossExponent)
            val residual = abs(estimatedDistance - measuredDistance)
            val variance = totalMeasurementVariance(entry.measurement, entry.pathLossExponent)
            val standardizedResidual = residual / sqrt(variance)

            val threshold = 2.0 // within 2 standard deviations
            standardizedResidual <= threshold
        }

        if ((inliers.size >= minInliers) && (inliers.size > bestInliers.size)) {
            bestInliers = inliers
            bestResult = result
        }

        // exit early if sufficient inliers are found
        if (bestInliers.size > measurements.size * 0.8) {
            break
        }
    }

    if (bestInliers.isNotEmpty() && bestResult != null) {
        // refine position by using all inliers, and the best result as the initial guess
        return nonlinearLeastSquaresTrilateration(
            bestInliers,
            bestResult.estimatedPosition,
            confidenceLevel = confidenceLevel
        )?.let {
            RansacTrilaterationResult(it, bestInliers.size)
        }
    }
    return null
}

data class EstimatedPosition(
    val position: Point,
    val xzAccuracyRadius: Double,
    val zAccuracyRadius: Double?
)

/**
 * Estimate a position using robust methods to minimize the chance of an inaccurate position while
 * maximizing accuracy.
 *
 * Gracefully downgrades the methods used if the provided number of measurements is insufficient.
 */
fun estimatePosition(
    measurements: List<Measurement>,
    confidenceLevel: Double
): EstimatedPosition? {
    when (measurements.size) {
        0 -> return null
        1 -> {
            val measurement = measurements[0]
            // since we have just 1 measurement, we can't exactly try multiple path loss exponents
            // as we can't assess the result
            val pathLossExponent = 3.0

            val xzAccuracyRadius =
                sqrt(totalMeasurementVariance(measurement, pathLossExponent)
                            * getChiSquaredValue(confidenceLevel))
            val zAccuracyRadius = measurement.zPositionVariance
            return EstimatedPosition(measurement.position, xzAccuracyRadius, zAccuracyRadius)
        }
        else -> {
            val results: List<RansacTrilaterationResult?> = runBlocking(Dispatchers.Default) {
                val tasks = mutableListOf<Deferred<RansacTrilaterationResult?>>()
                for (pathLossExponent in (20..59 step 3).map { it.toDouble() / 10 }) {
                    val result = async {
                        ransacTrilateration(
                            measurements.map { MeasurementExt(it, pathLossExponent) }
                                .take(MAX_MEASUREMENTS_FOR_RANSAC_TRILATERATION),
                            minInliers = if (measurements.size == 2) 2 else 3,
                            confidenceLevel = confidenceLevel,
                        )
                    }
                    tasks.add(result)
                }
                tasks.awaitAll()
            }

            val bestResult: RansacTrilaterationResult? = results.reduce { best, item ->
                when {
                    item == null -> best
                    best == null || item.inliersSize > best.inliersSize -> item
                    item.inliersSize < best.inliersSize -> best
                    // item.inliersSize == best.inliersSize
                    item.trilaterationResult.xyAccuracyRadius < best.trilaterationResult.xyAccuracyRadius &&
                            (item.trilaterationResult.zAccuracyRadius
                                ?: Double.MAX_VALUE) <= (best.trilaterationResult.zAccuracyRadius
                        ?: Double.MAX_VALUE) -> item
                    else -> best
                }
            }

            return bestResult?.trilaterationResult?.let {
                EstimatedPosition(it.estimatedPosition, it.xyAccuracyRadius, it.zAccuracyRadius)
            }
        }
    }
}

/**
 * Use the Weiszfeld algorithm to find the geometric median.
 */
private fun geometricMedian(
    points: List<Point>,
    maxIterations: Int = 100,
    tolerance: Double = 1e-6
): Point {
    var x = points[0].x
    var y = points[0].y
    var z = points[0].z

    for (iteration in 1..maxIterations) {
        var numX = 0.0
        var numY = 0.0
        var numZ = 0.0
        var den = 0.0
        var maxShift = 0.0

        for (point in points) {
            val px = point.x
            val py = point.y
            val pz = point.z

            val dx = x - px
            val dy = y - py
            val dz = if (pz != null && z != null) z - pz else 0.0
            val dist = sqrt((dx * dx) + (dy * dy) + (dz * dz))
            val weight = 1.0 / max(dist, tolerance)

            numX += px * weight
            numY += py * weight
            numZ += pz?.times(weight) ?: 0.0
            den += weight
            maxShift = max(maxShift, dist)
        }

        val xNew = numX / den
        val yNew = numY / den
        val zNew = numZ / den

        val shift = sqrt(
            ((x - xNew) * (x - xNew)) + ((y - yNew) * (y - yNew)) + (((z ?: 0.0) - zNew) * ((z
                ?: 0.0) - zNew))
        )
        x = xNew
        y = yNew
        z = zNew

        if (shift < tolerance) {
            break
        }
    }

    return Point(x, y, z)
}

/**
 * Converts the GeoPoint to an ENU (East-North-Up) point relative to the given reference GeoPoint.
 * This method uses a simple Equirectangular approximation which assumes that the Earth is flat in
 * the vicinity of the reference point. While this approximation works reasonably well for short
 * distances, its accuracy diminishes over longer distances due to Earth’s curvature.
 *
 * TODO: Consider using a more accurate implementation in the future, which may be needed when we
 *  support using cell towers as a data point for determining location because of how much further
 *  away from the user they may be compared to Wi-Fi networks.
 */
fun geoPointToEnuPoint(geoPoint: GeoPoint, refGeoPoint: GeoPoint): Point {
    val dLat = Math.toRadians(geoPoint.latitude - refGeoPoint.latitude)
    val dLon = Math.toRadians(geoPoint.longitude - refGeoPoint.longitude)
    val latRad = Math.toRadians(refGeoPoint.latitude)

    val x = EARTH_RADIUS * dLon * cos(latRad)
    val y = EARTH_RADIUS * dLat
    val z = geoPoint.altitude

    return Point(x, y, z)
}

fun enuPointToGeoPoint(enuPoint: Point, refGeoPoint: GeoPoint): GeoPoint {
    val latRad = Math.toRadians(refGeoPoint.latitude)
    val dLat = enuPoint.y / EARTH_RADIUS
    val dLon = enuPoint.x / (EARTH_RADIUS * cos(latRad))

    val lat = refGeoPoint.latitude + Math.toDegrees(dLat)
    val lon = refGeoPoint.longitude + Math.toDegrees(dLon)
    val alt = enuPoint.z

    return GeoPoint(lat, lon, alt)
}
