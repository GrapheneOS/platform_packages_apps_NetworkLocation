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
    pub fn average(
        positions: impl std::clone::Clone + std::iter::ExactSizeIterator<Item = Position>,
    ) -> Self {
        Position {
            x: Coordinate::new_real(
                positions.clone().map(|p| p.x.value).sum::<f64>() / positions.len() as f64,
                positions.clone().map(|p| p.x.variance).sum::<f64>() / positions.len() as f64,
            ),
            y: Coordinate::new_real(
                positions.clone().map(|p| p.y.value).sum::<f64>() / positions.len() as f64,
                positions.clone().map(|p| p.y.variance).sum::<f64>() / positions.len() as f64,
            ),
            z: Coordinate::new_real(
                positions.clone().map(|p| p.z.value).sum::<f64>() / positions.len() as f64,
                positions.clone().map(|p| p.z.variance).sum::<f64>() / positions.len() as f64,
            ),
        }
    }
}
