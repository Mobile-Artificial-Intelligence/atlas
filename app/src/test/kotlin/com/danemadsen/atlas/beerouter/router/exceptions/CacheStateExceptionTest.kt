package com.danemadsen.atlas.beerouter.router.exceptions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CacheStateExceptionTest {
    @Test
    fun `CacheStateException is a RuntimeException`() {
        val ex = CacheStateException("bad state")
        assertIs<RuntimeException>(ex)
    }

    @Test
    fun `CacheStateException preserves message and cause`() {
        val cause = IllegalStateException("root")
        val ex = CacheStateException("mismatch in variable-count: 3<->5", cause)
        assertEquals("mismatch in variable-count: 3<->5", ex.message)
        assertEquals(cause, ex.cause)
    }
}
