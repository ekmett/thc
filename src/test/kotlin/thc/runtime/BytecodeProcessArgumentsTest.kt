// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.LocalAccessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

class BytecodeProcessArgumentsTest {
    @Test fun nullableMetadataPreservesPrimitiveAndObjectLocals() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in ProcessOp.values()) {
                    val locals = mutableListOf<LocalAccessor>()
                    val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                        b.beginRoot()
                        repeat(operation.arguments.size) {
                            locals += LocalAccessor.constantOf(b.createLocal("operand $it", null))
                        }
                        b.beginReturn(); b.emitLoadConstant(0L); b.endReturn()
                        b.endRoot()
                    }.getNode(0)
                    val bytecode = root.bytecodeNode
                    val frame = Truffle.getRuntime().createVirtualFrame(arrayOf(0L), root.frameDescriptor)
                    val reader = BytecodeProcessArguments(operation, locals.toTypedArray())
                    val address = ManagedAddress.fromHex("1122")
                    var previous: Array<Any?>? = null
                    // Null representation metadata denotes the object-carried State#
                    // local, not an absent operand. The reader preserves even null.
                    for (state in arrayOf(Unit, null)) {
                        val expected = operation.arguments.mapIndexed { index, representation ->
                            when (representation) {
                                "Int32Rep" -> if (state == null) Int.MIN_VALUE.toLong() + index else Int.MAX_VALUE.toLong() - index
                                "AddrRep" -> address
                                null -> state
                                else -> error("Unexpected process argument metadata: $representation")
                            }.also { value ->
                                if (value is Long) locals[index].setInt(bytecode, frame, value.toInt())
                                else locals[index].setObject(bytecode, frame, value)
                            }
                        }
                        val values = reader.read(bytecode, frame)
                        assertNotSame(previous, values)
                        assertEquals(expected.size, values.size)
                        expected.forEachIndexed { index, value ->
                            if (value is Long) assertEquals(value, values[index])
                            else assertSame(value, values[index])
                        }
                        previous = values
                    }
                }
            } finally { context.leave() }
        }
    }
}
