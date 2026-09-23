package com.nyora.hasan72341.core.vault

import android.content.Context

/**
 * Runtime side of the domain obfuscation (see buildSrc/DomainObfuscator.kt).
 *
 * The release build's ASM instrumentation rewrites every baked domain string constant into a call to
 * [d]. This loads the XOR-encrypted `dv.bin` asset once ([init], from Application.attachBaseContext) and hands
 * back the plain domain by index. Obfuscation, not security: the string is plaintext once returned —
 * this only stops static `strings`/apktool extraction, which is the Play-scan concern.
 */
object DomainVault {

    // Must match the key in the dv.bin generator.
    private val KEY = byteArrayOf(
        0x4e, 0x79, 0x6f, 0x72, 0x61, 0x56, 0x61, 0x75,
        0x6c, 0x74, 0x21, 0x37, 0x2a, 0x5f, 0x9c.toByte(), 0x3b,
    )

    @Volatile
    private var table: Array<String> = emptyArray()

    /**
     * Load + decrypt the table once. Safe to call repeatedly. Reads [context]'s own assets: during
     * Application.attachBaseContext the application context is not attached yet.
     */
    fun init(context: Context) {
        if (table.isNotEmpty()) return
        val bytes = runCatching {
            context.assets.open("dv.bin").use { it.readBytes() }
        }.getOrNull() ?: return
        table = parse(bytes)
    }

    /** Decrypt the domain at [index]. Called by the instrumented parser classes. */
    @JvmStatic
    fun d(index: Int): String = table.getOrElse(index) { "" }

    private fun parse(b: ByteArray): Array<String> {
        var p = 0
        fun u16(): Int {
            val v = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
            p += 2
            return v
        }
        val count = u16()
        return Array(count) {
            val len = u16()
            val out = ByteArray(len)
            for (i in 0 until len) {
                out[i] = (b[p + i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
            }
            p += len
            String(out, Charsets.UTF_8)
        }
    }
}
