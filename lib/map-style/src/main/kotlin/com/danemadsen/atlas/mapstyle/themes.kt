package com.danemadsen.atlas.mapstyle

/**
 * A map color theme: one palette entry per `{{token}}` marker in the shared
 * style template. Light is OSM Liberty's original palette; dark is modeled on
 * Google Maps' dark basemap (near-black base, desaturated blue-gray roads,
 * blue-tinted water, and aqua-teal vegetation instead of CARTO's greens).
 */
data class AtlasMapTheme(
    val name: String,
    val colors: Map<String, String>,
    /**
     * Layers to hide in this theme. The pedestrian-area and wetland fill
     * patterns come from the (light) sprite, so they cannot be recolored —
     * dark switches them off rather than showing light pinstripes.
     */
    val hiddenLayers: Set<String> = emptySet(),
) {

    /**
     * The map's "brand family" — the road colors that carry a hue and the
     * transit label color. These follow the Material You accent's hue; the
     * base land/water/building palette stays fixed so a red wallpaper never
     * turns the oceans to lava.
     */
    private val accent_tokens = setOf(
        "roadCasing",
        "roadMajor",
        "motorwayLow",
        "motorway",
        "tunnelLink",
        "tunnelMotorway",
        "textTransit",
    )

    /**
     * Re-hues the [accent_tokens] colors toward the Material You accent
     * ([accent_argb], e.g. `colorScheme.primary`): each token keeps its own
     * saturation and lightness — light stays pastel, dark stays near-black —
     * and takes the accent's hue, so the map changes *which color it sings
     * in*, not how loud it is. Near-gray tokens (dark's motorways) adopt a
     * restrained slice of the accent's saturation, or the swap would be a
     * no-op on them. A null accent (no dynamic color, pre-Android-12) returns
     * the theme untouched, preserving the stock look. Unparseable colors fall
     * back to the original rather than crashing the map over a color.
     */
    fun withMaterialAccent(accent_argb: Int?): AtlasMapTheme {
        if (accent_argb == null) return this
        val retinted = colors.mapValues { (token, color) ->
            if (token in accent_tokens) MapColors.rehue(color, accent_argb) ?: color else color
        }
        if (retinted == colors) return this
        return copy(name = "$name (accent)", colors = retinted)
    }
}

object Themes {
    val LIGHT: AtlasMapTheme = AtlasMapTheme(
        name = "OSM Liberty",
        colors = mapOf(
            "background" to "rgb(239,239,239)",
            "park" to "#d8e8c8",
            "parkOutlineFill" to "rgba(95, 208, 100, 1)",
            "parkOutline" to "rgba(228, 241, 215, 1)",
            "residentialLow" to "hsla(0, 3%, 85%, 0.84)",
            "residentialHigh" to "hsla(35, 57%, 88%, 0.49)",
            "wood" to "hsla(98, 61%, 72%, 0.7)",
            "grass" to "rgba(176, 213, 154, 1)",
            "ice" to "rgba(224, 236, 236, 1)",
            "pitch" to "#DEE3CD",
            "cemetery" to "hsl(75, 37%, 81%)",
            "hospital" to "#fde",
            "school" to "rgb(236,238,204)",
            "sand" to "rgba(247, 239, 195, 1)",
            "water" to "rgb(198,218,255)",
            "waterway" to "#c6def6",
            "waterLabel" to "#5d60be",
            "aeroway" to "rgba(229, 228, 224, 1)",
            "runway" to "#f0ede9",
            "roadCasing" to "#e9ac77",
            "roadCasingMinor" to "#cfcdca",
            "path" to "hsl(0, 0%, 100%)",
            "road" to "#fff",
            "roadMajor" to "#fea",
            "motorwayLow" to "hsl(26, 87%, 62%)",
            "motorway" to "#fc8",
            "tunnelLink" to "#fff4c6",
            "tunnelMotorway" to "#ffdaa6",
            "bridgeStreetCasing" to "hsl(36, 6%, 74%)",
            "bridgePathCasing" to "hsl(35, 6%, 80%)",
            "rail" to "#bbb",
            "building" to "hsl(35, 8%, 85%)",
            "buildingOutlineLow" to "hsla(35, 6%, 79%, 0.32)",
            "buildingOutline" to "hsl(35, 6%, 79%)",
            "boundaryCounty" to "#9e9cab",
            "boundaryState" to "hsl(248, 1%, 41%)",
            "textHalo" to "rgba(255,255,255,0.8)",
            "textHaloSoft" to "rgba(255,255,255,0.7)",
            "textHaloSolid" to "#ffffff",
            "textPoi" to "#666",
            "textTransit" to "#4898ff",
            "textRoad" to "#765",
            "textPlace" to "#333",
            "textRegion" to "#633",
            "textCountry" to "#334",
        ),
    )

    val DARK: AtlasMapTheme = AtlasMapTheme(
        name = "Dark Matter",
        colors = mapOf(
            "background" to "#0e0e0e",
            // Vegetation goes aqua/teal, the way Google Maps' dark basemap
            // shades its parks — a green-teal that reads as "plant life" on a
            // near-black base without the CARTO greens' yellow cast.
            "park" to "#10201c",
            "parkOutlineFill" to "#0e0e0e",
            "parkOutline" to "#0e0e0e",
            "residentialLow" to "#080808",
            "residentialHigh" to "#0a0a0a",
            "wood" to "#0e1a17",
            "grass" to "#14261f",
            "ice" to "#1e262c",
            "pitch" to "#12221d",
            "cemetery" to "#11211d",
            "hospital" to "#1c1517",
            "school" to "#191b13",
            "sand" to "#1c1912",
            "water" to "#2c353c",
            "waterway" to "#3f5a6d",
            "waterLabel" to "rgba(158,168,173,1)",
            "aeroway" to "#191919",
            "runway" to "#232323",
            "roadCasing" to "#1a1d24",
            "roadCasingMinor" to "#14161c",
            "path" to "#262626",
            "road" to "#414758",
            "roadMajor" to "#535666",
            "motorwayLow" to "#2b2e35",
            "motorway" to "#494949",
            "tunnelLink" to "#2f3440",
            "tunnelMotorway" to "#3c3f46",
            "bridgeStreetCasing" to "#17191f",
            "bridgePathCasing" to "#34363d",
            "rail" to "#1a1a1a",
            "building" to "#333333",
            "buildingOutlineLow" to "#1c1c1c",
            "buildingOutline" to "#0e0e0e",
            "boundaryCounty" to "#24262c",
            "boundaryState" to "#5e606b",
            "textHalo" to "rgba(14,14,14,0.9)",
            "textHaloSoft" to "rgba(14,14,14,0.72)",
            "textHaloSolid" to "rgba(17,17,17,0.95)",
            "textPoi" to "#8c8c8c",
            "textTransit" to "#8aa1af",
            "textRoad" to "#b2b2b2",
            "textPlace" to "#c8cddd",
            "textRegion" to "#969aa3",
            "textCountry" to "#a8abb5",
        ),
        hiddenLayers = setOf("road_area_pattern", "landcover_wetland"),
    )
}