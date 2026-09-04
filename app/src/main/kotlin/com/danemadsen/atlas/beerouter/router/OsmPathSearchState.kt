package com.danemadsen.atlas.beerouter.router

internal data class OsmPathSearchState(
    var lastClassifier: Float = 0f,
    var lastInitialCost: Float = 0f,
    var priorityclassifier: Int = 0,
    var bitfield: Int = OsmPath.PATH_START_BIT,
)
