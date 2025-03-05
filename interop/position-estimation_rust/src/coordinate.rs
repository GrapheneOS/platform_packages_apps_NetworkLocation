//! Documentation for coordinate module.

/// Coordinate.
#[derive(Clone, Copy, Debug)]
pub struct Coordinate {
    /// whether the value is real (not estimated based on other data)
    pub real: bool,
    /// coordinate value
    pub value: f64,
    /// variance (1-sigma²)
    pub variance: f64,
}

impl Coordinate {
    /// new real coordinate
    pub fn new_real(value: f64, variance: f64) -> Self {
        Coordinate {
            real: true,
            value,
            variance,
        }
    }

    /// new fake coordinate
    pub fn new_fake() -> Self {
        Coordinate {
            real: false,
            value: 0.0,
            variance: 0.0,
        }
    }
}
