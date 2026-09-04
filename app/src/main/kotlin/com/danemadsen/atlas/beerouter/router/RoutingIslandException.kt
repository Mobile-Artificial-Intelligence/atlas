package com.danemadsen.atlas.beerouter.router

import com.danemadsen.atlas.beerouter.router.exceptions.RoutingException

public class RoutingIslandException(
    message: String = "routing island detected",
    cause: Throwable? = null,
) : RoutingException(message, cause)
