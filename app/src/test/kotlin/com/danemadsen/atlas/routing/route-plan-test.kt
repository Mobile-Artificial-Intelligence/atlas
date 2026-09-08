package com.danemadsen.atlas.routing

import org.junit.Test
import kotlin.test.*

class RoutePlanTest {
    private val home = RouteStop(point = GeoPoint(144.9631000123, -37.8142000456), name = "Home")
    private val cafe = RouteStop(point = GeoPoint(144.98, -37.82), name = "Café")
    private val park = RouteStop(point = GeoPoint(145.01, -37.84), name = "Park")

    @Test fun chosenEndpointsResolveWithoutGps() {
        val plan = RoutePlan(listOf(home, cafe, park))
        assertTrue(plan.isComplete)
        assertFalse(plan.startsAtCurrentLocation)
        assertEquals(listOf(home.point, cafe.point, park.point), plan.resolve(null))
    }

    @Test fun currentLocationRemainsLiveWhenReversedAndRestored() {
        val plan = RoutePlan(listOf(RouteStop(isCurrentLocation = true), home, cafe)).reversed()
        val restored = assertNotNull(RoutePlanStore.decode(RoutePlanStore.encode(plan)))
        assertTrue(restored.stops.last().isCurrentLocation)
        assertEquals(listOf(cafe.point, home.point, park.point), restored.resolve(park.point))
        assertFailsWith<IllegalArgumentException> { restored.resolve(null) }
    }

    @Test fun reorderingAndEditingPreserveStopIdentityAndDestination() {
        val plan = RoutePlan(listOf(home, cafe, park))
        val moved = plan.move(park.id, 1)
        assertEquals(listOf(home, park, cafe), moved.stops)
        val renamed = moved.replace(park.id, RouteStop(point = park.point, name = "New park name"))
        assertEquals(park.id, renamed.stops[1].id)
        assertEquals(cafe, renamed.stops.last())
        assertEquals(listOf(home, cafe), renamed.remove(park.id).stops)
    }

    @Test fun removingEndpointPromotesTheAdjacentStopAndNeverLeavesFewerThanTwo() {
        val plan = RoutePlan(listOf(home, cafe, park))
        assertEquals(listOf(cafe, park), plan.remove(home.id).stops)
        assertEquals(listOf(home, cafe), plan.remove(park.id).stops)
        assertEquals(plan.remove(park.id), plan.remove(park.id).remove(home.id))
    }

    @Test fun addingAnEmptyStopRequiresSelectionBeforeRoutingAndHonorsLimit() {
        var plan = RoutePlan(listOf(home, cafe)).add()
        assertFalse(plan.isComplete)
        assertFailsWith<IllegalArgumentException> { plan.resolve(null) }
        while (plan.canAddStop) plan = plan.add()
        assertEquals(10, plan.stops.size)
        assertEquals(plan, plan.add(park))
    }

    @Test fun itineraryRoundTripsLabelsPrecisionModeAndIncompleteRows() {
        val plan = RoutePlan(listOf(home, cafe, RouteStop()), RouteProfile.BIKE)
        assertEquals(plan, RoutePlanStore.decode(RoutePlanStore.encode(plan)))
        assertNull(RoutePlanStore.decode(null))
        assertNull(RoutePlanStore.decode("bad JSON"))
        assertNull(RoutePlanStore.decode("""{"version":2,"stops":[]}"""))
        assertNull(RoutePlanStore.decode(RoutePlanStore.encode(plan).replace("144.9631000123", "999")))
        assertNull(RoutePlanStore.decode(RoutePlanStore.encode(plan).replace(cafe.id, home.id)))
    }
}
