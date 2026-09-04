package com.danemadsen.atlas.beerouter.router.exceptions

public class CacheStateException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
