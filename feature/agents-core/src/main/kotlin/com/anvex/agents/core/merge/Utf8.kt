package com.anvex.agents.core.merge

/**
 * Strict UTF-8 validation per RFC 3629 (no overlong forms, no surrogates, nothing above U+10FFFF, no
 * truncated sequences). Written by hand so agents-core needs no java.nio (architecture guard); the test
 * suite cross-checks it against the JDK's strict decoder on random input.
 */
object Utf8 {
    fun isValid(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        fun cont(at: Int, lo: Int = 0x80, hi: Int = 0xBF): Boolean = at < n && (bytes[at].toInt() and 0xFF) in lo..hi
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> i += 1
                b in 0xC2..0xDF -> { if (!cont(i + 1)) return false; i += 2 }
                b == 0xE0 -> { if (!cont(i + 1, 0xA0, 0xBF) || !cont(i + 2)) return false; i += 3 }
                b == 0xED -> { if (!cont(i + 1, 0x80, 0x9F) || !cont(i + 2)) return false; i += 3 }
                b in 0xE1..0xEC || b in 0xEE..0xEF -> { if (!cont(i + 1) || !cont(i + 2)) return false; i += 3 }
                b == 0xF0 -> { if (!cont(i + 1, 0x90, 0xBF) || !cont(i + 2) || !cont(i + 3)) return false; i += 4 }
                b in 0xF1..0xF3 -> { if (!cont(i + 1) || !cont(i + 2) || !cont(i + 3)) return false; i += 4 }
                b == 0xF4 -> { if (!cont(i + 1, 0x80, 0x8F) || !cont(i + 2) || !cont(i + 3)) return false; i += 4 }
                else -> return false
            }
        }
        return true
    }
}
