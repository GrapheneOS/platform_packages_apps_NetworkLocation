//! Position estimation using robust algorithms.

use crate::multilateration::multilateration;
use itertools::Itertools;
use measurement::Measurement;
use position::Position;
use rand::rngs::SmallRng;
use rand::SeedableRng;

pub mod coordinate;
mod jni;
pub mod measurement;
pub mod multilateration;
pub mod position;

/// Estimated position.
pub struct EstimatedPosition {
    /// estimated position
    position: Position,
    /// inliers size
    inliers_size: usize,
}

/**
 * Estimate a position using robust methods.
 */
pub fn estimate_position(measurements: &[Measurement]) -> Option<EstimatedPosition> {
    if measurements.is_empty() {
        return None;
    }

    let mut random = SmallRng::seed_from_u64(0);

    // only applies to measurements used for combos, all measurements
    // are still used when checking for inliers
    let max_measurements_for_combos = measurements.len().min(10);

    let sample_size = measurements.len().min(4);
    let min_inliers = measurements.len().min(4);
    let mut candidate_inliers_indices = Vec::with_capacity(measurements.len());

    let mut best_inliers_indices = Vec::with_capacity(measurements.len());
    let mut best_position: Option<Position> = None;

    let mut measurements_indices: Vec<usize> = (0..measurements.len()).collect();
    measurements_indices = measurements_indices
        .iter()
        .sorted_by(|m1, m2| {
            measurements[**m1]
                .distance
                .total_cmp(&measurements[**m2].distance)
        })
        .take(max_measurements_for_combos)
        .cloned()
        .collect();
    let top_closest_measurements_indices = &measurements_indices[0..max_measurements_for_combos];

    for combo in top_closest_measurements_indices.iter().combinations(sample_size) {
        let mut sample: Vec<Measurement> =
            combo.iter().map(|index| measurements[**index]).collect();

        let initial_guess = Position::average(sample.iter().map(|m| m.position));

        let candidate_position = multilateration(
            &mut random,
            &mut sample,
            Some(initial_guess),
            (sample_size * 100).min(1000),
        );

        candidate_inliers_indices.clear();
        for index in 0..measurements.len() {
            let measurement = &measurements[index];
            let dx = candidate_position.x.value - measurement.position.x.value;
            let dy = candidate_position.y.value - measurement.position.y.value;
            let dz = candidate_position.z.value - measurement.position.z.value;

            let estimated_distance = (dx.powi(2) + dy.powi(2) + dz.powi(2)).sqrt();
            let residual = (estimated_distance - measurement.distance).abs();
            let total_variance = measurement.position.x.variance
                + measurement.position.y.variance
                + measurement.position.z.variance;
            let standardized_residual = residual / total_variance.sqrt();
            // within 2 standard deviations
            let threshold = 2.0;

            if standardized_residual <= threshold {
                candidate_inliers_indices.push(index);
            }
        }

        if candidate_inliers_indices.len() >= min_inliers
            && candidate_inliers_indices.len() > best_inliers_indices.len()
        {
            best_inliers_indices = candidate_inliers_indices.clone();
            best_position = Some(candidate_position);
        }
    }

    if let Some(best_position) = best_position {
        let mut best_inliers: Vec<Measurement> = best_inliers_indices
            .iter()
            .map(|&i| measurements[i])
            .collect();
        let best_inliers_size = best_inliers.len();

        Some(EstimatedPosition {
            position: multilateration(
                &mut random,
                &mut best_inliers,
                Some(best_position),
                10_000,
            ),
            inliers_size: best_inliers_size,
        })
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use log::{info, LevelFilter};
    use rand::{Rng, SeedableRng};
    use std::ops::Range;

    use crate::coordinate::Coordinate;

    use super::*;

    fn generate_random_measurement(
        x_range: Range<f64>,
        y_range: Range<f64>,
        z_range: Range<f64>,
        real_position: Position,
        random: &mut SmallRng,
    ) -> Measurement {
        let x = random.gen_range(x_range);
        let y = random.gen_range(y_range);
        let z = random.gen_range(z_range);
        let x_variance: f64 = random.gen_range(3000.0..5000.0);
        let y_variance: f64 = random.gen_range(3000.0..5000.0);
        let z_variance: f64 = random.gen_range(50.0..100.0);
        let distance = ((x - real_position.x.value).powi(2)
            + (y - real_position.y.value).powi(2)
            + (z - real_position.z.value).powi(2))
        .sqrt()
            + random.gen_range(10.0..100.0);

        Measurement {
            position: Position {
                x: Coordinate::new_real(x, x_variance),
                y: Coordinate::new_real(y, y_variance),
                z: Coordinate::new_real(z, z_variance),
            },
            distance,
            probability: 0.0,
        }
    }

    /// checks that position estimation fails due to not having enough inliers
    #[test]
    fn not_enough_inliers() {
        env_logger::builder()
            .filter_level(LevelFilter::max())
            .init();

        let mut results = vec![];
        let iterations = 100;

        for s in 0..iterations {
            let mut random = SmallRng::seed_from_u64(s);

            let real_position = Position {
                x: Coordinate::new_real(random.gen_range(-10000.0..10000.0), 0.0),
                y: Coordinate::new_real(random.gen_range(-10000.0..10000.0), 0.0),
                z: Coordinate::new_real(random.gen_range(-10000.0..10000.0), 0.0),
            };

            let mut measurements = vec![];

            for i in 0..9 {
                let mut measurement = generate_random_measurement(
                    real_position.x.value - random.gen_range(0.0..1000.0)
                        ..real_position.x.value + random.gen_range(0.0..1000.0),
                    real_position.y.value - random.gen_range(0.0..1000.0)
                        ..real_position.y.value + random.gen_range(0.0..1000.0),
                    real_position.z.value - random.gen_range(0.0..1000.0)
                        ..real_position.z.value + random.gen_range(0.0..1000.0),
                    real_position,
                    &mut random,
                );

                // make 6 of them outliers, meaning we only have 3 inliers which is not
                // enough (minimum inliers is currently 4)
                if i < 6 {
                    measurement.position.x.value *= 100.0;
                    measurement.position.y.value *= 100.0;
                    measurement.position.z.value *= 100.0;
                }

                measurements.push(measurement);
            }

            let estimated_position = estimate_position(&measurements);

            results.push((real_position, estimated_position));
        }

        let position_deltas = results.iter().map(|p| {
            p.1.as_ref().map(|result| {
                let result = result.position;
                Position {
                    x: Coordinate::new_real(
                        (p.0.x.value - result.x.value).abs(),
                        result.x.variance,
                    ),
                    y: Coordinate::new_real(
                        (p.0.y.value - result.y.value).abs(),
                        result.y.variance,
                    ),
                    z: Coordinate::new_real(
                        (p.0.z.value - result.z.value).abs(),
                        result.z.variance,
                    ),
                }
            })
        });

        let successful_results_deltas = position_deltas
            .flatten()
            .collect::<Vec<Position>>()
            .into_iter();
        let success_results_deltas_count = successful_results_deltas.len();

        let average_delta = Position::average(successful_results_deltas);

        info!(
            "\nreal position: {:#?},\naverage delta: {:#?},\nratio of success: {}/{iterations}",
            Position::average(results.iter().map(|p| p.0)),
            average_delta,
            success_results_deltas_count,
        );

        assert_eq!(success_results_deltas_count, 0);
    }

    /// checks that we can estimate a position successfully despite convincing outliers,
    /// as long as the majority of measurements are inliers
    #[test]
    fn majority_wins() {
        env_logger::builder()
            .filter_level(LevelFilter::max())
            .init();

        let mut results = vec![];
        let iterations = 100;

        for s in 0..iterations {
            let mut random = SmallRng::seed_from_u64(s);

            let real_position = Position {
                x: Coordinate::new_real(random.gen_range(0.0..10000.0), 0.0),
                y: Coordinate::new_real(random.gen_range(0.0..10000.0), 0.0),
                z: Coordinate::new_real(random.gen_range(0.0..10000.0), 0.0),
            };

            let mut measurements = vec![];

            for i in 0..9 {
                let mut measurement = generate_random_measurement(
                    real_position.x.value - random.gen_range(0.0..1000.0)
                        ..real_position.x.value + random.gen_range(0.0..1000.0),
                    real_position.y.value - random.gen_range(0.0..1000.0)
                        ..real_position.y.value + random.gen_range(0.0..1000.0),
                    real_position.z.value - random.gen_range(0.0..1000.0)
                        ..real_position.z.value + random.gen_range(0.0..1000.0),
                    real_position,
                    &mut random,
                );

                // make 4 of them convincing outliers by just inversing their coordinates,
                // meaning they still seem valid
                if i < 4 {
                    measurement.position.x.value *= -1.0;
                    measurement.position.y.value *= -1.0;
                    measurement.position.z.value *= -1.0;
                }

                measurements.push(measurement);
            }

            let estimated_position = estimate_position(&measurements);

            results.push((real_position, estimated_position));
        }

        let position_deltas = results.iter().map(|p| {
            p.1.as_ref().map(|result| {
                let result = result.position;
                Position {
                    x: Coordinate::new_real(
                        (p.0.x.value - result.x.value).abs(),
                        result.x.variance,
                    ),
                    y: Coordinate::new_real(
                        (p.0.y.value - result.y.value).abs(),
                        result.y.variance,
                    ),
                    z: Coordinate::new_real(
                        (p.0.z.value - result.z.value).abs(),
                        result.z.variance,
                    ),
                }
            })
        });

        let successful_results_deltas = position_deltas
            .flatten()
            .collect::<Vec<Position>>()
            .into_iter();
        let success_results_deltas_count = successful_results_deltas.len();

        let average_delta = Position::average(successful_results_deltas);

        info!(
            "\nreal position: {:#?},\naverage delta: {:#?},\nratio of success: {}/{iterations}",
            Position::average(results.iter().map(|p| p.0)),
            average_delta,
            success_results_deltas_count,
        );

        assert!(average_delta.x.value < 100.0);
        assert!(average_delta.y.value < 100.0);
        assert!(average_delta.z.value < 100.0);

        assert!(average_delta.x.variance < 5000.0);
        assert!(average_delta.y.variance < 5000.0);
        assert!(average_delta.z.variance < 100.0);
    }
}
