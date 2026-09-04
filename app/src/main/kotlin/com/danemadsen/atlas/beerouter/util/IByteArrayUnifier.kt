package com.danemadsen.atlas.beerouter.util

public interface IByteArrayUnifier {
    public fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray?
}
