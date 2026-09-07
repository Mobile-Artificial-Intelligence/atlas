package com.danemadsen.atlas.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage of the geo: URI parser (no android.net.Uri — the tests
 * drive the String overload directly, so they run without Robolectric).
 */
class MapIntentParserTest {

    // ---- the five shapes from the product request ----

    @Test
    fun `bare coordinate`() {
        val r = MapIntentParser.parse("geo:-27.4698,153.0251")
        assertEquals(MapIntentRequest.Coordinate(-27.4698, 153.0251, null, null), r)
    }

    @Test
    fun `coordinate with zoom`() {
        val r = MapIntentParser.parse("geo:-27.4698,153.0251?z=16")
        assertEquals(MapIntentRequest.Coordinate(-27.4698, 153.0251, 16.0, null), r)
    }

    @Test
    fun `plain text search`() {
        val r = MapIntentParser.parse("geo:0,0?q=Brisbane")
        assertEquals(MapIntentRequest.Search("Brisbane", null, null), r)
    }

    @Test
    fun `search anchored on the path coordinates`() {
        val q = MapIntentParser.parse("geo:-27.4698,153.0251?q=coffee") as MapIntentRequest.Search
        assertEquals("coffee", q.query)
        assertEquals(-27.4698, q.latitude!!, 1e-12)
        assertEquals(153.0251, q.longitude!!, 1e-12)
    }

    @Test
    fun `coordinate in query with label`() {
        val r = MapIntentParser.parse("geo:0,0?q=-27.4698,153.0251(Brisbane)")
        assertEquals(MapIntentRequest.Coordinate(-27.4698, 153.0251, null, "Brisbane"), r)
    }

    // ---- coordinates, negative and out of range ----

    @Test
    fun `negative coordinates`() {
        val r = MapIntentParser.parse("geo:-27.4698,-153.0251?z=4.5")
        assertEquals(MapIntentRequest.Coordinate(-27.4698, -153.0251, 4.5, null), r)
    }

    @Test
    fun `invalid latitude rejected`() {
        assertNull(MapIntentParser.parse("geo:-91,0"))
        assertNull(MapIntentParser.parse("geo:91,0"))
    }

    @Test
    fun `invalid longitude rejected`() {
        assertNull(MapIntentParser.parse("geo:0,181"))
        assertNull(MapIntentParser.parse("geo:0,-181"))
    }

    @Test
    fun `non-numeric coordinates rejected`() {
        assertNull(MapIntentParser.parse("geo:abc,def"))
        assertNull(MapIntentParser.parse("geo:,"))
        assertNull(MapIntentParser.parse("geo:-27.4698"))
    }

    @Test
    fun `malformed URIs rejected`() {
        assertNull(MapIntentParser.parse("not a geo uri at all"))
        assertNull(MapIntentParser.parse("http://example.com/?q=x"))
        assertNull(MapIntentParser.parse("geo:"))
        assertNull(MapIntentParser.parse("geo:?q=Brisbane"))
        // 0,0 with no usable query: "look at nothing in particular" — no-op.
        assertNull(MapIntentParser.parse("geo:0,0;crs=wgs84"))
        assertNull(MapIntentParser.parse(""))
        assertNull(MapIntentParser.parse(null as String?))
    }

    // ---- zoom ----

    @Test
    fun `fractional zoom respected`() {
        val r = MapIntentParser.parse("geo:10.0,20.0?z=16.5")
        assertEquals(16.5, (r as MapIntentRequest.Coordinate).zoom!!, 1e-12)
    }

    @Test
    fun `absurd zoom dropped, request survives`() {
        assertEquals(
            MapIntentRequest.Coordinate(10.0, 20.0, null, null),
            MapIntentParser.parse("geo:10.0,20.0?z=99"),
        )
        assertEquals(
            MapIntentRequest.Coordinate(10.0, 20.0, null, null),
            MapIntentParser.parse("geo:10.0,20.0?z=-3"),
        )
    }

    @Test
    fun `malformed zoom ignored, request survives`() {
        val r = MapIntentParser.parse("geo:10.0,20.0?z=banana")
        assertEquals(MapIntentRequest.Coordinate(10.0, 20.0, null, null), r)
    }

    // ---- queries and labels ----

    @Test
    fun `empty query rejected`() {
        assertNull(MapIntentParser.parse("geo:0,0?q="))
        assertNull(MapIntentParser.parse("geo:0,0?"))
        assertNull(MapIntentParser.parse("geo:0,0?q=%20%20"))
    }

    @Test
    fun `percent-encoded query decoded`() {
        val r = MapIntentParser.parse("geo:0,0?q=Brisbane%20City%20Hall")
        assertEquals(MapIntentRequest.Search("Brisbane City Hall", null, null), r)
    }

    @Test
    fun `percent-encoded label decoded`() {
        val r = MapIntentParser.parse("geo:0,0?q=-27.4698,153.0251(Brisbane%20City)")
        assertEquals(MapIntentRequest.Coordinate(-27.4698, 153.0251, null, "Brisbane City"), r)
    }

    @Test
    fun `plus is NOT a space in geo queries`() {
        val r = MapIntentParser.parse("geo:0,0?q=coffee+beans")
        assertEquals(MapIntentRequest.Search("coffee+beans", null, null), r)
    }

    @Test
    fun `labels containing spaces`() {
        val r = MapIntentParser.parse("geo:0,0?q=10.0,20.0(Two%20Words%20Here)")
        assertEquals(MapIntentRequest.Coordinate(10.0, 20.0, null, "Two Words Here"), r)
    }

    @Test
    fun `dangling percent survives as literal`() {
        val r = MapIntentParser.parse("geo:0,0?q=100%25")
        assertEquals(MapIntentRequest.Search("100%", null, null), r)
    }

    @Test
    fun `uppercase scheme and param names accepted`() {
        val r = MapIntentParser.parse("GEO:10.0,20.0?Z=14&Q=cafe")
        assertEquals(MapIntentRequest.Search("cafe", 10.0, 20.0), r)
    }
}