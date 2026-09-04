package com.danemadsen.atlas.beerouter.router.exceptions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExpressionParseExceptionTest {
    @Test
    fun `ExpressionParseException is a RuntimeException`() {
        val ex = ExpressionParseException("parse error")
        assertIs<RuntimeException>(ex)
    }

    @Test
    fun `ExpressionParseException preserves message and cause`() {
        val cause = IllegalArgumentException("bad token")
        val ex = ExpressionParseException("ParseException at line 5: bad token", cause)
        assertEquals("ParseException at line 5: bad token", ex.message)
        assertEquals(cause, ex.cause)
    }
}
