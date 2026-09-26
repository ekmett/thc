// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.nio.ByteOrder

class Simd128AddressTest {
    private val families = setOf(VectorMemoryFamily.INT8, VectorMemoryFamily.WORD8,
        VectorMemoryFamily.INT16, VectorMemoryFamily.WORD16, VectorMemoryFamily.INT64, VectorMemoryFamily.WORD64)
    private val operations = VectorMemoryOp.entries.filter { it.isAddress && it.vectorBytes == 16 && it.family in families }
    private fun scalar(kind: String, rep: String?) = mapOf("kind" to kind,
        "primReps" to listOfNotNull(rep), "evaluated" to true)
    private val state = scalar("void", null)
    private val integer = scalar("long", "IntRep")
    private val array = scalar("address", "AddrRep")
    private val closure = scalar("closure", "BoxedRep (Just Lifted)")
    private fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { it.key as String to copy(it.value) }
        is List<*> -> value.map(::copy).toMutableList()
        else -> value
    }
    private fun binder(id: String, proof: Map<String, Any?>) = mutableMapOf<String, Any?>(
        "id" to id, "name" to id, "lifted" to false, "coercion" to false, "rep" to copy(proof))
    private fun variable(id: String, proof: Map<String, Any?>) = mutableListOf<Any?>("var", id, mutableMapOf("rep" to copy(proof)))
    private fun literal(value: Long, kind: String = "int", proof: Map<String, Any?> = integer) =
        mutableListOf<Any?>("lit", kind, value.toString(), mutableMapOf("rep" to copy(proof)))
    private fun call(name: String, args: List<List<Any?>>, proof: Map<String, Any?>) = mutableListOf<Any?>(
        "app", listOf("prim", name, mapOf("rep" to closure)), args.toMutableList(),
        MutableList(args.size) { false }, false, false, mutableMapOf("rep" to copy(proof)))

    private class Shape(operation: VectorMemoryOp) {
        val vector = operation.vectorProof.vector!!
        val lanes = vector.lanes
        val width = 16 / lanes
        val scalar = vector.element.removeSuffix("ElemRep")
        val name = scalar + "X" + lanes
        val unsigned = scalar.startsWith("Word")
        val proof = mapOf("kind" to "vector", "evaluated" to true,
            "primReps" to listOf("VecRep $lanes ${vector.element}"),
            "vector" to mapOf("lanes" to lanes, "element" to vector.element))
        val lane = mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf(scalar + "Rep"))
        val unpacked = mapOf("kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-tuple",
            "primReps" to List(lanes) { scalar + "Rep" }, "components" to List(lanes) { lane })
        val written = LongArray(lanes) { index ->
            val raw = Long.MIN_VALUE + 0x7fff_ffffL * index - 129
            if (width == 8) raw else if (unsigned) raw and ((1L shl (width * 8)) - 1)
            else raw shl (64 - width * 8) shr (64 - width * 8)
        }
    }
    private data class Fixture(val module: MutableMap<String, Any?>, val app: MutableList<Any?>,
        val body: MutableList<Any?>)
    private fun fixture(op: VectorMemoryOp): Fixture {
        val shape = Shape(op)
        fun checksum(): MutableList<Any?> {
            var result = literal(0)
            for (i in 0 until shape.lanes) {
                val value = variable("lane$i", shape.lane)
                val widened = if (shape.unsigned) call("word2Int#", listOf(call("word${shape.width * 8}ToWord#",
                    listOf(value), scalar("long", "WordRep"))), integer)
                else call("int${shape.width * 8}ToInt#", listOf(value), integer)
                result = call("+#", listOf(result, call("*#", listOf(widened, literal(2L * i + 1)), integer)), integer)
            }
            return result
        }
        fun consume(value: List<Any?>): MutableList<Any?> = mutableListOf("case",
            call("unpack${shape.name}#", listOf(value), shape.unpacked), "lanes",
            mutableListOf(mutableListOf<Any?>("data", "Lanes", MutableList(shape.lanes) { "lane$it" }, checksum(),
                mutableMapOf("binders" to MutableList(shape.lanes) { binder("lane$it", shape.lane) }))),
            mutableMapOf("rep" to copy(integer), "binder" to binder("lanes", shape.unpacked)))
        fun readResult(evaluated: Boolean) = shape.proof + mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
            "components" to listOf(state, shape.proof), "evaluated" to evaluated)
        val packed = call("pack${shape.name}#", listOf(mutableListOf<Any?>("app", listOf("con", "Lanes", shape.lanes),
            shape.written.map { literal(it, shape.scalar.lowercase(), shape.lane).also { expression ->
                if (shape.unsigned) expression[2] = it.toULong().toString()
            } }, List(shape.lanes) { false }, false, true,
            mutableMapOf("rep" to copy(shape.unpacked)))), shape.proof)
        val args = listOf(variable("array", array), variable("offset", integer))
        val app = when {
            op.isRead -> call(op.primitive, args + listOf(variable("state", state)), readResult(false))
            op.isWrite -> call(op.primitive, args + listOf(packed, variable("state", state)), state)
            else -> call(op.primitive, args, shape.proof)
        }
        val body = when {
            op.isRead -> mutableListOf<Any?>("case", app, "whole", mutableListOf(mutableListOf<Any?>("data", "StateVector",
                mutableListOf("nextState", "vector"), consume(variable("vector", shape.proof)),
                mutableMapOf("binders" to mutableListOf(binder("nextState", state), binder("vector", shape.proof))))),
                mutableMapOf("rep" to copy(integer), "binder" to binder("whole", readResult(true))))
            op.isWrite -> mutableListOf<Any?>("case", app, "written", mutableListOf(mutableListOf<Any?>(
                "default", null, emptyList<String>(), consume(call("index" +
                    (if (op.scalarOffset) shape.scalar + "OffAddrAs" + shape.name else shape.name + "OffAddr") + "#", args, shape.proof)),
                mutableMapOf("binders" to emptyList<Any>()))), mutableMapOf("rep" to copy(integer), "binder" to binder("written", state)))
            else -> consume(app)
        }
        val module = mutableMapOf<String, Any?>("schema" to 1, "ghc" to "9.14.1", "instrument" to true,
            "constructors" to listOf(mapOf("id" to "StateVector", "kind" to "unboxed-tuple", "arity" to 2),
                mapOf("id" to "Lanes", "kind" to "unboxed-tuple", "arity" to shape.lanes)),
            "bindings" to listOf(mapOf("id" to "root", "name" to "root", "arity" to 3, "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", listOf(binder("array", array), binder("offset", integer),
                    binder("state", state)), body, mapOf("rep" to closure, "resultRep" to integer)))))
        return Fixture(module, app, body)
    }
    private fun withLanguage(inlining: Boolean = false, action: (Language) -> Unit) = Context.newBuilder("thc")
        .allowNativeAccess(true).allowExperimentalOptions(true).option("compiler.Inlining", inlining.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw")
        .build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
        }
    private fun program(language: Language, backend: String, fixture: Fixture): ExecutableProgram =
        if (backend == "ast") Program(language, fixture.module) else BytecodeProgram(language, fixture.module)
    private fun invoke(p: ExecutableProgram, bytes: Any, index: Long, token: Any? = Unit): Any? =
        Calls.target(p.hostEntryTarget(3), arrayOf(p.entryValue("root"), arrayOf(bytes, index, token)))
    private fun shift(byte: Int, width: Int) = 8 * (if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) byte else width - byte - 1)
    private fun expected(bytes: ByteArray, offset: Int, shape: Shape): Long = (0 until shape.lanes).sumOf { lane ->
        val raw = (0 until shape.width).fold(0L) { bits, byte ->
            bits or ((bytes[offset + lane * shape.width + byte].toLong() and 255) shl shift(byte, shape.width))
        }
        val value = if (shape.unsigned || shape.width == 8) raw else raw shl (64 - shape.width * 8) shr (64 - shape.width * 8)
        value * (2L * lane + 1)
    }
    private fun store(bytes: ByteArray, offset: Int, shape: Shape) {
        for (lane in 0 until shape.lanes) for (byte in 0 until shape.width)
            bytes[offset + lane * shape.width + byte] = (shape.written[lane] ushr shift(byte, shape.width)).toByte()
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }
    @Test fun all36OperationsPreserveBytesThroughInteriorAddressesOnBothBackends() {
        assertEquals(36, operations.size)
        for (backend in listOf("ast", "bytecode")) withLanguage { language ->
            for (op in operations) {
                val shape = Shape(op)
                val p = program(language, backend, fixture(op))
                val stride = if (op.scalarOffset) shape.width else 16
                for (size in listOf(16,17,31,32,33,48,49)) for (offset in 0..(size-16)/stride) for (owned in listOf(false,true)) {
                    val bytes = ByteArray(size) { (it * 47 + 129).toByte() }
                    val model = bytes.copyOf()
                    if (op.isWrite) store(model, offset * stride, shape)
                    val storage: Any = if (owned) ManagedByteArray.allocateGuest(size.toLong()).also {
                        it.copyBytesIn(bytes, 0, 0, size.toLong())
                    } else bytes
                    val address = ManagedAddress.fromGuestByteArray(storage).plus(16)
                    assertEquals(expected(model, offset * stride, shape),
                        invoke(p, address, offset.toLong() - 16 / stride), "$backend/$op/$size/$offset/$owned")
                    assertArrayEquals(model, if (storage is ManagedAllocation) storage.copyBytesOut(0, size.toLong()) else bytes)
                    released(language)
                }
            }
        }
    }

    @Test fun completeRangesTokensAndImmutableStorageAreCheckedBeforeStores() {
        for (backend in listOf("ast", "bytecode")) withLanguage { language ->
            for (op in operations) {
                val p = program(language, backend, fixture(op))
                val stride = if (op.scalarOffset) Shape(op).width else 16
                for (size in listOf(0,1,15,16,17,31,48)) {
                    val bytes = ByteArray(size) { it.toByte() }
                    val original = bytes.copyOf()
                    val address = ManagedAddress.fromByteArray(bytes)
                    val invalid = listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE,
                        if (size < 16) 0L else (size-16).toLong()/stride+1)
                    for (index in invalid) {
                        assertThrows(RuntimeFault::class.java) { invoke(p, address, index) }
                        assertArrayEquals(original, bytes); released(language)
                    }
                }
                val owner = ManagedByteArray.allocateGuest(48)
                val address = ManagedAddress.fromAllocation(owner)
                owner.shrink(16)
                assertThrows(RuntimeFault::class.java) { invoke(p, address, 1) }
                for (opaque in listOf(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(123L)))
                    assertThrows(RuntimeFault::class.java) { invoke(p, opaque, 0) }
                if (!op.isIndex) {
                    val bytes = ByteArray(32) { it.toByte() }
                    val original = bytes.copyOf()
                    assertThrows(RuntimeFault::class.java) { invoke(p, ManagedAddress.fromByteArray(bytes), 0, 1L) }
                    assertArrayEquals(original, bytes)
                }
                val bytes = ByteArray(32) { (it * 7).toByte() }
                for (immutable in listOf(ManagedAddress.fromAllocation(ManagedAllocation.immutable(bytes,8)),
                    ManagedAddress.fromNativeImageSource(bytes,0))) {
                    if (op.isWrite) assertThrows(RuntimeFault::class.java) { invoke(p, immutable, 0) }
                    else assertEquals(expected(bytes,0,Shape(op)),invoke(p,immutable,0))
                }
                released(language)
            }
        }
    }

    @Test fun pointerCellReadsAndPartialOverwritesRejectWithoutLosingReferences() = withLanguage { _ ->
        val pointer = ManagedAddress.fromByteArray(ByteArray(8))
        val owner = ManagedAllocation.mutable(40,8)
        val address = ManagedAddress.fromAllocation(owner)
        address.writeAddressElementIndex(2,pointer) // bytes 16..23
        assertThrows(RuntimeFault::class.java) { address.readVectorBytes(8,1) }
        val value = jdk.incubator.vector.ByteVector.broadcast(jdk.incubator.vector.ByteVector.SPECIES_128,0x5a.toByte())
        assertThrows(RuntimeFault::class.java) { address.writeVectorBytes(1,1,value) }
        assertSame(pointer,address.readAddressElementIndex(2))
        assertEquals(0L,address.readWord8(0))
        address.writeVectorBytes(8,1,value) // Complete overlap invalidates the reference.
        assertThrows(RuntimeFault::class.java) { address.readAddressElementIndex(2) }
        assertEquals(List(16) { 0x5aL },(8L until 24L).map(address::readWord8))
    }

    @Test fun nativeStorageSharesBytesAndRejectsFreedAndForeignContextOwners() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64","x86_64"))
        withLanguage { language ->
            val registry = Language.currentState().nativeAllocations
            for (backend in listOf("ast","bytecode")) for (op in operations) {
                val p = program(language,backend,fixture(op))
                val shape = Shape(op)
                val stride = if (op.scalarOffset) shape.width else 16
                val base = registry.malloc(48)
                try {
                    val bytes = ByteArray(48) { (it * 47 + 129).toByte() }
                    bytes.indices.forEach { base.writeWord8(it.toLong(),bytes[it].toLong()) }
                    val model = bytes.copyOf()
                    if (op.isWrite) store(model,0,shape)
                    val alias = base.plus(16)
                    assertEquals(expected(model,0,shape),invoke(p,alias,-16L/stride))
                    assertArrayEquals(model,ByteArray(48) { base.readWord8(it.toLong()).toByte() })
                    assertThrows(RuntimeFault::class.java) { invoke(p,base,Long.MAX_VALUE) }
                    withLanguage { _ ->
                        assertThrows(RuntimeFault::class.java) { alias.readVectorBytes(0,1) }
                    }
                    released(language)
                } finally { registry.free(base) }
                assertThrows(RuntimeFault::class.java) { invoke(p,base,0) }
            }
            assertEquals(0,registry.liveCount())
        }
    }

    @Test fun loweringKeepsCarrierAndVectorTupleChecksButAcceptsLongAliases() {
        for (backend in listOf("ast","bytecode")) withLanguage { language ->
            for (op in operations) {
                fun rejects(change: (Fixture) -> Unit) {
                    val f = fixture(op); change(f)
                    assertThrows(RuntimeFault::class.java,{ program(language,backend,f) },"$backend/$op")
                }
                rejects { f ->
                    (f.app[2] as MutableList<Any?>)[1] =
                        listOf("lit","double","0.0",mapOf("rep" to scalar("double","DoubleRep")))
                }
                rejects { (it.app[3] as MutableList<Boolean>)[0] = true }
                rejects { (it.app[2] as MutableList<Any?>).removeLast() }
                for (alias in listOf("IntRep","WordRep","Int8Rep","Word16Rep","Int64Rep","Word64Rep")) {
                    val f = fixture(op)
                    val offset = (f.app[2] as MutableList<Any?>)[1] as MutableList<Any?>
                    offset[2] = mapOf("rep" to scalar("long",alias))
                    val p = program(language,backend,f)
                    val bytes = ByteArray(32)
                    val model = bytes.copyOf()
                    if (op.isWrite) store(model,0,Shape(op))
                    assertEquals(expected(model,0,Shape(op)),invoke(p,ManagedAddress.fromByteArray(bytes),0))
                }
                if (op.isRead) {
                    rejects { (it.app[6] as MutableMap<String,Any?>)["rep"] = Shape(op).proof }
                    rejects { f -> (f.body[3] as MutableList<Any?>).add(copy((f.body[3] as List<*>).single())) }
                } else if (op.isWrite) rejects { f ->
                    val value = (f.app[2] as List<*>)[2] as MutableList<Any?>
                    (value[6] as MutableMap<String,Any?>)["rep"] = Shape(operations.first { it.family != op.family }).proof
                }
                released(language)
            }
        }
    }
}
