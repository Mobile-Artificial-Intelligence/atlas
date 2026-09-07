package com.danemadsen.atlas.mapstyle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StyleBuilderTest {

    private val template: String =
        File("src/main/assets/style-template.json").readText()

    private fun sources(vararg ids: String) =
        ids.mapIndexed { i, id ->
            StyleBuilder.RegionSource(id, "/data/user/0/com.danemadsen.atlas/files/map/$id/map.pmtiles")
        }

    private val melbourne = sources("atlas")

    @Test
    fun outputIsFullyOffline() {
        // The only allowed remote string is the OSM Liberty license URL in
        // metadata (documentation/attribution, never fetched by the renderer).
        val licenseUrl = "https://github.com/maputnik/osm-liberty/blob/gh-pages/LICENSE.md"
        for (theme in listOf(Themes.LIGHT, Themes.DARK)) {
            val style = StyleBuilder.buildStyleJson(template, theme, melbourne)
            val fetchable = style.replace(licenseUrl, "")
            assertTrue(!style.contains("{{"), "unresolved token in ${theme.name}")
            assertTrue(!fetchable.contains("https://"), "remote URL left in ${theme.name}")
            assertTrue(!fetchable.contains("http://"), "remote URL left in ${theme.name}")
        }
    }

    @Test
    fun wiresPmtilesSourceAtArchivePath() {
        val style = StyleBuilder.buildStyleJson(template, Themes.LIGHT, melbourne)
        // MapLibre reads zooms/bounds from the archive header itself, so the
        // source is just a pmtiles:// url — no z/x/y tile template.
        assertTrue(
            "\"url\": \"pmtiles://file:///data/user/0/com.danemadsen.atlas/files/map/atlas/map.pmtiles\"" in style,
        )
        assertTrue("/{z}/{x}/{y}" !in style)
    }

    @Test
    fun fontsAndSpritePointAtBundledAssets() {
        val style = StyleBuilder.buildStyleJson(template, Themes.LIGHT, melbourne)
        assertTrue("\"sprite\": \"asset://sprites/osm-liberty\"" in style)
        assertTrue("\"glyphs\": \"asset://glyphs/{fontstack}/{range}.pbf\"" in style)
        assertTrue("\"Roboto-Regular\"" in style)
        assertTrue("\"Roboto-Medium\"" in style)
        assertTrue("\"Roboto-Condensed-Italic\"" in style)
        assertTrue("\"Roboto Regular\"" !in style)
    }

    @Test
    fun darkHidesSpritePatternLayers() {
        val light = StyleBuilder.buildStyleJson(template, Themes.LIGHT, melbourne)
        val dark = StyleBuilder.buildStyleJson(template, Themes.DARK, melbourne)
        // One hidden pair per region source.
        assertEquals(2 * melbourne.size, "\"visibility\": \"none\"".toRegex().findAll(dark).count())
        assertTrue("\"name\": \"Dark Matter\"" in dark)
        assertTrue("\"name\": \"OSM Liberty\"" in light)
    }

    @Test
    fun themesRenderDifferentBackgrounds() {
        val light = StyleBuilder.buildStyleJson(template, Themes.LIGHT, melbourne)
        val dark = StyleBuilder.buildStyleJson(template, Themes.DARK, melbourne)
        assertNotEquals(light, dark)
        assertTrue("\"background-color\": \"rgb(239,239,239)\"" in light)
        assertTrue("\"background-color\": \"#0e0e0e\"" in dark)
    }

    @Test
    fun emptyStyleIsSelfContained() {
        val empty = StyleBuilder.emptyStyleJson(Themes.DARK)
        assertTrue("\"background-color\": \"#0e0e0e\"" in empty)
        assertTrue("\"sources\": {}" in empty)
        assertTrue(!empty.contains("{{"))
    }

    @Test
    fun missingTokenFailsLoudly() {
        val broken = AtlasMapTheme("broken", mapOf("background" to "#000"))
        assertFailsWith<IllegalArgumentException> {
            StyleBuilder.buildStyleJson(template, broken, melbourne)
        }
    }

    @Test
    fun hiddenLayerMustExist() {
        val broken = Themes.LIGHT.copy(hiddenLayers = setOf("no_such_layer"))
        assertFailsWith<IllegalArgumentException> {
            StyleBuilder.buildStyleJson(template, broken, melbourne)
        }
    }

    @Test
    fun everySourcedLayerIsDuplicatedPerRegion() {
        val two = sources("australia", "us-georgia")
        val style = StyleBuilder.buildStyleJson(template, Themes.LIGHT, two)
        for (region in two) {
            assertTrue(
                "\"openmaptiles-${region.regionId}\"" in style,
                "missing source for ${region.regionId}",
            )
        }
        assertTrue("\"openmaptiles-us-georgia\"" in style)
        // `source` fields pointing at the template's placeholder name must
        // all be gone.
        val leftovers = Regex("\"source\"\\s*:\\s*\"openmaptiles\"").findAll(style).count()
        assertEquals(0, leftovers)
    }

    @Test
    fun layerIdsStayUniqueAcrossRegions() {
        val two = sources("australia", "us-georgia")
        val style = StyleBuilder.buildStyleJson(template, Themes.LIGHT, two)
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(style).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size, "duplicate layer id in multi-source style")
    }

    @Test
    fun sourceLayerAndPaintAreUntouched() {
        val two = sources("australia", "us-georgia")
        val style = StyleBuilder.buildStyleJson(template, Themes.LIGHT, two)
        val template_layers = Regex("\"source-layer\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(template).map { it.groupValues[1] }.toList()
        val style_layers = Regex("\"source-layer\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(style).map { it.groupValues[1] }.toList()
        // Each template layer is duplicated per region, copies consecutive.
        val expected = template_layers.flatMap { list -> List(two.size) { list } }
        assertEquals(expected, style_layers)
    }
}