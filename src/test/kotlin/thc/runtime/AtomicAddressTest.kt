// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantReadWriteLock

class AtomicAddressTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val numeric = listOf(AtomicAddressOp.READ, AtomicAddressOp.WRITE, AtomicAddressOp.EXCHANGE,
        AtomicAddressOp.CAS, AtomicAddressOp.CAS8, AtomicAddressOp.CAS16, AtomicAddressOp.CAS32,
        AtomicAddressOp.CAS64, AtomicAddressOp.ADD, AtomicAddressOp.SUB, AtomicAddressOp.AND,
        AtomicAddressOp.NAND, AtomicAddressOp.OR, AtomicAddressOp.XOR)
    private val prefix = 0x0123456789abcdefL
    private val suffix = 0xfedcba9876543210UL.toLong()
    private data class Row(val kind: String, val operation: Int, val initial: Long,
        val operand: Long, val desired: Long, val answers: List<Long>)
    private fun context(native: Boolean = false, inline: Boolean = false) = Context.newBuilder("thc")
        .allowNativeAccess(native).allowExperimentalOptions(true)
        .option("compiler.Inlining", inline.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()

    /** The original Core calls entry -> runRW state worker -> keepAlive action once each. */
    private fun originalGuestEntries(module: Map<String, Any?>, name: String): Long {
        val evidence = ArrayCoreEvidence(module, name)
        assertEquals(1, evidence.bindings.size, "$name closed original binding")
        assertEquals(emptyList<String>(), evidence.globalReferences(evidence.root["expr"]))
        val outer = evidence.root["expr"] as List<Any?>
        assertEquals("lam", outer[0]); assertEquals(5, (outer[1] as List<*>).size)
        val stateCall = outer[2] as List<Any?>
        assertEquals("app", stateCall[0])
        val state = stateCall[1] as List<Any?>
        val void = mapOf("primReps" to emptyList<String>(), "kind" to "void", "evaluated" to true)
        fun stateLambda(lambda: List<Any?>) {
            assertEquals("lam", lambda[0])
            val formal = (lambda[1] as List<Map<String, Any?>>).single()
            assertEquals("State# RealWorld", formal["type"])
            assertEquals(void, formal["rep"]); assertEquals(false, formal["lifted"])
        }
        stateLambda(state)
        val argument = (stateCall[2] as List<List<Any?>>).single()
        assertEquals("void", argument[0]); assertEquals(void, (argument.last() as Map<*, *>)["rep"])
        assertEquals(listOf(listOf(false), false, false), stateCall.drop(3).take(3))
        val allocation = state[2] as List<Any?>
        assertEquals("case", allocation[0])
        val allocate = allocation[1] as List<Any?>
        assertEquals(listOf("prim", "newPinnedByteArray#"), (allocate[1] as List<*>).take(2))
        val allocated = (allocation[3] as List<List<Any?>>).single()
        val resultCase = allocated[3] as List<Any?>
        assertEquals("case", resultCase[0])
        val keepAlive = resultCase[1] as List<Any?>
        assertEquals("app", keepAlive[0])
        assertEquals(listOf("prim", "keepAlive#"), (keepAlive[1] as List<*>).take(2))
        val action = (keepAlive[2] as List<List<Any?>>)[2]
        stateLambda(action)
        val nodes = evidence.nodes(outer)
        val join = nodes.filter { it.firstOrNull() == "let" }
            .flatMap { it[2] as List<Map<String, Any?>> }.single { "joinValueArity" in it }
        assertEquals(2L, (join["joinValueArity"] as Number).toLong())
        val joinLambda = join["expr"] as List<Any?>
        assertEquals("lam", joinLambda[0]); assertEquals(2, (joinLambda[1] as List<*>).size)
        val joinResult = join["joinResultRep"] as Map<String, Any?>
        assertEquals("unboxed-tuple", joinResult["aggregate"])
        val components = joinResult["components"] as List<Map<String, Any?>>
        assertEquals(2, components.size); assertEquals(void, components[0]); assertEquals("long", components[1]["kind"])
        assertEquals(joinResult, (joinLambda.last() as Map<*, *>)["resultRep"])
        // A saturated local join is control flow, not a fourth guest root.
        val lambdas = nodes.filter { it.firstOrNull() == "lam" }
        assertEquals(4, lambdas.size)
        assertTrue(lambdas[0] === outer && lambdas[1] === state && lambdas[2] === action && lambdas[3] === joinLambda)
        return 3L
    }

    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val node = target.rootNode
            val nodes = if (node is BytecodeRoot) listOf(node) + node.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() } else listOf(node)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry)
        return targets
    }

    /** Independent byte-buffer model; never calls the runtime operation enum. */
    private fun model(row: Row): List<Long> {
        if (row.kind == "pointer") return listOf(row.initial,
            if (row.operation == 0 || row.initial == row.operand) row.desired else row.initial)
        val width = when (row.operation) { 4 -> 1; 5 -> 2; 6 -> 4; else -> 8 }
        val memory = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
            .putLong(0, prefix).putLong(8, row.initial).putLong(16, suffix)
        val mask = if (width == 8) -1L else (1L shl (width * 8)) - 1
        val old = when (width) { 1 -> memory.get(8).toLong() and mask
            2 -> memory.getShort(8).toLong() and mask; 4 -> memory.getInt(8).toLong() and mask
            else -> memory.getLong(8) }
        val next = when (row.operation) {
            0 -> old; 1, 2 -> row.operand
            in 3..7 -> if (old == (row.operand and mask)) row.desired and mask else old
            8 -> old + row.operand; 9 -> old - row.operand
            10 -> old and row.operand; 11 -> (old and row.operand).inv()
            12 -> old or row.operand; 13 -> old xor row.operand
            else -> error("Unknown model operation")
        }
        when (width) { 1 -> memory.put(8, next.toByte()); 2 -> memory.putShort(8, next.toShort())
            4 -> memory.putInt(8, next.toInt()); else -> memory.putLong(8, next) }
        return listOf(if (row.operation == 1) 0L else old, memory.getLong(8), prefix, suffix)
    }

    private fun rows(text: String): List<Row> = text.lineSequence().filter { it.isNotEmpty() }.map { line ->
        val f = line.split('\t')
        require(f[0] in setOf("numeric", "pointer"))
        require(f.size == if (f[0] == "numeric") 9 else 7)
        fun number(s: String) = if (f[0] == "numeric") s.toULong().toLong() else s.toLong()
        Row(f[0], f[1].toInt(), number(f[2]), number(f[3]), number(f[4]), f.drop(5).map(::number)).also {
            require(it.answers == model(it)) { "Native/model atomic mismatch: $line" }
        }
    }.toList().also { result ->
        require(result.size == 108)
        require(result.filter { it.kind == "numeric" }.groupingBy { it.operation }.eachCount() ==
            (0..13).associateWith { 7 })
        require(result.filter { it.kind == "pointer" }.groupingBy { it.operation }.eachCount() ==
            (0..1).associateWith { 5 })
    }

    @Test fun nativeOracleMatchesIndependentModelAndBothFirstCompiledEntries() {
        val directory = File(root, "build/atomic-address")
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale atomic-address $kind: $path")
            }
        val text = File(directory, "oracle.tsv").readText()
        val corpus = rows(text)
        assertThrows(IllegalArgumentException::class.java) { rows(text.lineSequence().drop(1).joinToString("\n")) }
        assertThrows(IllegalArgumentException::class.java) { rows(text.replaceFirst("\t81985529216486895\t", "\t1\t")) }
        for (stage in listOf("pre", "post")) {
            val audit = Json.parse(File(directory, "$stage/audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = audit["primitives"] as List<Map<String, Any?>>
            for (operation in AtomicAddressOp.entries) {
                val evidence = primitives.single { it["name"] == operation.primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                val name = if (operation.pointer) "atomicAddressPointer" else "atomicAddressNumeric"
                assertEquals(setOf("main:AtomicAddressAudit.$name", "main:AtomicAddressAudit." + name + "At"), owners)
            }
            val module = Json.parse(File(directory, "$stage/core/AtomicAddressAudit.json").readText()) as Map<String, Any?>
            val expectedEntries = listOf("atomicAddressNumeric", "atomicAddressPointer")
                .associateWith { originalGuestEntries(module, it) }
            for (backend in listOf("ast", "bytecode")) for (inline in listOf(false, true)) context(inline = inline).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, listOf("atomicAddressNumeric","atomicAddressPointer")) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                        else BytecodeProgram(language, linked)
                    val targets = listOf("atomicAddressNumeric", "atomicAddressPointer").associateWith(program::entryTarget)
                    var compiled = false
                    var compiledTargets = emptyMap<String, List<RootCallTarget>>()
                    val handoff = language.handoffState.get()
                    var argumentAllocations = 0L
                    var resultAllocations = 0L
                    fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    fun check(row: Row) {
                        val entry = if (row.kind == "numeric") "atomicAddressNumeric" else "atomicAddressPointer"
                        val target = targets.getValue(entry)
                        for (selector in row.answers.indices) {
                            val before = count()
                            val label = "$stage/$backend/$inline/$entry/$selector"
                            try {
                                val actual = Calls.target(target, arrayOf(0L, row.operation.toLong(), row.initial,
                                    row.operand, row.desired, selector.toLong()))
                                assertEquals(row.answers[selector], actual, "$label/$row")
                                if (compiled) {
                                    assertEquals(expectedEntries.getValue(entry), count() - before,
                                        "Exact original guest entries, including first installed call: $label")
                                    assertEquals(compiledTargets.getValue(entry), activeTargets(target), "$label target identities")
                                    for (active in compiledTargets.getValue(entry))
                                        assertEquals(true, active.javaClass.getMethod("isValidLastTier").invoke(active), label)
                                    assertEquals(argumentAllocations, handoff.arguments.allocations, label)
                                    assertEquals(resultAllocations, handoff.results.allocations, label)
                                }
                            } finally {
                                assertEquals(0, handoff.arguments.depth, label); assertEquals(0, handoff.results.depth, label)
                                assertEquals(0, handoff.arguments.retainedReferences(), label)
                                assertEquals(0, handoff.results.retainedReferences(), label)
                                assertNull(handoff.pending, label)
                            }
                        }
                    }
                    corpus.forEach(::check)
                    compiledTargets = targets.mapValues { (entry, target) -> activeTargets(target).also {
                        assertEquals(expectedEntries.getValue(entry), it.size.toLong(), "$stage/$backend/$entry original guest roots")
                    } }
                    val allTargets = compiledTargets.values.flatten()
                    val beforeSetup = count()
                    assertEquals(0L, beforeSetup)
                    val beforeCalls = allTargets.map { it.javaClass.getMethod("getCallCount").invoke(it) }
                    argumentAllocations = handoff.arguments.allocations
                    resultAllocations = handoff.results.allocations
                    val runtime = Truffle.getRuntime()
                    val targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    for (target in allTargets) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target),
                            "$stage/$backend/$inline/${target.rootNode.name} installation")
                        runtime.javaClass.getMethod("bypassedInstalledCode", targetType).invoke(runtime, target)
                    }
                    assertEquals(beforeSetup, count(), "Compilation must not enter guest code")
                    assertEquals(beforeCalls, allTargets.map { it.javaClass.getMethod("getCallCount").invoke(it) })
                    compiled = true
                    corpus.asReversed().forEach(::check)
                    assertEquals(beforeCalls, allTargets.map { it.javaClass.getMethod("getCallCount").invoke(it) },
                        "No interpreted guest entries after installation")
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                    assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                    assertEquals(0, language.handoffState.get().results.retainedReferences())
                } finally { context.leave() }
            }
        }
    }

    private fun exerciseStorage(base: ManagedAddress) {
        for (op in numeric.indices) for ((x, y, z) in listOf(Triple(-1L,-1L,0L),
            Triple(0x123456789abcdef0L,0x123456789abcdef0L,-1L), Triple(42L,99L,17L))) {
            base.writeNativeScalar(0,8,prefix); base.writeNativeScalar(1,8,x); base.writeNativeScalar(2,8,suffix)
            val row = Row("numeric",op,x,y,z,emptyList())
            val result = numeric[op].numeric(base.plus(8), y, z)
            val expected = model(row)
            if (op != 1) assertEquals(expected[0], result)
            assertEquals(expected[1], ManagedAddressRead.WORD.read(base,1))
            assertEquals(prefix, ManagedAddressRead.WORD.read(base,0))
            assertEquals(suffix, ManagedAddressRead.WORD.read(base,2))
        }
        for (operation in numeric) {
            assertThrows(RuntimeFault::class.java) { operation.numeric(base.plus(24),0,0) }
            if (operation.width > 1) assertThrows(RuntimeFault::class.java) { operation.numeric(base.plus(1),0,0) }
        }
    }

    @Test fun originalAddressConsumersKeepCompiledNativeManagedAndRawPaths() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64","x86_64"))
        for (stage in listOf("pre","post")) for (backend in listOf("ast","bytecode"))
            context(native = true).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val module = Json.parse(File(root,"build/atomic-address/$stage/core/AtomicAddressAudit.json").readText()) as Map<String, Any?>
                    val linked = CoreModules.reachable(module,listOf("atomicAddressNumericAt","atomicAddressPointerAt")) + ("instrument" to true)
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program: ExecutableProgram = if (backend == "ast") Program(language,linked) else BytecodeProgram(language,linked)
                    val targets = listOf("atomicAddressNumericAt","atomicAddressPointerAt").associateWith(program::entryTarget)
                    val registry = ManagedNativeAllocations.current(null)
                    val native = registry.malloc(24)
                    val managed = ManagedAddress.fromAllocation(PinnedMemory.allocate(24,8))
                    val raw = ManagedAddress.fromByteArray(ByteArray(24))
                    var compiled = false
                    fun call(entry: String, vararg arguments: Any): Any? {
                        val target = targets.getValue(entry)
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val answer = Calls.target(target,arrayOf(0L,*arguments))
                        if (compiled) {
                            assertEquals(before+1,(program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                                "$stage/$backend/$entry exact installed entry")
                            assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target))
                        }
                        return answer
                    }
                    fun exercise() {
                        for (base in listOf(managed,raw,native)) for (op in numeric.indices)
                            for ((initial,operand,desired) in listOf(Triple(-1L,-1L,7L),Triple(23L,17L,99L))) {
                                base.writeNativeScalar(0,8,prefix)
                                base.writeNativeScalar(1,8,initial)
                                base.writeNativeScalar(2,8,suffix)
                                val expected = model(Row("numeric",op,initial,operand,desired,emptyList()))
                                assertEquals(expected[0],call("atomicAddressNumericAt",base.plus(8),op.toLong(),operand,desired))
                                assertEquals(expected[1],ManagedAddressRead.WORD.read(base,1))
                                assertEquals(prefix,ManagedAddressRead.WORD.read(base,0))
                                assertEquals(suffix,ManagedAddressRead.WORD.read(base,2))
                            }
                        for (base in listOf(managed,native)) for (op in 0L..1L) for (success in listOf(false,true)) {
                            val initial = base.plus(16)
                            val desired = base.plus(24)
                            val expected = if (success) base.plus(16) else ManagedAddress.nullAddress()
                            if (base === native) AtomicAddressOp.WRITE.numeric(base.plus(8),initial.toNativeBits())
                            else base.writeAddressElementIndex(1,initial)
                            val old = call("atomicAddressPointerAt",base.plus(8),op,expected,desired) as ManagedAddress
                            assertTrue(old.sameLocation(initial))
                            val actual = if (base === native) AtomicAddressOp.READ.numeric(base.plus(8)) else 0L
                            val final = if (op == 0L || success) desired else initial
                            if (base === native) assertEquals(final.toNativeBits(),actual)
                            else assertTrue(base.readAddressElementIndex(1).sameLocation(final))
                        }
                        // Erase the entire pointer cell before the next numeric pass.
                        managed.writeNativeScalar(1,8,0)
                    }
                    try {
                        repeat(2) { exercise() }
                        val runtime = Truffle.getRuntime()
                        val targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                        for (target in targets.values) {
                            target.javaClass.getMethod("compile",Boolean::class.javaPrimitiveType).invoke(target,true)
                            assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target))
                            runtime.javaClass.getMethod("bypassedInstalledCode",targetType).invoke(runtime,target)
                        }
                        compiled = true
                        exercise()
                        assertEquals(0,language.handoffState.get().arguments.depth)
                        assertEquals(0,language.handoffState.get().results.depth)
                        assertEquals(0,language.handoffState.get().arguments.retainedReferences())
                        assertEquals(0,language.handoffState.get().results.retainedReferences())
                    } finally { registry.free(native) }
                } finally { context.leave() }
            }
    }

    @Test fun managedAndRawAliasesHaveExactWidthsSentinelsAndFailureAtomicity() {
        exerciseStorage(ManagedAddress.fromAllocation(PinnedMemory.allocate(24,8)))
        exerciseStorage(ManagedAddress.fromByteArray(ByteArray(24)))
        val allocation = PinnedMemory.allocate(24,8)
        val base = ManagedAddress.fromAllocation(allocation)
        val target = base.plus(24)
        base.writeAddressElementIndex(1,target)
        for (operation in numeric) assertThrows(RuntimeFault::class.java) { operation.numeric(base.plus(8),0,0) }
        assertSame(target, base.readAddressElementIndex(1))
        assertSame(target, AtomicAddressOp.CAS_ADDR.address(base.plus(8), base.plus(24), base.plus(16)))
        assertTrue(base.readAddressElementIndex(1).sameLocation(base.plus(16)))
        assertTrue(AtomicAddressOp.CAS_ADDR.address(base.plus(8), base.plus(24), target).sameLocation(base.plus(16)))
        assertTrue(AtomicAddressOp.EXCHANGE_ADDR.address(base.plus(8), ManagedAddress.nullAddress(), null).sameLocation(base.plus(16)))
        assertSame(ManagedAddress.nullAddress(), base.readAddressElementIndex(1))
        val literal = ManagedAddress.fromHex("0102030405060708")
        AtomicAddressOp.READ.numeric(literal)
        for (operation in numeric.filter { it != AtomicAddressOp.READ })
            assertThrows(RuntimeFault::class.java) { operation.numeric(literal,0,0) }
        for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(1234)))
            for (operation in numeric) assertThrows(RuntimeFault::class.java) { operation.numeric(bad,0,0) }
        allocation.shrink(8)
        assertThrows(RuntimeFault::class.java) { AtomicAddressOp.READ.numeric(base.plus(8)) }
    }

    @Test fun loweredLongCarriersStayValidButShapeAndStateErrorsHaveNoEffects() {
        val address = CoreRepresentation(CoreKind.ADDRESS, primReps = listOf("AddrRep"))
        val integer = CoreRepresentation(CoreKind.LONG, primReps = listOf("Int8Rep"))
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val tuple = CoreRepresentation(CoreKind.UNKNOWN, components = listOf(state,integer))
        AtomicAddressOp.CAS16.validate(listOf(address,integer,integer,state), List(4) { false },tuple)
        for (bad in listOf(tuple.copy(components = listOf(integer,state)),
            tuple.copy(components = listOf(state,address)), integer))
            assertThrows(RuntimeFault::class.java) {
                AtomicAddressOp.CAS16.validate(listOf(address,integer,integer,state),List(4) { false },bad)
            }
        assertThrows(RuntimeFault::class.java) {
            AtomicAddressOp.CAS16.validate(listOf(integer,integer,integer,state),List(4) { false },tuple)
        }
        val descriptor = FrameDescriptor.newBuilder()
        descriptor.addSlot(FrameSlotKind.Long,null,null)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(),descriptor.build())
        val base = ManagedAddress.fromByteArray(ByteArray(8))
        AtomicAddressOp.WRITE.numeric(base,37)
        fun literal(value: Any) = object : Expr() { override fun execute(frame: VirtualFrame): Any = value }
        var stateCalls = 0
        val invalidState = object : Expr() {
            override fun execute(frame: VirtualFrame): Any { stateCalls++; return 0L }
        }
        val expression = AtomicAddressExpression(AtomicAddressOp.ADD,tuple,
            arrayOf(literal(base),literal(5L),invalidState))
        assertThrows(RuntimeFault::class.java) { expression.execute(frame) }
        assertEquals(0,stateCalls)
        assertThrows(RuntimeFault::class.java) { expression.executeTuple(frame,intArrayOf(0),0) }
        assertEquals(1,stateCalls)
        assertEquals(37L,AtomicAddressOp.READ.numeric(base))
    }

    @Test fun pointerCasRecognizesRawAliasesInBothDirections() {
        // This case specifically checks owner/JVM-byte-array alias identity.
        val target = ManagedAllocation.mutable(64,8)
        val owned = ManagedAddress.fromAllocation(target)
        val raw = ManagedAddress.fromByteArray(target.rawBytesIfPointerFree())
        val location = ManagedAddress.fromAllocation(PinnedMemory.allocate(8,8))
        for ((stored,expected) in listOf(owned.plus(16) to raw.plus(16), raw.plus(16) to owned.plus(16))) {
            assertTrue(stored.sameLocation(expected))
            assertTrue(expected.sameLocation(stored))
            location.writeAddressElementIndex(0,stored)
            assertSame(stored,AtomicAddressOp.CAS_ADDR.address(location,expected.plus(8),ManagedAddress.nullAddress()))
            assertSame(stored,location.readAddressElementIndex(0))
            assertSame(stored,AtomicAddressOp.CAS_ADDR.address(location,expected,ManagedAddress.nullAddress()))
            assertSame(ManagedAddress.nullAddress(),location.readAddressElementIndex(0))
        }
    }

    @Test fun pointerValidationDoesNotHoldCellMonitorBehindQueuedNativeFree() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64","x86_64"))
        context(native = true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val registry = ManagedNativeAllocations.current(null)
                for (role in listOf("old","expected","desired")) {
                    val referent = registry.malloc(8)
                    val nativeOwner = referent.nativeAllocation()!!
                    // Observe the existing fair lifetime lock to establish an
                    // actual queued writer/reader, without timing assumptions.
                    val field = nativeOwner.javaClass.getDeclaredField("lifetime")
                    field.isAccessible = true
                    val lifetime = field.get(nativeOwner) as ReentrantReadWriteLock
                    val cell = PinnedMemory.allocate(8,8)
                    val location = ManagedAddress.fromAllocation(cell)
                    val old = if (role == "old") referent else ManagedAddress.nullAddress()
                    val expected = if (role == "expected") referent else ManagedAddress.nullAddress()
                    val desired = if (role == "desired") referent else ManagedAddress.nullAddress()
                    location.writeAddressElementIndex(0,old)
                    val pool = Executors.newFixedThreadPool(3)
                    val freeingThread = AtomicReference<Thread>()
                    val comparingThread = AtomicReference<Thread>()
                    fun awaitQueued(thread: AtomicReference<Thread>) {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (thread.get()?.let(lifetime::hasQueuedThread) != true) {
                            check(System.nanoTime() < deadline) { "$role native lifetime waiter did not queue" }
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                        }
                    }
                    var freeing: Future<*>? = null
                    var comparing: Future<*>? = null
                    val borrow = nativeOwner.borrow()
                    try {
                        freeing = pool.submit {
                            context.enter()
                            try { freeingThread.set(Thread.currentThread()); registry.free(referent) }
                            finally { context.leave() }
                        }
                        awaitQueued(freeingThread)
                        comparing = pool.submit {
                            context.enter()
                            try {
                                comparingThread.set(Thread.currentThread())
                                assertThrows(RuntimeFault::class.java) {
                                    AtomicAddressOp.CAS_ADDR.address(location,expected,desired)
                                }
                            } finally { context.leave() }
                        }
                        awaitQueued(comparingThread)
                        // The old implementation holds cell while blocked on
                        // the fair native lock. A native-to-managed copy would
                        // then deadlock with free. This probe is bounded, and
                        // finally releases the borrow even on regression.
                        val available = pool.submit<Boolean> { synchronized(cell) { true } }
                        assertTrue(available.get(5,TimeUnit.SECONDS),role)
                    } finally {
                        borrow.close()
                        try {
                            freeing?.get(10,TimeUnit.SECONDS)
                            comparing?.get(10,TimeUnit.SECONDS)
                        } finally { pool.shutdownNow() }
                    }
                    assertSame(old,location.readAddressElementIndex(0),"Validation failure must not mutate the cell")
                }
            } finally { context.leave() }
        }
    }

    private fun contend(first: ManagedAddress, second: ManagedAddress, enter: () -> Unit = {}, leave: () -> Unit = {}) {
        enter()
        try { AtomicAddressOp.WRITE.numeric(first,0) } finally { leave() }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..3).map { thread -> pool.submit<List<Long>> {
                enter()
                try {
                    start.await()
                    val alias = if (thread % 2 == 0) first else second
                    List(500) {
                        if (thread % 2 == 0) AtomicAddressOp.ADD.numeric(alias,1)
                        else {
                            var old = AtomicAddressOp.READ.numeric(alias)
                            while (true) {
                                val observed = AtomicAddressOp.CAS.numeric(alias,old,old+1)
                                if (observed == old) break
                                old = observed
                            }
                            old
                        }
                    }
                } finally { leave() }
            } }
            start.countDown()
            assertEquals((0L until 2000L).toList(), jobs.flatMap { it.get(30,TimeUnit.SECONDS) }.sorted())
            enter()
            try { assertEquals(2000L, AtomicAddressOp.READ.numeric(first)) } finally { leave() }
        } finally { pool.shutdownNow() }
    }

    @Test fun aliasesLinearizeAndPublishAcrossThreads() {
        // Native pinned storage has no JVM-array alias; exercise the heap lock pair.
        val owner = ManagedAllocation.mutable(16,8)
        val owned = ManagedAddress.fromAllocation(owner).plus(8)
        val exposed = ManagedAddress.fromByteArray(owner.rawBytesIfPointerFree()).plus(8)
        contend(owned,exposed)
        val raw = ByteArray(16)
        contend(ManagedAddress.fromByteArray(raw).plus(8), ManagedAddress.fromByteArray(raw).plus(8))
        val data = IntArray(1)
        AtomicAddressOp.WRITE.numeric(owned,0)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val reader = pool.submit<Int> {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (AtomicAddressOp.READ.numeric(exposed) == 0L) {
                    check(System.nanoTime() < until) { "Atomic publication timed out" }
                    Thread.onSpinWait()
                }
                data[0]
            }
            data[0] = 123456
            AtomicAddressOp.WRITE.numeric(owned,1)
            assertEquals(123456,reader.get(15,TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }

    @Test fun nativeStorageUsesTrueAtomicsAndRejectsLifetimeAndContextViolations() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64","x86_64"))
        context(native = true).use { context ->
            context.initialize("thc"); context.enter()
            val registry = ManagedNativeAllocations.current(null)
            val base = registry.malloc(24)
            try {
                exerciseStorage(base)
                // Tiny final elements forbid implementing narrow CAS using a wider load.
                for ((width, operation) in listOf(1 to AtomicAddressOp.CAS8, 2 to AtomicAddressOp.CAS16)) {
                    val tail = registry.malloc(width.toLong())
                    try {
                        repeat(width) { tail.writeWord8(it.toLong(),255) }
                        assertEquals(if (width == 1) 255L else 65535L, operation.numeric(tail,-1,7))
                        assertEquals(7L,tail.readWord8(0))
                    } finally { registry.free(tail) }
                }
                AtomicAddressOp.WRITE.numeric(base,base.plus(16).toNativeBits())
                val old = AtomicAddressOp.CAS_ADDR.address(base,base.plus(16),base.plus(24))
                assertTrue(old.sameLocation(base.plus(16)))
                assertTrue(AtomicAddressOp.EXCHANGE_ADDR.address(base,ManagedAddress.nullAddress(),null).sameLocation(base.plus(24)))
                assertSame(ManagedAddress.nullAddress(),AtomicAddressOp.CAS_ADDR.address(base,ManagedAddress.nullAddress(),base.plus(8)))
                assertThrows(RuntimeFault::class.java) { AtomicAddressOp.EXCHANGE_ADDR.address(base,
                    ManagedAddress.fromByteArray(ByteArray(8)),null) }
                val first = base.plus(8)
                val second = base.plus(8)
                context.leave()
                try { contend(first,second,{ context.enter() },{ context.leave() }) }
                finally { context.enter() }
                context(native = true).use { other ->
                    context.leave(); other.initialize("thc"); other.enter()
                    try { assertThrows(RuntimeFault::class.java) { AtomicAddressOp.READ.numeric(base) } }
                    finally { other.leave(); context.enter() }
                }
                registry.free(base)
                assertThrows(RuntimeFault::class.java) { AtomicAddressOp.READ.numeric(base) }
            } finally { context.leave() }
        }
    }
}
