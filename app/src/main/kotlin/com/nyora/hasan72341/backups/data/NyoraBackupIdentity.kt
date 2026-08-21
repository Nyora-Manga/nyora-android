package com.nyora.hasan72341.backups.data

/**
 * Stable portable identities used by the Nyora backup contract.
 *
 * The implementation deliberately lives in common code so every Nyora client
 * computes exactly the same IDs without relying on platform-specific hash APIs.
 */
object NyoraBackupIdentity {
    private val remoteSourceId = Regex("data:[a-z0-9][a-z0-9._-]*")
    private val sha256Id = Regex("[0-9a-f]{64}")

    fun requireRemoteSourceId(sourceId: String): String {
        require(remoteSourceId.matches(sourceId)) {
            "Portable backups require a canonical data:<catalogue-id> source identity"
        }
        return sourceId
    }

    fun mangaId(sourceId: String, contentKey: String): String {
        requireRemoteSourceId(sourceId)
        require(contentKey.isNotEmpty()) { "contentKey must not be empty" }
        return sha256("$sourceId\u0000$contentKey")
    }

    fun chapterId(mangaId: String, chapterKey: String): String {
        require(sha256Id.matches(mangaId)) { "mangaId must be a lower-case SHA-256 hex value" }
        require(chapterKey.isNotEmpty()) { "chapterKey must not be empty" }
        return sha256("$mangaId\u0000$chapterKey")
    }

    fun isCanonicalMangaId(value: String): Boolean = sha256Id.matches(value)

    private fun sha256(value: String): String = Sha256.digest(value.encodeToByteArray()).joinToString("") {
        it.toUByte().toString(16).padStart(2, '0')
    }
}

private object Sha256 {
    private val roundConstants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1,
        0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(), 0xe49b69c1.toInt(), 0xefbe4786.toInt(),
        0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152.toInt(),
        0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(),
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb,
        0x81c2c92e.toInt(), 0x92722c85.toInt(), 0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee,
        0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(),
        0xc67178f2.toInt(),
    )

    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padding = (64 - ((input.size + 9) % 64)) % 64
        val message = ByteArray(input.size + 1 + padding + 8)
        input.copyInto(message)
        message[input.size] = 0x80.toByte()
        for (index in 0 until 8) message[message.lastIndex - index] = (bitLength ushr (index * 8)).toByte()

        var a0 = 0x6a09e667
        var b0 = 0xbb67ae85.toInt()
        var c0 = 0x3c6ef372
        var d0 = 0xa54ff53a.toInt()
        var e0 = 0x510e527f
        var f0 = 0x9b05688c.toInt()
        var g0 = 0x1f83d9ab
        var h0 = 0x5be0cd19
        val words = IntArray(64)

        for (offset in message.indices step 64) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                words[index] = ((message[base].toInt() and 0xff) shl 24) or
                    ((message[base + 1].toInt() and 0xff) shl 16) or
                    ((message[base + 2].toInt() and 0xff) shl 8) or
                    (message[base + 3].toInt() and 0xff)
            }
            for (index in 16 until 64) {
                val s0 = words[index - 15].rotateRight(7) xor words[index - 15].rotateRight(18) xor (words[index - 15] ushr 3)
                val s1 = words[index - 2].rotateRight(17) xor words[index - 2].rotateRight(19) xor (words[index - 2] ushr 10)
                words[index] = words[index - 16] + s0 + words[index - 7] + s1
            }

            var a = a0
            var b = b0
            var c = c0
            var d = d0
            var e = e0
            var f = f0
            var g = g0
            var h = h0
            for (index in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val choose = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + choose + roundConstants[index] + words[index]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + majority
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
            e0 += e
            f0 += f
            g0 += g
            h0 += h
        }
        return intArrayOf(a0, b0, c0, d0, e0, f0, g0, h0).flatMap { word ->
            listOf((word ushr 24).toByte(), (word ushr 16).toByte(), (word ushr 8).toByte(), word.toByte())
        }.toByteArray()
    }
}
