package com.danemadsen.atlas.beerouter.router.exceptions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataCorruptionExceptionTest {
    @Test
    fun `DataCorruptionException is a RuntimeException`() {
        val ex = DataCorruptionException("corrupt")
        assertIs<RuntimeException>(ex)
    }

    @Test
    fun `DataCorruptionException preserves message and cause`() {
        val cause = IllegalStateException("root")
        val ex = DataCorruptionException("identity corruption!", cause)
        assertEquals("identity corruption!", ex.message)
        assertEquals(cause, ex.cause)
    }
}
