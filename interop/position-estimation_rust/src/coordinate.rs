//! Documentation for coordinate module.

/// Coordinate.
#[derive(Clone, Copy, Debug)]
pub struct Coordinate {
    /// whether the value is real (not estimated based on other data)
    pub real: bool,
    /// coordinate value
    pub value: f64,
    /// Squared uncertainty scale in m², used for residual tests, weighting,
    /// and uncertainty probes.
    /// Wi-Fi uses six-sigma output scaling and divides the square root by six.
    /// Cellular reports the square root directly.
    /// See tools/accuracy-semantics.md for input construction and reporting.
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
