package com.danemadsen.atlas.beerouter.expressions

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class BExpressionContextCacheTest {
    @Test
    fun unifyReturnsCachedArrayForEqualContent() {
        val ctx = wayContext()
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(0, 1, 2, 3, 4, 9)

        val cached = ctx.unify(first, 0, first.size)!!
        ctx.evaluate(false, cached)
        val hit = ctx.unify(second, 1, 4)!!

        assertContentEquals(first, cached)
        assertSame(cached, hit)
        assertNotSame(second, hit)
    }

    @Test
    fun unifyDoesNotReturnCachedArrayForDifferentContent() {
        val ctx = wayContext()
        val first = byteArrayOf(1, 2, 3, 4)
        val different = byteArrayOf(1, 2, 3, 5)

        val cached = ctx.unify(first, 0, first.size)!!
        ctx.evaluate(false, cached)
        val miss = ctx.unify(different, 0, different.size)!!

        assertContentEquals(different, miss)
        assertNotSame(cached, miss)
    }

    @Test
    fun unifyDoesNotReturnCachedArrayForCrcCollision() {
        val ctx = wayContext()
        val first = "plumless".encodeToByteArray()
        val collision = "buckeroo".encodeToByteArray()

        val cached = ctx.unify(first, 0, first.size)!!
        ctx.evaluate(false, cached)
        val miss = ctx.unify(collision, 0, collision.size)!!

        assertContentEquals(collision, miss)
        assertNotSame(cached, miss)
    }

    private fun wayContext(): BExpressionContextWay {
        val lookupContent = javaClass.getResource("/lookups_test.dat")!!.readText()
        val profileContent = javaClass.getResource("/profile_test.brf")!!.readText()
        val meta = BExpressionMetaData()
        val ctx = BExpressionContextWay(16, meta)

        meta.readMetaData(lookupContent)
        ctx.parseProfile(profileContent, "global")

        return ctx
    }
}
