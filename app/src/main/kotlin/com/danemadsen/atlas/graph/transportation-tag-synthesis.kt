package com.danemadsen.atlas.graph

/**
 * Synthesizes BRouter-compatible OSM tags from OpenMapTiles `transportation`
 * layer properties.
 *
 * A PMTiles archive built with the standard OMT (Planetiler) profile carries
 * pre-classified roads, while BeeRouter's routing profiles key off raw OSM
 * tag values — first and foremost `highway`. This mapping reconstructs the
 * tags the profiles can actually evaluate; anything the `lookups.dat` table
 * does not define is silently dropped by the encoder anyway.
 *
 * Properties actually observed in Planetiler-built archives (see
 * OmtPropertyDumpTest): `class` (incl. `track`, `pier` and
 * `<class>_construction` variants), `subclass`, `brunnel`, `oneway`, `ramp`,
 * `service` (the service-road detail) and — on tagged features — `surface`,
 * `access`, `bicycle`, `foot`.
 * `name`/`ref` live only in the separate `transportation_name` layer and the
 * lookup table has no key for them, so street names cannot travel into the
 * routing graph; turn instructions are direction-only. `layer` and `ford`
 * have no lookup key either and are not synthesized.
 *
 * Deliberately NOT synthesized: maxspeed (absent from OMT), turn
 * restrictions and route relations (do not exist in MVT at all).
 */
object TransportationTagSynthesis {

    /**
     * Routable road classes and the OSM `highway` value they map onto.
     *
     * `minor` merges OSM's residential/unclassified/living_street, which the
     * profiles cost quite differently (unclassified: 50 km/h, priority 20;
     * residential/living_street: 30 km/h, priority 6). The conservative
     * single choice is `residential`: mis-costing a few country lanes slow
     * is far safer than letting every city street look like a 50 km/h
     * through-road to the car profile.
     */
    private val CLASS_TO_HIGHWAY = mapOf(
        "motorway" to "motorway",
        "trunk" to "trunk",
        "primary" to "primary",
        "secondary" to "secondary",
        "tertiary" to "tertiary",
        "minor" to "residential",
        "service" to "service",
        "track" to "track",
        // not a road, but a walkable structure: nearest routable OSM value
        "pier" to "footway",
    )

    /** Classes whose OMT `ramp` flag selects the OSM `_link` variant. */
    private val RAMPABLE_CLASSES = setOf("motorway", "trunk", "primary", "secondary", "tertiary")

    /** OMT `path` subclasses and the OSM `highway` value they map onto. */
    private val PATH_SUBCLASS_TO_HIGHWAY = mapOf(
        "footway" to "footway",
        "cycleway" to "cycleway",
        "pedestrian" to "pedestrian",
        "track" to "track",
        "path" to "path",
        "bridleway" to "bridleway",
        "steps" to "steps",
        "corridor" to "corridor",
        "platform" to "platform",
    )

    /** OSM `highway=service` detail values worth carrying across. */
    private val SERVICE_DETAILS = setOf(
        "alley", "driveway", "parking_aisle", "drive-through", "emergency_access",
    )

    /**
     * Transportation classes that carry no routable geometry for any of our
     * profiles (rail, ferries, aerialways, busways, race circuits).
     *
     * Ferries stay dropped for parity, not for taste: `all.brf` would retain
     * them via `route=`, but `lookups.dat` defines no `route` key, so the
     * PBF reference pipeline cannot encode one either — a ferry way dies at
     * `encode()` there exactly as `class=ferry` dies here. Raceways die the
     * same way: `lookups.dat` defines no `raceway` highway value, so the
     * encoder drops them whether or not we synthesize the tag (they would
     * not be drivable public roads regardless — the fixture's 49 features
     * are the Albert Park GP circuit and kart tracks).
     */
    private val DROPPED_CLASSES = setOf(
        "major_rail", "minor_rail", "rail", "transit", "aerialway",
        "ferry", "busway", "bus_guideway", "construction", "raceway",
    )

    /** Sparse OSM tags the `lookups.dat` table defines and profiles evaluate. */
    private val CARRIED_STRING_TAGS = listOf("surface", "access", "bicycle", "foot")

    /**
     * Maps one OMT transportation feature's properties to OSM-style routing
     * tags, or null when the feature is not routable (rail, ferry, roads
     * under construction, unknown or future class values — the caller drops
     * those ways entirely).
     */
    fun synthesizeTags(properties: Map<String, Any?>): Map<String, String>? {
        val class_name = properties.stringValue(CLASS) ?: return null
        val subclass = properties.stringValue(SUBCLASS)
        if (subclass == CONSTRUCTION) return null
        if (isUnderConstruction(class_name)) return null

        val highway = highwayValue(class_name, subclass, properties.isTruthy(RAMP))
            ?: return null

        val tags = LinkedHashMap<String, String>()
        tags[HIGHWAY] = highway

        // OMT carries the service-road detail in the `service` property
        // (subclass is only set for path/rail classes) — but only for the
        // known detail values; anything else is a plain service road.
        if (class_name == SERVICE) {
            val detail = properties.stringValue(SERVICE)
            if (detail != null && detail in SERVICE_DETAILS) {
                tags[SERVICE] = detail
            }
        }

        when (properties.intValue(ONEWAY)) {
            1 -> tags[ONEWAY] = YES
            -1 -> tags[ONEWAY] = REVERSE
        }

        when (properties.stringValue(BRUNNEL)) {
            BRIDGE -> tags[BRIDGE] = YES
            TUNNEL -> tags[TUNNEL] = YES
        }

        // Sparse tags: present only on features OSM carries them on, and
        // dropped by the encoder when the profile lookup doesn't know them.
        for (key in CARRIED_STRING_TAGS) {
            properties.stringValue(key)?.let { if (it.isNotBlank()) tags[key] = it }
        }

        return tags
    }

    /**
     * Whether [class_name] is a known transportation class — mapped to a
     * highway value or explicitly dropped. Used by the class-census test to
     * fail loudly when a future OMT schema adds values this mapping (and its
     * tests) have never seen.
     */
    fun isKnownClass(class_name: String): Boolean {
        if (class_name in CLASS_TO_HIGHWAY) return true
        if (class_name in DROPPED_CLASSES) return true
        if (class_name == PATH) return true
        // Planetiler encodes highway=construction as "<base>_construction"
        // where <base> is the class the road will have once finished.
        if (class_name.endsWith(CONSTRUCTION_SUFFIX)) {
            val base = class_name.removeSuffix(CONSTRUCTION_SUFFIX)
            return base in CLASS_TO_HIGHWAY || base in DROPPED_CLASSES || base == PATH
        }
        return false
    }

    /** `<class>_construction` features are not routable until they exist. */
    private fun isUnderConstruction(class_name: String): Boolean =
        class_name.endsWith(CONSTRUCTION_SUFFIX) && isKnownClass(class_name)

    private fun highwayValue(
        class_name: String,
        subclass: String?,
        is_ramp: Boolean,
    ): String? = when {
        class_name in RAMPABLE_CLASSES && is_ramp ->
            CLASS_TO_HIGHWAY[class_name] + LINK_SUFFIX
        class_name in CLASS_TO_HIGHWAY ->
            CLASS_TO_HIGHWAY[class_name]
        class_name == PATH ->
            if (subclass != null) PATH_SUBCLASS_TO_HIGHWAY[subclass] ?: PATH else PATH
        else -> null
    }

    // ---- MVT value coercion (properties arrive as String/Long/Double/Bool) ----

    private fun Map<String, Any?>.stringValue(key: String): String? = when (val value = this[key]) {
        is String -> value
        else -> null
    }

    private fun Map<String, Any?>.intValue(key: String): Int? = when (val value = this[key]) {
        is Long -> value.toInt()
        is Int -> value
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toInt() else null
        else -> null
    }

    private fun Map<String, Any?>.isTruthy(key: String): Boolean = when (val value = this[key]) {
        is Long -> value == 1L
        is Int -> value == 1
        is Boolean -> value
        else -> false
    }

    private const val CLASS = "class"
    private const val SUBCLASS = "subclass"
    private const val RAMP = "ramp"
    private const val BRUNNEL = "brunnel"
    private const val ONEWAY = "oneway"
    private const val HIGHWAY = "highway"
    private const val SERVICE = "service"
    private const val BRIDGE = "bridge"
    private const val TUNNEL = "tunnel"
    private const val YES = "yes"
    private const val REVERSE = "-1"
    private const val PATH = "path"
    private const val CONSTRUCTION = "construction"
    private const val CONSTRUCTION_SUFFIX = "_construction"
    private const val LINK_SUFFIX = "_link"
}