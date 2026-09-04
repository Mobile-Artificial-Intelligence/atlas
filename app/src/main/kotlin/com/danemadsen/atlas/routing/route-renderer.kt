package com.danemadsen.atlas.routing

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Draws the calculated route onto the map as style layers over persistent
 * GeoJson sources, so repeated routes swap source content instead of
 * tearing layers down (no flicker between profile switches).
 *
 * A theme restyle rebuilds every layer from the style JSON, silently
 * dropping ours — the render effect re-runs [showRoute]/[clear] on the
 * loaded style after every restyle, which re-arms the layers.
 *
 * The user-location puck is NOT here: it lives in [LocationPuck] (it shows
 * while browsing too, not only around a route, so its lifecycle is the
 * location stream's, not the route's).
 */
object RouteRenderer {

    private const val ROUTE_SOURCE_ID = "atlas-route-source"
    private const val ROUTE_CASING_ID = "atlas-route-casing"
    private const val ROUTE_LINE_ID = "atlas-route-line"
    private const val ENDPOINT_SOURCE_ID = "atlas-route-endpoint-source"
    private const val ENDPOINT_ID = "atlas-route-endpoint"

    private const val ROUTE_WIDTH_DP = 5f
    private const val CASING_WIDTH_DP = 9f
    private const val ENDPOINT_RADIUS_DP = 6f
    private const val ENDPOINT_STROKE_DP = 2f

    /** Puts [result]'s line and endpoints on the style; idempotent. */
    fun showRoute(style: Style, result: RouteResult, accentColor: Int, casingColor: Int) {
        if (style.getLayer(ROUTE_CASING_ID) == null) {
            if (style.getSource(ROUTE_SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, emptyCollection()))
            }
            // Layers stack in add order: the wide halo under the colored
            // line. Both go BELOW the map's first symbol layer — added at
            // the top they would paint over every road label and route
            // shield along the route, hiding exactly the names the user
            // needs to read it. The endpoint marker still belongs above
            // labels — but below the puck (see the ENDPOINT block below).
            val first_symbol_id = style.layers.firstOrNull { it is SymbolLayer }?.id
            val casing = LineLayer(ROUTE_CASING_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(casingColor),
                PropertyFactory.lineWidth(CASING_WIDTH_DP),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            val line = LineLayer(ROUTE_LINE_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(accentColor),
                PropertyFactory.lineWidth(ROUTE_WIDTH_DP),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            if (first_symbol_id != null) {
                // addLayerBelow inserts directly under the target: casing
                // first, then line under the symbol (i.e. above the
                // casing) — the same stacking an add-order pair gives.
                style.addLayerBelow(casing, first_symbol_id)
                style.addLayerBelow(line, first_symbol_id)
            } else {
                style.addLayer(casing)
                style.addLayer(line)
            }
        }
        if (style.getLayer(ENDPOINT_ID) == null) {
            if (style.getSource(ENDPOINT_SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(ENDPOINT_SOURCE_ID, emptyCollection()))
            }
            // The marker stays above labels but below the puck: addLayer
            // would put it on top of everything, and the destination is
            // exactly where the user arrives — the puck must win that spot.
            val endpoint = CircleLayer(ENDPOINT_ID, ENDPOINT_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(ENDPOINT_RADIUS_DP),
                PropertyFactory.circleColor(accentColor),
                PropertyFactory.circleStrokeWidth(ENDPOINT_STROKE_DP),
                PropertyFactory.circleStrokeColor(casingColor),
            )
            val puck_bottom = LocationPuck.bottomLayerId(style)
            if (puck_bottom != null) {
                style.addLayerBelow(endpoint, puck_bottom)
            } else {
                style.addLayer(endpoint)
            }
        }

        (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(listOf(
                Feature.fromGeometry(LineString.fromLngLats(
                    result.points.map { Point.fromLngLat(it.lon, it.lat) }
                ))
            ))
        )
        // Only the destination gets a marker: every route originates at the
        // user's live location, where the puck already is — an origin marker
        // would land on it and paint the dot over.
        (style.getSource(ENDPOINT_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(listOf(
                Feature.fromGeometry(Point.fromLngLat(result.destination.lon, result.destination.lat)),
            ))
        )
    }

    /** Drops the route layers and sources; safe on a freshly restyled style. */
    fun clear(style: Style) {
        for (id in listOf(ENDPOINT_ID, ROUTE_LINE_ID, ROUTE_CASING_ID)) {
            style.getLayer(id)?.let { style.removeLayer(it) }
        }
        for (id in listOf(ENDPOINT_SOURCE_ID, ROUTE_SOURCE_ID)) {
            style.getSource(id)?.let { style.removeSource(it) }
        }
    }

    private fun emptyCollection(): FeatureCollection =
        FeatureCollection.fromFeatures(emptyList())
}