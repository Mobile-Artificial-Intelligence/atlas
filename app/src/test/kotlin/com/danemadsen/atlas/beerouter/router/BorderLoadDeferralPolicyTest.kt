package com.danemadsen.atlas.beerouter.router

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BorderLoadDeferralPolicyTest {
    @Test
    fun entersDeferralWhenUnboundedCleanupCannotMakeRoom() {
        val policy = BorderLoadDeferralPolicy()

        val result = policy.onCleanupStillOverBudget(
            maxTotalCost = BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST,
            fastPartialRecalc = false,
        )

        assertEquals(BorderLoadDeferralPolicy.OverBudgetAction.DEFER_BORDER_LOADS, result)
        assertTrue(policy.deferringBorderLoads)
    }

    @Test
    fun reportsMemoryLimitWhenBoundedCleanupCannotMakeRoom() {
        val policy = BorderLoadDeferralPolicy()

        val result = policy.onCleanupStillOverBudget(
            maxTotalCost = BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST - 1,
            fastPartialRecalc = false,
        )

        assertEquals(BorderLoadDeferralPolicy.OverBudgetAction.MEMORY_LIMIT_REACHED, result)
        assertFalse(policy.deferringBorderLoads)
    }

    @Test
    fun requiresProgressAfterDeferredFrontierIsRequeued() {
        val policy = BorderLoadDeferralPolicy()

        policy.onDeferredFrontierRequeued()
        val result = policy.onCleanupStillOverBudget(
            maxTotalCost = BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST,
            fastPartialRecalc = false,
        )

        assertEquals(BorderLoadDeferralPolicy.OverBudgetAction.MEMORY_LIMIT_REACHED, result)
        assertFalse(policy.deferringBorderLoads)
    }

    @Test
    fun reportsMemoryLimitDuringFastPartialRecalculation() {
        val policy = BorderLoadDeferralPolicy()

        val result = policy.onCleanupStillOverBudget(
            maxTotalCost = BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST,
            fastPartialRecalc = true,
        )

        assertEquals(BorderLoadDeferralPolicy.OverBudgetAction.MEMORY_LIMIT_REACHED, result)
        assertFalse(policy.deferringBorderLoads)
    }

    @Test
    fun clearsProgressRequirementAfterPathAcceptedForExpansion() {
        val policy = BorderLoadDeferralPolicy()

        policy.onDeferredFrontierRequeued()
        policy.onPathAcceptedForExpansion()
        val result = policy.onCleanupStillOverBudget(
            maxTotalCost = BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST,
            fastPartialRecalc = false,
        )

        assertEquals(BorderLoadDeferralPolicy.OverBudgetAction.DEFER_BORDER_LOADS, result)
        assertTrue(policy.deferringBorderLoads)
    }

    @Test
    fun exitsDeferralWhenDeferredFrontierIsRequeued() {
        val policy = BorderLoadDeferralPolicy()

        policy.onCleanupStillOverBudget(
            maxTotalCost = BorderLoadDeferralPolicy.UNBOUNDED_MAX_TOTAL_COST,
            fastPartialRecalc = false,
        )
        policy.onDeferredFrontierRequeued()

        assertFalse(policy.deferringBorderLoads)
    }
}
