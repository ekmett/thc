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
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class OriginalSigsetTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val prefix = "build/original-sigset"
    private val names = listOf("originalSigEmpty", "originalSigAdd")
    private val operations = listOf(OriginalStdioOp.SIGEMPTYSET, OriginalStdioOp.SIGADDSET)
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun module(stage: String) = CoreModules.merge(listOf("OriginalSigsetAudit", "THC.InterfaceClosure")
        .map { json("$prefix/$stage/core/$it.json") })
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun document() = SigsetImage::class.java.getResourceAsStream("/thc/native/sigset-abi.json")!!.use {
        Json.parse(it.reader().readText()) as Map<*, *>
    }
    private fun parse(value: Any?) = SigsetImage.parse(value, System.getProperty("os.name"), System.getProperty("os.arch"))
    private fun context() = Context.newBuilder("thc").allowIO(IOAccess.NONE).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val callee = call.currentCallTarget as? RootCallTarget ?: continue
                if (callee.rootNode is GuestRoot) visit(callee)
            }
            result.add(target)
        }
        visit(entry); return result
    }
    private fun validate(call: List<Any?>) = CoreOriginalStdio.validate(call[6],
        (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") },
        call[3] as List<*>, (call[6] as Map<*, *>)["rep"])

    @Test fun nativeImagesErrnoAndExactFirstInstalledTargetsMatchBothBackends() {
        val manifest = json("$prefix/manifest.json")
        assertEquals(1L, manifest["schema"]); assertEquals("linux", manifest["platform"])
        assertEquals(true, manifest["supported"]); assertEquals(names, manifest["entries"])
        assertEquals(true, manifest["strictAccepted"]); assertEquals(false, manifest["runtimeVerified"])
        assertEquals(false, manifest["installedArtifactsHashed"]); assertEquals(532L, manifest["nativeRows"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/OriginalSigsetAudit.hs",
            "compiler/test-fixtures/OriginalSigsetNative.hs", "test/haskell-fixtures/OriginalSigsetFixtures.hs",
            "scripts/audit-core.py", "scripts/core_original_foreign.py", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json", "$prefix/native/oracle") +
            listOf("pre", "post").flatMap { stage -> names.map { "$prefix/$stage/$it.audit.json" } +
                listOf("$prefix/$stage/core/OriginalSigsetAudit.json", "$prefix/$stage/core/THC.InterfaceClosure.json") }, "$prefix/")
        val oracle = json("$prefix/oracle.json")
        val size = (oracle["size"] as Long).toInt()
        val rows = oracle["rows"] as List<List<Any?>>
        assertEquals(532, rows.size)
        val signals = listOf(Int.MIN_VALUE.toLong(), -1L) + (0L..128L) + Int.MAX_VALUE.toLong()
        val cases = listOf(0L, 90L, 165L, 255L).flatMap { fill ->
            listOf(listOf(0L, fill, 0L)) + signals.map { listOf(1L, fill, it) }
        }
        assertEquals(cases, rows.map { it.take(3) }, "missing, duplicate or reordered native cases")
        val doc = document(); val layout = doc["sigset"] as Map<*, *>
        assertEquals(oracle["size"], layout["size"])
        assertEquals(OriginalStdioChecks.hex(MessageDigest.getInstance("SHA-256")
            .digest(File(root, "src/main/c/sigset-abi-probe.c").readBytes())), doc["sourceSha256"])
        val bits = layout["signalBits"] as List<Long>
        for (signal in 1..bits.size) {
            val row = rows.single { it[0] == 1L && it[1] == 0L && it[2] == signal.toLong() }
            assertEquals(if (bits[signal - 1] < 0) -1L else 0L, row[3])
        }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val stdio = Language.currentState(null).stdio
                for ((index, name) in names.withIndex()) {
                    val audit = json("$prefix/$stage/$name.audit.json")
                    assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
                    assertEquals(emptyList<Any?>(), audit["missingGlobals"])
                    assertEquals(listOf(operations[index].symbol), (audit["foreignCalls"] as List<Map<*, *>>).map { it["symbol"] })
                    val linked = CoreModules.reachable(module(stage), name) + ("instrument" to true)
                    val binding = (linked["bindings"] as List<Map<String, Any?>>).single()
                    assertEquals(operations[index], validate(OriginalStdioChecks.foreignCalls(binding["expr"]).single()))
                    assertEquals(2, OriginalStdioChecks.nodes(binding["expr"]).count { it.firstOrNull() == "lam" })
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val entry = program.entryTarget(name)
                    var retained = emptyList<RootCallTarget>()
                    fun exercise(compiled: Boolean) {
                        for (row in rows.filter { it[0] == index.toLong() }) {
                            val bytes = ByteArray(size + 16) { 77 }
                            bytes.fill((row[1] as Long).toByte(), 8, 8 + size)
                            val address = ManagedAddress.fromByteArray(bytes).plus(8)
                            val args = if (index == 0) arrayOf(0L, address) else arrayOf(0L, address, row[2])
                            stdio.captureForeignErrno(0)
                            repeat(2) {
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(row[3], Calls.target(entry, args), "$stage/$backend/$name/${row.take(5)}")
                                assertEquals(row[4], stdio.errno())
                                assertArrayEquals((row[5] as List<Long>).map { it.toByte() }.toByteArray(), bytes)
                                if (compiled) {
                                    assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                    assertEquals(retained, targets(entry)); retained.forEach(::valid)
                                }
                                val handoff = language.handoffState.get()
                                assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                                assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                            }
                        }
                    }
                    exercise(false)
                    retained = targets(entry); assertEquals(2, retained.size)
                    for (target in retained) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                    }
                    exercise(true)
                }
            } finally { context.leave() }
        }
    }

    @Test fun strictOriginalProofsAndMemoryPreflightsRejectBeforeImageOrErrnoEffects() {
        val source = module("pre")
        val calls = OriginalStdioChecks.foreignCalls(source)
        assertEquals(operations.toSet(), calls.map { validate(it)!! }.toSet())
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val stdio = Language.currentState(null).stdio
                fun load(raw: Map<String, Any?>): ExecutableProgram = if (backend == "ast") Program(language, raw) else BytecodeProgram(language, raw)
                val abi = parse(document())
                for (call in calls) {
                    val operation = validate(call)!!
                    val raw = OriginalStdioChecks.rawModule(call, source)
                    val target = load(raw).entryTarget("entry")
                    val bytes = ByteArray(abi.size.toInt() + 16) { 90 }
                    val address = ManagedAddress.fromByteArray(bytes).plus(8)
                    fun invoke(pointer: ManagedAddress, signal: Long = 1, state: Any? = Unit): Any? =
                        Calls.target(target, if (operation == OriginalStdioOp.SIGADDSET) arrayOf(0L, pointer, signal, state)
                            else arrayOf(0L, pointer, state))
                    stdio.captureForeignErrno(123)
                    assertThrows(RuntimeFault::class.java) { invoke(address, state = 9L) }
                    for (index in operation.arguments.indices)
                        assertThrows(RuntimeFault::class.java) { load(OriginalStdioChecks.rawModule(call, source, index)) }
                    for (bad in listOf(ManagedAddress.nullAddress(), address.plus(abi.size),
                        ManagedAddress.fromByteArray(ByteArray(abi.size.toInt() - 1)), ManagedAddress.fromHex("00".repeat(abi.size.toInt()))))
                        assertThrows(RuntimeFault::class.java) { invoke(bad) }
                    val allocation = PinnedMemory.allocate(abi.size, 8)
                    val pinned = ManagedAddress.fromAllocation(allocation)
                    pinned.writeAddressElementIndex(0, address)
                    assertThrows(RuntimeFault::class.java) { invoke(pinned) }
                    assertSame(address, pinned.readAddressElementIndex(0))
                    val writablePinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(abi.size, 8))
                    for (index in 0 until abi.size) writablePinned.writeWord8(index, 90)
                    if (operation == OriginalStdioOp.SIGADDSET)
                        for (bad in listOf(1L shl 32, Int.MIN_VALUE.toLong() - 1, Long.MIN_VALUE, Long.MAX_VALUE))
                            assertThrows(RuntimeFault::class.java) { invoke(address, bad) }
                    assertEquals(123L, stdio.errno()); assertTrue(bytes.all { it == 90.toByte() })
                    assertEquals(0L, invoke(address))
                    assertEquals(123L, stdio.errno()) // success preserves sticky errno
                    assertEquals(0L, invoke(writablePinned))
                    assertEquals(123L, stdio.errno())
                    for (index in 0 until abi.size)
                        assertEquals(address.readWord8(index), writablePinned.readWord8(index))
                    if (operation == OriginalStdioOp.SIGADDSET) {
                        val saved = bytes.clone()
                        assertEquals(-1L, invoke(address, 0))
                        assertEquals((document()["sigset"] as Map<*, *>)["invalidErrno"], stdio.errno())
                        assertArrayEquals(saved, bytes)
                        val error = stdio.errno()
                        assertEquals(0L, invoke(address, 1)); assertEquals(error, stdio.errno())
                    }
                    for (wrong in listOf("IntRep", "Word32Rep")) {
                        val forged = copy(call) as MutableList<Any?>
                        val meta = forged[6] as MutableMap<String, Any?>
                        val descriptor = meta["foreignCall"] as MutableMap<String, Any?>
                        for (record in listOf(meta["rep"], descriptor["resultRep"])) {
                            val tuple = record as MutableMap<String, Any?>
                            tuple["primReps"] = listOf(wrong)
                            ((tuple["components"] as List<MutableMap<String, Any?>>)[1])["primReps"] = listOf(wrong)
                        }
                        assertThrows(RuntimeFault::class.java) { validate(forged) }
                    }
                    val malformed = copy(raw) as Map<String, Any?>
                    (OriginalStdioChecks.foreignCalls(malformed).single()[1] as MutableList<Any?>)[1] = 17L
                    assertThrows(RuntimeFault::class.java) { load(malformed) }
                }
            } finally { context.leave() }
        }
        context().use { second ->
            second.initialize("thc"); second.enter()
            try { assertEquals(0L, Language.currentState(null).stdio.errno()) } finally { second.leave() }
        }
    }

    @Test fun closedNativeImageTableRejectsWrongHostMissingAndMalformedFields() {
        val doc = document(); parse(doc)
        for ((key, value) in listOf("schema" to true, "schema" to 1.0, "system" to "Darwin",
            "architecture" to "aarch64", "target" to "x86_64-unknown-linux-gnux32", "extra" to 0L))
            assertThrows(RuntimeFault::class.java) { parse(doc + (key to value)) }
        val layout = doc["sigset"] as Map<*, *>
        for (key in layout.keys) {
            assertThrows(RuntimeFault::class.java) { parse(doc + ("sigset" to (layout - key))) }
            for (bad in listOf(null, true, 1.0, "1"))
                assertThrows(RuntimeFault::class.java) { parse(doc + ("sigset" to (layout + (key to bad)))) }
        }
        for ((key, bad) in listOf("size" to 0L, "size" to 4097L, "alignment" to 4L, "invalidErrno" to 0L,
            "clearBytes" to listOf(0L,0L), "clearBytes" to listOf(1L,0L), "clearBytes" to listOf(-1L),
            "clearBytes" to listOf(4096L), "clearBytes" to emptyList<Long>(), "signalBits" to listOf(-1L),
            "signalBits" to listOf(-2L), "signalBits" to listOf(0L,0L), "signalBits" to listOf(32768L),
            "signalBits" to List(129) { it.toLong() }, "signalBits" to emptyList<Long>()))
            assertThrows(RuntimeFault::class.java) { parse(doc + ("sigset" to (layout + (key to bad)))) }
        val image = layout + mapOf("clearBytes" to listOf(0L), "signalBits" to listOf(8L))
        assertThrows(RuntimeFault::class.java) { parse(doc + ("sigset" to image)) }
    }
}
