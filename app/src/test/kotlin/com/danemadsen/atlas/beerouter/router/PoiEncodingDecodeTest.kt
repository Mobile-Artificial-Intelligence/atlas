package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.expressions.BExpressionContextNode
import com.danemadsen.atlas.beerouter.expressions.BExpressionMetaData
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PoiEncodingDecodeTest {
    private lateinit var nodeContext: BExpressionContextNode

    @BeforeTest
    fun before() {
        ensureGeneratedTestSegmentDir()
        val segmentDir = generatedTestSegmentDir
        val lookupContent = SystemFileSystem.source(lookupPathForSegments(segmentDir)).buffered()
            .use { it.readString() }
        val meta = BExpressionMetaData()
        nodeContext = BExpressionContextNode(meta)
        meta.readMetaData(lookupContent)
    }

    @Test
    fun encodeDecodeToiletTags() {
        val lookupData = nodeContext.createNewLookupData()!!
        nodeContext.addLookupValue("amenity", "toilets", lookupData)
        nodeContext.addLookupValue("access", "yes", lookupData)
        val desc = nodeContext.encode(lookupData)!!
        val tags = nodeContext.getMap(false, desc)
        assertEquals("toilets", tags["amenity"], "amenity should decode to toilets, got: $tags")
        assertEquals("yes", tags["access"], "access should decode to yes, got: $tags")
    }

    @Test
    fun encodeDecodeCafeTags() {
        val lookupData = nodeContext.createNewLookupData()!!
        nodeContext.addLookupValue("amenity", "cafe", lookupData)
        val desc = nodeContext.encode(lookupData)!!
        val tags = nodeContext.getMap(false, desc)
        assertEquals("cafe", tags["amenity"], "amenity should decode to cafe, got: $tags")
    }
}