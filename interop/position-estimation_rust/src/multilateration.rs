//! Documentation for multilateration module.

use rand::{seq::IteratorRandom, Rng};

use crate::{measurement::Measurement, position::Position};

/**
 * Multilateration using an algorithm based on Expectation-Maximization.
 */
pub fn multilateration(
    random: &mut impl Rng,
    measurements: &mut [Measurement],
    initial_guess: Option<Position>,
    iterations: usize,
) -> Position {
    let mut estimated_position = initial_guess.unwrap_or_default();

    for _ in 0..iterations {
        let measurement = measurements
            .iter_mut()
            .choose(random)
            .expect("should be given at least 1 measurement");

        // E step: compare difference between estimated position and measurement position and distance to get a probability
        let mut delta_update = Position::default();

        let x_delta = estimated_position.x.value - measurement.position.x.value;
        let y_delta = estimated_position.y.value - measurement.position.y.value;
        let z_delta = estimated_position.z.value - measurement.position.z.value;

        let estimated_distance = (x_delta.powi(2) + y_delta.powi(2) + z_delta.powi(2)).sqrt();

        measurement.probability = if (measurement.distance - estimated_distance).abs()
            > (measurement.position.x.variance
                + measurement.position.y.variance
                + measurement.position.z.variance)
                .sqrt()
            && estimated_distance > 0.0
        {
            measurement.distance / estimated_distance - 1.0
        } else {
            0.0
        };

        // M step: adjust estimated position to fit the point to a degree based on the probability
        let weight_x = measurement.probability;
        let weight_y = measurement.probability;
        let weight_z = measurement.probability;

        delta_update.x.value = x_delta * weight_x;
        delta_update.y.value = y_delta * weight_y;
        delta_update.z.value = z_delta * weight_z;

        if !measurement.position.x.real {
            measurement.position.x.value -= delta_update.x.value;
        }
        if !measurement.position.y.real {
            measurement.position.y.value -= delta_update.y.value;
        }
        if !measurement.position.z.real {
            measurement.position.z.value -= delta_update.z.value;
        }

        estimated_position.x.value += delta_update.x.value;
        estimated_position.y.value += delta_update.y.value;
        estimated_position.z.value += delta_update.z.value;
    }

    // TODO: take latent variables into account when calculating variance instead of just
    // counting them the same as the others since the algorithm does not update their variance.
    // the variance for a latent variable should be able to be determined by the other coordinate's
    // variances.
    let mut sum_weight = 0.0;
    let mut weighted_variance_x = 0.0;
    let mut weighted_variance_y = 0.0;
    let mut weighted_variance_z = 0.0;

    for measurement in measurements {
        let weight = measurement.probability + 1.0;

        weighted_variance_x += weight * (measurement.position.x.variance);
        weighted_variance_y += weight * (measurement.position.y.variance);
        weighted_variance_z += weight * (measurement.position.z.variance);
        sum_weight += weight;
    }

    if sum_weight > 0.0 {
        estimated_position.x.variance = weighted_variance_x / sum_weight;
        estimated_position.y.variance = weighted_variance_y / sum_weight;
        estimated_position.z.variance = weighted_variance_z / sum_weight;
    }

    estimated_position
}
