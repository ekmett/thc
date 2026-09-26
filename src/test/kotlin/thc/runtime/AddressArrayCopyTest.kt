// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class AddressArrayCopyTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = "build/address-array-copy"
    private val names = listOf("addrToArray", "arrayToAddr", "mutableArrayToAddr")
    private val primitives = AddressArrayCopyOp.entries.map { it.primitive }
    private val seeds = listOf(0L, 1L, 127L, 255L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)
    private val ranges = buildList {
        for (from in 0..4) for (to in 0..4) for (count in 0..minOf(4 - from, 4 - to))
            add(listOf(from.toLong(), to.toLong(), count.toLong()))
        addAll(listOf(listOf(0L, 0L, 8L), listOf(0L, 1L, 7L), listOf(1L, 0L, 7L),
            listOf(3L, 5L, 3L), listOf(5L, 3L, 3L), listOf(8L, 8L, 0L), listOf(8L, 0L, 0L), listOf(0L, 8L, 0L)))
    }
    private data class Row(val name: String, val arguments: List<Long>, val bytes: List<Long>)
    private fun rows(text: String): List<Row> {
        val rows = text.lineSequence().filter(String::isNotEmpty).map { line ->
            val fields = line.split('\t'); require(fields.size == 21)
            Row(fields[0], fields.subList(1, 5).map(String::toLong), fields.drop(5).map(String::toLong))
        }.toList()
        val requested = names.flatMap { name -> seeds.flatMap { seed -> ranges.map { name to (listOf(seed) + it) } } }
        require(rows.map { it.name to it.arguments } == requested) { "Missing, repeated or reordered native copy rows" }
        for (row in rows) {
            val (seed, from, to, count) = row.arguments
            val source = List(8) { (seed + 17L * it) and 255 }
            val destination = MutableList(8) { (seed * 3 + 91 + 29L * it) and 255 }
            for (i in 0 until count.toInt()) destination[to.toInt() + i] = source[from.toInt() + i]
            require(source + destination == row.bytes) { "Native copy/model mismatch: $row" }
        }
        return rows
    }
    private fun evidence() {
        val manifest = Json.parse(File(root, "$directory/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"]); assertEquals(16L, manifest["bytesPerRow"])
        assertEquals((names.size * seeds.size * ranges.size).toLong(), manifest["nativeRows"])
        val sources = setOf("compiler/test-fixtures/AddressArrayCopyAudit.hs", "compiler/test-fixtures/AddressArrayCopyNative.hs",
            "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
            "test/haskell-fixtures/AddressArrayCopyFixtures.hs", "compiler/build.sh", "compiler/export.sh",
            "compiler/toolchain.sh", "compiler/plugin.py", "scripts/audit-core.py", "scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json") +
            File(root, "compiler/THC").listFiles()!!.filter { it.extension == "hs" }.map { it.relativeTo(root).path } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }.map { it.relativeTo(root).path }
        val commands = listOf("native-build", "native-oracle") + listOf("pre", "post").flatMap { stage ->
            listOf("$stage-export") + names.map { "$stage-$it-audit" } }
        val artifacts = setOf("$directory/inputs.tsv", "$directory/oracle.tsv", "$directory/native/oracle") +
            listOf("pre", "post").flatMap { stage -> listOf("$directory/$stage-core/AddressArrayCopyAudit.json") +
                names.map { "$directory/$stage-$it-audit.json" } } +
            commands.flatMap { command -> listOf("stdout", "stderr", "command.json").map { "$directory/commands/$command.$it" } }
        for ((kind, required) in listOf("inputHashes" to sources, "artifactHashes" to artifacts)) {
            val hashes = manifest[kind] as Map<String, String>; assertEquals(required, hashes.keys, kind)
            for ((path, expected) in hashes) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale address/array copy evidence: $path")
            }
        }
        for (stage in listOf("pre", "post")) for ((index, name) in names.withIndex()) {
            val binding = (module(stage)["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
            assertEquals(5L, binding["arity"])
            val expression = binding["expr"] as List<*>
            assertEquals("lam", expression[0]); assertEquals(5, (expression[1] as List<*>).size)
            val calls = applications(expression)
            val stateCall = calls.single { (it[1] as List<*>)[0] != "prim" }
            val lambda = stateCall[1] as List<*>
            assertEquals("lam", lambda[0])
            val state = (lambda[1] as List<Map<String, Any?>>).single()
            assertEquals(false, state["lifted"])
            assertEquals("void", (state["rep"] as Map<*, *>)["kind"])
            assertEquals(1, (stateCall[2] as List<*>).size)
            val report = Json.parse(File(root, "$directory/$stage-$name-audit.json").readText()) as Map<*, *>
            assertEquals(true, report["accepted"]); assertEquals(emptyList<Any>(), report["issues"])
            assertEquals(emptyList<Any>(), report["missingGlobals"])
            val primitive = (report["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitives[index] }
            assertEquals(1, (primitive["uses"] as List<*>).size, "$stage/$name saturated copy")
        }
    }
    private fun context(inlining: Boolean = false, native: Boolean = false) = Context.newBuilder("thc")
        .allowNativeAccess(native).allowExperimentalOptions(true).option("compiler.Inlining", inlining.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun module(stage: String) = Json.parse(File(root, "$directory/$stage-core/AddressArrayCopyAudit.json").readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true); valid(target)
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target)
        valid(target)
    }
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun released(language: Language) {
        val pools = language.handoffState.get()
        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
        assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
    }
    @Test fun nativeCopiesWithResidualCalls() = native(false)
    @Test fun nativeCopiesWithInlining() = native(true)
    private fun native(inlining: Boolean) {
        evidence()
        val rows = rows(File(root, "$directory/oracle.tsv").readText()).groupBy { it.name }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (name in names) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val p = program(language, CoreModules.reachable(module(stage), name) + ("instrument" to true), backend)
                    val function = context.asValue(EntryValue(p, name, 5))
                    val entry = p.entryTarget(name); val host = p.hostEntryTarget(5)
                    fun active() = targets(NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                        .single { it.callTarget === entry }.currentCallTarget as RootCallTarget)
                    fun check(row: Row, installed: Boolean) {
                        for ((field, byte) in row.bytes.withIndex()) {
                            val before = count(p)
                            val label = "$stage/$backend/$name/${row.arguments}/$field/inlining=$inlining"
                            assertEquals(byte, function.execute(*(row.arguments + field.toLong()).toTypedArray()).asLong(), label)
                            if (installed) assertEquals(before + 2L, count(p), "$label exact entry and runRW lambda")
                            released(language)
                        }
                    }
                    rows.getValue(name).forEach { check(it, false) }
                    val targets = active(); assertEquals(2, targets.size, "entry and genuine runRW state lambda")
                    targets.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    assertEquals(targets, active()); (targets + host).forEach(::valid)
                    for (row in rows.getValue(name).asReversed()) {
                        check(row, true)
                        assertEquals(targets, active()); (targets + host).forEach(::valid)
                    }
                    assertEquals(0L, language.handoffState.get().results.allocations, "Copies return bare State")
                    for (counter in listOf("unsupportedTraps", "blackholes")) assertEquals(0L, (p.diagnostics().getValue(counter) as Number).toLong())
                } finally { context.leave() }
            }
    }

    @Test fun missingReorderedAndCorruptNativeObservationsReject() {
        evidence()
        val lines = File(root, "$directory/oracle.tsv").readLines()
        for (bad in listOf(lines.drop(1), lines.reversed(), lines + lines.first(),
            listOf(lines.first().substringBeforeLast('\t') + "\t999") + lines.drop(1)))
            assertThrows(IllegalArgumentException::class.java) { rows(bad.joinToString("\n")) }
    }
    private fun storage(bytes: ByteArray, owned: Boolean): Any = if (owned)
        ManagedAllocation.mutable(bytes.size.toLong(), 8).also { it.copyBytesIn(bytes, 0, 0, bytes.size.toLong()) } else bytes.copyOf()
    private fun bytes(value: Any): ByteArray = if (value is ManagedAllocation) value.copyBytesOut(0, value.size) else (value as ByteArray).copyOf()

    @Test fun everyContainedRangePreservesSourcesSentinelsAndIdentity() {
        for (sourceOwned in listOf(false, true)) for (destinationOwned in listOf(false, true))
            for (from in 0..8) for (to in 0..8) for (count in 0..minOf(8 - from, 8 - to)) for (toArray in listOf(false, true)) {
                val original = ByteArray(8) { (17 * it + 127).toByte() }
                val initial = ByteArray(8) { (29 * it + 91).toByte() }
                val source = storage(original, sourceOwned); val destination = storage(initial, destinationOwned)
                val expected = initial.copyOf().also { System.arraycopy(original, from, it, to, count) }
                if (toArray) ManagedAddress.fromGuestByteArray(source).plus(from.toLong())
                    .copyToByteArray(destination, to.toLong(), count.toLong())
                else ManagedAddress.fromGuestByteArray(destination).plus(to.toLong())
                    .copyFromByteArray(source, from.toLong(), count.toLong())
                assertArrayEquals(original, bytes(source)); assertArrayEquals(expected, bytes(destination))
                assertEquals(8, bytes(source).size); assertEquals(8, bytes(destination).size)
            }
    }

    @Test fun fullWidthBoundsMutabilityAndBackingAliasesRejectBeforeMutation() {
        for (owned in listOf(false, true)) {
            val source = storage(ByteArray(8) { it.toByte() }, owned)
            val target = storage(ByteArray(8) { 71 }, owned)
            val sourceAddress = ManagedAddress.fromGuestByteArray(source)
            val targetAddress = ManagedAddress.fromGuestByteArray(target)
            for (bad in listOf(-1L, 9L, Int.MAX_VALUE.toLong() + 1, Long.MIN_VALUE, Long.MAX_VALUE)) {
                assertThrows(RuntimeFault::class.java) { sourceAddress.copyToByteArray(target, bad, 0) }
                assertThrows(RuntimeFault::class.java) { sourceAddress.copyToByteArray(target, 0, bad) }
                assertThrows(RuntimeFault::class.java) { targetAddress.copyFromByteArray(source, bad, 0) }
                assertThrows(RuntimeFault::class.java) { targetAddress.copyFromByteArray(source, 0, bad) }
                assertArrayEquals(ByteArray(8) { 71 }, bytes(target))
            }
            for ((addressOffset, arrayOffset, count) in listOf(Triple(0L, 4L, 2L), Triple(4L, 0L, 2L), Triple(8L, 8L, 0L))) {
                assertThrows(RuntimeFault::class.java) { sourceAddress.plus(addressOffset).copyToByteArray(source, arrayOffset, count) }
                assertThrows(RuntimeFault::class.java) { sourceAddress.plus(addressOffset).copyFromByteArray(source, arrayOffset, count) }
            }
            assertArrayEquals(ByteArray(8) { it.toByte() }, bytes(source))
            assertThrows(RuntimeFault::class.java) { sourceAddress.plus(8).copyToByteArray(target, 0, 1) }
            assertThrows(RuntimeFault::class.java) { targetAddress.plus(8).copyFromByteArray(source, 0, 1) }
            assertThrows(RuntimeFault::class.java) { sourceAddress.copyToByteArray(Any(), 0, 0) }
            assertThrows(RuntimeFault::class.java) { targetAddress.copyFromByteArray(Any(), 0, 0) }
        }
        val owner = ManagedAllocation.mutable(8, 8)
        val raw = owner.rawBytesIfPointerFree()
        for ((address, array) in listOf(ManagedAddress.fromAllocation(owner) to raw, ManagedAddress.fromByteArray(raw) to owner)) {
            assertThrows(RuntimeFault::class.java) { address.copyToByteArray(array, 8, 0) }
            assertThrows(RuntimeFault::class.java) { address.copyFromByteArray(array, 4, 1) }
        }
        val immutable = ManagedAllocation.immutable(byteArrayOf(1, 2, 3), 8)
        val literal = ManagedAddress.fromHex("0041ff")
        val destination = ByteArray(4)
        literal.copyToByteArray(destination, 0, 4); assertArrayEquals(byteArrayOf(0, 65, -1, 0), destination)
        ManagedAddress.fromByteArray(destination).copyFromByteArray(immutable, 0, 3)
        for (count in listOf(0L, 1L)) {
            assertThrows(RuntimeFault::class.java) { literal.copyFromByteArray(destination, 0, count) }
            assertThrows(RuntimeFault::class.java) { literal.copyToByteArray(immutable, 0, count) }
            assertThrows(RuntimeFault::class.java) { ManagedAddress.fromAllocation(immutable).copyFromByteArray(destination, 0, count) }
            for (address in listOf(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(12345))) {
                assertThrows(RuntimeFault::class.java) { address.copyToByteArray(destination, 0, count) }
                assertThrows(RuntimeFault::class.java) { address.copyFromByteArray(destination, 0, count) }
            }
        }
        owner.shrink(4)
        assertThrows(RuntimeFault::class.java) { ManagedAddress.fromAllocation(owner).copyToByteArray(destination, 0, 5) }
        assertThrows(RuntimeFault::class.java) { ManagedAddress.fromByteArray(destination).copyFromByteArray(owner, 4, 1) }
    }

    @Test fun pointerCellsStayReferencesAndPartialRawCopiesFailBeforeEffects() {
        val source = ManagedAllocation.mutable(32, 8); val target = ManagedAllocation.mutable(32, 8)
        val pointer = ManagedAddress.fromHex("abcd")
        source.writeAddressByteOffset(8, pointer)
        val from = ManagedAddress.fromAllocation(source).plus(8)
        val to = ManagedAddress.fromAllocation(target).plus(16)
        from.copyToByteArray(target, 16, 8); assertSame(pointer, target.readAddressByteOffset(16))
        target.fill(16, 8, 77)
        to.copyFromByteArray(source, 8, 8); assertSame(pointer, target.readAddressByteOffset(16))
        from.plus(1).copyToByteArray(target, 17, 0)
        to.plus(1).copyFromByteArray(source, 9, 0)
        assertSame(pointer, target.readAddressByteOffset(16)); assertSame(pointer, source.readAddressByteOffset(8))
        for (count in listOf(1L, 7L)) {
            assertThrows(RuntimeFault::class.java) { from.copyToByteArray(target, 0, count) }
            assertThrows(RuntimeFault::class.java) { to.copyFromByteArray(ByteArray(8), 0, count) }
            assertSame(pointer, target.readAddressByteOffset(16)); assertSame(pointer, source.readAddressByteOffset(8))
        }
        val raw = ByteArray(8) { 99 }
        assertThrows(RuntimeFault::class.java) { from.copyToByteArray(raw, 0, 8) }
        assertArrayEquals(ByteArray(8) { 99 }, raw)
        val exposed = ManagedAllocation.mutable(8, 8); exposed.rawBytesIfPointerFree()
        assertThrows(RuntimeFault::class.java) { from.copyToByteArray(exposed, 0, 8) }
        assertArrayEquals(ByteArray(8), bytes(exposed))
        to.copyFromByteArray(raw, 0, 8)
        assertThrows(RuntimeFault::class.java) { target.readAddressByteOffset(16) }
        assertEquals(99L, target.readByte(16))
    }

    @Test fun opposingManagedCopiesUseExistingOrderedOwnerLocks() {
        val first = ManagedAllocation.mutable(16, 8); val second = ManagedAllocation.mutable(16, 8)
        val pointer = ManagedAddress.fromHex("41")
        first.writeAddressByteOffset(0, pointer); second.writeAddressByteOffset(0, pointer)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit { repeat(1000) { ManagedAddress.fromAllocation(first).copyToByteArray(second, 0, 16) } }
            val b = executor.submit { repeat(1000) { ManagedAddress.fromAllocation(first).copyFromByteArray(second, 0, 16) } }
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS)
            assertSame(pointer, first.readAddressByteOffset(0)); assertSame(pointer, second.readAddressByteOffset(0))
        } finally { executor.shutdownNow() }
    }

    @Test fun fixedAstChildrenEvaluateStateBeforeStorageAndLeaveNoMutationOnFailure() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val proof = CoreRepresentation(CoreKind.VOID, present = true)
        for (toArray in listOf(false, true)) {
            val log = mutableListOf<Int>(); val source = ByteArray(8) { 3 }; val target = ByteArray(8) { 71 }
            fun value(index: Int, value: Any) = object : Expr() {
                override fun execute(frame: VirtualFrame): Any { log.add(index); return value }
                override fun executeLong(frame: VirtualFrame): Long { log.add(index); return value as Long }
                override fun executeAddress(frame: VirtualFrame): ManagedAddress { log.add(index); return value as ManagedAddress }
            }
            val state = object : Expr() { override fun execute(frame: VirtualFrame): Any { log.add(4); throw RuntimeFault("state failed") } }
            val expression = if (toArray) AddressToByteArrayExpression(proof, value(0, ManagedAddress.fromByteArray(source)),
                value(1, target), value(2, 0L), value(3, 8L), state)
            else ByteArrayToAddressExpression(proof, value(0, source), value(1, 0L),
                value(2, ManagedAddress.fromByteArray(target)), value(3, 8L), state)
            assertThrows(RuntimeFault::class.java) { expression.execute(frame) }
            assertEquals(listOf(0, 1, 2, 3, 4), log); assertArrayEquals(ByteArray(8) { 71 }, target)
        }
    }

    private fun synthetic(operation: AddressArrayCopyOp): Map<String, Any?> {
        fun rep(kind: String, primitive: String?) = mapOf("kind" to kind, "primReps" to listOfNotNull(primitive), "evaluated" to true)
        val long = rep("long", "IntRep"); val address = rep("address", "AddrRep")
        val array = rep("object", "BoxedRep (Just Unlifted)"); val state = rep("void", null)
        val closure = rep("closure", "BoxedRep (Just Lifted)")
        val args = (if (operation.toArray) listOf(address, array, long, long, state) else listOf(array, long, address, long, state))
            .mapIndexed { i, proof -> mapOf("id" to "a$i", "lifted" to false, "rep" to proof) }
        val call = listOf("app", listOf("prim", operation.primitive), args.map { listOf("var", it["id"], mapOf("rep" to it["rep"])) },
            List(5) { false }, false, false, mapOf("rep" to state))
        val body = listOf("case", call, "state", listOf(listOf("default", null, emptyList<String>(),
            listOf("lit", "int", "23", mapOf("rep" to long)))), mapOf("rep" to long,
            "binder" to mapOf("id" to "state", "lifted" to false, "rep" to state)))
        return mapOf("module" to "SyntheticAddressArrayCopy", "schema" to 1, "instrument" to true,
            "bindings" to listOf(mapOf("id" to "copy", "name" to "copy", "arity" to 5, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", args, body, mapOf("rep" to closure, "resultRep" to long)))))
    }

    @Test fun ownedNativeCopiesExecuteOnFirstCompiledEntriesAndRejectStaleOrForeignOwners() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        for (backend in listOf("ast", "bytecode")) for (inlining in listOf(false, true)) context(inlining, true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val registry = Language.currentState().nativeAllocations
                val base = registry.malloc(24)
                try {
                    for (operation in AddressArrayCopyOp.entries) {
                        val p = program(language, synthetic(operation), backend); val target = p.entryTarget("copy")
                        fun call(array: Any, state: Any = Unit) = Calls.target(target, if (operation.toArray)
                            arrayOf(0L, base.plus(4), array, 2L, 8L, state) else arrayOf(0L, array, 2L, base.plus(4), 8L, state))
                        for (installed in listOf(false, true)) {
                            for (owned in listOf(false, true)) {
                                // The previous case deliberately trips a state
                                // guard, which may invalidate installed code.
                                // Install before this case's first measured call.
                                if (installed) compile(target)
                                for (i in 0L until 24L) base.writeWord8(i, i + 31)
                                val array = storage(ByteArray(12) { (it + 91).toByte() }, owned)
                                val before = count(p); assertEquals(23L, call(array))
                                if (installed) { assertEquals(before + 1, count(p), "$backend/$operation/owned=$owned"); valid(target) }
                                if (operation.toArray) assertArrayEquals(ByteArray(12) { if (it in 2..9) (it + 33).toByte() else (it + 91).toByte() }, bytes(array))
                                else assertEquals(List(24) { if (it in 4..11) it + 89L else it + 31L }, (0L until 24L).map(base::readWord8))
                                val old = bytes(array); val nativeOld = (0L until 24L).map(base::readWord8)
                                assertThrows(RuntimeFault::class.java) { call(array, 7L) }
                                assertArrayEquals(old, bytes(array)); assertEquals(nativeOld, (0L until 24L).map(base::readWord8))
                                released(language)
                            }
                        }
                    }
                    val pointers = ManagedAllocation.mutable(8, 8)
                    pointers.writeAddressByteOffset(0, ManagedAddress.fromHex("41"))
                    val before = (0L until 24L).map(base::readWord8)
                    assertThrows(RuntimeFault::class.java) { base.copyFromByteArray(pointers, 0, 8) }
                    assertEquals(before, (0L until 24L).map(base::readWord8))
                    assertThrows(RuntimeFault::class.java) { base.copyToByteArray(pointers, 0, 1) }
                    assertNotNull(pointers.readAddressByteOffset(0))
                    this.context(true, true).use { other ->
                        other.initialize("thc"); other.enter()
                        try {
                            assertThrows(RuntimeFault::class.java) { base.copyToByteArray(ByteArray(8), 0, 0) }
                            assertThrows(RuntimeFault::class.java) { base.copyFromByteArray(ByteArray(8), 0, 0) }
                        } finally { other.leave() }
                    }
                } finally { registry.free(base) }
                assertThrows(RuntimeFault::class.java) { base.copyToByteArray(ByteArray(8), 0, 0) }
                assertThrows(RuntimeFault::class.java) { base.copyFromByteArray(ByteArray(8), 0, 0) }
                assertEquals(0, registry.liveCount())
            } finally { context.leave() }
        }
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }

    @Test fun nativeOwnerRemainsBorrowedWhileWaitingForManagedCopyMonitor() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        context(native = true).use { context ->
            val executor = Executors.newFixedThreadPool(2)
            context.initialize("thc"); context.enter()
            try {
                val registry = Language.currentState().nativeAllocations
                for (toArray in listOf(false, true)) {
                    val base = registry.malloc(8)
                    val array = ManagedAllocation.mutable(8, 8)
                    for (i in 0L..7L) base.writeWord8(i, 73)
                    array.fill(0, 8, 91)
                    val copyingThread = AtomicReference<Thread>()
                    val startedFree = CountDownLatch(1)
                    val pending = synchronized(array) {
                        val copy = executor.submit {
                            context.enter()
                            try {
                                copyingThread.set(Thread.currentThread())
                                if (toArray) base.copyToByteArray(array, 0, 8) else base.copyFromByteArray(array, 0, 8)
                            } finally { context.leave() }
                        }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (copyingThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline)
                            Thread.sleep(1)
                        assertEquals(Thread.State.BLOCKED, copyingThread.get()?.state, "Copy must reach the managed owner monitor")
                        assertFalse(copy.isDone)
                        val free = executor.submit {
                            context.enter()
                            try { startedFree.countDown(); registry.free(base) } finally { context.leave() }
                        }
                        assertTrue(startedFree.await(5, TimeUnit.SECONDS))
                        assertThrows(TimeoutException::class.java) { free.get(100, TimeUnit.MILLISECONDS) }
                        copy to free
                    }
                    pending.first.get(5, TimeUnit.SECONDS); pending.second.get(5, TimeUnit.SECONDS)
                    assertArrayEquals(ByteArray(8) { if (toArray) 73 else 91 }, bytes(array))
                    assertThrows(RuntimeFault::class.java) { base.copyToByteArray(array, 0, 0) }
                    assertThrows(RuntimeFault::class.java) { base.copyFromByteArray(array, 0, 0) }
                    assertEquals(0, registry.liveCount())
                }
            } finally {
                executor.shutdown()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
                context.leave()
            }
        }
    }
    @Test fun genuineCoreCarrierArityAndScalarStateFailuresStayStrict(@TempDir temporary: Path) {
        evidence()
        for (stage in listOf("pre", "post")) for ((index, name) in names.withIndex()) for (mutation in
            listOf("valid", "argument", "state", "result", "tuple", "partial", "over", "lifted", "bare")) {
            val linked = CoreModules.reachable(module(stage), name)
            val app = applications(linked).single { (it[1] as? List<*>)?.take(2) == listOf("prim", primitives[index]) }
            val metadata = app[6] as MutableMap<String, Any?>; val arguments = app[2] as MutableList<Any?>
            val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
            when (mutation) {
                "argument", "state" -> (CoreRepresentations.metadata(arguments[if (mutation == "state") 4 else 0] as List<Any?>)
                    as MutableMap<String, Any?>)["rep"] = long
                "result" -> metadata["rep"] = long
                "tuple" -> metadata["rep"] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "primReps" to emptyList<String>(),
                    "components" to listOf(mapOf("kind" to "void", "primReps" to emptyList<String>())))
                "partial" -> { arguments.removeLast(); (app[3] as MutableList<Any?>).removeLast(); metadata.remove("callDemand") }
                "over" -> { arguments.add(arguments.last()); (app[3] as MutableList<Any?>).add(false); metadata.remove("callDemand") }
                "lifted" -> (app[3] as MutableList<Any?>)[0] = true
                "bare" -> { val head = (app[1] as List<*>).toList(); app.clear(); app.addAll(head) }
            }
            val label = "$stage-$name-$mutation"; val input = temporary.resolve("$label.json").toFile()
            input.writeText(Json.stringify(linked)); val report = temporary.resolve("$label-report.json").toFile()
            val process = ProcessBuilder("python3", "scripts/audit-core.py", input.path, "--entry", name, "--output", report.path)
                .directory(root).redirectOutput(temporary.resolve("$label.stdout").toFile()).redirectError(temporary.resolve("$label.stderr").toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail<Unit>("Auditor timeout: $label") }
            assertEquals(if (mutation == "valid") 0 else 1, process.exitValue(), label)
            assertEquals(mutation == "valid", (Json.parse(report.readText()) as Map<*, *>)["accepted"], label)
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    if (mutation == "valid") program(language, linked, backend)
                    else for (diagnostic in listOf(false, true)) {
                        if (mutation == "bare" && diagnostic) {
                            val loaded = program(language, linked + ("diagnosticUnsupported" to true), backend)
                            assertThrows(RuntimeException::class.java, { Calls.target(loaded.hostEntryTarget(5),
                                arrayOf(loaded.entryValue(name), arrayOf(0L, 0L, 0L, 1L, 8L))) }, "$backend/$label deferred diagnostic trap")
                        } else assertThrows(RuntimeException::class.java,
                            { program(language, linked + ("diagnosticUnsupported" to diagnostic), backend) }, "$backend/$label")
                    }
                } finally { context.leave() }
            }
        }
        for (operation in AddressArrayCopyOp.entries) {
            val proofs = if (operation.toArray) listOf(CoreKind.ADDRESS, CoreKind.OBJECT, CoreKind.LONG, CoreKind.LONG, CoreKind.VOID)
                else listOf(CoreKind.OBJECT, CoreKind.LONG, CoreKind.ADDRESS, CoreKind.LONG, CoreKind.VOID)
            for (register in listOf("IntRep", "WordRep", "Int64Rep", "Word64Rep"))
                operation.validate(proofs.map { CoreRepresentation(it, present = true,
                    primReps = if (it == CoreKind.LONG) listOf(register) else emptyList()) }, List(5) { false }, CoreRepresentation(CoreKind.VOID))
        }
    }
}
