package com.danemadsen.atlas.beerouter.router.exceptions

public class DataFileNotFoundException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
