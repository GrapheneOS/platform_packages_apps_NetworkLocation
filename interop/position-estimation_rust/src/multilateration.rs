//! Documentation for multilateration module.

use core::f64;

use crate::{measurement::Measurement, position::Position};
use itertools::Itertools;

/**
 * Multilateration using an algorithm based on Expectation-Maximization.
 */
pub fn multilateration(
    measurements: &mut [Measurement],
    initial_guess: Option<Position>,
    iterations: usize,
) -> Position {
    let mut estimated_position = initial_guess.unwrap_or_default();

    let iterations_per_phase = iterations / 3;
    // Make sure we don't have any extra iterations.
    let iterations = iterations_per_phase * 3;

    for iter_index in 0..iterations {
        let is_last_iter = iter_index == (iterations_per_phase * 3) - 1;

        let only_use_measurements_with_no_latent_variables = iter_index < iterations_per_phase;
        let only_update_position_with_fully_real_measurements =
            (iterations_per_phase..(iterations_per_phase * 2)).contains(&iter_index);
        let can_use_measurement_for_position_update = |measurement: Measurement| {
            measurement.position.is_all_real() || !only_update_position_with_fully_real_measurements
        };
        let learning_rate = iter_index as f64 / iterations_per_phase as f64;
        let learning_rate = 1.0 - learning_rate.fract();
        let mut measurements_to_work_on = None;

        if only_use_measurements_with_no_latent_variables {
            let measurements_with_no_latent_variables = measurements
                .iter_mut()
                .enumerate()
                .filter(|measurement| {
                    let position = measurement.1.position;
                    position.x.real && position.y.real && position.z.real
                })
                .map(|measurement| measurement.0)
                .collect_vec();

            if !measurements_with_no_latent_variables.is_empty() {
                measurements_to_work_on = Some(measurements_with_no_latent_variables);
            }
        }

        let measurements_to_work_on = match measurements_to_work_on {
            None => measurements
                .iter()
                .enumerate()
                .map(|measurement| measurement.0)
                .collect_vec(),
            Some(measurements) => measurements,
        };

        let mut update = Position::default();

        let mut total_weighted_std_dev_x = 0.0;
        let mut total_weighted_std_dev_y = 0.0;
        let mut total_weighted_std_dev_z = 0.0;

        for measurement_index in &measurements_to_work_on {
            let mut measurement = measurements[*measurement_index];

            let mut x_delta = estimated_position.x.value - measurement.position.x.value;
            let mut y_delta = estimated_position.y.value - measurement.position.y.value;
            let mut z_delta = estimated_position.z.value - measurement.position.z.value;

            if x_delta.abs() <= measurement.position.x.variance.sqrt() {
                x_delta = 0.0
            }
            if y_delta.abs() <= measurement.position.y.variance.sqrt() {
                y_delta = 0.0
            }
            if z_delta.abs() <= measurement.position.z.variance.sqrt() {
                z_delta = 0.0
            }

            let x_delta_squared = x_delta.powi(2);
            let y_delta_squared = y_delta.powi(2);
            let z_delta_squared = z_delta.powi(2);

            let space_diagonal_length_squared = x_delta_squared + y_delta_squared + z_delta_squared;
            let estimated_distance = space_diagonal_length_squared.sqrt();

            let is_x_real = measurement.position.x.real;
            let is_y_real = measurement.position.y.real;
            let is_z_real = measurement.position.z.real;

            let x_variance = measurement.position.x.variance;
            let y_variance = measurement.position.y.variance;
            let z_variance = measurement.position.z.variance;

            let measurement_distance_std_dev = (measurement.position.x.variance
                + measurement.position.y.variance
                + measurement.position.z.variance)
                .sqrt();
            measurement.weight = if (measurement.distance - estimated_distance).abs()
                > measurement_distance_std_dev
                && estimated_distance > 0.0
            {
                (measurement.distance
                    - (measurement_distance_std_dev
                        * (measurement.distance - estimated_distance).signum()))
                    / estimated_distance
                    - 1.0
            } else {
                0.0
            };

            let update_x = ((x_delta_squared * measurement.weight).abs().sqrt()
                * (x_delta * measurement.weight).signum())
                * learning_rate;
            let update_y = ((y_delta_squared * measurement.weight).abs().sqrt()
                * (y_delta * measurement.weight).signum())
                * learning_rate;
            let update_z = ((z_delta_squared * measurement.weight).abs().sqrt()
                * (z_delta * measurement.weight).signum())
                * learning_rate;

            if !is_x_real {
                measurement.position.x.value -= update_x;
            }
            if !is_y_real {
                measurement.position.y.value -= update_y;
            }
            if !is_z_real {
                measurement.position.z.value -= update_z;
            }

            if can_use_measurement_for_position_update(measurement) {
                if is_x_real {
                    update.x.value += update_x;
                }
                if is_y_real {
                    update.y.value += update_y;
                }
                if is_z_real {
                    update.z.value += update_z;
                }
            }

            if is_last_iter && can_use_measurement_for_position_update(measurement) {
                let weight = ((estimated_distance
                    + (measurement_distance_std_dev
                        * (measurement.distance - estimated_distance).signum()))
                    - measurement.distance)
                    / measurement_distance_std_dev;
                let weight = weight.max(1.0);
                if is_x_real {
                    total_weighted_std_dev_x += x_variance.sqrt() * weight;
                }
                if is_y_real {
                    total_weighted_std_dev_y += y_variance.sqrt() * weight;
                }
                if is_z_real {
                    total_weighted_std_dev_z += z_variance.sqrt() * weight;
                }
            }
        }

        let real_measurements_to_work_on_lens =
            measurements_to_work_on
                .iter()
                .fold((0.0, 0.0, 0.0), |acc, measurement_index| {
                    let measurement = measurements[*measurement_index];

                    if can_use_measurement_for_position_update(measurement) {
                        (
                            acc.0
                                + if measurement.position.x.real {
                                    1.0
                                } else {
                                    0.0
                                },
                            acc.1
                                + if measurement.position.y.real {
                                    1.0
                                } else {
                                    0.0
                                },
                            acc.2
                                + if measurement.position.z.real {
                                    1.0
                                } else {
                                    0.0
                                },
                        )
                    } else {
                        acc
                    }
                });
        if real_measurements_to_work_on_lens.0 == 0.0 {
            update.x.value = 0.0
        } else {
            update.x.value /= real_measurements_to_work_on_lens.0
        }
        if real_measurements_to_work_on_lens.1 == 0.0 {
            update.y.value = 0.0
        } else {
            update.y.value /= real_measurements_to_work_on_lens.1
        }
        if real_measurements_to_work_on_lens.2 == 0.0 {
            update.z.value = 0.0
        } else {
            update.z.value /= real_measurements_to_work_on_lens.2
        }

        estimated_position.x.value += update.x.value;
        estimated_position.y.value += update.y.value;
        estimated_position.z.value += update.z.value;

        if is_last_iter {
            estimated_position.x.variance = if real_measurements_to_work_on_lens.0 == 0.0 {
                estimated_position.x.real = false;
                0.0
            } else {
                (total_weighted_std_dev_x / real_measurements_to_work_on_lens.0).powi(2)
            };
            estimated_position.y.variance = if real_measurements_to_work_on_lens.1 == 0.0 {
                estimated_position.y.real = false;
                0.0
            } else {
                (total_weighted_std_dev_y / real_measurements_to_work_on_lens.1).powi(2)
            };
            estimated_position.z.variance = if real_measurements_to_work_on_lens.2 == 0.0 {
                estimated_position.z.real = false;
                0.0
            } else {
                (total_weighted_std_dev_z / real_measurements_to_work_on_lens.2).powi(2)
            };
        }
    }

    estimated_position
}
