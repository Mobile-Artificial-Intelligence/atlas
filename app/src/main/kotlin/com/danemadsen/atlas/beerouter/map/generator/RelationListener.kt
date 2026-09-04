package com.danemadsen.atlas.beerouter.map.generator

public interface RelationListener {
    public fun nextRelation(data: RelationData)

    public fun nextRestriction(data: RelationData, fromWid: Long, toWid: Long, viaNid: Long)
}
