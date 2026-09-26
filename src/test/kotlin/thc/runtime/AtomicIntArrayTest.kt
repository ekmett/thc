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
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicIntArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/atomic-int-arrays")
    private val named = linkedMapOf(
        "fetchAddResult" to AtomicIntArrayOp.ADD, "fetchSubResult" to AtomicIntArrayOp.SUB,
        "fetchAndResult" to AtomicIntArrayOp.AND, "fetchNandResult" to AtomicIntArrayOp.NAND,
        "fetchOrResult" to AtomicIntArrayOp.OR, "fetchXorResult" to AtomicIntArrayOp.XOR,
        "casIntResult" to AtomicIntArrayOp.CAS, "casInt8Result" to AtomicIntArrayOp.CAS8,
        "casInt16Result" to AtomicIntArrayOp.CAS16, "casInt32Result" to AtomicIntArrayOp.CAS32,
        "casInt64Result" to AtomicIntArrayOp.CAS64, "atomicLoadStore" to AtomicIntArrayOp.WRITE)
    private val initials = listOf(Long.MIN_VALUE, -2147483649L, -32769L, -129L, -1L, 0L,
        127L, 128L, 32768L, 2147483648L, Long.MAX_VALUE)
    private val replacements = listOf(Long.MIN_VALUE, -129L, 0L, 128L, Long.MAX_VALUE)
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString())
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "installed")
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target)
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
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    private fun narrow(value: Long, width: Int): Long = when (width) {
        1 -> value.toByte().toLong(); 2 -> value.toShort().toLong(); 4 -> value.toInt().toLong(); else -> value
    }
    private fun model(operation: AtomicIntArrayOp, old: Long, operand: Long, replacement: Long = 0): Long =
        when (operation) {
            AtomicIntArrayOp.READ -> old
            AtomicIntArrayOp.WRITE -> operand
            AtomicIntArrayOp.ADD -> old + operand
            AtomicIntArrayOp.SUB -> old - operand
            AtomicIntArrayOp.AND -> old and operand
            AtomicIntArrayOp.NAND -> (old and operand).inv()
            AtomicIntArrayOp.OR -> old or operand
            AtomicIntArrayOp.XOR -> old xor operand
            else -> if (old == narrow(operand, operation.width)) narrow(replacement, operation.width) else old
        }
    private data class Row(val name: String, val initial: Long, val operand: Long, val replacement: Long, val result: Long)
    private fun expectedRows(): List<Row> = named.flatMap { (name, operation) ->
        initials.flatMap { initial ->
            listOf(initial, initial + 1, initial + 256, 0L, -1L).distinct().sorted().flatMap { operand ->
                replacements.map { replacement ->
                    val old = narrow(initial, operation.width)
                    val second = model(operation, old, operand, replacement)
                    val final = if (operation.operands == 2) model(operation, second, operand, initial)
                        else model(operation, second, replacement)
                    Row(name, initial, operand, replacement, old + 17L * second + 31L * final)
                }
            }
        }
    }
    @Test fun nativeFamilyWithInlining() = native(true)
    @Test fun nativeFamilyAcrossResidualCalls() = native(false)
    @Test fun loweredIntegralCarriersKeepArityStateAndTupleOrderChecks() {
        val owner = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Unlifted)"))
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val integer = CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
        for (operation in AtomicIntArrayOp.entries) {
            val arguments = listOf(owner) + List(operation.operands + 1) { integer } + state
            val tuple = CoreRepresentation(CoreKind.UNKNOWN, primReps = listOf("IntRep"), components = listOf(state, integer))
            val result = if (operation.tuple) tuple else state
            val flags = List(arguments.size) { false }
            for (rep in listOf("IntRep", "WordRep", "Int8Rep", "Word16Rep", "Int32Rep", "Word64Rep")) {
                val relabelled = arguments.map { if (it.kind == CoreKind.LONG) it.copy(primReps = listOf(rep)) else it }
                operation.validate(relabelled, flags, result)
            }
            for (index in arguments.indices) {
                val broken = arguments.toMutableList()
                broken[index] = CoreRepresentation(CoreKind.DOUBLE, primReps = listOf("DoubleRep"))
                assertThrows(RuntimeFault::class.java) { operation.validate(broken, flags, result) }
            }
            assertThrows(RuntimeFault::class.java) { operation.validate(arguments.dropLast(1), flags, result) }
            assertThrows(RuntimeFault::class.java) { operation.validate(arguments, List(arguments.size) { true }, result) }
            for (bad in listOf(integer, tuple.copy(components = listOf(integer, state)),
                tuple.copy(components = listOf(integer)), tuple.copy(components = emptyList())))
                assertThrows(RuntimeFault::class.java) { operation.validate(arguments, flags, bad) }
        }
    }
    private fun native(inlining: Boolean) {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(named.keys.toList(), manifest["entries"])
        assertEquals(setOf("compiler/test-fixtures/AtomicIntArrayAudit.hs", "test/haskell-fixtures/AtomicIntArrayFixtures.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
            "scripts/core-capabilities.json", "scripts/audit-core.py", "compiler/build.sh", "compiler/export.sh",
            "compiler/toolchain.sh", "compiler/plugin.py", "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { "compiler/THC/" + it.name } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { "scripts/" + it.name },
            (manifest["inputHashes"] as Map<*, *>).keys)
        val artifactNames = mutableSetOf("NativeAtomicIntArrays.hs", "requests.tsv", "oracle.tsv")
        val commands = mutableListOf("native-build", "native-oracle")
        for (stage in listOf("pre", "post")) {
            artifactNames.add("$stage/core/AtomicIntArrayAudit.json")
            artifactNames.add("$stage/core/THC.InterfaceClosure.json")
            commands.add("$stage-export")
            for (name in named.keys) {
                artifactNames.add("$stage/$name.audit.json")
                commands.add("$stage-$name-audit")
            }
        }
        for (command in commands) for (suffix in listOf("stdout", "stderr", "command.json"))
            artifactNames.add("commands/$command.$suffix")
        assertEquals(artifactNames.map { "build/atomic-int-arrays/$it" }.toSet(),
            (manifest["artifactHashes"] as Map<*, *>).keys, "Closed native/Core provenance inventory")
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale atomic-array $kind: $path")
            }
        val rows = File(directory, "oracle.tsv").readLines().map { line ->
            val fields = line.split(' ')
            assertEquals(5, fields.size)
            Row(fields[0], fields[1].toLong(), fields[2].toLong(), fields[3].toLong(), fields[4].toLong())
        }
        assertEquals(expectedRows(), rows, "Native oracle must independently match signed old-value/wraparound model")
        assertEquals(rows.size.toLong(), manifest["nativeRows"])
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val merged = CoreModules.merge(paths.map { json(File(root, it)) })
            for ((name, operation) in named) {
                val audit = json(File(directory, "$stage/$name.audit.json"))
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any>(), audit["issues"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
                val primops = (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet()
                assertTrue(operation.primitive in primops)
                if (name == "atomicLoadStore") assertTrue("atomicReadIntArray#" in primops)
                val cases = rows.filter { it.name == name }
                for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(merged, name) + ("instrument" to true)
                        val program = program(language, linked, backend)
                        val entry = program.entryTarget(name)
                        val label = "$stage/$backend/$name/inlining=$inlining"
                        fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        fun call(row: Row) {
                            assertEquals(row.result, Calls.target(entry, arrayOf(0L, row.initial, row.operand, row.replacement)), "$label/$row")
                            released(language)
                        }
                        cases.forEach(::call)
                        val targets = activeTargets(entry)
                        assertEquals(2, targets.size, "$label public entry and runRW state worker")
                        targets.forEach(::compile)
                        val allocations = language.handoffState.get().results.allocations
                        for (row in cases.asReversed()) {
                            val before = count()
                            call(row)
                            assertEquals(2L, count() - before, "$label exact entries, including the first call after installation")
                            assertEquals(targets, activeTargets(entry), "$label target identities")
                            targets.forEach { valid(it, label) }
                        }
                        assertEquals(allocations, language.handoffState.get().results.allocations, "$label result packet reuse")
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), label)
                        println("AtomicIntArray PASS $label rows=${cases.size}")
                    } finally { context.leave() }
                }
            }
        }
    }

    private fun image(width: Int, value: Long): ByteArray = ByteArray(32) { 53 }.also { store(it, width, value) }
    private fun store(bytes: ByteArray, width: Int, value: Long) {
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        when (width) {
            1 -> bytes[width] = value.toByte(); 2 -> view.putShort(width, value.toShort())
            4 -> view.putInt(width, value.toInt()); else -> view.putLong(width, value)
        }
    }
    private fun owner(bytes: ByteArray) = ManagedByteArray.allocateGuest(bytes.size.toLong()).also {
        it.copyBytesIn(bytes, 0, 0, bytes.size.toLong())
    }
    @Test fun everyWidthChecksOwnedMutableContainedPointerFreeStorageBeforeEffects() {
        for (operation in AtomicIntArrayOp.entries) {
            val bytes = image(operation.width, -1)
            val owner = owner(bytes)
            for (index in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L shl 32, 32L / operation.width)) {
                assertThrows(RuntimeFault::class.java) { operation.execute(owner, index, -1, 7) }
                assertArrayEquals(bytes, owner.copyBytesOut(0, 32))
            }
            val short = ManagedByteArray.allocateGuest((2 * operation.width - 1).toLong())
            assertThrows(RuntimeFault::class.java) { operation.execute(short, 1, 0, 1) }
            val empty = ManagedByteArray.allocateGuest(0)
            assertThrows(RuntimeFault::class.java) { operation.execute(empty, 0, 0, 1) }
            for (bad in listOf(null, Any(), bytes, ManagedAllocation.immutable(bytes, 8)))
                assertThrows(RuntimeFault::class.java) { operation.execute(bad, 1, -1, 1) }
            val pointerOwner = ManagedByteArray.allocateGuest(16)
            val pointer = ManagedAddress.fromAllocation(pointerOwner)
            pointerOwner.writeAddressByteOffset(0, pointer)
            for (index in listOf(0L, (8L / operation.width) - 1)) {
                assertThrows(RuntimeFault::class.java) { operation.execute(pointerOwner, index, 0, 1) }
                assertSame(pointer, pointerOwner.readAddressByteOffset(0))
            }
            operation.execute(pointerOwner, 8L / operation.width, 0, 1)
            assertSame(pointer, pointerOwner.readAddressByteOffset(0), "Disjoint atomic access preserves pointer cell")
            owner.shrink(operation.width.toLong())
            assertThrows(RuntimeFault::class.java) { operation.execute(owner, 1, -1, 1) }
            assertArrayEquals(bytes.copyOf(operation.width), owner.copyBytesOut(0, operation.width.toLong()))
        }
    }

    private fun synthetic(operation: AtomicIntArrayOp): Map<String, Any?> {
        // Use the actual GHC application proofs, not the whole capability document:
        // unrelated unsigned bounds in that document need not fit JSON's Long carrier.
        val name = if (operation == AtomicIntArrayOp.READ) "atomicLoadStore"
            else named.entries.single { it.value == operation }.key
        val evidence = ArrayCoreEvidence(json(File(directory, "pre/core/AtomicIntArrayAudit.json")), name)
        val calls = evidence.nodes(evidence.root["expr"]).filter {
            val function = it.getOrNull(1) as? List<*>
            it.firstOrNull() == "app" && function?.firstOrNull() == "prim" && function.getOrNull(1) == operation.primitive
        }
        assertEquals(if (operation in listOf(AtomicIntArrayOp.READ, AtomicIntArrayOp.WRITE)) 3 else 2, calls.size)
        fun proof(expression: List<*>) = (expression.last() as Map<String, Any?>).getValue("rep") as Map<String, Any?>
        val signatures = calls.map { call ->
            val arguments = call[2] as List<List<Any?>>
            assertEquals(List(arguments.size) { false }, call[3])
            arguments.map(::proof) to proof(call)
        }.distinct()
        assertEquals(1, signatures.size, "Original ${operation.primitive} application proofs must agree")
        val (proofs, result) = signatures.single()
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val parameters = proofs.mapIndexed { i, proof -> mapOf("id" to "p$i", "lifted" to false, "rep" to proof) }
        val app = listOf("app", listOf("prim", operation.primitive), parameters.map {
            listOf("var", it["id"], mapOf("rep" to it["rep"])) }, List(parameters.size) { false }, false, false, mapOf("rep" to result))
        val constructors: List<Map<String, Any?>>
        val body: List<Any?>
        if (operation.tuple) {
            val components = result["components"] as List<Map<String, Any?>>
            val fields = components.mapIndexed { i, proof -> mapOf("id" to "f$i", "lifted" to false, "rep" to proof) }
            constructors = listOf(mapOf("id" to "Tuple2", "kind" to "unboxed-tuple", "arity" to 2,
                "fieldReps" to components.map { it["primReps"] }, "fieldLifted" to listOf(false, false), "strictFields" to listOf(false, false)))
            body = listOf("case", app, "tuple", listOf(listOf("data", "Tuple2", listOf("f0", "f1"),
                listOf("var", "f1", mapOf("rep" to components[1])), mapOf("binders" to fields))),
                mapOf("rep" to components[1], "binder" to mapOf("id" to "tuple", "lifted" to false, "rep" to result)))
        } else {
            constructors = emptyList()
            body = listOf("case", app, "state", listOf(listOf("default", null, emptyList<String>(),
                listOf("lit", "int", "19", mapOf("rep" to long)))),
                mapOf("rep" to long, "binder" to mapOf("id" to "state", "lifted" to false, "rep" to result)))
        }
        return mapOf("instrument" to true, "constructors" to constructors, "bindings" to listOf(mapOf(
            "id" to "atomic", "name" to "atomic", "arity" to parameters.size, "lifted" to true, "rep" to closure,
            "expr" to listOf("lam", parameters, body, mapOf("rep" to closure,
                "resultRep" to if (operation.tuple) (result["components"] as List<*>)[1] else long)))))
    }
    @Test fun typedBackendsReturnOldSignedValuesAndRejectStateBeforeMutation() {
        for (operation in AtomicIntArrayOp.entries) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = program(language, synthetic(operation), backend)
                val entry = program.entryTarget("atomic")
                fun call(owner: Any?, index: Long, operand: Long, replacement: Long, state: Any? = Unit): Any? {
                    val args = mutableListOf<Any?>(0L, owner, index)
                    if (operation.operands > 0) args.add(operand)
                    if (operation.operands == 2) args.add(replacement)
                    args.add(state)
                    return Calls.target(entry, args.toTypedArray())
                }
                fun positives(compiled: Boolean) {
                    for (initial in initials) for (operand in listOf(initial, initial + 256, initial.inv())) {
                        val expected = image(operation.width, initial)
                        val owner = owner(expected)
                        val old = narrow(initial, operation.width)
                        store(expected, operation.width, model(operation, old, operand, 128))
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(if (operation.tuple) old else 19L, call(owner, 1, operand, 128))
                        assertArrayEquals(expected, owner.copyBytesOut(0, 32), "$backend/$operation")
                        if (compiled) {
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                            valid(entry, "$backend/$operation")
                        }
                        released(language)
                    }
                }
                positives(false); compile(entry); positives(true)
                val bytes = image(operation.width, -1)
                val owner = owner(bytes)
                val failure = assertThrows(RuntimeFault::class.java) { call(owner, Long.MAX_VALUE, 0, 1, state = 17L) }
                assertTrue(failure.message.orEmpty().contains("zero-width scalar carrier"))
                for (index in listOf(-1L, Long.MAX_VALUE, 32L / operation.width))
                    assertThrows(RuntimeFault::class.java) { call(owner, index, -1, 1) }
                assertThrows(RuntimeFault::class.java) { call(bytes, 1, -1, 1) }
                assertArrayEquals(bytes, owner.copyBytesOut(0, 32))
                released(language)
            } finally { context.leave() }
        }
    }

    private fun parallel(threads: Int = 4, action: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { thread -> pool.submit {
                assertTrue(start.await(10, TimeUnit.SECONDS)); action(thread)
            } }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    @Test fun concurrentFetchHistoriesAreLinearizable() {
        for (operation in listOf(AtomicIntArrayOp.ADD, AtomicIntArrayOp.SUB, AtomicIntArrayOp.AND,
            AtomicIntArrayOp.NAND, AtomicIntArrayOp.OR, AtomicIntArrayOp.XOR)) {
            val initial = if (operation == AtomicIntArrayOp.AND) -1L else 0L
            val owner = owner(image(8, initial))
            val rows = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
            val perThread = if (operation in listOf(AtomicIntArrayOp.AND, AtomicIntArrayOp.OR)) 8 else 250
            parallel { thread -> repeat(perThread) { i ->
                val operand = when (operation) {
                    AtomicIntArrayOp.AND -> (1L shl (thread * perThread + i)).inv()
                    AtomicIntArrayOp.OR -> 1L shl (thread * perThread + i)
                    AtomicIntArrayOp.XOR, AtomicIntArrayOp.NAND -> -1L
                    else -> 1L
                }
                rows.add(operand to operation.execute(owner, 1, operand, 0))
            } }
            var current = initial
            val remaining = rows.toMutableList()
            while (remaining.isNotEmpty()) {
                val index = remaining.indexOfFirst { it.second == current }
                assertTrue(index >= 0, "$operation cannot linearize old value $current")
                current = model(operation, current, remaining.removeAt(index).first)
            }
            assertEquals(current, AtomicIntArrayOp.READ.execute(owner, 1, 0, 0))
            assertEquals(53L, owner.readByte(7)); assertEquals(53L, owner.readByte(16))
        }
    }
    @Test fun everyCasWidthHasOneWinnerAndLinearizableRetryLoops() {
        for (operation in AtomicIntArrayOp.entries.filter { it.operands == 2 }) {
            val owner = owner(image(operation.width, 0))
            val old = Collections.synchronizedList(mutableListOf<Long>())
            parallel { old.add(operation.execute(owner, 1, 0, 1)) }
            assertEquals(listOf(0L, 1L, 1L, 1L), old.sorted(), "$operation single winner")
            operation.execute(owner, 1, 1, 0)
            parallel { repeat(100) {
                var expected = 0L
                while (true) {
                    val observed = operation.execute(owner, 1, expected, expected + 1)
                    if (observed == expected) break
                    expected = observed
                }
            } }
            assertEquals(narrow(400, operation.width), operation.execute(owner, 1, 0, 0), "$operation retry increments")
        }
    }
    @Test fun overlappingCasWidthsAndExposedAliasesUseOneAtomicOrdering() {
        val owner = owner(image(8, 0))
        val lowByteIndex = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) 8L else 15L
        parallel { thread -> repeat(250) {
            if (thread < 2) AtomicIntArrayOp.ADD.execute(owner, 1, 256, 0)
            else {
                var expected = 0L
                while (true) {
                    val observed = AtomicIntArrayOp.CAS8.execute(owner, lowByteIndex, expected, expected + 1)
                    if (observed == expected) break
                    expected = observed
                }
            }
        } }
        assertEquals(500L * 256 + (500L and 255), AtomicIntArrayOp.READ.execute(owner, 1, 0, 0))

        AtomicIntArrayOp.WRITE.execute(owner, 1, 0, 0)
        val raw = ManagedAddress.fromByteArray(owner.rawBytesIfPointerFree()).plus(8)
        val ownedAddress = ManagedAddress.fromAllocation(owner).plus(8)
        val observations = Collections.synchronizedList(mutableListOf<Long>())
        parallel { thread -> repeat(250) {
            val old = when (thread) {
                0 -> AtomicAddressOp.ADD.numeric(raw, 1)
                1 -> {
                    var expected = AtomicAddressOp.READ.numeric(ownedAddress)
                    while (true) {
                        val observed = AtomicAddressOp.CAS.numeric(ownedAddress, expected, expected + 1)
                        if (observed == expected) break
                        expected = observed
                    }
                    expected
                }
                else -> AtomicIntArrayOp.ADD.execute(owner, 1, 1, 0)
            }
            observations.add(old)
        } }
        assertEquals((0L until 1000).toList(), observations.sorted())
        assertEquals(1000L, AtomicIntArrayOp.READ.execute(owner, 1, 0, 0))
    }
    @Test fun atomicReadAndWritePublishPayloadBetweenAllocations() {
        val flag = ManagedByteArray.allocateGuest(8)
        val payload = LongArray(1)
        parallel(2) { thread ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            fun waitFor(value: Long) {
                while (AtomicIntArrayOp.READ.execute(flag, 0, 0, 0) != value) {
                    check(System.nanoTime() < deadline) { "Atomic publication timed out" }
                    Thread.onSpinWait()
                }
            }
            if (thread == 0) repeat(1000) { i ->
                waitFor(0); payload[0] = i.toLong(); AtomicIntArrayOp.WRITE.execute(flag, 0, 1, 0)
            } else repeat(1000) { i ->
                waitFor(1); assertEquals(i.toLong(), payload[0]); AtomicIntArrayOp.WRITE.execute(flag, 0, 0, 0)
            }
        }
    }
}
