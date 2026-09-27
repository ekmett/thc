// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.Json

class NarrowIntegerCarrierTest {
    private fun entered(action: (Language) -> Unit) = Context.newBuilder("thc").build().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun proof(integer: NarrowInteger) = CoreRepresentation(CoreKind.LONG, true, true, listOf(integer.rep))

    @Test fun exactLoweredProofSeparatesIntComputationFromMachineAnd64BitLong() {
        for (integer in NarrowInteger.entries) {
            val proof = proof(integer)
            assertTrue(proof.isInt); assertFalse(proof.isLong)
            assertEquals(listOf(integer.rep), proof.primReps)
            assertEquals(if (integer.bits == 8) Byte::class.javaPrimitiveType else
                if (integer.bits == 16) Short::class.javaPrimitiveType else Int::class.javaPrimitiveType, integer.storageClass)
            assertThrows(RuntimeFault::class.java) {
                proof.refine(CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep")))
            }
        }
        for (rep in listOf("IntRep", "WordRep", "Int64Rep", "Word64Rep")) {
            val proof = CoreRepresentation(CoreKind.LONG, true, true, listOf(rep))
            assertTrue(proof.isLong); assertFalse(proof.isInt)
        }
        assertEquals(-1, NarrowInteger.WORD32.narrow(-1))
        assertEquals(4294967295L, NarrowInteger.WORD32.widen(-1))
        assertEquals(-1L, NarrowInteger.INT32.widen(-1))
    }

    @Test fun primitiveIntFramesWidenOnlyToObjectAndRetainConcurrentActivationTags() {
        val descriptor = FrameDescriptor.newBuilder().apply { addSlot(FrameSlotKind.Illegal, "value", null) }.build()
        val first = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        val second = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
        FrameAccess.writeInt(first, 0, Int.MIN_VALUE)
        assertEquals(FrameSlotKind.Int, descriptor.getSlotKind(0))
        FrameAccess.writeObject(second, 0, "reference")
        assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0))
        assertEquals(Int.MIN_VALUE, FrameAccess.read(first, 0))
        FrameAccess.writeInt(first, 0, Int.MAX_VALUE)
        assertTrue(first.isObject(0)); assertEquals(Int.MAX_VALUE, first.getObject(0))
        assertEquals("reference", FrameAccess.read(second, 0))
    }

    @Test fun capturesHandoffsAndConstructorsKeepNarrowStoredWidthsAndIntCarriers() = entered { language ->
        for (integer in NarrowInteger.entries) {
            val proof = proof(integer)
            val capture = CaptureLayout.withVectors(language, arrayOf(null), booleanArrayOf(true),
                exactInt = arrayOf(integer))
            val packet = language.handoffLayouts.intern(listOf(integer.rep))
            val data = DataLayout(language, "NarrowCarrier.${integer.name}", integer.name, arrayOf(integer.rep))
            for (bits in listOf(Int.MIN_VALUE, -65537, -129, -1, 0, 1, 127, 65535, Int.MAX_VALUE)) {
                val expected = integer.narrow(bits)
                val value = capture.captureValues(arrayOf(bits))
                assertTrue(capture.isInt(value, 0)); assertFalse(capture.isLong(value, 0))
                assertEquals(expected, capture.readInt(value, 0)); assertEquals(expected, capture.read(value, 0))
                val storage = packet.create(); packet.setInt(storage, 0, bits)
                assertTrue(packet.isInt(0)); assertFalse(packet.isLong(0)); assertEquals(expected, packet.getInt(storage, 0))
                assertThrows(RuntimeFault::class.java) { packet.getLong(storage, 0) }
                val constructed = data.createInt(bits)
                assertTrue(data.isInt(0)); assertFalse(data.isLong(0)); assertEquals(expected, data.readInt(constructed, 0))
                assertThrows(RuntimeFault::class.java) { data.readLong(constructed, 0) }
            }
            assertTrue(ArgumentLayout.fromProofs(listOf(proof))!!.requiresTyped)
            assertThrows(RuntimeFault::class.java) { capture.captureValues(arrayOf(1L)) }
            assertThrows(RuntimeFault::class.java) { packet.copyIn(packet.create(), arrayOf(1L)) }
            assertThrows(RuntimeFault::class.java) { data.create(arrayOf(1L)) }
        }
    }

    @Test fun scalarOperationsUseIntAndOnlyDeclaredWideningsProduceLong() {
        for (integer in NarrowInteger.entries) {
            val family = integer.rep.removeSuffix("Rep")
            val op = NarrowScalarOp.named("plus$family#")!!
            assertEquals(integer.narrow(Int.MAX_VALUE + 1), op.intResult(Int.MAX_VALUE, 1))
            assertTrue(op.result.isInt)
        }
        assertNull(NarrowScalarOp.named("narrow32Int#"))
        assertTrue(NarrowScalarOp.named("intToInt32#")!!.sourceLong)
        assertEquals(-1L, NarrowScalarOp.named("int32ToInt#")!!.longResult(-1, 0))
        assertEquals(4294967295L, NarrowScalarOp.named("word32ToWord#")!!.longResult(-1, 0))
        assertEquals(2147483647, NarrowScalarOp.named("quotWord32#")!!.intResult(-1, 2))
        assertEquals(1, NarrowScalarOp.named("remWord32#")!!.intResult(-1, 2))
        assertEquals(1L, NarrowScalarOp.named("gtWord32#")!!.longResult(Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test fun bothBackendsKeepNarrowArithmeticBetweenExplicitMachineBoundaries() {
        fun app(name: String, vararg operands: List<Any?>): List<Any?> =
            listOf("app", listOf("prim", name), operands.toList(), List(operands.size) { false })
        val cases = listOf(Long.MIN_VALUE, -4294967297L, -65537L, -129L, -1L, 0L, 1L,
            127L, 65535L, 2147483648L, 4294967295L, Long.MAX_VALUE)
        for (backend in listOf("ast", "bytecode")) for (integer in NarrowInteger.entries) {
            val family = integer.rep.removeSuffix("Rep")
            val lower = family.replaceFirstChar(Char::lowercaseChar)
            val machine = if (integer.unsigned) "word" else "int"
            val body = app("${lower}To${machine.replaceFirstChar(Char::uppercaseChar)}#",
                app("plus$family#", app("${machine}To$family#", listOf("var", "x")),
                    app("${machine}To$family#", listOf("var", "y"))))
            val parameters = listOf("x", "y").map { id -> mapOf("id" to id, "name" to id,
                "type" to "Int#", "lifted" to false, "coercion" to false,
                "rep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)) }
            val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "NarrowCarrierControl",
                "constructors" to emptyList<Any>(), "bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
                    "lifted" to true, "arity" to 2, "expr" to listOf("lam", parameters, body))))
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    val function = context.eval("thc", Json.stringify(mapOf("backend" to backend, "entry" to "entry",
                        "instrument" to true, "modules" to listOf(module))))
                    fun check() {
                        for (x in cases) for (y in cases) assertEquals(integer.widen(integer.narrow(x.toInt() + y.toInt())),
                            function.execute(x, y).asLong(), "$backend/$family/$x/$y")
                    }
                    check()
                    assertTrue(function.invokeMember("compile").asBoolean())
                    check()
                }
        }
    }
}

/** Internal scalar tests supply exact JVM carriers and still call the original
 * compiled target. Narrow formals now use the typed packet convention; this is
 * not the public numeric conversion boundary and performs no guest warmup. */
internal fun callScalarTestTarget(target: com.oracle.truffle.api.RootCallTarget, arguments: Array<Any?>): Any? {
    val entry = (target.rootNode as? GuestRoot)?.typedInput ?: return Calls.target(target, arguments)
    if (entry.logical.physicalArity != entry.logical.logicalArity ||
        (0 until entry.logical.logicalArity).any { entry.logical.isTyped(it) })
        fault("Scalar test caller cannot flatten aggregate inputs")
    if (arguments.size != entry.header + entry.logical.physicalArity) fault("Wrong scalar test argument count")
    val shape = entry.packet
    val storage = entry.state().arguments.acquire(shape).also { it.inputMode = 1 }
    try {
        for (index in arguments.indices) {
            val value = arguments[index]
            when {
                shape.isInt(index) -> shape.setInt(storage, index, value as? Int ?: fault("Expected Int test argument"))
                shape.isLong(index) -> shape.setLong(storage, index, value as? Long ?: fault("Expected Long test argument"))
                shape.isFloat(index) -> shape.setFloat(storage, index, value as? Float ?: fault("Expected Float test argument"))
                shape.isDouble(index) -> shape.setDouble(storage, index, value as? Double ?: fault("Expected Double test argument"))
                else -> shape.setObject(storage, index, value)
            }
        }
        return invokeTypedInput(entry, storage) { Calls.target(target, it) }
    } finally { entry.releaseChecked(storage) }
}
