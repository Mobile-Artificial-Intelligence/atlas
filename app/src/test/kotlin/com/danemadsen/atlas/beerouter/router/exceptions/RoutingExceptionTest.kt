package com.danemadsen.atlas.beerouter.router.exceptions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RoutingExceptionTest {
    @Test
    fun `RoutingException is a RuntimeException`() {
        val ex = RoutingException("test")
        assertIs<RuntimeException>(ex)
    }

    @Test
    fun `RoutingException preserves message`() {
        val ex = RoutingException("routing failed")
        assertEquals("routing failed", ex.message)
    }

    @Test
    fun `RoutingException preserves cause`() {
        val cause = IllegalStateException("root")
        val ex = RoutingException("routing failed", cause)
        assertEquals("routing failed", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `RoutingException cause defaults to null`() {
        val ex = RoutingException("test")
        assertNull(ex.cause)
    }

    @Test
    fun `RoutingIslandException extends RoutingException`() {
        val ex = com.danemadsen.atlas.beerouter.router.RoutingIslandException()
        assertIs<RoutingException>(ex)
    }

    @Test
    fun `RoutingIslandException has default message`() {
        val ex = com.danemadsen.atlas.beerouter.router.RoutingIslandException()
        assertEquals("routing island detected", ex.message)
    }

    @Test
    fun `RoutingIslandException accepts custom message and cause`() {
        val cause = RuntimeException("root")
        val ex = com.danemadsen.atlas.beerouter.router.RoutingIslandException("custom island", cause)
        assertEquals("custom island", ex.message)
        assertEquals(cause, ex.cause)
    }
}
