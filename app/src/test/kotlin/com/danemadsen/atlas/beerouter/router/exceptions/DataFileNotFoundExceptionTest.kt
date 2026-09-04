package com.danemadsen.atlas.beerouter.router.exceptions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataFileNotFoundExceptionTest {
    @Test
    fun `DataFileNotFoundException is a RuntimeException`() {
        val ex = DataFileNotFoundException("not found")
        assertIs<RuntimeException>(ex)
    }

    @Test
    fun `DataFileNotFoundException preserves message and cause`() {
        val cause = IllegalStateException("root")
        val ex = DataFileNotFoundException("error reading datafile /path/to/file", cause)
        assertEquals("error reading datafile /path/to/file", ex.message)
        assertEquals(cause, ex.cause)
    }
}
