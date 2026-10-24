//! Documentation for multilateration module.

use core::f64;
use std::ops::{Div, Sub};

use crate::{measurement::Measurement, position::Position};
use itertools::Itertools;

/// Inflate uncertainty when the estimate lies too far from an anchor.
/// Zero tolerance has no multiplicative inflation; this is not a noise model.
pub(crate) fn residual_inflation(distance: f64, target: f64, tolerance: f64) -> f64 {
    if tolerance == 0.0 {
        1.0
    } else {
        (((distance + tolerance * (target - distance).signum()) - target) / tolerance).max(1.0)
    }
}

/// Numerical entry point for the generic/cellular solver. The coordinate
/// cutoffs and finite probes are heuristic, not a calibrated confidence model.
pub fn multilateration_checked(
    measurements: &[Measurement],
    initial_guess: Option<Position>,
    iterations: usize,
) -> Option<Position> {
    fn finite(p: Position) -> bool {
        [p.x, p.y, p.z].iter().all(|c| {
            c.value.is_finite() && c.six_sigma_squared.is_finite() && c.six_sigma_squared >= 0.0
        })
    }
    if measurements.is_empty()
        || iterations < 3
        || iterations.checked_add(36).is_none()
        || initial_guess.is_some_and(|p| !finite(p))
        || measurements
            .iter()
            .any(|m| !finite(m.position) || !m.distance.is_finite() || m.distance < 0.0)
    {
        return None;
    }
    let result = multilateration_internal(measurements, initial_guess, iterations);
    if !finite(result) || result.number_of_real() == 0 {
        return None;
    }
    Some(result)
}

fn multilateration_internal(
    measurements: &[Measurement],
    initial_guess: Option<Position>,
    iterations: usize,
) -> Position {
    // Also used for six_sigma_squared estimation iterations.
    let mut estimated_position = initial_guess.unwrap_or_default();
    let mut actual_estimated_position = estimated_position;
    let six_sigma_squared_estimation_iterations_per_point = 6;
    // 2 points for each dimension.
    let mut six_sigma_squared_estimation_points = [Position::default(); 6];
    let six_sigma_squared_estimation_iterations = six_sigma_squared_estimation_points.len()
        * six_sigma_squared_estimation_iterations_per_point;
    let mut previous_estimated_position = estimated_position;

    let iterations_per_phase = iterations / 3;
    // Make sure we don't have any extra iterations.
    let iterations = (iterations_per_phase * 3) + six_sigma_squared_estimation_iterations;

    for iter_index in 0..iterations {
        let is_last_phases_iter = iter_index == (iterations_per_phase * 3) - 1;

        let only_use_measurements_with_no_latent_variables = iter_index < iterations_per_phase;
        let only_update_position_with_fully_real_measurements =
            (iterations_per_phase..(iterations_per_phase * 2)).contains(&iter_index);
        let can_use_measurement_for_position_update = |measurement: Measurement| {
            measurement.position.is_all_real() || !only_update_position_with_fully_real_measurements
        };
        let is_in_final_six_sigma_squared_estimation =
            (iterations.sub(six_sigma_squared_estimation_iterations)..iterations)
                .contains(&iter_index);
        let learning_rate = if iter_index < iterations - six_sigma_squared_estimation_iterations {
            iter_index as f64 / iterations_per_phase as f64
        } else {
            1.0
        };
        let learning_rate = 1.0 - learning_rate.fract();
        let mut measurements_to_work_on = None;

        let six_sigma_squared_estimation_iter_index =
            iter_index.wrapping_sub(iterations - six_sigma_squared_estimation_iterations);
        let six_sigma_squared_estimation_point_index = six_sigma_squared_estimation_iter_index
            / six_sigma_squared_estimation_iterations_per_point;
        if is_in_final_six_sigma_squared_estimation
            && six_sigma_squared_estimation_iter_index
                % six_sigma_squared_estimation_iterations_per_point
                == 0
        {
            estimated_position = actual_estimated_position;
            match six_sigma_squared_estimation_point_index {
                0 => estimated_position.x.value -= estimated_position.x.six_sigma_squared.sqrt(),
                1 => estimated_position.x.value += estimated_position.x.six_sigma_squared.sqrt(),
                2 => estimated_position.y.value -= estimated_position.y.six_sigma_squared.sqrt(),
                3 => estimated_position.y.value += estimated_position.y.six_sigma_squared.sqrt(),
                4 => estimated_position.z.value -= estimated_position.z.six_sigma_squared.sqrt(),
                5 => estimated_position.z.value += estimated_position.z.six_sigma_squared.sqrt(),
                6.. => panic!(
                    "There shouldn't be more than 6 six_sigma_squared estimation points (2 for each dimension)!"
                ),
            }
        };

        if only_use_measurements_with_no_latent_variables {
            let measurements_with_no_latent_variables = measurements
                .iter()
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

        let mut total_weighted_six_sigma_x = 0.0;
        let mut total_weighted_six_sigma_y = 0.0;
        let mut total_weighted_six_sigma_z = 0.0;

        for measurement_index in &measurements_to_work_on {
            let measurement = measurements[*measurement_index];

            let mut x_delta = estimated_position.x.value - measurement.position.x.value;
            let mut y_delta = estimated_position.y.value - measurement.position.y.value;
            let mut z_delta = estimated_position.z.value - measurement.position.z.value;

            // A probe can land exactly on an anchor's axis cutoff. Roundoff
            // must not activate an entire axis-sized update at that boundary.
            // Use the same tolerance as the horizontal Wi-Fi specialization.
            if x_delta.abs() <= measurement.position.x.six_sigma_squared.sqrt() * (1.0 + 1e-12) {
                x_delta = 0.0
            }
            if y_delta.abs() <= measurement.position.y.six_sigma_squared.sqrt() * (1.0 + 1e-12) {
                y_delta = 0.0
            }
            if z_delta.abs() <= measurement.position.z.six_sigma_squared.sqrt() * (1.0 + 1e-12) {
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

            let x_six_sigma_squared = measurement.position.x.six_sigma_squared;
            let y_six_sigma_squared = measurement.position.y.six_sigma_squared;
            let z_six_sigma_squared = measurement.position.z.six_sigma_squared;

            let measurement_distance_std_dev = (measurement.position.x.six_sigma_squared
                + measurement.position.y.six_sigma_squared
                + measurement.position.z.six_sigma_squared)
                .sqrt();
            let weight = if (measurement.distance - estimated_distance).abs()
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

            let update_x = ((x_delta_squared * weight).abs().sqrt() * (x_delta * weight).signum())
                * learning_rate;
            let update_y = ((y_delta_squared * weight).abs().sqrt() * (y_delta * weight).signum())
                * learning_rate;
            let update_z = ((z_delta_squared * weight).abs().sqrt() * (z_delta * weight).signum())
                * learning_rate;

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

            if is_last_phases_iter && can_use_measurement_for_position_update(measurement) {
                let weight = residual_inflation(
                    estimated_distance,
                    measurement.distance,
                    measurement_distance_std_dev,
                );
                if is_x_real {
                    total_weighted_six_sigma_x += x_six_sigma_squared.sqrt() * weight;
                }
                if is_y_real {
                    total_weighted_six_sigma_y += y_six_sigma_squared.sqrt() * weight;
                }
                if is_z_real {
                    total_weighted_six_sigma_z += z_six_sigma_squared.sqrt() * weight;
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

        if is_last_phases_iter {
            estimated_position.x.real = real_measurements_to_work_on_lens.0 > 0.0;
            estimated_position.y.real = real_measurements_to_work_on_lens.1 > 0.0;
            estimated_position.z.real = real_measurements_to_work_on_lens.2 > 0.0;
            estimated_position.x.six_sigma_squared = if real_measurements_to_work_on_lens.0 == 0.0 {
                estimated_position.x.real = false;
                0.0
            } else {
                (total_weighted_six_sigma_x / real_measurements_to_work_on_lens.0).powi(2)
            };
            estimated_position.y.six_sigma_squared = if real_measurements_to_work_on_lens.1 == 0.0 {
                estimated_position.y.real = false;
                0.0
            } else {
                (total_weighted_six_sigma_y / real_measurements_to_work_on_lens.1).powi(2)
            };
            estimated_position.z.six_sigma_squared = if real_measurements_to_work_on_lens.2 == 0.0 {
                estimated_position.z.real = false;
                0.0
            } else {
                (total_weighted_six_sigma_z / real_measurements_to_work_on_lens.2).powi(2)
            };

            actual_estimated_position = estimated_position;
        }

        if is_in_final_six_sigma_squared_estimation {
            let mut six_sigma_squared_estimation_point = estimated_position;
            // If even, we use the lowest and if odd we use the highest since even estimated points had
            // lower initial guesses and odd ones had higher guesses. This way, we are protected against
            // oscillating position estimates since we will always choose the one that yields less
            // reported accuracy.
            let most_general_point = |v1: f64, v2: f64| -> f64 {
                if six_sigma_squared_estimation_point_index % 2 == 0 {
                    v1.min(v2)
                } else {
                    v1.max(v2)
                }
            };
            six_sigma_squared_estimation_point.x.value = most_general_point(
                estimated_position.x.value,
                previous_estimated_position.x.value,
            );
            six_sigma_squared_estimation_point.y.value = most_general_point(
                estimated_position.y.value,
                previous_estimated_position.y.value,
            );
            six_sigma_squared_estimation_point.z.value = most_general_point(
                estimated_position.z.value,
                previous_estimated_position.z.value,
            );

            six_sigma_squared_estimation_points[six_sigma_squared_estimation_point_index] =
                six_sigma_squared_estimation_point;

            if iter_index == iterations - 1 {
                let first_x = six_sigma_squared_estimation_points[0].x;
                let second_x = six_sigma_squared_estimation_points[1].x;
                let first_y = six_sigma_squared_estimation_points[2].y;
                let second_y = six_sigma_squared_estimation_points[3].y;
                let first_z = six_sigma_squared_estimation_points[4].z;
                let second_z = six_sigma_squared_estimation_points[5].z;

                let final_x = (first_x.value + second_x.value) / 2.0;
                let final_x_six_sigma_squared = (first_x.value - second_x.value).div(2.0).powi(2);
                let final_y = (first_y.value + second_y.value) / 2.0;
                let final_y_six_sigma_squared = (first_y.value - second_y.value).div(2.0).powi(2);
                let final_z = (first_z.value + second_z.value) / 2.0;
                let final_z_six_sigma_squared = (first_z.value - second_z.value).div(2.0).powi(2);

                actual_estimated_position.x.value = final_x;
                actual_estimated_position.x.six_sigma_squared = final_x_six_sigma_squared;
                actual_estimated_position.y.value = final_y;
                actual_estimated_position.y.six_sigma_squared = final_y_six_sigma_squared;
                actual_estimated_position.z.value = final_z;
                actual_estimated_position.z.six_sigma_squared = final_z_six_sigma_squared;
            }
        }

        previous_estimated_position = estimated_position;
    }

    actual_estimated_position
}

#[cfg(test)]
mod checked_contract {
    use crate::{
        coordinate::Coordinate, measurement::Measurement,
        multilateration::multilateration_checked as solve, position::Position,
    };

    #[test]
    fn uncertainty_probe_roundoff_cannot_move_the_position() {
        let estimate = |axes: (usize, usize), epsilon: f64| {
            let measurements: Vec<_> = (0..3)
                .map(|index| {
                    let mut coordinates = [Coordinate::new_fake(); 3];
                    coordinates[axes.0] =
                        Coordinate::new_real(if index == 0 { -2000.0 } else { 0.0 }, 1_000_000.0);
                    coordinates[axes.1] =
                        Coordinate::new_real(if index == 0 { epsilon } else { 0.0 }, 1_000_000.0);
                    Measurement {
                        position: Position {
                            x: coordinates[0],
                            y: coordinates[1],
                            z: coordinates[2],
                        },
                        distance: 0.0,
                        weight: 0.0,
                    }
                })
                .collect();
            let initial =
                Position::average(&measurements.iter().map(|m| m.position).collect::<Vec<_>>());
            solve(&measurements, Some(initial), 99).unwrap()
        };
        for axes in [(0, 1), (1, 2), (2, 0)] {
            let baseline = estimate(axes, 0.0);
            for epsilon in [-1e-10, -1e-11, -1e-12, 1e-12, 1e-11, 1e-10] {
                let result = estimate(axes, epsilon);
                let movement = (result.x.value - baseline.x.value)
                    .hypot(result.y.value - baseline.y.value)
                    .hypot(result.z.value - baseline.z.value);
                assert!(
                    movement < 1e-6,
                    "axes {axes:?}: input perturbation {epsilon} moved the result {movement} m"
                );
            }
        }
    }

    #[test]
    fn public_estimator_cannot_report_a_fix_without_observed_coordinates() {
        let m = Measurement {
            position: Position::default(),
            distance: 0.0,
            weight: 0.0,
        };
        assert!(crate::estimate_position(&[m]).is_none());
    }

    fn anchor() -> Measurement {
        Measurement {
            position: Position {
                x: Coordinate::new_real(0.0, 1.0),
                y: Coordinate::new_real(0.0, 1.0),
                z: Coordinate::new_real(0.0, 1.0),
            },
            distance: 4.0,
            weight: 0.0,
        }
    }

    #[test]
    fn empty_and_too_short_requests_return_no_estimate() {
        assert!(solve(&[], None, 50).is_none());
        for iterations in 0..3 {
            assert!(solve(&[anchor()], None, iterations).is_none());
        }
    }

    #[test]
    fn invalid_inputs_do_not_mutate_measurements() {
        for bad in [f64::NAN, f64::INFINITY, -1.0] {
            let mut a = anchor();
            a.position.y.six_sigma_squared = bad;
            let before = a.position.x.value;
            let m = [a];
            assert!(solve(&m, None, 50).is_none());
            assert_eq!(m[0].position.x.value, before);
        }
    }

    #[test]
    fn unobserved_axis_remains_unobserved() {
        let mut a = anchor();
        a.position.x.real = false;
        let m = [a];
        let initial = Position {
            x: Coordinate::new_real(20.0, 0.0),
            y: Coordinate::new_real(0.0, 0.0),
            z: Coordinate::new_real(0.0, 0.0),
        };
        let p = solve(&m, Some(initial), 99).unwrap();
        assert_eq!(m[0].position.x.value, a.position.x.value);
        assert_eq!(m[0].position.y.value, a.position.y.value);
        assert_eq!(m[0].position.z.value, a.position.z.value);
        assert!(!m[0].position.x.real);
        assert!(!p.x.real, "an unobserved axis must remain unobserved");
    }

    #[test]
    fn all_observed_axes_are_marked_real_without_an_initial_guess() {
        let p = solve(&[anchor()], None, 50).unwrap();
        assert!(p.x.real && p.y.real && p.z.real);
    }

    #[test]
    fn zero_tolerance_produces_a_finite_estimate() {
        let m = Measurement {
            position: Position {
                x: Coordinate::new_real(0.0, 0.0),
                y: Coordinate::new_real(0.0, 0.0),
                z: Coordinate::new_real(0.0, 0.0),
            },
            distance: 3.0,
            weight: 0.0,
        };
        let initial = Position {
            x: Coordinate::new_real(20.0, 0.0),
            y: Coordinate::new_real(0.0, 0.0),
            z: Coordinate::new_real(0.0, 0.0),
        };
        // At the last fit iteration the residual is positive. An infinite
        // inflation factor would turn the zero scale into NaN.
        let p = solve(&[m], Some(initial), 3).unwrap();
        for coordinate in [p.x, p.y, p.z] {
            assert!(coordinate.value.is_finite());
            assert!(coordinate.six_sigma_squared.is_finite());
            assert!(coordinate.six_sigma_squared >= 0.0);
        }
    }

    #[test]
    fn exact_well_conditioned_ranges_recover_known_three_dimensional_position() {
        let truth = [2.0_f64, -3.0, 4.0];
        let measurements: Vec<_> = [
            [-10.0, 0.0, 0.0],
            [10.0, 0.0, 0.0],
            [0.0, -10.0, 0.0],
            [0.0, 10.0, 0.0],
            [0.0, 0.0, -10.0],
            [0.0, 0.0, 10.0],
        ]
        .into_iter()
        .map(|p| Measurement {
            position: Position {
                x: Coordinate::new_real(p[0], 1e-12),
                y: Coordinate::new_real(p[1], 1e-12),
                z: Coordinate::new_real(p[2], 1e-12),
            },
            distance: (p[0] - truth[0])
                .hypot(p[1] - truth[1])
                .hypot(p[2] - truth[2]),
            weight: 0.0,
        })
        .collect();
        let p = solve(&measurements, None, 999).unwrap();
        let error = (p.x.value - truth[0])
            .hypot(p.y.value - truth[1])
            .hypot(p.z.value - truth[2]);
        assert!(error < 1e-3, "exact, well-conditioned 3D error = {error} m");
    }
}
