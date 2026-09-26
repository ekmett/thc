// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class AlignedScalarMemoryTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val types = listOf("WideChar", "StablePtr")
    private val names = types.flatMap { type -> listOf("Array", "OffAddr").flatMap { domain ->
        listOf("index", "read", "write").map { it + type + domain + "#" }
    } }.toSet()
    private fun context(native: Boolean = false) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .allowNativeAccess(native).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100000")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun model(type: String, raw: Long): Long {
        if (type == "StablePtr") return 1
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        bytes.putInt(0, (raw and 0x10ffffL).toInt())
        return bytes.getInt(0).toLong() and 0xffffffffL
    }
    private data class Row(val type: String, val raw: Long, val offset: Long, val expected: List<Long>)
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val child = call.currentCallTarget as? RootCallTarget ?: continue
                if (child.rootNode is GuestRoot) visit(child)
            }
            targets.add(target)
        }
        visit(entry)
        return targets
    }
    private fun valid(target: RootCallTarget, label: String) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget, label: String) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, label + " installation")
        // Match EntryValue.compile and the Int16 retired-boundary regression:
        // restore the shared call boundary without executing any guest entry.
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode",
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
        valid(target, label + " restored boundary")
    }
    private fun released(language: Language, label: String) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth, label + " argument depth")
        assertEquals(0, state.arguments.retainedReferences(), label + " retained arguments")
        assertEquals(0, state.results.depth, label + " result depth")
        assertEquals(0, state.results.retainedReferences(), label + " retained results")
        assertNull(state.pending, label + " pending argument loan")
    }

    @Test fun allPinnedScalarNamesMatchNativeAndIndependentModelOnFirstInstalledEntry() {
        val directory = File(root, "build/aligned-scalar-memory")
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, (manifest["primitives"] as List<String>).toSet())
        assertEquals(12, names.size)
        for (key in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[key] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale aligned scalar memory " + path)
            }
        val rows = File(directory, "oracle.tsv").readLines().map { line ->
            val columns = line.split('\t')
            assertEquals(9, columns.size)
            Row(columns[0], columns[1].toLong(), columns[2].toLong(), columns.drop(3).map(String::toLong))
        }
        assertEquals(128L, manifest["nativeRows"])
        assertEquals(128, rows.size)
        assertEquals(types.toSet(), rows.map { it.type }.toSet())
        for (row in rows) {
            val replacement = row.raw.inv()
            assertEquals(listOf(model(row.type, row.raw), model(row.type, row.raw),
                model(row.type, replacement), model(row.type, replacement), 165L, 165L), row.expected,
                "independent native-endian model: " + row)
        }
        for (stage in listOf("pre", "post")) {
            val audit = Json.parse(File(directory, stage + "/audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = audit["primitives"] as List<Map<String, Any?>>
            for (name in names) {
                val uses = primitives.single { it["name"] == name }["uses"] as List<Map<String, Any?>>
                val type = if (name.contains("StablePtr")) "StablePtr" else "WideChar"
                assertEquals(setOf("main:AlignedScalarMemoryAudit.aligned" + type),
                    uses.map { it["owner"] }.toSet(), stage + "/" + name)
            }
            val module = Json.parse(File(directory, stage + "/core/AlignedScalarMemoryAudit.json").readText()) as Map<String, Any?>
            val expectedLabels = types.associateWith { type ->
                val entry = "aligned" + type
                val evidence = ArrayCoreEvidence(module, entry)
                assertEquals(1, evidence.bindings.size, stage + "/" + entry + " original Core closure")
                assertTrue(evidence.globalReferences(evidence.root["expr"]).isEmpty())
                val outer = evidence.root["expr"] as List<*>
                assertEquals("lam", outer[0])
                val stateCall = outer[2] as List<*>
                assertEquals("app", stateCall[0], entry + " immediate runRW state call")
                val state = stateCall[1] as List<*>
                assertEquals("lam", state[0])
                val stateFormal = (state[1] as List<Map<String, Any?>>).single()
                assertEquals("State# RealWorld", stateFormal["type"])
                assertEquals("void", (stateFormal["rep"] as Map<*, *>)["kind"])
                val stateArgument = (stateCall[2] as List<List<*>>).single()
                assertEquals("void", stateArgument[0])
                val lambdas = evidence.guestLambdas(outer)
                // This fixed proof comes from original Core, never from the
                // runtime's discovered targets or its compiled-entry counter.
                assertEquals(2, lambdas.size, entry + " public and runRW state roots")
                assertSame(outer, lambdas[0])
                assertSame(state, lambdas[1])
                lambdas.map { expression ->
                    val formals = expression[1] as List<Map<String, Any?>>
                    "lambda ${formals.joinToString { it["name"].toString() }}"
                }.toSet().also { assertEquals(2, it.size, entry + " distinct original Core root labels") }
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source = module + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
                    val stable = Language.currentState().stablePointers
                    val firstStable = stable.make(17L)
                    val secondStable = stable.make(29L)
                    try {
                        for (type in types) {
                            val entry = "aligned" + type
                            val target = program.entryTarget(entry)
                            val inputs = rows.filter { it.type == type }
                            val entryLabel = stage + "/" + backend + "/" + entry
                            fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            fun call(row: Row, selector: Int): Any? {
                                val arguments: Array<Any?> = when (type) {
                                    "StablePtr" -> arrayOf(0L, firstStable, secondStable, row.offset, selector.toLong())
                                    else -> arrayOf(0L, row.raw, row.offset, selector.toLong())
                                }
                                return try { Calls.target(target, arguments) }
                                finally { released(language, entryLabel + "/" + row + "/" + selector) }
                            }
                            for (row in inputs) for (selector in 0..5)
                                assertEquals(row.expected[selector], call(row, selector), stage + "/" + backend + "/" + row)
                            val targets = activeTargets(target)
                            assertEquals(2, targets.size, entryLabel + " active public and state roots")
                            assertEquals(expectedLabels.getValue(type), targets.map { it.rootNode.name }.toSet(),
                                entryLabel + " original Core root labels")
                            val callCount = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                                .getMethod("getCallCount")
                            fun interpretedCalls() = targets.map { callCount.invoke(it) as Int }
                            val entriesBeforeSetup = count()
                            val callsBeforeSetup = interpretedCalls()
                            val handoff = language.handoffState.get()
                            val argumentAllocations = handoff.arguments.allocations
                            val resultAllocations = handoff.results.allocations
                            targets.forEach { compile(it, entryLabel) }
                            assertEquals(entriesBeforeSetup, count(), entryLabel + " setup executes no compiled guest code")
                            assertEquals(callsBeforeSetup, interpretedCalls(), entryLabel + " setup executes no interpreted guest code")
                            released(language, entryLabel + " installation")
                            // No invocation is allowed between installation and the counted first call.
                            for (row in inputs.asReversed()) for (selector in 5 downTo 0) {
                                val label = stage + "/" + backend + "/" + row + "/" + selector
                                val before = count()
                                assertEquals(row.expected[selector], call(row, selector), label)
                                assertEquals(2L, count() - before, label + " exact public and state compiled entries")
                                assertEquals(callsBeforeSetup, interpretedCalls(), label + " no interpreted guest entries")
                                assertEquals(argumentAllocations, handoff.arguments.allocations, label + " pooled arguments reused")
                                assertEquals(resultAllocations, handoff.results.allocations, label + " pooled results reused")
                                assertSame(target, program.entryTarget(entry), label)
                                val after = activeTargets(target)
                                assertEquals(2, after.size, label)
                                assertTrue(targets.zip(after).all { (a, b) -> a === b }, label)
                                targets.forEach { valid(it, label) }
                            }
                            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        }
                    } finally { stable.free(firstStable); stable.free(secondStable) }
                } finally { context.leave() }
            }
        }
    }

    @Test fun wideCharArrayIndicesUseFourBytesAndKeepUnsignedPayloads() {
        for (allocationOwned in listOf(false, true)) for (index in listOf(0L, 1L, 3L, 7L))
            for (value in listOf(0L, 255L, 256L, 65535L, 65536L, 1114111L, 0x80000000L, 0xffffffffL, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                val owner = ManagedAllocation.mutable(32, 8)
                owner.fill(0, 32, 0xa5)
                val raw = ByteArray(32) { 0xa5.toByte() }
                val array: Any = if (allocationOwned) owner else raw
                ManagedByteArray.writeInt32Guest(array, index, value)
                val expected = ByteArray(32) { 0xa5.toByte() }
                ByteBuffer.wrap(expected).order(ByteOrder.nativeOrder()).putInt(index.toInt() * 4, value.toInt())
                assertArrayEquals(expected, if (allocationOwned) owner.copyBytesOut(0, 32) else raw)
                assertEquals(value and 0xffffffffL, ManagedByteArray.readInt32Guest(array, index, true))
                val address = ManagedAddress.fromGuestByteArray(array)
                assertEquals(value and 0xffffffffL, ManagedAddressRead.WIDE_CHAR.read(address.plus(32), index - 8))
                address.plus(32).writeNativeScalar(index - 8, 4, value.inv())
                assertEquals(value.inv() and 0xffffffffL, ManagedByteArray.readInt32Guest(array, index, true))
                for (invalid in listOf(-1L, 8L, Long.MIN_VALUE, Long.MAX_VALUE)) {
                    val before = if (allocationOwned) owner.copyBytesOut(0, 32) else raw.copyOf()
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.readInt32Guest(array, invalid, true) }
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32Guest(array, invalid, 3) }
                    assertArrayEquals(before, if (allocationOwned) owner.copyBytesOut(0, 32) else raw)
                }
            }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readInt32Guest(ByteArray(0), 0, true) }
        val immutable = ManagedAddress.fromHex("00010203")
        assertThrows(RuntimeFault::class.java) { immutable.writeNativeScalar(0, 4, 1) }
    }

    @Test fun stablePointerCellsRetainOpaqueHandlesWithoutExtendingTheirRegistryLifetime() {
        val cells = ManagedAllocation.mutable(24, 8)
        val base = ManagedAddress.fromAllocation(cells)
        lateinit var retained: ManagedAddress
        context().use { first ->
            first.initialize("thc"); first.enter()
            try {
                val registry = Language.currentState().stablePointers
                val referent = Any()
                retained = registry.make(referent)
                val replacement = registry.make(29L)
                try {
                    PinnedMemory.writeAddressArray(cells, 1, retained)
                    assertSame(retained, base.plus(24).readAddressElementIndex(-2))
                    assertSame(referent, registry.dereference(PinnedMemory.readAddressArray(cells, 1)))
                    val copy = ManagedAllocation.mutable(24, 8)
                    copy.copyFrom(cells, 0, 0, 24)
                    assertSame(retained, PinnedMemory.readAddressArray(copy, 1))
                    // Partial byte writes must not tear a retained pointer cell.
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32Guest(cells, 2, 7) }
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(cells, 1) }
                    assertThrows(RuntimeFault::class.java) { cells.rawBytesIfPointerFree() }
                    assertThrows(RuntimeFault::class.java) { cells.exposeToNative() }
                    assertSame(retained, PinnedMemory.readAddressArray(cells, 1))
                    base.plus(24).writeAddressElementIndex(-2, replacement)
                    assertEquals(29L, registry.dereference(PinnedMemory.readAddressArray(cells, 1)))
                    assertSame(referent, registry.dereference(PinnedMemory.readAddressArray(copy, 1)))
                    context().use { foreign ->
                        foreign.initialize("thc"); foreign.enter()
                        try {
                            assertThrows(RuntimeFault::class.java) { Language.currentState().stablePointers.dereference(PinnedMemory.readAddressArray(copy, 1)) }
                            assertThrows(RuntimeFault::class.java) { Language.currentState().stablePointers.equal(retained, retained) }
                        } finally { foreign.leave() }
                    }
                    registry.free(retained)
                    assertSame(retained, PinnedMemory.readAddressArray(copy, 1))
                    assertThrows(RuntimeFault::class.java) { registry.dereference(PinnedMemory.readAddressArray(copy, 1)) }
                    // A complete scalar overwrite removes, rather than fabricates, a reference cell.
                    ManagedByteArray.writeIntGuest(cells, 1, 0)
                    assertThrows(RuntimeFault::class.java) { PinnedMemory.readAddressArray(cells, 1) }
                    assertEquals(0L, ManagedByteArray.readIntGuest(cells, 1))
                } finally { registry.free(replacement) }
            } finally { first.leave() }
        }
        context().use { later ->
            later.initialize("thc"); later.enter()
            try { assertThrows(RuntimeFault::class.java) { Language.currentState().stablePointers.dereference(retained) } }
            finally { later.leave() }
        }
    }

    @Test fun pointerCellBoundariesRejectRawNativeAndOutOfRangeStorage() {
        val cells = ManagedAllocation.mutable(24, 8)
        val address = ManagedAddress.fromAllocation(cells)
        val pointer = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
        for (index in listOf(0L, 1L, 2L)) {
            PinnedMemory.writeAddressArray(cells, index, pointer)
            assertSame(pointer, address.plus(24).readAddressElementIndex(index - 3))
        }
        for (invalid in listOf(-1L, 3L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { PinnedMemory.readAddressArray(cells, invalid) }
            assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(cells, invalid, pointer) }
            assertThrows(RuntimeFault::class.java) { address.readAddressElementIndex(invalid) }
            assertSame(pointer, PinnedMemory.readAddressArray(cells, 1))
        }
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(ByteArray(24), 1, pointer) }
        val exposed = ManagedAllocation.mutable(24, 8)
        exposed.rawBytesIfPointerFree()
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(exposed, 1, pointer) }
        assertThrows(RuntimeFault::class.java) { ManagedAddress.nullAddress().readAddressElementIndex(0) }
        assertThrows(RuntimeFault::class.java) { ManagedAddress.unownedNumeric(123).readAddressElementIndex(0) }
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val state = Language.currentState()
                val native = state.nativeAllocations.malloc(24)
                val handle = state.stablePointers.make(37L)
                try {
                    assertThrows(RuntimeFault::class.java) { native.writeAddressElementIndex(1, handle) }
                    assertThrows(RuntimeFault::class.java) { native.readAddressElementIndex(1) }
                } finally {
                    state.stablePointers.free(handle)
                    state.nativeAllocations.free(native)
                }
                assertThrows(RuntimeFault::class.java) { native.readAddressElementIndex(1) }
            } finally { context.leave() }
        }
    }

    @Test fun loweringChecksCarriersArityStateAndTupleOrderWithoutIntegralIdentityChecks() {
        for (stage in listOf("pre", "post")) {
            val module = Json.parse(File(root,
                "build/aligned-scalar-memory/$stage/core/AlignedScalarMemoryAudit.json").readText()) as Map<String, Any?>
            val calls = types.flatMap { type ->
                val evidence = ArrayCoreEvidence(module, "aligned" + type)
                evidence.nodes(evidence.root["expr"])
            }.filter { app ->
                val function = app.getOrNull(1) as? List<*>
                app.firstOrNull() == "app" && function?.firstOrNull() == "prim" && function.getOrNull(1) in names
            }
            assertEquals(names, calls.map { (it[1] as List<*>)[1] }.toSet())
            assertEquals(12, calls.size, "$stage exact original memory applications")
            for (call in calls) {
                val name = (call[1] as List<*>)[1] as String
                val arguments = (call[2] as List<List<Any?>>).map(CoreRepresentations::expression)
                val result = CoreRepresentations.expression(call)
                fun validate(args: List<CoreRepresentation>, flags: List<*>, out: CoreRepresentation) {
                    when {
                        FloatingAddressOp.named(name) != null -> FloatingAddressOp.named(name)!!.validate(args, flags, out)
                        PinnedMemoryOp.named(name) != null -> PinnedMemoryOp.named(name)!!.validate(args, flags, out)
                        else -> ByteArrayOp.named(name)!!.validate(args, flags, out)
                    }
                }
                val flags = call[3] as List<*>
                assertEquals(List(arguments.size) { false }, flags)
                validate(arguments, flags, result)
                val compatible = arguments.map { if (it.kind == CoreKind.LONG) it.copy(primReps = listOf("Word64Rep")) else it }
                assertDoesNotThrow { validate(compatible, flags, result) }
                assertThrows(RuntimeFault::class.java) { validate(arguments.dropLast(1), flags, result) }
                assertThrows(RuntimeFault::class.java) { validate(arguments, flags.map { true }, result) }
                for (index in arguments.indices) {
                    val wrong = if (arguments[index].kind == CoreKind.LONG)
                        CoreRepresentation(CoreKind.DOUBLE, primReps = listOf("DoubleRep"))
                        else CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
                    assertThrows(RuntimeFault::class.java, {
                        validate(arguments.toMutableList().also { it[index] = wrong }, flags, result)
                    }, "$stage/$name argument $index actual carrier")
                }
                if (result.isTuple) assertThrows(RuntimeFault::class.java) {
                    validate(arguments, flags, result.copy(components = result.components!!.reversed()))
                }
                else {
                    val wrong = if (result.kind == CoreKind.LONG)
                        CoreRepresentation(CoreKind.DOUBLE, primReps = listOf("DoubleRep"))
                        else CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
                    assertThrows(RuntimeFault::class.java) { validate(arguments, flags, wrong) }
                }
            }
        }
    }
}
