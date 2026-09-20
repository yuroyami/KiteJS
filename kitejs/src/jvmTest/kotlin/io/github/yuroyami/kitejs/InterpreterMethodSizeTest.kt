/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The interpreter's instruction dispatch has to stay small enough for the JVM to compile it.
 *
 * HotSpot refuses to compile any method longer than 8000 bytecodes and runs it interpreted for
 * the life of the process. `Interpreter.execute` is one `when` over every instruction the engine
 * has, so it grows whenever an instruction is added, and crossing that line would make the whole
 * engine several times slower with nothing in the build to say why. The flag that lifts the limit,
 * `-XX:-DontCompileHugeMethods`, is not something a library can ask an embedder to set.
 *
 * This test reads the class file and measures the method. Splitting rarely used instructions out
 * into `executeCold` is what has kept it under the line so far.
 */
class InterpreterMethodSizeTest {

    /** What HotSpot calls `HugeMethodLimit`. A method at or above this is never compiled. */
    private val hugeMethodLimit = 8000

    /** How much room to keep, so this fails while there is still time to do something about it. */
    private val headroom = 600

    @Test
    fun the_dispatch_method_stays_inside_the_limit() {
        val sizes = methodSizesOf("io/github/yuroyami/kitejs/Interpreter\$Companion.class")
        val execute = sizes["execute"] ?: error("Interpreter\$Companion has no execute method")
        assertTrue(
            execute < hugeMethodLimit - headroom,
            "Interpreter.execute is $execute bytecodes. HotSpot stops compiling a method at " +
                "$hugeMethodLimit, and the engine keeps $headroom in hand. Move instructions " +
                "into executeCold.",
        )
    }

    /** Every method of a class file, by name, with the length of its code. */
    private fun methodSizesOf(resource: String): Map<String, Int> {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.readBytes()
            ?: error("$resource is not on the test classpath")
        val input = DataInputStream(bytes.inputStream())
        require(input.readInt() == -0x35014542) { "$resource is not a class file" }
        input.readUnsignedShort() // minor version
        input.readUnsignedShort() // major version

        val constants = arrayOfNulls<String>(input.readUnsignedShort())
        var index = 1
        while (index < constants.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> constants[index] = input.readUTF()
                7, 8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    index++ // a long or a double takes two entries
                }
                else -> error("unknown constant pool tag $tag")
            }
            index++
        }

        input.skipBytes(6) // access flags, this class, super class
        input.skipBytes(2 * input.readUnsignedShort()) // interfaces
        repeat(input.readUnsignedShort()) { skipMember(input) } // fields

        val sizes = HashMap<String, Int>()
        repeat(input.readUnsignedShort()) {
            input.skipBytes(2) // access flags
            val name = constants[input.readUnsignedShort()]!!
            input.skipBytes(2) // descriptor
            repeat(input.readUnsignedShort()) {
                val attribute = constants[input.readUnsignedShort()]!!
                val length = input.readInt()
                if (attribute == "Code") {
                    input.skipBytes(4) // max stack, max locals
                    sizes[name] = input.readInt()
                    input.skipBytes(length - 8)
                } else {
                    input.skipBytes(length)
                }
            }
        }
        return sizes
    }

    private fun skipMember(input: DataInputStream) {
        input.skipBytes(6)
        repeat(input.readUnsignedShort()) {
            input.skipBytes(2)
            input.skipBytes(input.readInt())
        }
    }
}
