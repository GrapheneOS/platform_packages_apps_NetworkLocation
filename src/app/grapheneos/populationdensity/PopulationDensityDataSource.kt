package app.grapheneos.populationdensity

/** Supplies coarse S2 cells derived from population density data. */
fun interface PopulationDensityDataSource {
    /**
     * Finds the deepest database cell containing the supplied coordinate.
     */
    fun getCoarseLocationCellId(
        latitude: Double,
        longitude: Double,
    ): Long
}
