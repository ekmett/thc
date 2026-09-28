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
import thc.CoreForeignArtifacts
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant

class OriginalTimeClockTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-time-clock"
    private fun json(name: String) = Json.parse(File(root, "$prefix/$name.json").readText()) as Map<String, Any?>
    private fun context(native: Boolean = true) = Context.newBuilder("thc").allowNativeAccess(native)
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun <T> inside(native: Boolean = true, block: (Language) -> T): T = context(native).use { context ->
        context.initialize("thc"); context.enter()
        try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun source(stage: String) = CoreModules.merge(listOf(json("linked"), json(stage)))
    private fun program(language: Language, backend: String, source: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, source + ("instrument" to true))
        else BytecodeProgram(language, source + ("instrument" to true))
    private fun link() = CoreForeignArtifacts.linked(json("linked"))!!
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        Truffle.getRuntime().let { runtime -> runtime.javaClass.getMethod("bypassedInstalledCode",
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target) }
    }
    private fun bytes(address: ManagedAddress) = (0L..31L).map(address::readWord8)
    private fun fields(address: ManagedAddress): Pair<Long, Long> {
        val view = ByteBuffer.wrap(bytes(address).map(Long::toByte).toByteArray()).order(ByteOrder.nativeOrder())
        return view.getLong(8) to view.getLong(16)
    }
    private fun allocation(kind: Int): ManagedAddress = when (kind) {
        0 -> ManagedAddress.fromByteArray(ByteArray(32))
        3 -> Language.currentState().nativeAllocations.malloc(32)
        else -> ManagedAddress.fromAllocation(ManagedAllocation.mutable(32, 8, kind == 2))
    }.also { for (offset in 0L..31L) it.writeWord8(offset, 0x5a) }
    private fun release(address: ManagedAddress, kind: Int) {
        if (kind == 3) Language.currentState().nativeAllocations.free(address)
    }

    @Test fun genuineNativeResultsGuardsErrnoAndFirstCompiledEntries() {
        val manifest = json("manifest")
        assertEquals(9L, manifest["nativeRows"])
        for (key in listOf("inputHashes", "artifactHashes", "interfaceHashes"))
            for ((path, hash) in manifest[key] as Map<String, String>) {
                val file = File(path).let { if (it.isAbsolute) it else File(root, path) }
                assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }, path)
            }
        val oracle = json("oracle")
        val rows = oracle["rows"] as List<Map<String, Any?>>
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) inside { language ->
            val state = Language.currentState()
            state.cbits().link(link())
            for (entry in listOf("originalConstant", "originalResolution", "originalTime")) {
                assertEquals(true, json("$stage-$entry.audit")["accepted"])
                val selected = CoreModules.reachable(source(stage), entry)
                val evidence = ArrayCoreEvidence(selected, entry)
                assertEquals(1, evidence.loweredGuestLambdas(evidence.root["expr"]).size)
                val expectedNames = if (entry == "originalConstant") setOf(entry, "\$woriginalConstant") else setOf(entry)
                assertEquals(expectedNames, evidence.bindings.map { it["name"] }.toSet())
                val executedRoots = evidence.bindings.sumOf { evidence.loweredGuestLambdas(it["expr"]).size }.toLong()
                assertEquals(if (entry == "originalConstant") 2L else 1L, executedRoots)
                if (entry == "originalConstant") assertEquals(listOf(evidence.bindings.single {
                    it["name"] == "\$woriginalConstant" }["id"]), evidence.globalReferences(evidence.root["expr"]))
                val p = program(language, backend, selected)
                val target = p.entryTarget(entry)
                fun exercise(compiled: Boolean) {
                    if (entry == "originalConstant") {
                        val before = p.diagnostics().getValue("compiledEntries") as Long
                        assertEquals(oracle["realtime"], Calls.target(target, arrayOf(0L, 0L)))
                        if (compiled) { assertEquals(before + executedRoots, p.diagnostics().getValue("compiledEntries")); valid(target) }
                    } else for (row in rows.filter { it["entry"] == entry }) for (kind in 0..3) {
                        val base = allocation(kind)
                        try {
                            state.stdio.captureForeignErrno(row["seedErrno"] as Long)
                            val before = p.diagnostics().getValue("compiledEntries") as Long
                            val earliest = Instant.now()
                            val result = Calls.target(target, arrayOf(0L, row["clock"],
                                if (row["null"] == true) ManagedAddress.nullAddress() else base.plus(8)))
                            val latest = Instant.now()
                            assertEquals(row["status"], result, "$stage/$backend/$entry/$kind/$row")
                            assertEquals(row["errno"], state.stdio.errno())
                            if (result == 0L && row["null"] == false) {
                                val (seconds, nanos) = fields(base)
                                assertTrue(seconds >= 0 && nanos in 0..999999999)
                                if (entry == "originalResolution") {
                                    assertEquals(row["seconds"], seconds); assertEquals(row["nanos"], nanos)
                                } else {
                                    // CLOCK_REALTIME is allowed to jump; the observation must lie between
                                    // these live enclosing wall-clock samples, in either direction.
                                    val actual = Instant.ofEpochSecond(seconds, nanos)
                                    assertTrue(actual >= minOf(earliest, latest) && actual <= maxOf(earliest, latest))
                                }
                                assertEquals(List(8) { 0x5aL }, bytes(base).take(8))
                                assertEquals(List(8) { 0x5aL }, bytes(base).drop(24))
                            } else assertEquals(List(32) { 0x5aL }, bytes(base), "failure never publishes a staged image")
                            if (compiled) { assertEquals(before + executedRoots, p.diagnostics().getValue("compiledEntries")); valid(target) }
                        } finally { release(base, kind) }
                    }
                }
                exercise(false); compile(target); exercise(true)
                assertEquals(0, language.handoffState.get().arguments.depth)
                assertEquals(0, language.handoffState.get().results.depth)
            }
        }
    }

    @Test fun baseAndTimeLibrariesCoexistWithoutSharingAbiOrLosingErrno() = inside {
        val cbits = Language.currentState().cbits()
        val time = link()
        val base = CoreForeignArtifacts.linked(json("base-linked"))!!
        for (record in listOf(base, time, base, time)) cbits.link(record)
        val cpu = cbits.capiZero(base.unit, base.abi.entries.single { it.value == "clock-id" }.key)
        val address = allocation(0)
        assertEquals(0L, cbits.capiWordAddress(base.unit,
            base.abi.entries.first { it.value == "clock-buffer" }.key, cpu, address.plus(8)).value)
        val realtime = cbits.capiZero(time.unit, time.abi.entries.single { it.value == "time-clock-id" }.key, true)
        assertEquals(json("oracle")["realtime"], realtime)
        val call = CapiCall(time.unit, time.abi.entries.single { it.value == "time-clock-resolution" }.key, false, true, true)
        assertEquals(0L, cbits.capiWordAddress(call, realtime, ManagedAddress.nullAddress()).value)
        val failure = cbits.capiWordAddress(call, -1, address.plus(8))
        assertEquals(-1L, failure.value)
        assertEquals((json("oracle")["rows"] as List<Map<String, Any?>>).first {
            it["entry"] == "originalResolution" && it["clock"] == -1L }["errno"], failure.errno)
        assertEquals(cpu, cbits.capiZero(base.unit, base.abi.entries.single { it.value == "clock-id" }.key))
    }

    @Test fun checkedOutputsStateAndDynamicClockIdsFailBeforeObservation() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            Language.currentState().cbits().link(link())
            val source = source("pre")
            val original = OriginalStdioChecks.foreignCalls(CoreModules.reachable(source, "originalTime")).single()
            val raw = OriginalStdioChecks.rawModule(original, source) + ("foreignLinks" to source["foreignLinks"])
            val target = program(language, backend, raw).entryTarget("entry")
            fun invoke(clock: Long, address: ManagedAddress, state: Any = Unit) = Calls.target(target, arrayOf(0L, clock, address, state))
            val realtime = json("oracle")["realtime"] as Long
            for (bad in listOf(ManagedAddress.nullAddress(), ManagedAddress.unownedNumeric(0x1000),
                ManagedAddress.fromHex("00".repeat(16)), ManagedAddress.fromByteArray(ByteArray(15))))
                assertThrows(RuntimeFault::class.java) { invoke(realtime, bad) }
            val cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(32, 8))
            val pointer = ManagedAddress.fromByteArray(byteArrayOf(42))
            cells.writeAddressElementIndex(1, pointer)
            assertThrows(RuntimeFault::class.java) { invoke(realtime, cells.plus(8)) }
            assertSame(pointer, cells.readAddressElementIndex(1))
            val good = allocation(0)
            for (bad in listOf(-2L, -8L, Int.MIN_VALUE.toLong(), 0x100000000L)) {
                assertThrows(RuntimeFault::class.java) { invoke(bad, good.plus(8)) }
                assertEquals(List(32) { 0x5aL }, bytes(good))
            }
            assertThrows(RuntimeFault::class.java) { invoke(realtime, good.plus(8), 17L) }
            assertEquals(List(32) { 0x5aL }, bytes(good))
            val native = allocation(3)
            Language.currentState().nativeAllocations.free(native)
            assertThrows(RuntimeFault::class.java) { invoke(realtime, native) }
            val other = allocation(3)
            try {
                inside { second ->
                    Language.currentState().cbits().link(link())
                    val otherTarget = program(second, backend, raw).entryTarget("entry")
                    assertThrows(RuntimeFault::class.java) { Calls.target(otherTarget, arrayOf(0L, realtime, other, Unit)) }
                }
                assertEquals(List(32) { 0x5aL }, bytes(other))
            } finally { release(other, 3) }
            for (position in listOf(0, 1, 2)) {
                val contradiction = OriginalStdioChecks.rawModule(original, source, position) +
                    ("foreignLinks" to source["foreignLinks"])
                assertThrows(RuntimeFault::class.java) { program(language, backend, contradiction) }
            }
        }
        inside(false) {
            assertThrows(RuntimeFault::class.java) { Language.currentState().cbits().link(link()) }
        }
    }

    @Test fun originalOwnerHeadersAndExactCapiIndicesRejectMutations() {
        val original = json("linked")
        val metadata = original["foreignLink"] as Map<String, Any?>
        for (change in listOf(metadata + ("schema" to 2L), metadata - "headerHashes",
            metadata + ("headerHashes" to emptyList<Any?>()), metadata + ("unit" to "time-1.16-inplace"),
            metadata + ("abi" to (metadata["abi"] as List<Map<String, Any?>>).reversed().mapIndexed { index, item ->
                item + ("kind" to listOf("time-clock-id", "time-clock-resolution", "time-clock-time")[index]) })))
            assertThrows(IllegalArgumentException::class.java) { CoreForeignArtifacts.linked(original + ("foreignLink" to change)) }
        val source = source("pre")
        for (call in OriginalStdioChecks.foreignCalls(CoreModules.reachable(source, "originalTime"))) {
            val meta = call[6] as Map<String, Any?>
            val descriptor = meta["foreignCall"] as Map<String, Any?>
            val operands = (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
            fun validate(metadata: Map<String, Any?>) = CoreCapiForeign.validate(metadata, operands,
                call[3] as List<*>, meta["rep"], listOf(link()))
            assertNotNull(validate(meta))
            assertThrows(RuntimeFault::class.java) { validate(meta + ("foreignCall" to (descriptor + ("safety" to "safe")))) }
            CoreCapiForeign.validateHead(call[1] as List<Any?>, false)
            assertThrows(RuntimeFault::class.java) { CoreCapiForeign.validateHead(call[1] as List<Any?>, true) }
            val admitted = validate(meta)!!
            val state = CoreRepresentation(CoreKind.VOID, present = true, primReps = emptyList())
            val number = CoreRepresentation(CoreKind.LONG, present = true, primReps = listOf("IntRep"))
            assertThrows(RuntimeFault::class.java) { CoreCapiForeign.validateOperand(admitted, 2, number, null) }
            assertThrows(RuntimeFault::class.java) { CoreCapiForeign.validateOperand(admitted, 2, state, number) }
        }
    }

    @Test fun firstClassForeignIdRemainsAnExplicitRejectedShapeWithItsWorkerRetained() {
        val closed = json("specialized-closed")
        val names = (closed["bindings"] as List<Map<String, Any?>>).map { it["name"] }
        assertTrue(names.contains("\$woriginalId"), "ordinary -O2 ignored-argument worker must survive specialization")
        for (stage in listOf("pre", "post")) {
            val audit = json("$stage-originalId.audit")
            assertEquals(false, audit["accepted"])
            assertEquals(emptyList<Any?>(), audit["issues"])
            val missing = audit["missingGlobals"] as List<*>
            assertEquals(1, missing.size)
            assertTrue(missing.single().toString().contains("HSzuCLOCKzuREALTIME"))
            assertThrows(IllegalArgumentException::class.java) { CoreModules.reachable(source(stage), "originalId", strictLink = true) }
        }
    }
}
