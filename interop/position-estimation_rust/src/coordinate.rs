//! Documentation for coordinate module.

/// Coordinate.
#[derive(Clone, Copy, Debug)]
pub struct Coordinate {
    /// whether the value is real (not estimated based on other data)
    pub real: bool,
    /// coordinate value
    pub value: f64,
    /// 6-sigma²
    pub six_sigma_squared: f64,
}

impl Coordinate {
    /// new real coordinate
    pub fn new_real(value: f64, six_sigma_squared: f64) -> Self {
        Coordinate {
            real: true,
            value,
            six_sigma_squared,
        }
    }

    /// new fake coordinate
    pub fn new_fake() -> Self {
        Coordinate {
            real: false,
            value: 0.0,
            six_sigma_squared: 0.0,
        }
    }
}
