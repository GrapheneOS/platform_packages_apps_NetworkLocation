package app.grapheneos.networklocation.interop.position_estimation

data class Measurement(
    var position: Position,
    var distance: Double,
    var probability: Double,
)
