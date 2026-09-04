package com.danemadsen.atlas.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportationTagSynthesisTest {

    @Test
    fun mapsRoadClassesToHighwayValues() {
        assertEquals(
            mapOf("highway" to "motorway"),
            TransportationTagSynthesis.synthesizeTags(mapOf("class" to "motorway")),
        )
        assertEquals(
            mapOf("highway" to "residential"),
            TransportationTagSynthesis.synthesizeTags(mapOf("class" to "minor")),
        )
        assertEquals(
            mapOf("highway" to "service"),
            TransportationTagSynthesis.synthesizeTags(mapOf("class" to "service")),
        )
    }

    @Test
    fun rampSelectsLinkVariants() {
        val motorway = TransportationTagSynthesis
            .synthesizeTags(mapOf("class" to "motorway", "ramp" to 1L))
        assertEquals("motorway_link", motorway?.get("highway"))

        // minor has no link variant in OSM; ramp is simply ignored
        val minor = TransportationTagSynthesis
            .synthesizeTags(mapOf("class" to "minor", "ramp" to true))
        assertEquals("residential", minor?.get("highway"))
    }

    @Test
    fun pathSubclassSelectsHighway() {
        for ((subclass, highway) in mapOf(
            "footway" to "footway",
            "cycleway" to "cycleway",
            "pedestrian" to "pedestrian",
            "track" to "track",
            "corridor" to "corridor",
            "platform" to "platform",
        )) {
            val tags = TransportationTagSynthesis
                .synthesizeTags(mapOf("class" to "path", "subclass" to subclass))
            assertEquals(highway, tags?.get("highway"), "subclass $subclass")
        }
        // unknown or absent subclass falls back to a generic path
        assertEquals(
            "path",
            TransportationTagSynthesis.synthesizeTags(mapOf("class" to "path"))
                ?.get("highway"),
        )
        assertEquals(
            "path",
            TransportationTagSynthesis.synthesizeTags(mapOf("class" to "path", "subclass" to "hanging_bridge"))
                ?.get("highway"),
        )
    }

    @Test
    fun mapsTrackAndPierClasses() {
        // newer OMT/Planetiler emits track and pier as top-level classes
        assertEquals(
            "track",
            TransportationTagSynthesis.synthesizeTags(mapOf("class" to "track"))
                ?.get("highway"),
        )
        val pier = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "pier", "access" to "no"),
        )
        assertEquals("footway", pier?.get("highway"))
        assertEquals("no", pier?.get("access"))
    }

    @Test
    fun dropsNonRoutableClasses() {
        for (class_name in listOf(
            "major_rail", "minor_rail", "ferry", "aerialway", "transit", "raceway",
        )) {
            assertNull(
                TransportationTagSynthesis.synthesizeTags(mapOf("class" to class_name)),
                "class $class_name should be dropped",
            )
            assertTrue(TransportationTagSynthesis.isKnownClass(class_name))
        }
        assertNull(TransportationTagSynthesis.synthesizeTags(mapOf("subclass" to "footway")))
        assertNull(TransportationTagSynthesis.synthesizeTags(emptyMap()))
    }

    @Test
    fun dropsConstruction() {
        // Planetiler encodes highway=construction as "<class>_construction"
        for (class_name in listOf(
            "minor_construction", "motorway_construction", "tertiary_construction",
            "service_construction", "path_construction", "trunk_construction",
        )) {
            assertTrue(TransportationTagSynthesis.isKnownClass(class_name), class_name)
            assertNull(
                TransportationTagSynthesis.synthesizeTags(mapOf("class" to class_name)),
                class_name,
            )
        }
        // older schema variant: subclass=construction on a routable class
        assertNull(
            TransportationTagSynthesis.synthesizeTags(mapOf("class" to "minor", "subclass" to "construction")),
        )
    }

    @Test
    fun unknownFutureClassIsDroppedNotGuessed() {
        // A class the mapping has never seen must fail the census test, not
        // silently route traffic over it.
        assertFalse(TransportationTagSynthesis.isKnownClass("hypertube"))
        assertFalse(TransportationTagSynthesis.isKnownClass("hypertube_construction"))
        assertNull(TransportationTagSynthesis.synthesizeTags(mapOf("class" to "hypertube")))
    }

    @Test
    fun carriesOnewayBothDirections() {
        val forward = TransportationTagSynthesis
            .synthesizeTags(mapOf("class" to "motorway", "oneway" to 1L))
        assertEquals("yes", forward?.get("oneway"))

        val reverse = TransportationTagSynthesis
            .synthesizeTags(mapOf("class" to "motorway", "oneway" to -1L))
        assertEquals("-1", reverse?.get("oneway"))

        val twoway = TransportationTagSynthesis
            .synthesizeTags(mapOf("class" to "motorway", "oneway" to 0L))
        assertEquals(null, twoway?.get("oneway"))
    }

    @Test
    fun carriesBrunnelButNotLayerOrFord() {
        // bridge/tunnel have lookup keys; layer and ford do not, so they are
        // not synthesized at all
        val bridge = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "minor", "brunnel" to "bridge", "layer" to 1L),
        )
        assertEquals("yes", bridge?.get("bridge"))
        assertNull(bridge?.get("layer"))
        assertNull(bridge?.get("tunnel"))

        val tunnel = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "motorway", "brunnel" to "tunnel", "layer" to -3L),
        )
        assertEquals("yes", tunnel?.get("tunnel"))
        assertNull(tunnel?.get("layer"))
    }

    @Test
    fun dropsNameAndRef() {
        // OMT keeps name/ref only in the transportation_name layer, and the
        // lookup table has no key for them — they must not be synthesized.
        val tags = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "motorway", "ref" to "M 1", "name" to "Monash Freeway"),
        )
        assertNull(tags?.get("name"))
        assertNull(tags?.get("ref"))
    }

    @Test
    fun carriesSparseRoutingTags() {
        // surface/access/bicycle/foot are present only on tagged features;
        // the profiles evaluate them when the lookup knows the value.
        val tags = TransportationTagSynthesis.synthesizeTags(
            mapOf(
                "class" to "path", "subclass" to "footway",
                "surface" to "paved", "foot" to "designated", "bicycle" to "no",
                "access" to "no",
            ),
        )
        assertEquals("paved", tags?.get("surface"))
        assertEquals("designated", tags?.get("foot"))
        assertEquals("no", tags?.get("bicycle"))
        assertEquals("no", tags?.get("access"))
        // absent tags stay absent — no defaults invented here
        val bare = TransportationTagSynthesis.synthesizeTags(mapOf("class" to "tertiary"))
        assertNull(bare?.get("surface"))
        assertNull(bare?.get("access"))
    }

    @Test
    fun serviceDetailSurvivesOnlyForKnownValues() {
        // OMT carries the detail in the `service` property (subclass is only
        // for path/rail); OSM semantics: highway=service + service=parking_aisle
        val aisle = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "service", "service" to "parking_aisle"),
        )
        assertEquals("service", aisle?.get("highway"))
        assertEquals("parking_aisle", aisle?.get("service"))

        val weird = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "service", "service" to "not_a_service_kind"),
        )
        assertEquals(null, weird?.get("service"))

        // a stray subclass on a service way is not a detail source
        val stray = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "service", "subclass" to "parking_aisle"),
        )
        assertEquals(null, stray?.get("service"))
    }

    @Test
    fun ignoresNonStringScalars() {
        // MVT numbers arrive as Long/Double — class must be a string or the
        // feature is dropped rather than crashing; layer has no lookup key
        // and is not synthesized whatever its type.
        assertNull(TransportationTagSynthesis.synthesizeTags(mapOf("class" to 3L)))
        val layer = TransportationTagSynthesis.synthesizeTags(
            mapOf("class" to "minor", "layer" to 1.0),
        )
        assertEquals("residential", layer?.get("highway"))
        assertNull(layer?.get("layer"))
    }
}