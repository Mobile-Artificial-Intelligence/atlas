package com.danemadsen.atlas.beerouter.map.generator

import java.io.File

public class ElevationRasterTileConverter {
    public fun getRaster(file: File, lon: Double, lat: Double): ElevationRaster {
        throw UnsupportedOperationException("HGT conversion path is not implemented in this port yet: $file @ $lon,$lat")
    }
}
