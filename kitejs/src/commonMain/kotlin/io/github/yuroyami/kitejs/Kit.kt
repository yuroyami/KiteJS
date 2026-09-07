/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Collection of utilities.
 *
 * Ported subset: the class-loading, listener-bag and IO helpers are JVM-only concerns
 * and are not part of the port.
 */
public object Kit {

    /**
     * If character [c] is a hexadecimal digit, return [accumulator] * 16 plus the
     * corresponding number. Otherwise return -1.
     */
    public fun xDigitToInt(c: Int, accumulator: Int): Int {
        var v = c
        when {
            v <= '9'.code -> {
                v -= '0'.code
                if (v < 0) return -1
            }
            v <= 'F'.code -> {
                if (v < 'A'.code) return -1
                v -= 'A'.code - 10
            }
            v <= 'f'.code -> {
                if (v < 'a'.code) return -1
                v -= 'a'.code - 10
            }
            else -> return -1
        }
        return (accumulator shl 4) or v
    }

    /**
     * Throws RuntimeException to indicate failed assertion. The function never returns and its
     * return type is RuntimeException only to be able to write `throw Kit.codeBug()` if plain
     * `Kit.codeBug()` triggers unreachable code error.
     */
    public fun codeBug(): RuntimeException {
        val ex = IllegalStateException("FAILED ASSERTION")
        ex.printStackTrace()
        throw ex
    }

    public fun codeBug(msg: String): RuntimeException {
        val ex = IllegalStateException("FAILED ASSERTION: $msg")
        ex.printStackTrace()
        throw ex
    }

    /**
     * Puts [initialValue] under [key] unless something is already there, and returns whichever
     * value ended up in the map.
     */
    public fun initHash(h: MutableMap<Any, Any>, key: Any, initialValue: Any): Any {
        val current = h[key]
        if (current == null) {
            h[key] = initialValue
            return initialValue
        }
        return current
    }
}
