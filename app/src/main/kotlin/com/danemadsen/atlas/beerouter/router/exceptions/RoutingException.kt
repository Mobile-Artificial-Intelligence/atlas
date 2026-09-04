package com.danemadsen.atlas.beerouter.router.exceptions

public open class RoutingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
