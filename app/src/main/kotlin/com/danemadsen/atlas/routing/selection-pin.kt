package com.danemadsen.atlas.routing

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The selection pin: parked on the point the user picked — a long-press, a
 * search-candidate row, or a geo: intent coordinate — for exactly as long
 * as the location menu stands open on it. When the point becomes a route
 * destination the route's own endpoint marker takes over (RouteRenderer),
 * and the menu's dismissal takes the pin with it: the pin marks a
 * selection, not a destination.
 *
 * The pin is the SAME MARKER as the route's end-of-route endpoint (accent
 * fill, casing-colored ring, same radius): the visual is established — a
 * circle means "this is where you'll arrive" — and a second, different
 * shape would needlessly imply a different meaning.
 *
 * Like LocationPuck and RouteRenderer, the layer sits on a persistent
 * GeoJson source and the render effect re-runs [show] after every theme
 * restyle, which rebuilds the style from JSON and silently drops ours.
 */
object SelectionPin {

    private const val SOURCE_ID = "atlas-selection-source"
    private const val LAYER_ID = "atlas-selection-pin"

    /**
     * Arms the pin layer on [style] and parks it at [point] (null arms the
     * layers empty, the same restyle-survival shape as LocationPuck.show).
     * Idempotent. Layer placement and colors mirror RouteRenderer's
     * endpoint: above labels, below the puck — the selection is the point
     * the user is acting on, yet the puck must win the spot when the two
     * coincide (e.g. pinning your own street corner).
     */
    fun show(style: Style, point: GeoPoint?, accentColor: Int, casingColor: Int) {
        if (style.getLayer(LAYER_ID) == null) {
            if (style.getSource(SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(SOURCE_ID, emptyCollection()))
            }
            val pin = CircleLayer(LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(RouteRenderer.ENDPOINT_RADIUS_DP),
                PropertyFactory.circleColor(accentColor),
                PropertyFactory.circleStrokeWidth(RouteRenderer.ENDPOINT_STROKE_DP),
                PropertyFactory.circleStrokeColor(casingColor),
            )
            val puck_bottom = LocationPuck.bottomLayerId(style)
            if (puck_bottom != null) {
                style.addLayerBelow(pin, puck_bottom)
            } else {
                style.addLayer(pin)
            }
        }
        val features = point?.let {
            listOf(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)))
        } ?: emptyList()
        (style.getSource(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(features)
        )
    }

    /** Empties the pin source, keeping the armed layer for the next selection. */
    fun clear(style: Style) {
        (style.getSource(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(emptyCollection())
    }

    private fun emptyCollection(): FeatureCollection =
        FeatureCollection.fromFeatures(emptyList())
}