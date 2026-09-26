// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import jdk.incubator.vector.IntVector

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.nio.ByteOrder

class Word32VectorMemoryProofTest {
    private val operations = VectorByteArrayOp.entries.filter { it.family == VectorMemoryFamily.WORD32 }
    private fun scalar(kind: String, rep: String?) = mapOf("kind" to kind,
        "primReps" to if (rep == null) emptyList<String>() else listOf(rep), "evaluated" to true)
    private val state = scalar("void", null)
    private val integer = scalar("long", "IntRep")
    private val lane = scalar("long", "Word32Rep")
    private val word = scalar("long", "WordRep")
    private val array = scalar("object", "BoxedRep (Just Unlifted)")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private val vector = mapOf("kind" to "vector", "primReps" to listOf("VecRep 4 Word32ElemRep"),
        "evaluated" to true, "vector" to mapOf("lanes" to 4, "element" to "Word32ElemRep"))
    private val signedVector = vector + mapOf("primReps" to listOf("VecRep 4 Int32ElemRep"),
        "vector" to mapOf("lanes" to 4, "element" to "Int32ElemRep"))
    private val unpacked = mapOf("kind" to "unknown", "primReps" to List(4) { "Word32Rep" },
        "evaluated" to true, "aggregate" to "unboxed-tuple", "components" to List(4) { lane })
    private val written = intArrayOf(Int.MIN_VALUE, 0x01234567, -1, Int.MAX_VALUE)
    private val weights = longArrayOf(3, 5, 7, 11)
    private fun readResult(evaluated: Boolean) = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to listOf("VecRep 4 Word32ElemRep"), "vector" to vector.getValue("vector"),
        "components" to listOf(state, vector), "evaluated" to evaluated)
    private fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { it.key as String to copy(it.value) }
        is List<*> -> value.map(::copy).toMutableList()
        else -> value
    }
    private fun map(value: Any?) = value as MutableMap<String, Any?>
    private fun list(value: Any?) = value as MutableList<Any?>
    private fun binder(id: String, proof: Map<String, Any?>) = mutableMapOf<String, Any?>(
        "id" to id, "name" to id, "lifted" to false, "coercion" to false, "rep" to copy(proof))
    private fun variable(id: String, proof: Map<String, Any?>) = mutableListOf<Any?>("var", id, mutableMapOf("rep" to copy(proof)))
    private fun literal(value: Long, kind: String = "int", proof: Map<String, Any?> = integer) =
        mutableListOf<Any?>("lit", kind, value.toString(), mutableMapOf("rep" to copy(proof)))
    private fun call(name: String, arguments: List<List<Any?>>, proof: Map<String, Any?>) = mutableListOf<Any?>(
        "app", listOf("prim", name, mapOf("rep" to closure)), arguments.toMutableList(),
        MutableList(arguments.size) { false }, false, false, mutableMapOf("rep" to copy(proof)))
    private fun checksumExpression(): MutableList<Any?> {
        var result = literal(0)
        for (i in 0..3) {
            val asWord = call("word32ToWord#", listOf(variable("lane$i", lane)), word)
            val asInt = call("word2Int#", listOf(asWord), integer)
            val weighted = call("*#", listOf(asInt, literal(weights[i])), integer)
            result = call("+#", listOf(result, weighted), integer)
        }
        return result
    }
    private fun consume(value: List<Any?>): MutableList<Any?> = mutableListOf("case",
        call("unpackWord32X4#", listOf(value), unpacked), "lanes", mutableListOf(mutableListOf<Any?>(
            "data", "Tuple4", MutableList(4) { "lane$it" }, checksumExpression(),
            mutableMapOf("binders" to MutableList(4) { binder("lane$it", lane) }))),
        mutableMapOf("rep" to copy(integer), "binder" to binder("lanes", unpacked)))
    private fun packed(): MutableList<Any?> {
        val tuple = mutableListOf<Any?>("app", listOf("con", "Tuple4", 4),
            written.map { literal(it.toLong() and 0xffff_ffffL, "word32", lane) }, List(4) { false }, false, true,
            mutableMapOf("rep" to copy(unpacked)))
        return call("packWord32X4#", listOf(tuple), vector)
    }
    private data class Fixture(val module: MutableMap<String, Any?>, val app: MutableList<Any?>,
        val body: MutableList<Any?>, val parameters: List<MutableMap<String, Any?>>)
    private fun fixture(operation: VectorByteArrayOp): Fixture {
        val parameters = listOf(binder("array", array), binder("offset", integer), binder("state", state))
        val operands = listOf(variable("array", array), variable("offset", integer))
        val app = when {
            operation.isRead -> call(operation.primitive, operands + listOf(variable("state", state)), readResult(false))
            operation.isWrite -> call(operation.primitive, operands + listOf(packed(), variable("state", state)), state)
            else -> call(operation.primitive, operands, vector)
        }
        val body = when {
            operation.isRead -> mutableListOf<Any?>("case", app, "whole", mutableListOf(mutableListOf<Any?>(
                "data", "Tuple2", mutableListOf("nextState", "vector"), consume(variable("vector", vector)),
                mutableMapOf("binders" to mutableListOf(binder("nextState", state), binder("vector", vector))))),
                mutableMapOf("rep" to copy(integer), "binder" to binder("whole", readResult(true))))
            operation.isWrite -> mutableListOf<Any?>("case", app, "afterWrite", mutableListOf(mutableListOf<Any?>(
                "default", null, emptyList<String>(), consume(call(if (operation.scalarOffset)
                    "indexWord32ArrayAsWord32X4#" else "indexWord32X4Array#", operands, vector)),
                mutableMapOf("binders" to emptyList<Any>()))),
                mutableMapOf("rep" to copy(integer), "binder" to binder("afterWrite", state)))
            else -> consume(app)
        }
        val module = mutableMapOf<String, Any?>("schema" to 1, "ghc" to "9.14.1", "instrument" to true,
            "constructors" to mutableListOf(mutableMapOf<String, Any?>("id" to "Tuple2", "kind" to "unboxed-tuple", "arity" to 2),
                mutableMapOf<String, Any?>("id" to "Tuple4", "kind" to "unboxed-tuple", "arity" to 4)),
            "bindings" to mutableListOf(mutableMapOf<String, Any?>("id" to "root", "name" to "root", "arity" to 3,
                "lifted" to true, "rep" to copy(closure), "expr" to mutableListOf("lam", parameters, body,
                    mutableMapOf("rep" to copy(closure), "resultRep" to copy(integer))))))
        return Fixture(module, app, body, parameters)
    }
    private fun withLanguage(coldTransition: Boolean = false, action: (Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .also { builder -> if (coldTransition) builder.option("engine.SingleTierCompilationThreshold", "10000000") }
        .build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
        }
    private fun program(language: Language, backend: String, fixture: Fixture, diagnostic: Boolean): ExecutableProgram {
        val module = fixture.module + ("diagnosticUnsupported" to diagnostic)
        return if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    }
    private fun invoke(program: ExecutableProgram, bytes: ByteArray, index: Long, token: Any? = Unit): Any? =
        Calls.target(program.hostEntryTarget(3), arrayOf(program.entryValue("root"), arrayOf(bytes, index, token)))
    private fun shift(byte: Int) = 8 * (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else 3 - byte)
    private fun expected(bytes: ByteArray, offset: Int): Long = (0..3).sumOf { lane ->
        val value = (0..3).fold(0) { bits, byte -> bits or ((bytes[offset + lane * 4 + byte].toInt() and 255) shl shift(byte)) }
        (value.toLong() and 0xffff_ffffL) * weights[lane]
    }
    private fun storeModel(bytes: ByteArray, offset: Int) {
        for (lane in 0..3) for (byte in 0..3) bytes[offset + lane * 4 + byte] = (written[lane] ushr shift(byte)).toByte()
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }

    @Test fun allSixExactContractsExecuteOnBothBackendsInBothLoadModes() {
        assertEquals(CoreKind.VECTOR, CoreVectors.proofWord32.kind)
        assertEquals(CoreVector.WORD32X4, CoreVectors.proofWord32.vector)
        assertTrue(operations.all { it.vectorProof == CoreVectors.proofWord32 })
        assertEquals(setOf("indexWord32X4Array#", "indexWord32ArrayAsWord32X4#", "readWord32X4Array#",
            "readWord32ArrayAsWord32X4#", "writeWord32X4Array#", "writeWord32ArrayAsWord32X4#"),
            operations.map { it.primitive }.toSet())
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) withLanguage { language ->
            for (operation in operations) for (indexRep in listOf("IntRep", "WordRep")) {
                // The binder shares a Long carrier; the operation keeps its exact index proof.
                val f = fixture(operation)
                f.parameters[1]["rep"] = copy(scalar("long", indexRep))
                val p = program(language, backend, f, diagnostic)
                for (index in if (operation.scalarOffset) 0L..3L else 0L..1L) {
                    val bytes = ByteArray(40) { (it * 47 + 129).toByte() }
                    val expectedBytes = bytes.copyOf(); val offset = (index * if (operation.scalarOffset) 4 else 16).toInt()
                    if (operation.isWrite) storeModel(expectedBytes, offset)
                    assertEquals(expected(expectedBytes, offset), invoke(p, bytes, index), "$backend/$diagnostic/${operation.primitive}/$indexRep/$index")
                    assertArrayEquals(expectedBytes, bytes); released(language)
                }
                assertEquals(0L, (p.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            }
        }
    }

    private fun reject(language: Language, backend: String, diagnostic: Boolean, fixture: Fixture, label: String) {
        val bytes = ByteArray(40) { (it * 13 + 97).toByte() }; val before = bytes.copyOf()
        assertThrows(RuntimeFault::class.java, { invoke(program(language, backend, fixture, diagnostic), bytes, 0) }, label)
        assertArrayEquals(before, bytes, "rejected before effects $label"); released(language)
    }
    @Test fun allOperationsRejectWrongArgumentsFlagsArityAndResultProofs() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) withLanguage { language ->
            for (operation in operations) {
                val mutations = listOf("array-levity", "array-unknown", "index-signedness", "index-unknown",
                    "lexical-array", "lexical-index-float", "lexical-index-double", "partial", "over", "result") +
                    if (operation.isRead || operation.isWrite) listOf("state", "state-unknown") else emptyList()
                for (mutation in mutations) {
                    val f = fixture(operation); val args = list(f.app[2]); val flags = list(f.app[3])
                    when (mutation) {
                        "array-levity" -> map(list(args[0])[2])["rep"] = copy(scalar("object", "BoxedRep (Just Lifted)"))
                        "array-unknown" -> map(map(list(args[0])[2])["rep"])["kind"] = "unknown"
                        "index-signedness" -> map(list(args[1])[2])["rep"] = copy(scalar("long", "WordRep"))
                        "index-unknown" -> map(map(list(args[1])[2])["rep"])["kind"] = "unknown"
                        "lexical-array" -> f.parameters[0]["rep"] = copy(scalar("object", "BoxedRep (Just Lifted)"))
                        "lexical-index-float" -> f.parameters[1]["rep"] = copy(scalar("float", "FloatRep"))
                        "lexical-index-double" -> f.parameters[1]["rep"] = copy(scalar("double", "DoubleRep"))
                        "partial" -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex) }
                        "over" -> { args.add(copy(args[0])); flags.add(false) }
                        "result" -> map(f.app[6]).remove("rep")
                        "state" -> map(list(args.last())[2])["rep"] = copy(integer)
                        "state-unknown" -> map(map(list(args.last())[2])["rep"])["kind"] = "unknown"
                    }
                    reject(language, backend, diagnostic, f, "$backend/$diagnostic/$operation/$mutation")
                }
                for (index in list(fixture(operation).app[2]).indices) for (flag in listOf(true, null, 0L, "false")) {
                    val f = fixture(operation); list(f.app[3])[index] = flag
                    reject(language, backend, diagnostic, f, "$backend/$diagnostic/$operation/flag[$index]=$flag")
                }
                if (!operation.isRead) for (count in listOf<Any?>(4.0, 4.5, true, "4", null)) {
                    val f = fixture(operation)
                    val proof = if (operation.isWrite) map(map(list(list(f.app[2])[2])[6])["rep"])
                        else map(map(f.app[6])["rep"])
                    map(proof["vector"])["lanes"] = count
                    reject(language, backend, diagnostic, f, "$backend/$diagnostic/$operation/direct lanes=$count")
                }
                if (operation.isWrite) {
                    val f = fixture(operation); map(list(list(f.app[2])[2])[6])["rep"] = copy(signedVector)
                    reject(language, backend, diagnostic, f, "$backend/$diagnostic/$operation/signed vector")
                    val sameCarrier = fixture(operation)
                    val pack = list(list(sameCarrier.app[2])[2])
                    val tuple = list(list(pack[2])[0])
                    val firstLane = list(list(tuple[2])[0])
                    // The word32 tag supplies unsigned semantics, not this duplicate Long annotation.
                    map(firstLane[3])["rep"] = copy(scalar("long", "Int32Rep"))
                    val p = program(language, backend, sameCarrier, diagnostic)
                    for (index in if (operation.scalarOffset) 0L..3L else 0L..1L) {
                        val bytes = ByteArray(40) { (it * 47 + 129).toByte() }
                        val expectedBytes = bytes.copyOf()
                        val offset = (index * if (operation.scalarOffset) 4 else 16).toInt()
                        storeModel(expectedBytes, offset)
                        assertEquals(expected(expectedBytes, offset), invoke(p, bytes, index),
                            "$backend/$diagnostic/$operation/signed lane annotation/$index")
                        assertArrayEquals(expectedBytes, bytes); released(language)
                    }
                    for ((kind, rep) in listOf("float" to "FloatRep", "double" to "DoubleRep")) {
                        val wrongLiteral = fixture(operation)
                        val wrongPack = list(list(wrongLiteral.app[2])[2])
                        val wrongTuple = list(list(wrongPack[2])[0])
                        val wrongLane = list(list(wrongTuple[2])[0])
                        map(wrongLane[3])["rep"] = copy(scalar(kind, rep))
                        reject(language, backend, diagnostic, wrongLiteral,
                            "$backend/$diagnostic/$operation/$kind lane literal carrier")
                    }
                }
            }
        }
    }

    @Test fun immediateReadCasesRejectEscapesWrongShapesAndPatternMetadata() {
        val mutations = listOf("whole-escape", "missing-vector", "signed-result", "width", "components", "component-state", "component-unknown",
            "default", "multiple", "constructor", "arity", "duplicate-pattern", "whole-pattern", "pattern-order",
            "pattern-state", "pattern-signed", "pattern-unknown", "whole-levity", "pattern-levity", "whole-coercion", "pattern-coercion",
            "missing-whole-coercion", "missing-pattern-coercion", "whole-id", "whole-unevaluated", "pattern-unevaluated", "outer-result")
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) withLanguage { language ->
            for (operation in operations.filter { it.isRead }) for (mutation in mutations) {
                val f = fixture(operation); val alternative = list(list(f.body[3])[0])
                val whole = map(map(f.body[4])["binder"]); val records = list(map(alternative[4])["binders"])
                val result = map(map(f.app[6])["rep"])
                when (mutation) {
                    "whole-escape" -> alternative[3] = mutableListOf<Any?>("var", "whole")
                    "missing-vector" -> result.remove("vector")
                    "signed-result" -> { result["vector"] = signedVector["vector"]; result["primReps"] = signedVector["primReps"] }
                    "width" -> map(result["vector"])["lanes"] = 2
                    "components" -> list(result["components"]).reverse()
                    "component-state" -> list(result["components"])[0] = copy(integer)
                    "component-unknown" -> map(list(result["components"])[1])["kind"] = "unknown"
                    "default" -> alternative[0] = "default"
                    "multiple" -> list(f.body[3]).add(copy(alternative))
                    "constructor" -> list(f.module["constructors"]).removeAt(0)
                    "arity" -> map(list(f.module["constructors"])[0])["arity"] = 4
                    "duplicate-pattern" -> list(alternative[2])[1] = "nextState"
                    "whole-pattern" -> list(alternative[2])[1] = "whole"
                    "pattern-order" -> records.reverse()
                    "pattern-state" -> map(records[0])["rep"] = copy(integer)
                    "pattern-signed" -> map(records[1])["rep"] = copy(signedVector)
                    "pattern-unknown" -> map(map(records[1])["rep"])["kind"] = "unknown"
                    "whole-levity" -> whole["lifted"] = true
                    "pattern-levity" -> map(records[1])["lifted"] = true
                    "whole-coercion" -> whole["coercion"] = true
                    "pattern-coercion" -> map(records[1])["coercion"] = true
                    "missing-whole-coercion" -> whole.remove("coercion")
                    "missing-pattern-coercion" -> map(records[0]).remove("coercion")
                    "whole-id" -> whole["id"] = "other"
                    "whole-unevaluated" -> map(whole["rep"])["evaluated"] = false
                    "pattern-unevaluated" -> map(map(records[1])["rep"])["evaluated"] = false
                    "outer-result" -> map(f.body[4])["rep"] = copy(scalar("double", "DoubleRep"))
                }
                reject(language, backend, diagnostic, f, "$backend/$diagnostic/$operation/$mutation")
            }
            for (operation in operations.filter { it.isRead }) {
                for (site in listOf("producer", "whole", "producer-component", "whole-component", "pattern")) {
                    for (count in listOf<Any?>(4.0, 4.5, true, "4", null)) {
                        val f = fixture(operation)
                        val producer = map(map(f.app[6])["rep"])
                        val whole = map(map(map(f.body[4])["binder"])["rep"])
                        val alternative = list(list(f.body[3])[0])
                        val shape = when (site) {
                            "producer" -> producer
                            "whole" -> whole
                            "producer-component" -> map(list(producer["components"])[1])
                            "whole-component" -> map(list(whole["components"])[1])
                            else -> map(map(list(map(alternative[4])["binders"])[1])["rep"])
                        }
                        map(shape["vector"])["lanes"] = count
                        reject(language, backend, diagnostic, f, "$backend/$diagnostic/$operation/$site/lanes=$count")
                    }
                }
                for (arity in listOf<Any?>(2.0, 2.5, true, "2", null)) {
                    val f = fixture(operation)
                    map(list(f.module["constructors"])[0])["arity"] = arity
                    reject(language, backend, diagnostic, f, "$backend/$diagnostic/$operation/arity=$arity")
                }
            }
        }
    }

    @Test fun stateExpressionsRunBeforeReadOrWriteAndFailurePrecedesVectorBounds() {
        for (backend in listOf("ast", "bytecode")) for (diagnostic in listOf(false, true)) withLanguage { language ->
            for (operation in operations.filter { !it.isIndex }) for (failure in listOf(false, true)) {
                val f = fixture(operation)
                val stride = if (operation.scalarOffset) 4L else 16L
                val effectIndex = if (failure) literal(Long.MAX_VALUE) else call("*#", listOf(variable("offset", integer), literal(stride)), integer)
                list(f.app[2])[list(f.app[2]).lastIndex] = call("writeWord8Array#", listOf(variable("array", array), effectIndex,
                    literal(93, "word8", scalar("long", "Word8Rep")), variable("state", state)), state)
                val p = program(language, backend, f, diagnostic)
                val bytes = ByteArray(40) { (it * 37 + 161).toByte() }; val expectedBytes = bytes.copyOf()
                if (failure) {
                    val error = assertThrows(RuntimeFault::class.java) { invoke(p, bytes, Long.MAX_VALUE) }
                    assertTrue(error.message.orEmpty().contains("ByteArray# index outside"), "State must fail before vector bounds: $error")
                } else {
                    val offset = stride.toInt(); expectedBytes[offset] = 93
                    if (operation.isWrite) storeModel(expectedBytes, offset)
                    assertEquals(expected(expectedBytes, offset), invoke(p, bytes, 1))
                }
                assertArrayEquals(expectedBytes, bytes); released(language)
            }
            for (operation in operations.filter { !it.isIndex }) {
                val p = program(language, backend, fixture(operation), diagnostic)
                for (index in listOf(0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    val bytes = ByteArray(40) { 37 }; val before = bytes.copyOf()
                    val error = assertThrows(RuntimeFault::class.java) { invoke(p, bytes, index, 0L) }
                    assertTrue(error.message.orEmpty().contains("zero-width"), "invalid State must precede bounds: $error")
                    assertArrayEquals(before, bytes); released(language)
                }
                for (index in listOf(-1L, 10L, 1L shl 32, Long.MAX_VALUE)) {
                    val bytes = ByteArray(40) { 37 }; val before = bytes.copyOf()
                    assertThrows(RuntimeFault::class.java) { invoke(p, bytes, index) }
                    assertArrayEquals(before, bytes); released(language)
                }
            }
        }
    }

    @Test fun installedGuestBoundsFailuresDeoptimizeWithoutEffectsAndRecover() {
        var transitions = 0
        for (backend in listOf("ast", "bytecode")) for (operation in operations) {
            val stride = if (operation.scalarOffset) 4 else 16
            val invalid = listOf(0 to 0L, 15 to 0L, 40 to -1L, 40 to Long.MIN_VALUE, 40 to Long.MAX_VALUE,
                40 to (1L shl 32), 40 to (Int.MAX_VALUE.toLong() + 1), 40 to ((40L - 16) / stride + 1))
            for ((size, index) in invalid) withLanguage(coldTransition = true) { language ->
                val label = "$backend/${operation.primitive}/size=$size/index=$index"
                val p = program(language, backend, fixture(operation), false)
                val host = p.hostEntryTarget(3)
                val original = p.entryTarget("root")
                fun active(): RootCallTarget {
                    val calls = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                        .filter { it.callTarget === original }
                    assertEquals(1, calls.size, "$label selected guest call")
                    return calls.single().currentCallTarget as RootCallTarget
                }
                fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
                fun count() = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                fun validCall(phase: String) {
                    val bytes = ByteArray(40) { (it * 47 + 129).toByte() }
                    val expectedBytes = bytes.copyOf()
                    if (operation.isWrite) storeModel(expectedBytes, stride)
                    assertEquals(expected(expectedBytes, stride), invoke(p, bytes, 1), "$label/$phase")
                    assertArrayEquals(expectedBytes, bytes, "$label/$phase backing bytes")
                    released(language)
                }
                // Fixed profiling only: fresh storage on every invocation, then one
                // compilation. Keep the host bridge interpreted so the guest owns
                // the compiled bounds failure and its invalidation.
                repeat(40) { validCall("warm/$it") }
                val target = active()
                assertEquals(0L, count(), "$label no automatic compiled entries")
                assertFalse(valid(target), "$label no automatic installation")
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertTrue(valid(target), "$label installed guest")
                val before = count()
                validCall("installed")
                assertEquals(before + 1, count(), "$label exact compiled guest entry")
                assertSame(target, active(), "$label active installed identity")
                assertTrue(valid(target), "$label installed immediately before invalid input")
                assertFalse(valid(host), "$label host bridge remains interpreted")

                val bytes = ByteArray(size) { (it * 19 + 83).toByte() }
                val unchanged = bytes.copyOf()
                val sentinel = Any(); var published: Any? = sentinel
                val error = assertThrows(RuntimeFault::class.java, { published = invoke(p, bytes, index) }, label)
                assertEquals("Word32X4 ByteArray# range outside its backing storage", error.message, label)
                assertSame(sentinel, published, "$label no failed result publication")
                assertArrayEquals(unchanged, bytes, "$label no partial memory effects")
                released(language)
                assertSame(target, active(), "$label same target after failure")
                assertFalse(valid(target), "$label bounds failure invalidates installed guest")

                val afterFailure = count()
                validCall("recovery")
                assertEquals(afterFailure, count(), "$label recovery without recompilation")
                assertSame(target, active(), "$label recovery target identity")
                assertFalse(valid(target), "$label no recovery recompilation")
                for (counter in listOf("unsupportedTraps", "blackholes"))
                    assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                transitions++
            }
        }
        assertEquals(96, transitions)
    }

    @Test fun directAstOperandsPreserveStateOrderAndDoNotPublishFailedLoads() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        for (operation in operations.filter { !it.isIndex }) {
            val bytes = ByteArray(32) { 53 }; val before = bytes.copyOf(); val events = mutableListOf<String>()
            val failure = RuntimeFault("state marker")
            fun operand(name: String, action: () -> Any?) = object : Expr() {
                override fun execute(frame: VirtualFrame): Any? { events.add(name); return action() }
            }
            val arguments = mutableListOf<Expr>(operand("array") { bytes }, operand("index") { Long.MAX_VALUE })
            if (operation.isWrite) arguments.add(operand("vector") { IntVector.broadcast(IntVector.SPECIES_128, 1).withLane(1, 2).withLane(2, 3).withLane(3, 4) })
            arguments.add(operand("state") { throw failure })
            val expression = VectorByteArrayExpression(operation, arguments.toTypedArray())
            val sentinel = Any(); var published: Any? = sentinel
            assertSame(failure, assertThrows(RuntimeFault::class.java) { published = expression.execute(frame) })
            assertSame(sentinel, published); assertArrayEquals(before, bytes)
            assertEquals(if (operation.isWrite) listOf("array", "index", "vector", "state") else listOf("array", "index", "state"), events)
        }
    }
}
