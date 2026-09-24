// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PinnedPointerCellsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private data class Row(val input: Long, val pointer: Long, val array: Long, val order: Long,
        val char8: Long, val byte8: Long, val halfwordRead: Long, val halfwordWrite: Long,
        val mutableContents: Long, val touchLazy: Long)
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val targets = mutableListOf<RootCallTarget>()
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val next = call.currentCallTarget as? RootCallTarget ?: continue
                if (next.rootNode is GuestRoot) visit(next)
            }
            targets += target
        }
        visit(entry)
        return targets
    }
    private fun strictContext(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun released(language: Language) {
        val pools = language.handoffState.get()
        assertEquals(0, pools.arguments.depth); assertEquals(0, pools.results.depth)
        assertEquals(0, pools.arguments.retainedReferences()); assertEquals(0, pools.results.retainedReferences())
    }
    private fun module(stage: String): Map<String, Any?> = CoreModules.merge(
        listOf("PinnedPointerCellsAudit.json", "THC.InterfaceClosure.json").map {
            Json.parse(File(root, "build/pinned-pointer-cells/$stage/core/$it").readText()) as Map<String, Any?>
        })
    private fun program(language: Language, source: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
    private fun rows(): List<Row> {
        val rows = File(root, "build/pinned-pointer-cells/oracle.tsv").readLines().map { line ->
            val fields = line.split('\t').map(String::toLong)
            assertEquals(10, fields.size)
            Row(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8], fields[9])
        }
        assertEquals(listOf(0L, 1L, 17L, 127L, 255L, 256L, 32767L, 32768L, 65535L, -1L, -32768L),
            rows.map { it.input })
        for (row in rows) {
            val byte = row.input and 255L
            assertEquals(1000000L + byte * 256L + (byte xor 90L), row.mutableContents)
            assertEquals(row.input + 37L, row.touchLazy)
        }
        return rows
    }
    private fun provenance() {
        val manifest = Json.parse(File(root, "build/pinned-pointer-cells/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"]); assertEquals("9.14.1", manifest["ghc"])
        val inputs = setOf("compiler/test-fixtures/PinnedPointerCellsAudit.hs",
            "compiler/test-fixtures/PinnedPointerCellsNative.hs", "test/haskell-fixtures/Main.hs",
            "test/haskell-fixtures/FixtureSupport.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
            "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py",
            "thc.cabal", "cabal.project") + File(root, "compiler/THC").listFiles()!!
                .filter { it.extension == "hs" }.map { "compiler/THC/${it.name}" } +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }
                .map { "scripts/${it.name}" }
        val artifacts = setOf("build/pinned-pointer-cells/oracle.tsv") + listOf("pre", "post").flatMap { stage ->
            listOf("audit.json", "core/PinnedPointerCellsAudit.json", "core/THC.InterfaceClosure.json")
                .map { "build/pinned-pointer-cells/$stage/$it" }
        }
        for ((kind, paths) in listOf("inputHashes" to inputs, "artifactHashes" to artifacts)) {
            val hashes = manifest[kind] as Map<String, String>
            assertEquals(paths, hashes.keys, kind)
            for ((path, expected) in hashes) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()), path)
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale pinned pointer-cell $kind: $path")
            }
        }
    }

    @Test fun pointerArrayCellsRequireAnOwnerAndRejectInvalidIndicesBeforeMutation() {
        val nullAddress = ManagedAddress.nullAddress()
        val raw = ByteArray(32)
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(raw, 0, nullAddress) }
        assertThrows(RuntimeFault::class.java) { PinnedMemory.readAddressArray(raw, 0) }
        val pinned = PinnedMemory.allocate(32, 1)
        PinnedMemory.writeAddressArray(pinned, 1, nullAddress)
        assertSame(nullAddress, PinnedMemory.readAddressArray(pinned, 1))
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(pinned, -1, nullAddress) }
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(pinned, Long.MAX_VALUE, nullAddress) }
        assertSame(nullAddress, PinnedMemory.readAddressArray(pinned, 1))
        val base = ManagedAddress.fromAllocation(pinned)
        val target = base.plus(24)
        base.writeAddressElementIndex(1, target)
        base.writeWord8(16, 0x1e9)
        assertEquals(0xe9L, base.readWord8(16))
        assertDoesNotThrow { ManagedAddressRead.WORD16.read(base, 8) }
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WORD16.read(base, 4) }
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.INT16.read(base, Long.MAX_VALUE) }
        assertThrows(RuntimeFault::class.java) { base.writeWord8(8, 0x41) }
        assertSame(target, base.readAddressElementIndex(1))
    }

    @Test fun orderedAddressesRequireOneAllocationAndPreserveCheckedOffsets() {
        val nullAddress = ManagedAddress.nullAddress()
        assertEquals(0, nullAddress.compareWithinAllocation(nullAddress))
        val bytes = ByteArray(16)
        val base = ManagedAddress.fromByteArray(bytes)
        val alias = ManagedAddress.fromByteArray(bytes)
        assertEquals(0, base.compareWithinAllocation(alias))
        assertTrue(base.compareWithinAllocation(alias.plus(1)) < 0)
        assertTrue(base.plus(16).compareWithinAllocation(alias) > 0) // one past
        assertThrows(RuntimeFault::class.java) { base.plus(Long.MAX_VALUE) }
        assertThrows(RuntimeFault::class.java) { base.plus(-1) }
        assertThrows(RuntimeFault::class.java) { base.compareWithinAllocation(nullAddress) }
        assertThrows(RuntimeFault::class.java) { nullAddress.compareWithinAllocation(base) }
        assertThrows(RuntimeFault::class.java) {
            base.compareWithinAllocation(ManagedAddress.fromByteArray(bytes.copyOf()))
        }
        val pinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(16, 1))
        assertTrue(pinned.compareWithinAllocation(pinned.plus(16)) < 0)
        assertThrows(RuntimeFault::class.java) { base.compareWithinAllocation(pinned) }
    }

    private fun primitiveCalls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::primitiveCalls)
        is List<*> -> {
            val head = value.getOrNull(1) as? List<*>
            val here = if (value.firstOrNull() == "app" && head?.firstOrNull() == "prim")
                listOf(value) else emptyList()
            here + value.flatMap(::primitiveCalls)
        }
        else -> emptyList()
    }
    private fun rep(expr: List<Any?>) = CoreRepresentations.metadata(expr)!!.getValue("rep") as Map<String, Any?>
    private fun proof(kind: String, reps: List<String>, evaluated: Boolean) =
        mapOf("kind" to kind, "primReps" to reps, "evaluated" to evaluated)
    private fun replace(value: Any?, target: Any, replacement: Any): Any? = when {
        value === target -> replacement
        value is List<*> -> value.map { replace(it, target, replacement) }
        value is Map<*, *> -> value.mapValues { replace(it.value, target, replacement) }
        else -> value
    }

    @Test fun mutableContentsAndTouchRetainExactOriginalProofsAndRejectMutations() {
        provenance()
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            val calls = primitiveCalls(source)
            val contents = calls.single { (it[1] as List<*>)[1] == "mutableByteArrayContents#" }
            val touches = calls.filter { (it[1] as List<*>)[1] == "touch#" }
            assertEquals(2, touches.size)
            val owner = proof("object", listOf("BoxedRep (Just Unlifted)"), true)
            val state = proof("void", emptyList(), true)
            assertEquals(listOf(false), contents[3])
            assertEquals(listOf(owner), (contents[2] as List<List<Any?>>).map(::rep))
            assertEquals(proof("address", listOf("AddrRep"), false), rep(contents))
            for (touch in touches) {
                val flags = touch[3] as List<Boolean>
                assertEquals(2, flags.size); assertEquals(false, flags[1])
                val args = touch[2] as List<List<Any?>>
                assertEquals(listOf(if (flags[0]) proof("data", listOf("BoxedRep (Just Lifted)"), false)
                    else owner, state), args.map(::rep))
                assertEquals(proof("void", emptyList(), false), rep(touch)) // Not (# State# #).
            }
            assertEquals(setOf(listOf(true, false), listOf(false, false)), touches.map { it[3] }.toSet())
            val mutable = CoreModules.reachable(source, "mutableContentsRoundtrip")
            assertFalse(primitiveCalls(mutable).any { (it[1] as List<*>)[1] == "unsafeFreezeByteArray#" })
            val lazy = CoreModules.reachable(source, "touchLazyPayload")
            assertEquals(1, primitiveCalls(lazy).count { (it[1] as List<*>)[1] == "raise#" })
            val audit = Json.parse(File(root, "build/pinned-pointer-cells/$stage/audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = (audit["primitives"] as List<Map<String, Any?>>).associateBy { it["name"] }
            for ((name, owners) in mapOf("mutableByteArrayContents#" to setOf("mutableContentsAt"),
                "touch#" to setOf("mutableContentsRoundtrip", "touchLazyPayload"))) {
                val uses = primitives.getValue(name)["uses"] as List<Map<String, Any?>>
                assertEquals(owners.map { "main:PinnedPointerCellsAudit.$it" }.toSet(), uses.map { it["owner"] }.toSet())
            }
            // Mutate the retained authentic applications, not a substitute synthetic ABI.
            val mutations = mutableListOf<Pair<Any, Any>>()
            for (app in listOf(contents) + touches) {
                mutations += app to app.toMutableList().also { copy ->
                    copy[3] = (app[3] as List<Boolean>).mapIndexed { i, flag -> if (i == 0) !flag else flag }
                }
                mutations += app to app.toMutableList().also { copy ->
                    copy[6] = (app[6] as Map<String, Any?>) + ("rep" to proof("long", listOf("IntRep"), false))
                }
            }
            for (touch in touches) {
                val args = touch[2] as List<List<Any?>>
                val badState = args[1].toMutableList().also {
                    it[2] = (it[2] as Map<String, Any?>) + ("rep" to proof("long", listOf("IntRep"), true))
                }
                mutations += touch to touch.toMutableList().also { it[2] = listOf(args[0], badState) }
                mutations += touch to touch.toMutableList().also {
                    it[6] = (it[6] as Map<String, Any?>) + ("rep" to mapOf("kind" to "unknown",
                        "primReps" to emptyList<String>(), "evaluated" to false,
                        "aggregate" to "unboxed-tuple", "components" to listOf(state)))
                }
            }
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    assertDoesNotThrow { program(language, source, backend) }
                    for ((before, after) in mutations) assertThrows(RuntimeFault::class.java, {
                        program(language, replace(source, before, after) as Map<String, Any?>, backend)
                    }, "$stage/$backend malformed authentic app")
                } finally { context.leave() }
            }
        }
    }

    @Test fun mutableContentsAndLazyTouchMatchNativeOnEveryInstalledCall() {
        provenance()
        val rows = rows()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (inlining in listOf(false, true)) strictContext(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (entry in listOf("mutableContentsRoundtrip", "touchLazyPayload")) {
                        val source = CoreModules.reachable(module(stage), entry) + ("instrument" to true)
                        val program = program(language, source, backend)
                        val target = program.entryTarget(entry)
                        fun check(row: Row) {
                            assertEquals(if (entry == "mutableContentsRoundtrip") row.mutableContents else row.touchLazy,
                                Calls.target(target, arrayOf(0L, row.input)), "$stage/$backend/$entry/${row.input}/inlining=$inlining")
                            released(language)
                        }
                        repeat(3) { rows.forEach(::check) }
                        // Include runRW's actual state lambda and active split helper
                        // targets, so disabling inlining still compiles the primops.
                        val targets = activeTargets(target)
                        assertTrue(targets.size >= 2, "Retain the original runRW lambda")
                        targets.forEach {
                            it.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(it, true)
                            valid(it, "$stage/$backend/$entry/${it.rootNode.name} installation")
                        }
                        targets.forEach { valid(it, "$stage/$backend/$entry/${it.rootNode.name} installed graph") }
                        for (row in rows.asReversed() + rows) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row) // First installed invocation is checked without settling/recompilation.
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() - before >= targets.size)
                            targets.forEach { valid(it, "$stage/$backend/$entry/${it.rootNode.name}/${row.input}/inlining=$inlining") }
                        }
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                } finally { context.leave() }
            }
    }

    @Test fun originalMutableContentsHelperPreservesAliasesLifetimeAndCheckedBounds() {
        provenance()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            for (inlining in listOf(false, true)) strictContext(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = program(language, CoreModules.reachable(module(stage), "mutableContentsAt") +
                        ("instrument" to true), backend)
                    val target = program.entryTarget("mutableContentsAt")
                    var compiled = false
                    fun address(owner: ManagedAllocation, offset: Long): ManagedAddress {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        try { return Calls.target(target, arrayOf(0L, owner, offset)) as ManagedAddress }
                        finally {
                            if (compiled) {
                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                                valid(target, "$stage/$backend/mutableContentsAt/$offset/inlining=$inlining")
                            }
                            released(language)
                        }
                    }
                    fun aliases(): Pair<ManagedAddress, WeakReference<ManagedAllocation>> {
                        val owner = PinnedMemory.allocate(32, 1)
                        val base = address(owner, 0)
                        assertTrue(base.sameLocation(address(owner, 0)))
                        for (offset in listOf(0L, 1L, 31L)) {
                            val interior = address(owner, offset)
                            assertTrue(base.plus(offset).sameLocation(interior))
                            owner.writeByte(offset, offset + 70L)
                            assertEquals(offset + 70L, interior.readWord8(0))
                            interior.writeWord8(0, offset + 100L)
                            assertEquals(offset + 100L, owner.readByte(offset))
                        }
                        assertTrue(base.plus(32).sameLocation(address(owner, 32)))
                        return base to WeakReference(owner)
                    }
                    repeat(3) { aliases() }
                    val boundsOwner = PinnedMemory.allocate(32, 1)
                    for (offset in listOf(-1L, 33L, Long.MIN_VALUE, Long.MAX_VALUE))
                        assertThrows(RuntimeFault::class.java) { address(boundsOwner, offset) }
                    assertThrows(RuntimeFault::class.java) { address(boundsOwner, 32).readWord8(0) }
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "mutableContentsAt installation")
                    compiled = true
                    val (retained, owner) = aliases()
                    repeat(3) { System.gc() }
                    assertNotNull(owner.get(), "An escaped Addr# must retain its pinned allocation")
                    assertEquals(131L, retained.readWord8(31))
                    retained.writeWord8(31, 211)
                    assertEquals(211L, owner.get()!!.readByte(31))
                    Reference.reachabilityFence(retained)
                    aliases()
                } finally { context.leave() }
            }
    }

    @Test fun halfwordStoresCheckWholeElementAndPreserveDisjointPointerCells() {
        val base = ManagedAddress.fromAllocation(PinnedMemory.allocate(32, 1))
        val target = base.plus(24)
        base.writeAddressElementIndex(1, target)
        base.writeWord16(8, 0x12345)
        val expected = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort(0x2345.toShort()).array()
        assertEquals(expected[0].toLong() and 255, base.readWord8(16))
        assertEquals(expected[1].toLong() and 255, base.readWord8(17))
        assertSame(target, base.readAddressElementIndex(1))
        for (index in listOf(4L, 7L, 16L, Long.MAX_VALUE, Long.MIN_VALUE))
            assertThrows(RuntimeFault::class.java) { base.writeWord16(index, 0x55aa) }
        assertSame(target, base.readAddressElementIndex(1))
        assertEquals(expected[0].toLong() and 255, base.readWord8(16))
        assertThrows(RuntimeFault::class.java) { ManagedAddress.fromHex("0000").writeWord16(0, 1) }
        base.plus(2).writeWord16(-1, -1)
        assertEquals(255L, base.readWord8(0))
        assertEquals(255L, base.readWord8(1))
    }

    private fun halfwordModel(input: Long): Long {
        val bytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder())
        bytes.putShort(24, input.toShort())
        bytes.putShort(16, (input + 32768).toShort())
        return (1L shl 32) + (16..17).plus(24..25).fold(0L) { packed, offset ->
            (packed shl 8) or (bytes.get(offset).toLong() and 255L)
        }
    }

    @Test fun originalPinnedFreezeContentsAndKeepAliveMatchNativeInBothBackends() {
        provenance()
        val rows = rows()
        for (row in rows) {
            assertEquals(1009L + 17L * (row.input and 255L), row.pointer)
            assertEquals(1L, row.array)
            assertEquals(if (row.input and 7L == 0L) 122L else 127L, row.order)
            assertEquals(0x01010101L * (row.input and 255L), row.char8)
            val unsigned = row.input and 255L
            val signed = unsigned.toByte().toLong()
            assertEquals(((signed + 128L) shl 24) + ((signed + 128L) shl 16) +
                (unsigned shl 8) + unsigned, row.byte8)
            val halfword = row.input and 65535L
            val signedHalfword = halfword.toShort().toLong()
            assertEquals(((signedHalfword + 32768L) shl 48) + ((signedHalfword + 32768L) shl 32) +
                (halfword shl 16) + halfword, row.halfwordRead)
            assertEquals(halfwordModel(row.input), row.halfwordWrite)
        }
        for (stage in listOf("pre", "post")) {
            val directory = File(root, "build/pinned-pointer-cells/$stage")
            val audit = Json.parse(File(directory, "audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet()
            assertTrue(primitives.containsAll(setOf("newPinnedByteArray#", "unsafeFreezeByteArray#", "byteArrayContents#",
                "keepAlive#", "writeAddrOffAddr#", "readAddrOffAddr#", "indexAddrOffAddr#",
                "writeAddrArray#", "readAddrArray#", "indexAddrArray#",
                "ltAddr#", "leAddr#", "gtAddr#", "geAddr#",
                "readCharOffAddr#", "writeCharOffAddr#", "indexCharOffAddr#",
                "readCharArray#", "writeCharArray#", "indexCharArray#",
                "readInt8OffAddr#", "writeInt8OffAddr#", "indexInt8OffAddr#", "indexWord8OffAddr#",
                "readInt16OffAddr#", "readWord16OffAddr#", "indexInt16OffAddr#", "indexWord16OffAddr#",
                "writeInt16OffAddr#", "writeWord16OffAddr#",
                "readWord8OffAddr#", "indexWord8Array#")))
            for (primitive in setOf("readInt8OffAddr#", "writeInt8OffAddr#", "indexInt8OffAddr#",
                "indexWord8OffAddr#", "writeInt16OffAddr#", "writeWord16OffAddr#")) {
                val evidence = (audit["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                val owner = if (primitive.endsWith("16OffAddr#")) "halfwordWriteRoundtrip" else "byte8Roundtrip"
                assertEquals(setOf("main:PinnedPointerCellsAudit.$owner"), owners, "$stage/$primitive")
            }
            for (primitive in setOf("readInt16OffAddr#", "readWord16OffAddr#", "indexInt16OffAddr#",
                "indexWord16OffAddr#")) {
                val evidence = (audit["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                assertEquals(setOf("main:PinnedPointerCellsAudit.halfwordReadRoundtrip"), owners, "$stage/$primitive")
            }
            val paths = listOf("PinnedPointerCellsAudit.json", "THC.InterfaceClosure.json")
                .map { File(directory, "core/$it") }
            val merged = CoreModules.merge(paths.map { Json.parse(it.readText()) as Map<String, Any?> })
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (entry in listOf("pointerRoundtrip", "pointerArrayRoundtrip", "pointerOrder",
                        "char8Roundtrip", "byte8Roundtrip", "halfwordReadRoundtrip", "halfwordWriteRoundtrip")) {
                        val source = CoreModules.reachable(merged, entry) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                            else BytecodeProgram(language, source)
                        val function = context.asValue(EntryValue(program, entry, 1))
                        fun check(input: Long, expected: Long) {
                            assertEquals(expected, function.execute(input).asLong(), "$stage/$backend/$entry/$input")
                            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        }
                        fun expected(row: Row) = when (entry) {
                            "pointerRoundtrip" -> row.pointer
                            "pointerArrayRoundtrip" -> row.array
                            "pointerOrder" -> row.order
                            "char8Roundtrip" -> row.char8
                            "byte8Roundtrip" -> row.byte8
                            "halfwordReadRoundtrip" -> row.halfwordRead
                            else -> row.halfwordWrite
                        }
                        rows.forEach { row -> check(row.input, expected(row)) }
                        // EntryValue compiles the active DirectCallNode target (which may
                        // be a split clone) and the public host root, checking both tiers.
                        assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend/$entry compilation")
                        valid(program.hostEntryTarget(1), "$stage/$backend/$entry host target after compilation")
                        for (row in rows.asReversed()) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row.input, expected(row))
                            var after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            if (after == before) {
                                // A dependency can retire the public call-boundary stub;
                                // require a bounded fresh last-tier execution for this row.
                                assertTrue(function.invokeMember("compile").asBoolean(),
                                    "$stage/$backend/$entry recompilation")
                                check(row.input, expected(row))
                                after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            }
                            assertTrue(after > before, "$stage/$backend/$entry/${row.input}: $before->$after")
                        }
                    }
                } finally { context.leave() }
            }
        }
    }
}
