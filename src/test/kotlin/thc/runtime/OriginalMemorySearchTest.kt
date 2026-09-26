// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.util.HexFormat

class OriginalMemorySearchTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-memory-search"
    private fun json(path: String) = Json.parse(File(root, path).readText())
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalMemorySearchAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") as Map<String, Any?> })
    private fun rows(): List<Map<String, Any?>> {
        val manifest = json("$prefix/manifest.json") as Map<String, Any?>
        assertEquals(true, manifest["strictAccepted"])
        assertEquals(392L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalMemorySearchAudit.hs", "compiler/test-fixtures/OriginalMemorySearchNative.hs",
            "test/haskell-fixtures/MemorySearchFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> listOf(
                "$prefix/$stage/core/OriginalMemorySearchAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val rows = json("$prefix/oracle.json") as List<Map<String, Any?>>
        assertEquals(mapOf("originalCompare" to 264, "originalFind" to 128), rows.groupingBy { it["entry"] }.eachCount())
        for (stage in listOf("pre", "post")) for (entry in listOf("originalCompare", "originalFind")) {
            val audit = json("$prefix/$stage/$entry.audit.json") as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
        }
        return rows
    }
    private fun context() = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun <T> inside(block: (Language) -> T): T = context().use { context ->
        context.initialize("thc"); context.enter()
        try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun load(language: Language, backend: String, source: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", type).invoke(runtime, target)
    }
    private fun address(values: Any?, kind: Int): ManagedAddress {
        val bytes = (values as List<Long>).map(Long::toByte).toByteArray()
        return when (kind) {
            0 -> ManagedAddress.fromByteArray(bytes)
            3 -> ManagedAddress.fromHex(HexFormat.of().formatHex(bytes))
            else -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(bytes.size.toLong(), 8, pinned = kind == 2))
                .also { result -> bytes.forEachIndexed { index, byte -> result.writeWord8(index.toLong(), byte.toLong()) } }
        }
    }

    @Test fun originalByteStringCallsMatchNativeBeforeAndOnFirstCompiledCalls() {
        val rows = rows()
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            val calls = OriginalStdioChecks.foreignCalls(source)
            assertEquals(MemorySearchOp.entries.toSet(), calls.map { call ->
                val metadata = call[6] as Map<*, *>
                CoreMemorySearchForeign.validate(metadata, (call[2] as List<List<Any?>>).map {
                    CoreRepresentations.metadata(it)?.get("rep") }, call[3] as List<*>, metadata["rep"])
            }.toSet())
            for (backend in listOf("ast", "bytecode")) inside { language ->
                for ((entry, examples) in rows.groupBy { it["entry"] as String }) {
                    val program = load(language, backend, CoreModules.reachable(source, entry) + ("instrument" to true))
                    val target = program.entryTarget(entry)
                    fun exercise(compiled: Boolean) {
                        for (kind in 0..3) for ((index, row) in examples.withIndex()) {
                            val base = address(row["left"], kind)
                            val left = base.plus(row["leftOffset"] as Long)
                            val second = if (entry == "originalCompare") address(row["right"], kind)
                                .plus(row["rightOffset"] as Long) else row["needle"]
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val returned = Calls.target(target, arrayOf(0L, left, second, row["count"]))
                            val label = "$stage/$backend/$entry/storage=$kind/$index/compiled=$compiled"
                            if (entry == "originalCompare") assertEquals(row["result"], (returned as Long).compareTo(0L).toLong(), label)
                            else {
                                val found = returned as ManagedAddress
                                val expected = row["result"] as Long
                                if (expected < 0) assertSame(ManagedAddress.nullAddress(), found, label)
                                else {
                                    assertTrue(found.sameLocation(base.plus(expected)), label)
                                    assertEquals(expected, found.difference(base), label)
                                    if (kind != 3) {
                                        base.writeWord8(expected, 23)
                                        assertEquals(23L, found.readWord8(0), "$label alias is original storage")
                                    }
                                }
                            }
                            if (compiled) {
                                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), label)
                                valid(target)
                            }
                            val handoff = language.handoffState.get()
                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                            assertEquals(0, handoff.results.retainedReferences())
                        }
                    }
                    exercise(false); compile(target); exercise(true)
                }
            }
        }
    }

    @Test fun searchPreservesBoundsOpaqueCellsAndNativeOwnerLifetime() {
        val nullAddress = ManagedAddress.nullAddress()
        assertEquals(0L, nullAddress.compareBytes(nullAddress, 0))
        assertSame(nullAddress, nullAddress.findByte(0, 0))
        val base = ManagedAddress.fromByteArray(byteArrayOf(0, -128, -1, 0, -1))
        assertTrue(base.findByte(-1, 5).sameLocation(base.plus(2)))
        assertTrue(base.findByte(511, 5).sameLocation(base.plus(2)))
        assertTrue(base.findByte(256, 5).sameLocation(base))
        assertTrue(base.plus(1).compareBytes(base, 1) > 0)
        for (count in listOf(-1L, 6L, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { base.findByte(0, count) }
            assertThrows(RuntimeFault::class.java) { base.compareBytes(base, count) }
        }
        assertThrows(RuntimeFault::class.java) { nullAddress.findByte(0, 1) }
        val cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8))
        cells.writeAddressElementIndex(1, base)
        assertThrows(RuntimeFault::class.java) { cells.findByte(0, 16) }
        assertThrows(RuntimeFault::class.java) { cells.compareBytes(cells, 16) }
        val literal = ManagedAddress.fromHex("00ff00")
        assertThrows(RuntimeFault::class.java) { literal.findByte(255, 3).writeWord8(0, 7) }
        if (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            lateinit var stale: ManagedAddress
            inside {
                val allocation = Language.currentState().nativeAllocations.malloc(8)
                repeat(8) { allocation.writeWord8(it.toLong(), it.toLong()) }
                val found = allocation.findByte(5, 8)
                assertTrue(found.sameLocation(allocation.plus(5)))
                found.writeWord8(0, 44)
                assertEquals(44L, allocation.readWord8(5))
                assertEquals(0L, allocation.compareBytes(ManagedAddress.fromByteArray(byteArrayOf(0,1,2,3,4,44,6,7)), 8))
                stale = found
                inside { assertThrows(RuntimeFault::class.java) { found.findByte(44, 1) } }
                Language.currentState().nativeAllocations.free(allocation)
                assertThrows(RuntimeFault::class.java) { found.findByte(44, 0) }
                assertThrows(RuntimeFault::class.java) { found.compareBytes(base, 0) }
            }
            inside { assertThrows(RuntimeFault::class.java) { stale.readWord8(0) } }
        }
    }

    @Test fun installedByteStringUnitIdentityRetainsTheSupportedPackageAndVersion() {
        val source = module("pre")
        val installed = listOf("bytestring-0.12.2.0-inplace", "bytestring-0.12.2.0-119b",
            "bytestring-0.12.2.0-5637", "bytestring-0.12.2.0-3f3f", "bytestring-0.12.2.0")
        val rejected = listOf("bytestring-0.12.1.0-119b", "bytestring-0.12.2.0-",
            "bytestring-0.12.2.0-119b-extra", "bytestring-0.12.2.0-119b extra",
            "bytestring-0.12.2.0-119b:forged", "bytestring-0.12.2.0-119b\n",
            "other-bytestring-0.12.2.0-119b", null)
        for (backend in listOf("ast", "bytecode")) inside { language ->
            for (original in OriginalStdioChecks.foreignCalls(source)) {
                val raw = OriginalStdioChecks.rawModule(original, source)
                for (unit in installed + rejected + "ghc-internal") {
                    val candidate = Json.parse(Json.stringify(raw)) as Map<String, Any?>
                    val call = OriginalStdioChecks.foreignCalls(candidate).single()
                    val descriptor = (call[6] as Map<*, *>)["foreignCall"] as Map<*, *>
                    val target = descriptor["target"] as MutableMap<String, Any?>
                    target["unit"] = unit
                    if (unit in installed || unit == "ghc-internal" && target["symbol"] == "memcmp") {
                        val program = load(language, backend, candidate)
                        val bytes = ManagedAddress.fromByteArray(byteArrayOf(1, 2))
                        val second: Any = if (target["symbol"] == "memcmp") bytes else 1L
                        val result = Calls.target(program.entryTarget("entry"), arrayOf(0L, bytes, second, 2L, Unit))
                        if (target["symbol"] == "memcmp") assertEquals(0L, result, "$backend/$unit")
                        else assertTrue((result as ManagedAddress).sameLocation(bytes), "$backend/$unit")
                    } else {
                        assertThrows(RuntimeFault::class.java, { load(language, backend, candidate) }, "$backend/$unit")
                    }
                }
            }
        }
    }

    @Test fun malformedOriginalABIsAndStateCarriersRejectBothBackends() {
        val source = module("pre")
        for (backend in listOf("ast", "bytecode")) inside { language ->
            for (original in OriginalStdioChecks.foreignCalls(source)) {
                val raw = OriginalStdioChecks.rawModule(original, source)
                for (variant in 0..6) {
                    val bad = Json.parse(Json.stringify(raw)) as Map<String, Any?>
                    val call = OriginalStdioChecks.foreignCalls(bad).single() as MutableList<Any?>
                    val descriptor = (call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                    when (variant) {
                        0 -> descriptor["safety"] = "safe"
                        1 -> descriptor["convention"] = "capi"
                        2 -> descriptor["arity"] = 3L
                        3 -> (descriptor["target"] as MutableMap<String, Any?>)["unit"] = "foreign"
                        4 -> (call[3] as MutableList<Any?>)[0] = true
                        5 -> (call[1] as MutableList<Any?>)[1] = "entry"
                        6 -> (descriptor["argumentReps"] as MutableList<Any?>)[2] = OriginalStdioFixtures.scalar("IntRep", false)
                    }
                    assertThrows(RuntimeFault::class.java, { load(language, backend, bad) }, "$backend/$variant")
                }
                val program = load(language, backend, raw)
                val target = program.entryTarget("entry")
                val symbol = (((original[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"]
                val bytes = ManagedAddress.fromByteArray(byteArrayOf(1,2))
                val second: Any = if (symbol == "memcmp") bytes else 1L
                assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, bytes, second, 2L, 17L)) }
            }
        }
    }
}
