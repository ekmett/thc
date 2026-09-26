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

class UnalignedScalarMemoryTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val types = listOf("Char", "WideChar", "Int", "Word", "Addr", "Float", "Double",
        "StablePtr", "Int16", "Int32", "Int64", "Word16", "Word32", "Word64")
    private val names = types.flatMap { type -> listOf("Array", "OffAddr").flatMap { domain ->
        listOf("index", "read", "write").map { it + "Word8" + domain + "As" + type + "#" }
    } }.toSet()
    private fun context(native: Boolean = false) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .allowNativeAccess(native).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100000")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun width(type: String) = when (type) {
        "Char" -> 1; "Int16", "Word16" -> 2; "Int32", "Word32", "WideChar", "Float" -> 4; else -> 8
    }
    private fun model(type: String, raw: Long): Long {
        if (type == "Addr" || type == "StablePtr") return 1
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        val input = when (type) { "Char" -> raw and 255; "WideChar" -> raw and 0x10ffff; else -> raw }
        when (width(type)) {
            1 -> bytes.put(0, input.toByte())
            2 -> bytes.putShort(0, input.toShort())
            4 -> bytes.putInt(0, input.toInt())
            else -> bytes.putLong(0, input)
        }
        return when (type) {
            "Char" -> bytes.get(0).toLong() and 255
            "Int16" -> bytes.getShort(0).toLong()
            "Word16" -> bytes.getShort(0).toLong() and 65535
            "Int32" -> bytes.getInt(0).toLong()
            "Word32", "WideChar", "Float" -> bytes.getInt(0).toLong() and 0xffffffffL
            else -> bytes.getLong(0)
        }
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
        val directory = File(root, "build/unaligned-scalar-memory")
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, (manifest["primitives"] as List<String>).toSet())
        assertEquals(84, names.size)
        for (key in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[key] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale unaligned scalar memory " + path)
            }
        val rows = File(directory, "oracle.tsv").readLines().map { line ->
            val columns = line.split('\t')
            assertEquals(9, columns.size)
            Row(columns[0], columns[1].toLong(), columns[2].toLong(), columns.drop(3).map(String::toLong))
        }
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.size)
        assertEquals(types.toSet(), rows.map { it.type }.toSet())
        for (row in rows) {
            val replacement = when (row.type) {
                "Float" -> row.raw xor 0x80000000L
                "Double" -> row.raw xor Long.MIN_VALUE
                else -> row.raw.inv()
            }
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
                val type = name.substringAfter("As").removeSuffix("#")
                assertEquals(setOf("main:UnalignedScalarMemoryAudit.unaligned" + type),
                    uses.map { it["owner"] }.toSet(), stage + "/" + name)
            }
            val module = Json.parse(File(directory, stage + "/core/UnalignedScalarMemoryAudit.json").readText()) as Map<String, Any?>
            val expectedLabels = types.associateWith { type ->
                val entry = "unaligned" + type
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
                    val first = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
                    val second = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
                    val stable = Language.currentState().stablePointers
                    val firstStable = stable.make(17L)
                    val secondStable = stable.make(29L)
                    try {
                        for (type in types) {
                            val entry = "unaligned" + type
                            val target = program.entryTarget(entry)
                            val inputs = rows.filter { it.type == type }
                            val entryLabel = stage + "/" + backend + "/" + entry
                            fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            fun call(row: Row, selector: Int): Any? {
                                val arguments: Array<Any?> = when (type) {
                                    "Addr" -> arrayOf(0L, first, second, row.offset, selector.toLong())
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

    @Test fun numericBoundariesSentinelsAliasesAndOwnerRestrictions() {
        for (width in listOf(1, 2, 4, 8)) {
            val operation = when (width) { 1 -> ManagedAddressRead.CHAR; 2 -> ManagedAddressRead.WORD16
                4 -> ManagedAddressRead.WORD32; else -> ManagedAddressRead.WORD64 }
            for (offset in listOf(0L, 1L, 3L, 16L - width))
                for (bits in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789abcdefL)) {
                    val bytes = ByteArray(16) { 0xa5.toByte() }
                    val owner = ManagedAllocation.mutable(16, 8)
                    owner.fill(0, 16, 0xa5)
                    val address = ManagedAddress.fromAllocation(owner)
                    if (width == 1) address.plus(16).writeWord8(offset - 16, bits)
                    else address.plus(16).writeNativeScalar(offset - 16, width, bits, true)
                    val expected = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                    when (width) { 1 -> expected.put(offset.toInt(), bits.toByte())
                        2 -> expected.putShort(offset.toInt(), bits.toShort())
                        4 -> expected.putInt(offset.toInt(), bits.toInt())
                        else -> expected.putLong(offset.toInt(), bits) }
                    assertArrayEquals(bytes, owner.copyBytesOut(0, 16))
                    val mask = when (width) { 1 -> 255L; 2 -> 65535L; 4 -> 0xffffffffL; else -> -1L }
                    assertEquals(bits and mask, operation.read(address.plus(16), offset - 16, true))
                    val arrayValue = when (width) {
                        1 -> ManagedByteArray.readGuest(owner, offset, true)
                        2 -> ManagedByteArray.readInt16ByteOffsetGuest(owner, offset, true)
                        4 -> ManagedByteArray.readInt32ByteOffsetGuest(owner, offset, true)
                        else -> ManagedByteArray.readIntGuest(owner, offset, true)
                    }
                    assertEquals(bits and mask, arrayValue)
                }
            val owner = ManagedAllocation.mutable(16, 8)
            val address = ManagedAddress.fromAllocation(owner)
            for (offset in listOf(-1L, 17L - width, Long.MIN_VALUE, Long.MAX_VALUE)) {
                val before = owner.copyBytesOut(0, 16)
                assertThrows(RuntimeFault::class.java) { operation.read(address, offset, true) }
                assertThrows(RuntimeFault::class.java) {
                    if (width == 1) address.writeWord8(offset, 1)
                    else address.writeNativeScalar(offset, width, 1, true)
                }
                assertArrayEquals(before, owner.copyBytesOut(0, 16))
            }
        }
        val raw = ByteArray(8)
        ManagedByteArray.writeIntGuest(raw, 0, Long.MIN_VALUE, true)
        assertEquals(Long.MIN_VALUE, ManagedByteArray.readIntGuest(raw, 0, true))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeIntGuest(raw, 1, 1, true) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(ByteArray(0), 0, true) }
        val literal = ManagedAddress.fromHex("0001020304050607")
        assertThrows(RuntimeFault::class.java) { literal.writeNativeScalar(1, 4, 1, true) }
        assertThrows(RuntimeFault::class.java) { ManagedAddress.nullAddress().readWord8(0) }
        assertThrows(RuntimeFault::class.java) { ManagedAddress.unownedNumeric(123).writeNativeScalar(0, 4, 1, true) }
    }

    @Test fun pointerCellsRetainReferencesAndRejectPartialOrRawOverwrites() {
        val owner = ManagedAllocation.mutable(24, 8)
        val base = ManagedAddress.fromAllocation(owner)
        val first = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
        first.writeWord8(3, 211)
        PinnedMemory.writeAddressArray(owner, 1, first.plus(3), true)
        assertEquals(211L, base.plus(24).readAddressElementIndex(-23, true).readWord8(0))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(owner, 1, true) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeIntGuest(owner, 2, 0, true) }
        assertThrows(RuntimeFault::class.java) { base.writeAddressElementIndex(2, first, true) }
        assertThrows(RuntimeFault::class.java) { owner.exposeToNative() }
        assertThrows(RuntimeFault::class.java) { owner.rawBytesIfPointerFree() }
        assertEquals(211L, PinnedMemory.readAddressArray(owner, 1, true).readWord8(0))
        val copied = ManagedAllocation.mutable(24, 8)
        copied.copyFrom(owner, 0, 0, 24)
        assertEquals(211L, copied.readAddressByteOffset(1).readWord8(0))
        ManagedByteArray.writeIntGuest(owner, 1, 0, true)
        assertThrows(RuntimeFault::class.java) { PinnedMemory.readAddressArray(owner, 1, true) }
        assertEquals(0L, ManagedByteArray.readIntGuest(owner, 1, true))
        val exposed = ManagedAllocation.mutable(16, 8)
        exposed.rawBytesIfPointerFree()
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(exposed, 1, first, true) }
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(ByteArray(16), 1, first, true) }
        for (offset in listOf(-1L, 17L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(owner, offset, first, true) }
    }

    @Test fun stableOwnersRejectForeignContextsAndReleasedLifetimes() {
        val pointerCell = ManagedAllocation.mutable(16, 8)
        lateinit var stable: ManagedAddress
        context(true).use { first ->
            first.initialize("thc"); first.enter()
            try {
                val state = Language.currentState()
                stable = state.stablePointers.make(37L)
                pointerCell.writeAddressByteOffset(1, stable)
                assertEquals(37L, state.stablePointers.dereference(pointerCell.readAddressByteOffset(1)))
                context(true).use { second ->
                    second.initialize("thc"); second.enter()
                    try {
                        assertThrows(RuntimeFault::class.java) { Language.currentState().stablePointers.dereference(pointerCell.readAddressByteOffset(1)) }
                    } finally { second.leave() }
                }
                state.stablePointers.free(stable)
                assertThrows(RuntimeFault::class.java) { state.stablePointers.dereference(pointerCell.readAddressByteOffset(1)) }
            } finally { first.leave() }
        }
    }

    @Test fun ownedNativeNumericAliasesCheckLifetimesAndContext() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        context(true).use { first ->
            first.initialize("thc"); first.enter()
            try {
                val state = Language.currentState()
                val native = state.nativeAllocations.malloc(16)
                try {
                    for (offset in listOf(0L, 1L, 7L, 8L)) {
                        native.plus(16).writeNativeScalar(offset - 16, 8, Long.MIN_VALUE, true)
                        assertEquals(Long.MIN_VALUE, ManagedAddressRead.INT64.read(native, offset, true))
                        FloatingAddresses.writeFloat(native, offset, Float.fromBits(0x7fc12345), true)
                        assertEquals(0x7fc12345, FloatingAddresses.readFloat(native, offset, true).toRawBits())
                    }
                    assertThrows(RuntimeFault::class.java) { native.writeNativeScalar(9, 8, 7, true) }
                    assertThrows(RuntimeFault::class.java) { native.writeAddressElementIndex(1, native, true) }
                    context(true).use { second ->
                        second.initialize("thc"); second.enter()
                        try {
                            assertThrows(RuntimeFault::class.java) { ManagedAddressRead.INT64.read(native, 1, true) }
                        } finally { second.leave() }
                    }
                } finally { state.nativeAllocations.free(native) }
                assertThrows(RuntimeFault::class.java) { ManagedAddressRead.INT64.read(native, 1, true) }
            } finally { first.leave() }
        }
    }

    @Test fun loweringChecksCarriersArityStateAndTupleOrderWithoutIntegralIdentityChecks() {
        for (stage in listOf("pre", "post")) {
            val module = Json.parse(File(root,
                "build/unaligned-scalar-memory/$stage/core/UnalignedScalarMemoryAudit.json").readText()) as Map<String, Any?>
            val calls = types.flatMap { type ->
                val evidence = ArrayCoreEvidence(module, "unaligned" + type)
                evidence.nodes(evidence.root["expr"])
            }.filter { app ->
                val function = app.getOrNull(1) as? List<*>
                app.firstOrNull() == "app" && function?.firstOrNull() == "prim" && function.getOrNull(1) in names
            }
            assertEquals(names, calls.map { (it[1] as List<*>)[1] }.toSet())
            assertEquals(84, calls.size, "$stage exact original memory applications")
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
        for (type in listOf("Int8", "Word8", "Int8X16", "Word8X16", "Bogus"))
            for (verb in listOf("index", "read", "write")) {
                val array = verb + "Word8ArrayAs" + type + "#"
                val addr = verb + "Word8OffAddrAs" + type + "#"
                assertNull(ByteArrayOp.named(array))
                assertNull(PinnedMemoryOp.named(addr))
                assertNull(FloatingAddressOp.named(addr))
            }
    }
}
