@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import thc.executionContext
import thc.loadEntry
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

class FloatingPrimitiveTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val module = File(root, "build/floating/core/FloatingAudit.json")
    private data class Row(val entry: String, val input: Long, val expected: Long)
    private fun rows() = File(root, "build/floating/oracle.tsv").readLines().filter { it.isNotBlank() }.map {
        val fields = it.split('\t'); Row(fields[0], fields[1].toLong(), fields[2].toLong())
    }

    @Test fun nativeArithmeticRoundingNonFiniteComparisonsAndStorageMatchBothCompiledBackends() {
        val provenance = Json.parse(File(root, "build/floating/checks.json").readText()) as Map<String, Any?>
        for (record in (provenance["sources"] as List<Map<String, String>>) +
                (provenance["artifacts"] as List<Map<String, String>>)) {
            val file = File(root, record.getValue("path"))
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(record["sha256"], hash, "Stale floating fixture: $file; rerun scripts/prepare-tests.sh")
        }
        val entries = rows().groupBy { it.entry }
        assertEquals(15, entries.size)
        assertEquals(441, entries.values.sumOf { it.size })
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for ((entry, rows) in entries) {
                val fn = loadEntry(context, listOf(module.path), entry, backend = backend)
                fun count(name: String) = ((Json.parse(fn.getMember("diagnostics").asString()) as Map<*, *>)[name] as Number).toLong()
                fun checkRows(phase: String) {
                    rows.forEach {
                        val before = count("compiledEntries")
                        assertEquals(it.expected, fn.execute(it.input).asLong(), "$backend/$entry/$phase/${it.input}")
                        if (phase == "after") assertTrue(count("compiledEntries") > before,
                            "$backend/$entry/${it.input} must execute installed code")
                    }
                }
                checkRows("before")
                repeat(40) { val row = rows[it % rows.size]; assertEquals(row.expected, fn.execute(row.input).asLong()) }
                assertTrue(fn.invokeMember("compile").asBoolean(), "$backend/$entry")
                val before = count("compiledEntries")
                checkRows("after")
                assertTrue(count("compiledEntries") > before, "$backend/$entry must execute installed code")
                assertEquals(0L, count("unsupportedTraps"), "$backend/$entry")
                if (entry == "floatingJoinSwap") assertTrue(count("localJoinTransfers") > 0, "$backend real join")
            }
        }
    }

    @Test fun previouslyColdNaNsInfinitiesSubnormalsAndSignedZerosRemainCorrectAfterCompilation() {
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (entry in listOf("floatComparisons", "doubleComparisons", "floatSignedZero", "doubleSignedZero")) {
                val fn = loadEntry(context, listOf(module.path), entry, backend = backend)
                val selected = rows().filter { it.entry == entry }
                val warm = selected.single { it.input == 7L }
                repeat(40) { assertEquals(warm.expected, fn.execute(warm.input).asLong()) }
                assertTrue(fn.invokeMember("compile").asBoolean())
                selected.filter { it.input in -3L..6L }.forEach {
                    assertEquals(it.expected, fn.execute(it.input).asLong(), "$backend/$entry/cold/${it.input}")
                }
            }
        }
    }

    @Test fun fieldsAndCapturesHaveConcreteFloatingStorageAndPreserveBits() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val data = DataLayout(language, "Floating", "Floating", arrayOf("FloatRep", "DoubleRep"))
                val captures = CaptureLayout(language, booleanArrayOf(false, false),
                    exactFloat = booleanArrayOf(true, false), exactDouble = booleanArrayOf(false, true))
                val layout = FrameLayout()
                val slots = intArrayOf(layout.bind("float"), layout.bind("double"))
                val descriptor = layout.build()
                val values = listOf(0.0f to -0.0, -0.0f to 0.0, Float.MIN_VALUE to Double.MIN_VALUE,
                    Float.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY,
                    Float.fromBits(0x7fc01234) to Double.fromBits(0x7ff8000000001234L))
                for ((f, d) in values) {
                    val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                    FrameAccess.writeFloat(frame, slots[0], f); FrameAccess.writeDouble(frame, slots[1], d)
                    assertTrue(frame.isFloat(slots[0])); assertTrue(frame.isDouble(slots[1]))
                    val box = data.create(arrayOf(f, d))
                    assertEquals(f.toRawBits(), data.readFloat(box, 0).toRawBits())
                    assertEquals(d.toRawBits(), data.readDouble(box, 1).toRawBits())
                    val payloadTypes = box.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.type }
                    assertTrue(Float::class.javaPrimitiveType in payloadTypes)
                    assertTrue(Double::class.javaPrimitiveType in payloadTypes)
                    for (capture in listOf(captures.capture(frame, slots), captures.captureValues(arrayOf(f, d)))) {
                        assertFalse(capture.isObject(0)); assertFalse(capture.isObject(1))
                        assertEquals(f.toRawBits(), captures.readFloat(capture, 0).toRawBits())
                        assertEquals(d.toRawBits(), captures.readDouble(capture, 1).toRawBits())
                        val storedTypes = capture.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.type }
                        assertTrue(Float::class.javaPrimitiveType in storedTypes)
                        assertTrue(Double::class.javaPrimitiveType in storedTypes)
                        val restored = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                        captures.restore(capture, 0, restored, slots[0]); captures.restore(capture, 1, restored, slots[1])
                        assertEquals(f.toRawBits(), restored.getFloat(slots[0]).toRawBits())
                        assertEquals(d.toRawBits(), restored.getDouble(slots[1]).toRawBits())
                    }
                    data.restore(box, 0, frame, slots[0]); data.restore(box, 1, frame, slots[1])
                    assertEquals(f.toRawBits(), frame.getFloat(slots[0]).toRawBits())
                    assertEquals(d.toRawBits(), frame.getDouble(slots[1]).toRawBits())
                }
                assertThrows(RuntimeFault::class.java) { captures.captureValues(arrayOf(1L, 2.0)) }
                assertThrows(RuntimeFault::class.java) { data.create(arrayOf(1.0, 2.0)) }

                // Shared descriptor widening must not erase the live tags of older frames.
                val old = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                FrameAccess.writeFloat(old, slots[0], -0.0f)
                val widened = Truffle.getRuntime().createVirtualFrame(emptyArray(), descriptor)
                FrameAccess.write(widened, slots[0], Any())
                assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(slots[0]))
                assertEquals((-0.0f).toRawBits(), (FrameAccess.read(old, slots[0]) as Float).toRawBits())

                // API/legacy robustness: no ordinary valid GHC scalar binder
                // that widens this way is known. New activations follow an
                // already widened descriptor and store exact boxed carriers.
                val scalarLayout = FrameLayout()
                val scalarSlots = intArrayOf(scalarLayout.bind("long"), scalarLayout.bind("float"), scalarLayout.bind("double"))
                val scalarDescriptor = scalarLayout.build()
                val scalarCaptures = CaptureLayout(language, booleanArrayOf(true, false, false),
                    exactLong = booleanArrayOf(true, false, false), exactFloat = booleanArrayOf(false, true, false),
                    exactDouble = booleanArrayOf(false, false, true))
                val scalarValues = arrayOf<Any>(Long.MIN_VALUE, -0.0f, Double.fromBits(0x7ff8000000001234L))
                val primitiveFrame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scalarDescriptor)
                scalarSlots.indices.forEach { FrameAccess.write(primitiveFrame, scalarSlots[it], scalarValues[it]) }
                val wideningFrame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scalarDescriptor)
                scalarSlots.forEach { FrameAccess.write(wideningFrame, it, Any()) }
                val boxedFrame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scalarDescriptor)
                FrameAccess.writeLong(boxedFrame, scalarSlots[0], scalarValues[0] as Long)
                FrameAccess.writeFloat(boxedFrame, scalarSlots[1], scalarValues[1] as Float)
                FrameAccess.writeDouble(boxedFrame, scalarSlots[2], scalarValues[2] as Double)
                assertTrue(scalarSlots.all { boxedFrame.isObject(it) })
                for (frame in listOf(primitiveFrame, boxedFrame)) {
                    val capture = scalarCaptures.capture(frame, scalarSlots)
                    assertEquals(scalarValues[0], scalarCaptures.readLong(capture, 0))
                    assertEquals((scalarValues[1] as Float).toRawBits(), scalarCaptures.readFloat(capture, 1).toRawBits())
                    assertEquals((scalarValues[2] as Double).toRawBits(), scalarCaptures.readDouble(capture, 2).toRawBits())
                }
                for ((index, wrong) in listOf(0 to 1.0, 1 to 1.0, 2 to 1.0f)) {
                    FrameAccess.write(boxedFrame, scalarSlots[index], wrong)
                    assertThrows(RuntimeFault::class.java) { scalarCaptures.capture(boxedFrame, scalarSlots) }
                    FrameAccess.write(boxedFrame, scalarSlots[index], scalarValues[index])
                }
            } finally { context.leave() }
        }
    }

    @Test fun exactFloatingTupleProofsDoNotEnableFloatingLiteralAlternatives() {
        for ((kind, register) in listOf("float" to "FloatRep", "double" to "DoubleRep")) {
            val proof = mapOf("kind" to kind, "primReps" to listOf(register), "evaluated" to true)
            assertEquals(kind.uppercase(), CoreRepresentations.parse(proof).kind.name)
            assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(proof + ("primReps" to listOf("IntRep"))) }
            assertTrue(CoreRepresentations.parse(mapOf(
                "kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-tuple",
                "components" to listOf(proof), "primReps" to listOf(register))).isTuple)
            val body = listOf("case", listOf("lit", kind, "0.0"), "scrutinee", listOf(
                listOf("lit", listOf(kind, "-0.0"), emptyList<String>(), listOf("lit", "int", "1")),
                listOf("default", null, emptyList<String>(), listOf("lit", "int", "0"))))
            val binding = mapOf("id" to "entry", "name" to "entry", "arity" to 0, "lifted" to true, "expr" to body)
            for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                val request = Json.stringify(mapOf("entry" to "entry", "backend" to backend,
                    "modules" to listOf(mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Floating.Invalid",
                        "bindings" to listOf(binding), "constructors" to emptyList<Any>()))))
                val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request) }
                assertTrue(error.message.orEmpty().contains("Floating literal alternatives"), error.message)
            }
            val opposite = if (kind == "float") "double" else "float"
            val carriers = listOf(
                Triple(opposite, listOf(if (opposite == "float") "FloatRep" else "DoubleRep"), listOf("lit", opposite, "1.0")),
                Triple("long", listOf("IntRep"), listOf("lit", "int", "1")),
                Triple("address", listOf("AddrRep"), listOf("lit", "string-bytes", "41")),
                Triple("void", emptyList<String>(), listOf("void")))
            val malformed = carriers.flatMap { (otherKind, registers, otherLiteral) ->
                val otherProof = mapOf("kind" to otherKind, "primReps" to registers, "evaluated" to true)
                listOf(listOf("lit", kind, "1.0") to otherProof, otherLiteral to proof).flatMap { (literal, declared) ->
                    listOf(literal + mapOf("rep" to declared),
                        listOf("case", listOf("lit", "int", "0"), "s", listOf(
                            listOf("default", null, emptyList<String>(), literal)), mapOf("rep" to declared)))
                }
            }
            for (expr in malformed) for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                val request = Json.stringify(mapOf("entry" to "entry", "backend" to backend,
                    "modules" to listOf(mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Floating.Invalid",
                        "bindings" to listOf(binding + ("expr" to expr)), "constructors" to emptyList<Any>()))))
                val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request) }
                assertTrue(error.message.orEmpty().contains("Conflicting Core representation proofs"), error.message)
            }
        }
    }

    @Test fun floatingCaseValidationChecksEveryKnownAlternativeWithoutOrderDependence() {
        for ((kind, register) in listOf("float" to "FloatRep", "double" to "DoubleRep")) {
            val proof = mapOf("kind" to kind, "primReps" to listOf(register), "evaluated" to true)
            val floating = listOf("lit", kind, "1.0")
            val others = listOf(listOf("lit", if (kind == "float") "double" else "float", "1.0"),
                listOf("lit", "int", "1"), listOf("lit", "string-bytes", "41"), listOf("void"))
            for (other in others) for (arms in listOf(listOf(floating, other), listOf(other, floating))) {
                for (declared in listOf(null, proof)) for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                    val expr = listOf("case", listOf("lit", "int", "0"), "s", listOf(
                        listOf("default", null, emptyList<String>(), arms[0]),
                        listOf("lit", listOf("int", "0"), emptyList<String>(), arms[1]))) +
                        if (declared == null) emptyList() else listOf(mapOf("rep" to declared))
                    val binding = mapOf("id" to "entry", "name" to "entry", "arity" to 0, "lifted" to true, "expr" to expr)
                    val request = Json.stringify(mapOf("entry" to "entry", "backend" to backend,
                        "modules" to listOf(mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Floating.MixedCase",
                            "bindings" to listOf(binding), "constructors" to emptyList<Any>()))))
                    val error = assertThrows(PolyglotException::class.java) { context.eval("thc", request) }
                    assertTrue(error.message.orEmpty().contains("Conflicting Core representation proofs"), error.message)
                }
            }
        }
    }

    @Test fun floatingSelfTransfersRejectWrongPrimitiveCarriers() {
        val target = object : RootNode(null) { override fun execute(frame: VirtualFrame): Any = 0L }.callTarget
        val closure = Closure(null, arity = 1, target = target)
        for (kind in listOf(CoreKind.FLOAT, CoreKind.DOUBLE)) {
            val layout = FrameLayout()
            val destination = layout.bind("formal")
            val temporary = layout.bind("temporary")
            val self = AstSelfLayout(null, intArrayOf(), intArrayOf(destination),
                arrayOf(CoreRepresentation(kind, evaluated = true)), booleanArrayOf(true))
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
            FrameAccess.writeLong(frame, temporary, 1L)
            assertThrows(RuntimeFault::class.java) { self.transfer(frame, closure, intArrayOf(temporary)) }
        }
    }
}
