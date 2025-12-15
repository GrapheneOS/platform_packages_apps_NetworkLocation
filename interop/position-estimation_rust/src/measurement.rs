//! Documentation for measurement module.

use crate::position::Position;

/// Measurement.
#[derive(Clone, Copy)]
pub struct Measurement {
    /// position of measurement
    pub position: Position,
    /// estimated distance away from device
    pub distance: f64,
    /// weight of measurement
    pub weight: f64,
}
