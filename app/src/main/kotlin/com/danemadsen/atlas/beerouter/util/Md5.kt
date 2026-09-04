package com.danemadsen.atlas.beerouter.util

internal class Md5 {
    private val block = ByteArray(BLOCK_SIZE)
    private var blockSize = 0
    private var messageLength = 0L

    private var a = 0x67452301
    private var b = -0x10325477
    private var c = -0x67452302
    private var d = 0x10325476

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size) {
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "offset=$offset length=$length size=${input.size}"
        }

        if (length == 0) {
            return
        }

        messageLength += length.toLong()
        var inputOffset = offset
        var remaining = length

        while (remaining > 0) {
            val toCopy = minOf(BLOCK_SIZE - blockSize, remaining)
            input.copyInto(
                destination = block,
                destinationOffset = blockSize,
                startIndex = inputOffset,
                endIndex = inputOffset + toCopy
            )
            blockSize += toCopy
            inputOffset += toCopy
            remaining -= toCopy

            if (blockSize == BLOCK_SIZE) {
                processBlock(block, 0)
                blockSize = 0
            }
        }
    }

    fun digest(): ByteArray {
        val bitLength = messageLength shl 3

        update(byteArrayOf(0x80.toByte()))

        val paddingSize = if (blockSize <= 56) 56 - blockSize else BLOCK_SIZE + 56 - blockSize
        if (paddingSize > 0) {
            update(ByteArray(paddingSize))
        }

        val lengthBytes = ByteArray(8)
        for (i in 0..7) {
            lengthBytes[i] = ((bitLength ushr (i * 8)) and 0xff).toByte()
        }
        update(lengthBytes)

        val digest = ByteArray(16)
        writeIntLe(a, digest, 0)
        writeIntLe(b, digest, 4)
        writeIntLe(c, digest, 8)
        writeIntLe(d, digest, 12)
        return digest
    }

    private fun processBlock(bytes: ByteArray, offset: Int) {
        val m = IntArray(16)
        for (i in 0..15) {
            val index = offset + i * 4
            m[i] =
                (bytes[index].toInt() and 0xff) or
                    ((bytes[index + 1].toInt() and 0xff) shl 8) or
                    ((bytes[index + 2].toInt() and 0xff) shl 16) or
                    ((bytes[index + 3].toInt() and 0xff) shl 24)
        }

        var aa = a
        var bb = b
        var cc = c
        var dd = d

        for (i in 0..63) {
            val f: Int
            val g: Int
            when {
                i < 16 -> {
                    f = (bb and cc) or (bb.inv() and dd)
                    g = i
                }
                i < 32 -> {
                    f = (dd and bb) or (dd.inv() and cc)
                    g = (5 * i + 1) and 15
                }
                i < 48 -> {
                    f = bb xor cc xor dd
                    g = (3 * i + 5) and 15
                }
                else -> {
                    f = cc xor (bb or dd.inv())
                    g = (7 * i) and 15
                }
            }

            val tmp = dd
            dd = cc
            cc = bb
            bb += rotateLeft(aa + f + K[i] + m[g], S[i])
            aa = tmp
        }

        a += aa
        b += bb
        c += cc
        d += dd
    }

    private fun rotateLeft(value: Int, count: Int): Int = (value shl count) or (value ushr (32 - count))

    private fun writeIntLe(value: Int, target: ByteArray, offset: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private companion object {
        private const val BLOCK_SIZE = 64

        private val S = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
        )

        private val K = intArrayOf(
            -680876936, -389564586, 606105819, -1044525330, -176418897, 1200080426, -1473231341,
            -45705983, 1770035416, -1958414417, -42063, -1990404162, 1804603682, -40341101,
            -1502002290, 1236535329,
            -165796510, -1069501632, 643717713, -373897302, -701558691, 38016083, -660478335,
            -405537848, 568446438, -1019803690, -187363961, 1163531501, -1444681467, -51403784,
            1735328473, -1926607734,
            -378558, -2022574463, 1839030562, -35309556, -1530992060, 1272893353, -155497632,
            -1094730640, 681279174, -358537222, -722521979, 76029189, -640364487, -421815835,
            530742520, -995338651,
            -198630844, 1126891415, -1416354905, -57434055, 1700485571, -1894986606, -1051523,
            -2054922799, 1873313359, -30611744, -1560198380, 1309151649, -145523070, -1120210379,
            718787259, -343485551
        )
    }
}
