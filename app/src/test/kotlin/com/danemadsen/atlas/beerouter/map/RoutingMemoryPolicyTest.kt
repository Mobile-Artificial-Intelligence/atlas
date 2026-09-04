package com.danemadsen.atlas.beerouter.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoutingMemoryPolicyTest {
    @Test
    fun defaultPolicyUses128MbHardLimitWithScaledBudgets() {
        val policy = RoutingMemoryPolicy.default()

        assertEquals(16L * 1024 * 1024, policy.graphInitialBudgetBytes)
        assertEquals(96L * 1024 * 1024, policy.graphHardLimitBytes)
        assertEquals(32L * 1024 * 1024, policy.tileCacheBudgetBytes)
        assertEquals(128L * 1024 * 1024, policy.totalHardLimitBytes)
    }

    @Test
    fun defaultPolicyCapsCombinedGraphAndTileBudgetsAt128Mb() {
        val policy = RoutingMemoryPolicy.default()

        assertEquals(128L * 1024 * 1024, policy.graphHardLimitBytes + policy.tileCacheBudgetBytes)
    }

    @Test
    fun policyScalesFromHardLimit() {
        val policy = RoutingMemoryPolicy.withTotalHardLimitMegabytes(256)

        assertEquals(32L * 1024 * 1024, policy.graphInitialBudgetBytes)
        assertEquals(192L * 1024 * 1024, policy.graphHardLimitBytes)
        assertEquals(64L * 1024 * 1024, policy.tileCacheBudgetBytes)
        assertEquals(256L * 1024 * 1024, policy.totalHardLimitBytes)
    }

    @Test
    fun rejectsInvalidBudgets() {
        assertFailsWith<IllegalArgumentException> {
            RoutingMemoryPolicy(
                graphInitialBudgetBytes = 2,
                graphHardLimitBytes = 1,
                tileCacheBudgetBytes = 1,
            )
        }
    }
}
