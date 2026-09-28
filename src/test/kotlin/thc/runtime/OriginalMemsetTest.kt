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

class OriginalMemsetTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-memset"
    private fun json(path: String) = Json.parse(File(root, path).readText())
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalMemsetAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") as Map<String, Any?> })
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
    private val nativeSupported = System.getProperty("os.name") == "Linux" &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64")

    private fun rows(): List<Map<String, Any?>> {
        val manifest = json("$prefix/manifest.json") as Map<String, Any?>
        assertEquals(true, manifest["strictAccepted"])
        assertEquals(198L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/OriginalMemsetAudit.hs", "compiler/test-fixtures/OriginalMemsetNative.hs",
            "test/haskell-fixtures/MemsetFixtures.hs", "scripts/core_original_foreign.py"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("pre", "post").flatMap { stage -> listOf(
                "$prefix/$stage/core/OriginalMemsetAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        for (stage in listOf("pre", "post")) {
            val audit = json("$prefix/$stage/originalFill.audit.json") as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any?>(), audit["issues"])
            assertEquals(emptyList<Any?>(), audit["missingGlobals"])
        }
        return (json("$prefix/oracle.json") as List<Map<String, Any?>>).also { assertEquals(198, it.size) }
    }

    private fun bytes(address: ManagedAddress, size: Int) = List(size) { address.readWord8(it.toLong()) }
    private fun address(values: List<Long>, kind: Int): ManagedAddress = when (kind) {
        0 -> ManagedAddress.fromByteArray(values.map(Long::toByte).toByteArray())
        3 -> Language.currentState().nativeAllocations.malloc(values.size.toLong())
        else -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(values.size.toLong(), 8, kind == 2))
    }.also { address -> values.forEachIndexed { index, value -> address.writeWord8(index.toLong(), value) } }

    @Test fun originalWrapperAndRawCIntCallMatchNativeOnFirstCompiledEntries() {
        val rows = rows()
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            val original = OriginalStdioChecks.foreignCalls(source).single()
            val metadata = original[6] as Map<*, *>
            assertTrue(CoreMemsetForeign.validate(metadata, (original[2] as List<List<Any?>>).map {
                CoreRepresentations.metadata(it)?.get("rep") }, original[3] as List<*>, metadata["rep"]))
            val evidence = ArrayCoreEvidence(source, "originalFill")
            val lambda = evidence.root["expr"] as List<Any?>
            evidence.immediateStateLambda(lambda[2])
            assertEquals(1, evidence.bindings.size)
            assertEquals(2, evidence.guestLambdas(lambda).size)
            val executedRoots = evidence.loweredGuestLambdas(lambda).size
            assertEquals(1, executedRoots, "exact immediate State# application is in-frame")
            for (backend in listOf("ast", "bytecode")) inside { language ->
                for (raw in listOf(false, true)) {
                    val selected = if (raw) OriginalStdioChecks.rawModule(original, source)
                        else CoreModules.reachable(source, "originalFill")
                    val program = load(language, backend, selected + ("instrument" to true))
                    val target = program.entryTarget(if (raw) "entry" else "originalFill")
                    fun exercise(compiled: Boolean) {
                        for (kind in 0..if (nativeSupported) 3 else 2) for ((index, row) in rows.withIndex()) {
                            val values = row["before"] as List<Long>
                            val base = address(values, kind)
                            try {
                                val destination = base.plus(row["offset"] as Long)
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                // Raw calls retain GHC's genuine original descriptor while bypassing
                                // the wrapper's Word8 narrowing, covering the entire CInt corpus.
                                val args = arrayOf<Any?>(0L, destination, if (raw) (row["value"] as Long).toInt() else row["value"], row["count"])
                                val result = if (raw) callScalarTestTarget(target, args + Unit) else Calls.target(target, args)
                                val label = "$stage/$backend/raw=$raw/storage=$kind/$index/compiled=$compiled"
                                assertSame(destination, result, label)
                                assertEquals(row["returned"], (result as ManagedAddress).difference(base), label)
                                assertEquals(row["after"], bytes(base, values.size), label)
                                if (compiled) {
                                    assertEquals(before + executedRoots,
                                        (program.diagnostics().getValue("compiledEntries") as Number).toLong(), label)
                                    valid(target)
                                }
                                val state = language.handoffState.get()
                                assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
                                assertEquals(0, state.results.retainedReferences())
                            } finally {
                                if (kind == 3) Language.currentState().nativeAllocations.free(base)
                            }
                        }
                    }
                    exercise(false)
                    compile(target) // Restore the shared entry stub before the first measured call; no warm-up invocation.
                    exercise(true)
                }
            }
        }
    }

    @Test fun boundsOwnershipAndPointerCellInvalidationUseTheActualForeignLeaf() {
        val source = module("pre")
        val raw = OriginalStdioChecks.rawModule(OriginalStdioChecks.foreignCalls(source).single(), source)
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val target = load(language, backend, raw).entryTarget("entry")
            fun fill(address: ManagedAddress, value: Int, count: Long, state: Any = Unit): Any? =
                callScalarTestTarget(target, arrayOf(0L, address, value, count, state))
            for (kind in 0..if (nativeSupported) 3 else 2) {
                val values = List(16) { it.toLong() }
                val base = address(values, kind)
                try {
                    for (count in listOf(-1L, 16L, Long.MAX_VALUE)) {
                        assertThrows(RuntimeFault::class.java) { fill(base.plus(1), 255, count) }
                        assertEquals(values, bytes(base, 16), "$backend/$kind rejection precedes every write")
                    }
                    assertThrows(RuntimeFault::class.java) {
                        Calls.target(target, arrayOf(0L, base, 0L, 1L, 17L))
                    }
                    assertThrows(RuntimeFault::class.java) { fill(base, 0, 1, 17L) }
                    assertEquals(values, bytes(base, 16))
                    val end = base.plus(16)
                    assertSame(end, fill(end, -1, 0))
                    assertEquals(values, bytes(base, 16))
                } finally {
                    if (kind == 3) Language.currentState().nativeAllocations.free(base)
                }
            }
            for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(0x1000),
                ManagedAddress.fromHex("001122"))) {
                assertThrows(RuntimeFault::class.java) { fill(bad, 0, 1) }
            }
            val cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(24, 8))
            val opaque = ManagedAddress.fromByteArray(byteArrayOf(9))
            for (index in 0L..2L) cells.writeAddressElementIndex(index, opaque)
            val partial = assertThrows(RuntimeFault::class.java) { fill(cells.plus(9), 0, 1) }
            assertEquals("Partial overwrite of a managed pointer cell", partial.message)
            for (index in 0L..2L) assertSame(opaque, cells.readAddressElementIndex(index))
            fill(cells.plus(8), 255, 8) // Whole-cell overwrites clear opaque reference authority.
            assertEquals(List(8) { 255L }, bytes(cells.plus(8), 8))
            assertEquals("No managed pointer cell at this address",
                assertThrows(RuntimeFault::class.java) { cells.readAddressElementIndex(1) }.message)
            assertSame(opaque, cells.readAddressElementIndex(2))
            fill(cells.plus(16), 0, 8)
            assertEquals(List(8) { 0L }, bytes(cells.plus(16), 8))
            assertEquals("No managed pointer cell at this address",
                assertThrows(RuntimeFault::class.java) { cells.readAddressElementIndex(2) }.message)
            assertSame(opaque, cells.readAddressElementIndex(0))
            if (nativeSupported) {
                val owned = Language.currentState().nativeAllocations.malloc(8)
                inside { other ->
                    val foreignTarget = load(other, backend, raw).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) {
                        callScalarTestTarget(foreignTarget, arrayOf(0L, owned, 0, 1L, Unit))
                    }
                }
                Language.currentState().nativeAllocations.free(owned)
                assertThrows(RuntimeFault::class.java) { fill(owned, 0, 0) }
            }
        }
    }

    @Test fun exactOriginalDescriptorAndLexicalOwnershipAreRequired() {
        val source = module("pre")
        val original = OriginalStdioChecks.foreignCalls(source).single()
        val raw = OriginalStdioChecks.rawModule(original, source)
        for (backend in listOf("ast", "bytecode")) inside { language ->
            for (unit in listOf("bytestring-0.12.2.0", "bytestring-0.12.2.0-inplace", "bytestring-0.12.2.0-119b")) {
                val candidate = Json.parse(Json.stringify(raw)) as Map<String, Any?>
                val call = OriginalStdioChecks.foreignCalls(candidate).single()
                val descriptor = (call[6] as Map<*, *>)["foreignCall"] as Map<*, *>
                (descriptor["target"] as MutableMap<String, Any?>)["unit"] = unit
                val target = load(language, backend, candidate).entryTarget("entry")
                val address = ManagedAddress.fromByteArray(byteArrayOf(4))
                assertSame(address, callScalarTestTarget(target, arrayOf(0L, address, -1, 1L, Unit)))
                assertEquals(255L, address.readWord8(0))
            }
            for (variant in 0..14) {
                val bad = Json.parse(Json.stringify(raw)) as Map<String, Any?>
                val call = OriginalStdioChecks.foreignCalls(bad).single() as MutableList<Any?>
                val descriptor = (call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>
                val target = descriptor["target"] as MutableMap<String, Any?>
                when (variant) {
                    0 -> descriptor["safety"] = "safe"
                    1 -> descriptor["convention"] = "capi"
                    2 -> descriptor["arity"] = 3L
                    3 -> target["unit"] = "ghc-internal"
                    4 -> target["unit"] = "bytestring-0.12.1.0-inplace"
                    5 -> target["unit"] = "bytestring-0.12.2.0-119b-extra"
                    6 -> (call[3] as MutableList<Any?>)[0] = true
                    7 -> (call[1] as MutableList<Any?>)[1] = "entry"
                    8 -> (descriptor["argumentReps"] as MutableList<Any?>)[2] = OriginalStdioFixtures.scalar("WordRep", false)
                    9 -> (descriptor["argumentReps"] as MutableList<Any?>)[1] = OriginalStdioFixtures.scalar("IntRep", false)
                    10 -> descriptor["suppliedArity"] = 3L
                    11 -> target["isFunction"] = false
                    12 -> (call[1] as MutableList<Any?>)[1] = ((call[2] as List<List<Any?>>)[0])[1]
                    13 -> (descriptor["resultRep"] as MutableMap<String, Any?>)["primReps"] = listOf("IntRep")
                    14 -> descriptor["schema"] = true
                }
                assertThrows(RuntimeFault::class.java, { load(language, backend, bad) }, "$backend/$variant")
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["ast", "bytecode"])
    fun lexicalJoinsCannotClaimOriginalMemsetAuthority(backend: String) {
        for (stage in listOf("pre", "post")) {
            val source = module(stage)
            inside { language ->
                for (original in OriginalStdioChecks.foreignCalls(source)) {
                    fun shadowed(claimForeign: Boolean): Map<String, Any?> {
                        val raw = Json.parse(Json.stringify(OriginalStdioChecks.rawModule(original, source)))
                            as Map<String, Any?>
                        val entry = (raw["bindings"] as List<Map<String, Any?>>).single()
                        val lambda = entry["expr"] as List<Any?>
                        val body = lambda[2] as MutableList<Any?>
                        val call = body[1] as MutableList<Any?>
                        val metadata = call[6] as Map<String, Any?>
                        val tuple = metadata["rep"] as Map<String, Any?>
                        val fields = tuple["components"] as List<Map<String, Any?>>
                        val operands = call[2] as List<List<Any?>>
                        val closure = OriginalStdioFixtures.closure()
                        val formals = operands.mapIndexed { index, operand ->
                            mapOf("id" to "join$index", "lifted" to false,
                                "rep" to CoreRepresentations.metadata(operand)!!.getValue("rep"))
                        }
                        val value = listOf("var", "join0", mapOf("rep" to fields[1]))
                        val pair = listOf("app", listOf("con", "T2", 2, mapOf("rep" to closure)),
                            listOf(listOf("var", "join3", mapOf("rep" to fields[0])), value),
                            listOf(false, false), false, false, mapOf("rep" to (tuple + ("evaluated" to true))))
                        val join = mapOf("id" to (call[1] as List<*>)[1], "name" to "shadowedMemset",
                            "lifted" to true, "rep" to closure,
                            "expr" to listOf("lam", formals, pair, mapOf("rep" to closure, "resultRep" to tuple)),
                            "joinValueArity" to 4L, "joinResultRep" to tuple, "info" to mapOf("joinArity" to 4L))
                        if (!claimForeign) call[6] = metadata - "foreignCall"
                        CoreJoins.validate(listOf(join), call, false)
                        body[1] = listOf("let", false, listOf(join), call, mapOf("rep" to tuple))
                        return raw
                    }
                    // The same well-formed lexical join works without claiming
                    // the original foreign declaration's authority.
                    val bytes = ManagedAddress.fromByteArray(byteArrayOf(1, 2))
                    val second = 9
                    val target = load(language, backend, shadowed(false)).entryTarget("entry")
                    val result = callScalarTestTarget(target, arrayOf(0L, bytes, second, 1L, Unit))
                    assertSame(bytes, result)
                    val failure = assertThrows(RuntimeFault::class.java) {
                        load(language, backend, shadowed(true))
                    }
                    assertTrue(failure.message.orEmpty().contains("unresolved original FCallId required"),
                        "$stage/$backend rejects the shadowed head specifically: " + failure.message)
                }
            }
        }
    }
}
