package com.danemadsen.atlas.beerouter.expressions

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstantOptimizerTest {
    @Test
    fun compareOptimizerModesTest() {
        val lookupContent = javaClass.getResource("/lookups_test.dat")!!.readText()
        val profileContent = javaClass.getResource("/profile_test.brf")!!.readText()

        val meta1 = BExpressionMetaData()
        val meta2 = BExpressionMetaData()
        val expctx1: BExpressionContext = BExpressionContextWay(meta1)
        val expctx2: BExpressionContext = BExpressionContextWay(meta2)
        expctx2.skipConstantExpressionOptimizations = true

        val keyValue = mutableMapOf(
            "global_inject1" to "5",
            "global_inject2" to "6",
            "global_inject3" to "7"
        )

        meta1.readMetaData(lookupContent)
        meta2.readMetaData(lookupContent)
        expctx1.parseProfile(profileContent, "global", keyValue)
        expctx2.parseProfile(profileContent, "global", keyValue)

        val d = 0.0001f
        assertEquals(5f, expctx1.getVariableValue("global_inject1", 0f), d)
        assertEquals(9f, expctx1.getVariableValue("global_inject2", 0f), d)
        assertEquals(7f, expctx1.getVariableValue("global_inject3", 0f), d)
        assertEquals(3f, expctx1.getVariableValue("global_inject4", 3f), d)

        assertTrue(
            expctx2.expressionNodeCount - expctx1.expressionNodeCount >= 311 - 144,
            "expected far less expression nodes if optimized"
        )

        val rnd = Random(17464)
        repeat(10000) {
            val data = expctx1.generateRandomValues(rnd)
            expctx1.evaluate(data)
            expctx2.evaluate(data)
            expctx1.assertAllVariablesEqual(expctx2)
        }
    }
}
