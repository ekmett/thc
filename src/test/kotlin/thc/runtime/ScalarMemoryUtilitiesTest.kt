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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class ScalarMemoryUtilitiesTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/scalar-memory-utilities")
    private val names = listOf("memoryCase", "pinCase", "thawCase", "shrinkCase", "differenceCase",
        "remainderCase", "numericDifference", "numericRemainder")
    private val primitives = setOf("copyAddrToAddr#", "setAddrRange#", "minusAddr#", "remAddr#",
        "isByteArrayPinned#", "isMutableByteArrayPinned#", "isByteArrayWeaklyPinned#",
        "isMutableByteArrayWeaklyPinned#", "unsafeThawByteArray#", "shrinkSmallMutableArray#")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun context(native: Boolean = false) = Context.newBuilder("thc").allowNativeAccess(native)
        .allowExperimentalOptions(true).option("compiler.Inlining", "false")
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private data class Row(val entry: String, val args: List<Long>, val answer: Long)
    private fun rows() = buildList {
        for ((from, to, count) in listOf(Triple(0,4,16), Triple(4,0,16), Triple(8,16,8), Triple(0,0,32), Triple(32,32,0)))
            for (value in listOf(-257L,-1L,0L,1L,256L,511L)) for (selected in listOf(0,8,15,23,31)) {
                val bytes = MutableList(32) { value and 255L }.also { it[8] = 99 }
                val snapshot = bytes.subList(from, from + count).toList()
                snapshot.forEachIndexed { index, byte -> bytes[to + index] = byte }
                add(Row("memoryCase", listOf(from.toLong(),to.toLong(),count.toLong(),value,selected.toLong()), bytes[selected]))
            }
        for (mode in 0L..2L) add(Row("pinCase", listOf(mode), if (mode == 0L) 0 else 15))
        for (value in listOf(-257L,-1L,0L,1L,127L,255L,256L,511L))
            add(Row("thawCase", listOf(value), (value and 255L) + 256L * ((value + 1) and 255L)))
        for (size in 0L..8L) add(Row("shrinkCase", listOf(size), if (size == 0L) 0 else size * 100 + 77))
        for (left in listOf(0L,1L,31L,64L)) for (right in listOf(0L,1L,31L,64L))
            add(Row("differenceCase", listOf(left,right), left - right))
        for (offset in listOf(0L,1L,7L,31L,64L)) for (divisor in listOf(1L,2L,8L,16L,64L))
            add(Row("remainderCase", listOf(offset,divisor), offset % divisor))
        for (left in listOf(Long.MIN_VALUE,-1L,0L,1L,Long.MAX_VALUE))
            for (right in listOf(Long.MIN_VALUE,-1L,0L,1L,Long.MAX_VALUE))
                add(Row("numericDifference", listOf(left,right), left - right))
        for (bits in listOf(Long.MIN_VALUE,-1L,0L,1L,Long.MAX_VALUE))
            for (divisor in listOf(Long.MIN_VALUE,-7L,-1L,1L,2L,7L,Long.MAX_VALUE))
                add(Row("numericRemainder", listOf(bits,divisor), (bits.toULong() % divisor.toULong()).toLong()))
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
    @Test fun nativeModelsAndBothBackendsIncludeTheFirstInstalledEntry() {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals("9.14.1", manifest["ghc"]); assertEquals(names, manifest["entries"])
        assertEquals(271L, manifest["nativeRows"])
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val digest = MessageDigest.getInstance("SHA-256").digest(File(root,path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, digest, "Stale scalar-memory input/artifact: $path")
        }
        val expected = rows()
        val oracle = File(directory, "oracle.tsv").readLines().map { line ->
            val fields = line.split(' ')
            Row(fields.first(), fields.drop(1).dropLast(1).map(String::toLong), fields.last().toLong())
        }
        assertEquals(expected, oracle, "Independent Kotlin memory/arithmetic model")
        for (stage in listOf("pre", "post")) {
            val audit = json(File(directory, "$stage/audit.json"))
            assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            assertTrue((audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.containsAll(primitives))
            val module = json(File(directory, "$stage/core/ScalarMemoryUtilities.json"))
            for (backend in listOf("ast", "bytecode")) for (name in names) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val requestedMode = System.getenv("THC_EXPECT_HANDOFF_MODE")?.toBooleanStrict()
                        ?: java.lang.Boolean.getBoolean(HANDOFF_PROPERTY)
                    assertEquals(requestedMode,java.lang.Boolean.getBoolean(HANDOFF_PROPERTY))
                    assertEquals(requestedMode,language.handoffLayouts.enabled)
                    val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language,linked) else BytecodeProgram(language,linked)
                    val entry = program.entryTarget(name)
                    val corpus = expected.filter { it.entry == name }
                    val handoff = language.handoffState.get()
                    fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    fun call(row: Row) {
                        try { assertEquals(row.answer, Calls.target(entry, arrayOf(0L,*row.args.toTypedArray())), "$stage/$backend/$row") }
                        finally {
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                            assertNull(handoff.pending)
                        }
                    }
                    corpus.forEach(::call)
                    // Count from original Core: pinCase additionally calls its
                    // floated $j once, regardless of which allocation branch wins.
                    // shrinkCase's shared I# 77 CAF was evaluated by the interpreted
                    // corpus; its cached target remains but is not re-entered.
                    val expectedEntries = when {
                        name == "pinCase" -> 3L
                        name.startsWith("numeric") -> 1L
                        else -> 2L
                    }
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val binding = bindings.single { it["name"] == name }
                    val body = (binding["expr"] as List<*>)[2] as List<*>
                    if (!name.startsWith("numeric")) {
                        assertEquals("app", body[0]); assertEquals("lam", (body[1] as List<*>)[0])
                    }
                    fun nodes(value: Any?): List<List<*>> = when (value) {
                        is List<*> -> listOf(value) + value.flatMap(::nodes)
                        is Map<*, *> -> value.values.flatMap(::nodes)
                        else -> emptyList()
                    }
                    assertEquals(expectedEntries, nodes(bindings).count { it.firstOrNull() == "lam" }.toLong())
                    if (name == "pinCase") {
                        val helper = bindings.single { it["name"] == "\$j" }
                        assertEquals(3, nodes(binding).count { it.firstOrNull() == "var" && it[1] == helper["id"] })
                    } else if (name == "shrinkCase") {
                        val constant = bindings.single { it["name"] == "lvl" }
                        val expression = constant["expr"] as List<*>
                        assertEquals("app", expression[0])
                        assertEquals("con", (expression[1] as List<*>)[0])
                        assertEquals("ghc-internal:GHC.Internal.Types.I#", (expression[1] as List<*>)[1])
                        assertEquals("77", ((expression[2] as List<*>).single() as List<*>)[2])
                    }
                    assertEquals(if (name in setOf("pinCase", "shrinkCase")) 2 else 1, bindings.size)
                    val targets = activeTargets(entry)
                    assertEquals(expectedEntries + if (name == "shrinkCase") 1L else 0L, targets.size.toLong())
                    assertEquals(0L, count())
                    val beforeCalls = targets.map { it.javaClass.getMethod("getCallCount").invoke(it) }
                    val arguments = handoff.arguments.allocations
                    val results = handoff.results.allocations
                    val runtime = Truffle.getRuntime()
                    val targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    for (target in targets) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target,true)
                        assertEquals(true,target.javaClass.getMethod("isValidLastTier").invoke(target))
                        runtime.javaClass.getMethod("bypassedInstalledCode",targetType).invoke(runtime,target)
                    }
                    assertEquals(0L,count())
                    assertEquals(beforeCalls,targets.map { it.javaClass.getMethod("getCallCount").invoke(it) })
                    for (row in corpus.asReversed()) {
                        val before = count()
                        call(row)
                        assertEquals(expectedEntries,count()-before,"$stage/$backend/$name exact first-installed entries")
                        assertEquals(targets,activeTargets(entry))
                        targets.forEach { assertEquals(true,it.javaClass.getMethod("isValidLastTier").invoke(it)) }
                        assertEquals(arguments,handoff.arguments.allocations); assertEquals(results,handoff.results.allocations)
                    }
                    assertEquals(beforeCalls,targets.map { it.javaClass.getMethod("getCallCount").invoke(it) })
                    assertEquals(0L,(program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                } finally { context.leave() }
            }
        }
    }

    @Test fun addressRangesAndPointerCellsAreCheckedBeforeEffects() {
        val allocation = PinnedMemory.allocate(32,8)
        assertTrue(allocation.isPinned)
        assertTrue(allocation.resized(40).isPinned)
        assertFalse(ManagedAllocation.mutable(32,8).isPinned)
        assertSame(allocation,ManagedByteArray.freezeGuest(allocation))
        val base = ManagedAddress.fromAllocation(allocation)
        base.fill(32,0x1ff)
        assertEquals(List(32) { 255L }, (0L..31L).map { allocation.readByte(it) })
        for (count in listOf(-1L,Long.MIN_VALUE,Long.MAX_VALUE,33L)) {
            assertThrows(RuntimeFault::class.java) { base.fill(count,0) }
            assertThrows(RuntimeFault::class.java) { base.moveTo(base,count) }
        }
        base.plus(32).fill(0,0)
        assertSame(base,base.moveTo(base,32))
        val pointer = base.plus(24)
        allocation.writeAddressByteOffset(0,pointer)
        base.moveTo(base.plus(8),8)
        assertSame(pointer,allocation.readAddressByteOffset(8))
        assertThrows(RuntimeFault::class.java) { base.plus(9).fill(2,0) }
        assertSame(pointer,allocation.readAddressByteOffset(8))
        base.plus(8).fill(8,0)
        assertSame(pointer,allocation.readAddressByteOffset(0))
        assertThrows(RuntimeFault::class.java) { allocation.readAddressByteOffset(8) }
        val immutable = ManagedAddress.fromHex("010203")
        assertThrows(RuntimeFault::class.java) { immutable.fill(0,0) }
        assertThrows(RuntimeFault::class.java) { base.moveTo(immutable,1) }
        assertThrows(RuntimeFault::class.java) { base.remainder(0) }
        assertEquals(-31L,base.difference(base.plus(31)))
        val raw = ManagedAddress.fromByteArray(ByteArray(4))
        assertThrows(RuntimeFault::class.java) { raw.difference(base) }
    }

    @Test fun shrinkPreservesAliasesAndDropsTruncatedLazyReferences() {
        val marker = Any()
        val storage = ManagedSmallArray.allocate(8,marker)
        val frozen = ManagedSmallArray.freeze(storage)
        val backing = storage.elements
        for (size in listOf(-1L,9L,Long.MAX_VALUE)) assertThrows(RuntimeFault::class.java) { storage.shrink(size) }
        assertTrue(backing.all { it === marker })
        storage.shrink(3)
        assertSame(backing,storage.elements); assertSame(storage,frozen)
        assertEquals(3L,ManagedSmallArray.size(frozen))
        assertSame(marker,ManagedSmallArray.read(frozen,2))
        assertTrue(backing.drop(3).all { it == null })
        assertThrows(RuntimeFault::class.java) { ManagedSmallArray.read(storage,3) }
        assertThrows(RuntimeFault::class.java) { ManagedSmallArray.write(storage,3,marker) }
        assertThrows(RuntimeFault::class.java) { ManagedSmallArray.slice(storage,2,2) }
        storage.shrink(0); assertTrue(backing.all { it == null })
        assertEquals(0L,ManagedSmallArray.size(storage))
    }

    @Test fun nativeFillAndMoveKeepBorrowLifetimeAndContextChecks() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64","x86_64"))
        context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val registry = ManagedNativeAllocations.current(null)
                val base = registry.malloc(32)
                try {
                    base.fill(32,511); base.writeWord8(0,7)
                    base.moveTo(base.plus(1),31)
                    assertEquals(7L,base.readWord8(1)); assertEquals(255L,base.readWord8(31))
                    assertEquals(31L,base.plus(31).difference(base))
                    assertThrows(RuntimeFault::class.java) { base.fill(33,0) }
                    context(true).use { other ->
                        other.initialize("thc"); other.enter()
                        try { assertThrows(RuntimeFault::class.java) { base.fill(1,0) } }
                        finally { other.leave() }
                    }
                } finally { registry.free(base) }
                assertThrows(RuntimeFault::class.java) { base.fill(0,0) }
                assertThrows(RuntimeFault::class.java) { base.difference(base) }
            } finally { context.leave() }
        }
    }
}
