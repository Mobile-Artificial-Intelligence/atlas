package com.danemadsen.atlas.beerouter.map.generator

import androidx.collection.MutableLongList

public class RelationData(id: Long, public var ways: MutableLongList = MutableLongList(16)) : GeneratorBase() {
    public var rid: Long = id
    public var description: Long = 0
}
