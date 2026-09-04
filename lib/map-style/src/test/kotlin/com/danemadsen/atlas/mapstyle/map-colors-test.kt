package com.danemadsen.atlas.mapstyle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The accent re-hue the map applies: parsing every notation the prototype
 * palettes use, and the hue swap that makes the road family follow the
 * Material You accent without changing the theme's lightness profile.
 */
class MapColorsTest {

    // A typical Material You blue accent (hue ~250, fully saturated).
    private val blue_accent = 0xFF6750FF.toInt()

    @Test
    fun parsesEveryNotationThePalettesUse() {
        // One representative literal per format present in themes.kt.
        assertEquals(0xFF6750A4.toInt(), MapColors.parse("#6750A4"))
        assertEquals(0xFFFFEEAA.toInt(), MapColors.parse("#fea"))
        assertEquals(0xFFC6DAFF.toInt(), MapColors.parse("rgb(198,218,255)"))
        assertEquals(0x80FFFFFF.toInt(), MapColors.parse("rgba(255,255,255,0.5)"))

        // hsl/hsla are checked semantically (round-tripped through the same
        // converter the re-hue uses) rather than against hand-computed bits.
        val hsl = MapColors.argbToHsl(MapColors.parse("hsl(26, 87%, 62%)")!!)
        assertEquals(26f, hsl.first, 1f)
        assertEquals(0.87f, hsl.second, 0.02f)
        assertEquals(0.62f, hsl.third, 0.01f)
        val hsla = MapColors.parse("hsla(98, 61%, 51%, 0.7)")!!
        assertEquals(179, hsla ushr 24, "alpha 0.7 quantizes to 179")
        assertEquals(98f, MapColors.argbToHsl(hsla).first, 1f)

        assertNull(MapColors.parse("not-a-color"))
        assertNull(MapColors.parse("#12"))

        // The teal vegetation literals added new notation variants:
        // spaces inside rgba plus an integer alpha, and hsl without an
        // alpha at hue 175.
        assertEquals(0xFF5FD0C7.toInt(), MapColors.parse("rgba(95, 208, 199, 1)"))
        assertEquals(175f, MapColors.argbToHsl(MapColors.parse("hsl(175, 37%, 81%)")!!).first, 1f)
    }

    @Test
    fun everyThemeLiteralParses() {
        // A literal MapColors cannot parse is silently DROPPED by the
        // re-hue's fail-soft path — on device the layer just fails to
        // render, and nothing else notices. So every token in both
        // themes must survive a parse, no exceptions.
        for (base in listOf(Themes.LIGHT, Themes.DARK)) {
            for ((token, color) in base.colors) {
                assertNotNull(MapColors.parse(color), "${base.name}.$token = $color must parse")
            }
        }
    }

    @Test
    fun hslRoundTripIsLosslessWithin8Bits() {
        for (argb in listOf(0xFFE9AC77.toInt(), 0xFF494949.toInt(), 0x802C353C.toInt(), 0xFF0E1A17.toInt())) {
            val (h, s, l) = MapColors.argbToHsl(argb)
            val alpha = (argb ushr 24) / 255f
            assertEquals(argb, MapColors.hslToArgb(h, s, l, alpha))
        }
    }

    @Test
    fun rehueKeepsTheTokensOwnSaturationAndLightness() {
        // Light motorway #fc8: pastel orange (s 100%, l 77%). A blue accent
        // must yield a pastel BLUE — same s and l, accent's hue.
        val retinted = MapColors.rehue("#fc8", blue_accent)
        assertNotNull(retinted)
        val argb = MapColors.parse(retinted)!!
        val (_, s, l) = MapColors.argbToHsl(argb)
        val (_, original_s, original_l) = MapColors.argbToHsl(MapColors.parse("#fc8")!!)
        assertEquals(original_s, s, 0.02f)
        assertEquals(original_l, l, 0.02f)
        assertTrue(MapColors.argbToHsl(argb).first in 240f..260f, "hue should follow the accent")
    }

    @Test
    fun rehueWakesUpNearGrayTokensWithARestrainedAccentSaturation() {
        // Dark motorway #494949 is pure gray — a hue swap alone is a no-op.
        val retinted = MapColors.rehue("#494949", blue_accent)
        assertNotNull(retinted)
        val (h, s, l) = MapColors.argbToHsl(MapColors.parse(retinted)!!)
        assertTrue(h in 240f..260f, "gray token should adopt the accent hue")
        assertTrue(s <= 0.41f, "saturation stays restrained ($s)")
        assertEquals(0.29f, l, 0.01f, "lightness unchanged")
    }

    @Test
    fun rehuePreservesAlphaAndFailsSoftOnGarbage() {
        val translucent = MapColors.rehue("rgba(255,244,198,0.6)", blue_accent)
        assertNotNull(translucent)
        assertTrue(translucent.startsWith("rgba("))
        assertEquals(153, (MapColors.parse(translucent)!! ushr 24), "alpha ~0.6 survives")
        // Fail-soft: an unparseable color reports null; the theme's caller
        // keeps the original token.
        assertNull(MapColors.rehue("garbage", blue_accent))
    }

    @Test
    fun nullAccentLeavesTheThemeUntouched() {
        assertTrue(Themes.DARK.withMaterialAccent(null) === Themes.DARK)
        assertTrue(Themes.LIGHT.withMaterialAccent(null) === Themes.LIGHT)
    }

    @Test
    fun accentRetintsTheBrandFamilyInBothThemesAndNothingElse() {
        val brand = setOf(
            "roadCasing", "roadMajor", "motorwayLow", "motorway",
            "tunnelLink", "tunnelMotorway", "textTransit",
        )
        for (base in listOf(Themes.LIGHT, Themes.DARK)) {
            val retinted = base.withMaterialAccent(blue_accent)
            assertNotEquals(base.colors, retinted.colors, "${base.name} should change")
            assertEquals(base.colors.keys, retinted.colors.keys)
            for ((token, color) in base.colors) {
                if (token in brand) {
                    assertNotEquals(color, retinted.colors.getValue(token), "$token should follow the accent")
                    // Whatever it became, the style must accept it back.
                    assertNotNull(MapColors.parse(retinted.colors.getValue(token)), "$token output must parse")
                } else {
                    assertEquals(color, retinted.colors.getValue(token), "$token must stay fixed")
                }
            }
        }
    }
}