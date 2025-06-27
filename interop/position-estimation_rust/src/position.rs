//! Documentation for position module.

use crate::coordinate::Coordinate;

/// Position.
#[derive(Clone, Copy, Debug)]
pub struct Position {
    /// x coordinate
    pub x: Coordinate,
    /// y coordinate
    pub y: Coordinate,
    /// z coordinate
    pub z: Coordinate,
}

impl Default for Position {
    fn default() -> Self {
        Self {
            x: Coordinate::new_fake(),
            y: Coordinate::new_fake(),
            z: Coordinate::new_fake(),
        }
    }
}

impl Position {
    /// Gets the average position.
    pub fn average(positions: &[Position]) -> Self {
        let positions_len = positions.len() as f64;

        Position {
            x: Coordinate::new_real(
                positions.iter().map(|p| p.x.value).sum::<f64>() / positions_len,
                positions.iter().map(|p| p.x.variance).sum::<f64>() / positions_len,
            ),
            y: Coordinate::new_real(
                positions.iter().map(|p| p.y.value).sum::<f64>() / positions_len,
                positions.iter().map(|p| p.y.variance).sum::<f64>() / positions_len,
            ),
            z: Coordinate::new_real(
                positions.iter().map(|p| p.z.value).sum::<f64>() / positions_len,
                positions.iter().map(|p| p.z.variance).sum::<f64>() / positions_len,
            ),
        }
    }
}
