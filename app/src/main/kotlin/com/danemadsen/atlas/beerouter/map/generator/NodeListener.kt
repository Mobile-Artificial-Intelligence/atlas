package com.danemadsen.atlas.beerouter.map.generator

import java.io.File

public interface NodeListener {
    public fun nodeFileStart(nodefile: File?)

    public fun nextNode(data: NodeData)

    public fun nodeFileEnd(nodefile: File?)
}
