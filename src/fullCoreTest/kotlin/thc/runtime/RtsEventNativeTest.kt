// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import thc.ContextProfile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RtsEventNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/rts-event")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, backend: String, entry: String, stage: String): ExecutableProgram {
        val module = CoreModules.merge(listOf(json(File(directory, "$entry-$stage.json"))))
        return if (backend == "ast") Program(language, module, true) else BytecodeProgram(language, module)
    }
    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::calls)
        is List<*> -> (if (value.firstOrNull() == "app" &&
            (value.lastOrNull() as? Map<*, *>)?.get("foreignCall") is Map<*, *>) listOf(value as List<Any?>)
            else emptyList()) + value.flatMap(::calls)
        else -> emptyList()
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
    }

    @Test fun originalPipeAndEventfdLifecyclesMatchNativeAtFirstCompiledEntry() {
        val manifest = json(File(directory, "manifest.json"))
        val entries = (manifest["descriptorEntries"] as List<List<String>>).associate { it[0] to it[1] }
        val rows = (json(File(directory, "oracle.json"))["descriptorRows"] as List<List<Any?>>).groupBy { it[0] as String }
        assertEquals(setOf("eventfdCycle", "pipeCycle", "epollCycle", "epollSafeCycle", "pollCycle", "pollSafeCycle", "controlCycle"), entries.keys)
        assertEquals(entries.keys, rows.keys)
        assertEquals(21, rows.values.sumOf { it.size })
        for (stage in listOf("pre", "post")) for ((entry, cases) in rows)
            for (backend in listOf("ast", "bytecode"))
                NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST).use { context ->
                context.enter()
                try {
                    assertEquals(true, json(File(directory, "$entry-$stage.audit.json"))["accepted"])
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val module = json(File(directory, "$entry-$stage.json"))
                    for (call in calls(module)) {
                        val meta = call.last() as Map<String, Any?>
                        val descriptor = meta["foreignCall"] as Map<String, Any?>
                        val target = descriptor["target"] as Map<String, Any?>
                        val operands = (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
                        assertNotNull(CoreOriginalStdio.validate(meta, operands, call[3] as List<*>, meta["rep"]))
                        assertThrows(RuntimeFault::class.java) {
                            CoreOriginalStdio.validate(meta + ("foreignCall" to (descriptor +
                                ("target" to (target + ("unit" to "main"))))), operands, call[3] as List<*>, meta["rep"])
                        }
                        if (CoreOriginalStdio.validate(meta, operands, call[3] as List<*>, meta["rep"])!!.eventManager) {
                            for (bad in listOf(descriptor + ("arity" to 0L), descriptor + ("safety" to "interruptible"),
                                descriptor + ("target" to (target + ("isFunction" to false))),
                                descriptor + ("resultRep" to mapOf("kind" to "long", "primReps" to listOf("IntRep")))))
                                assertThrows(RuntimeFault::class.java) {
                                    CoreOriginalStdio.validate(meta + ("foreignCall" to bad), operands, call[3] as List<*>, meta["rep"])
                                }
                            val wrong = (operands[0] as Map<String, Any?>) + ("primReps" to listOf("IntRep"))
                            assertThrows(RuntimeFault::class.java) {
                                CoreOriginalStdio.validate(meta, listOf(wrong) + operands.drop(1), call[3] as List<*>, meta["rep"])
                            }
                        }
                    }
                    val p = program(language, backend, entry, stage)
                    val callable = context.asValue(EntryValue(p, entries.getValue(entry), 1))
                    fun invoke(row: List<Any?>) {
                        val input = (row[1] as Number).toLong()
                        val expected = (row[2] as Number).toLong()
                        val model = when (entry) {
                            "eventfdCycle" -> 2 * input + 5
                            "pipeCycle" -> input + 13
                            "epollCycle", "epollSafeCycle" -> input + 31
                            "pollCycle", "pollSafeCycle" -> input + 17
                            else -> input
                        }
                        assertEquals(model, expected)
                        assertEquals(expected, callable.execute(input).asLong(), "$stage/$backend/$entry/$input")
                        released(language)
                    }
                    cases.forEach(::invoke)
                    assertTrue(callable.invokeMember("compile").asBoolean())
                    val host = p.hostEntryTarget(1)
                    val original = p.entryTarget(entries.getValue(entry))
                    // Public compilation follows the active split, not merely
                    // the closure's original call-target identity.
                    val installed = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                        .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                        .ifEmpty { listOf(original) } + host
                    fun installed() = installed.forEach { target ->
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target),
                            "$stage/$backend/$entry/${target.rootNode.name} remains installed")
                    }
                    installed()
                    for (row in cases) {
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        invoke(row)
                        assertEquals(before + 1, (p.diagnostics().getValue("compiledEntries") as Number).toLong(),
                            "$stage/$backend/$entry must enter its installed root immediately")
                        installed()
                    }
                } finally { context.leave() }
            }
    }

    @Test fun astSafeCompletionPublishesOnceAndHonorsEveryLogicalMask() {
        for (mask in MaskingState.entries) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p = program(language, "ast", "capabilities", "post")
                val root = p.entryTarget("originalCapabilities").rootNode as GuestRoot
                val leaf = NodeUtil.findAllNodeInstances(root, RtsEventForeignExpression::class.java).single()
                var evaluations = 0
                // Isolate this genuine lowered safe-return cut from entry polls.
                // Replacing its input lets us observe evaluation/replay separately.
                (leaf.children.first() as Expr).replace(object : Expr() {
                    override fun execute(frame: VirtualFrame): Any { evaluations++; return 3 }
                })
                val threads = Language.currentState().threads
                threads.enterCurrent()
                try {
                    SynchronousMasking.set(root, mask)
                    val self = threads.currentIdentity()
                    val incoming = CompletableFuture.supplyAsync { threads.send(self, "after setter") }.get(5, TimeUnit.SECONDS)
                    val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), root.frameDescriptor)
                    if (mask == MaskingState.UNMASKED) {
                        val cut = assertThrows(AstCapture::class.java) { leaf.executeTuple(frame, intArrayOf(), 0) }
                        assertSame(incoming, cut.yielded)
                        assertEquals(3L, threads.capabilityCount())
                        incoming.acknowledge()
                        val saved = cut.freeze(root, frame.materialize())
                        threads.setCapabilityCount(5)
                        assertNull(saved.continueWith(Unit))
                        assertEquals(5L, threads.capabilityCount(), "Resumption must not replay the completed setter")
                        assertThrows(RuntimeFault::class.java) { saved.continueWith(Unit) }
                    } else {
                        assertNull(leaf.executeTuple(frame, intArrayOf(), 0))
                        assertEquals(3L, threads.capabilityCount())
                        assertEquals(AsyncRequestState.PENDING, incoming.state)
                        SynchronousMasking.set(root, MaskingState.UNMASKED)
                        assertSame(incoming, threads.poll(root)); incoming.acknowledge()
                    }
                    assertEquals(1, evaluations)
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, incoming.state)
                } finally { SynchronousMasking.set(root, MaskingState.UNMASKED); threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun originalInstalledDeclarationsRetainExactAbiAndUnresolvedIdentity() {
        val entries = json(File(directory, "manifest.json"))["entries"] as List<List<String>>
        val seen = mutableSetOf<String>()
        for ((entry, _) in entries) for (stage in listOf("pre", "post")) {
            val actual = calls(json(File(directory, "$entry-$stage.json")))
            assertTrue(actual.isNotEmpty())
            for (call in actual) {
                val metadata = call.last() as Map<String, Any?>
                val descriptor = metadata["foreignCall"] as Map<String, Any?>
                val target = descriptor["target"] as Map<String, Any?>
                val symbol = target["symbol"] as String
                seen += symbol
                val operands = (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
                fun validate(meta: Map<String, Any?>): Any? =
                    CoreRtsEventForeign.validate(meta, operands, call[3] as List<*>, meta["rep"])
                    ?: CoreOriginalStdio.validate(meta, operands, call[3] as List<*>, meta["rep"])
                    ?: CoreSharedCAFStores.validate(meta, operands, call[3] as List<*>, meta["rep"])
                assertNotNull(validate(metadata), symbol)
                for (bad in listOf(descriptor + ("target" to (target + ("unit" to "main"))),
                    descriptor + ("target" to (target + ("isFunction" to false))),
                    descriptor + ("safety" to if (descriptor["safety"] == "safe") "unsafe" else "safe"),
                    descriptor + ("arity" to 99L), descriptor + ("suppliedArity" to 0L),
                    descriptor + ("resultRep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to false))))
                    assertThrows(RuntimeFault::class.java) { validate(metadata + ("foreignCall" to bad)) }
                if (RtsEventForeignOp.values().any { it.symbol == symbol }) {
                    CoreRtsEventForeign.validateHead(call[1] as List<Any?>, false)
                    assertThrows(RuntimeFault::class.java) { CoreRtsEventForeign.validateHead(call[1] as List<Any?>, true) }
                }
            }
        }
        assertEquals(8, seen.size)
    }

    @Test fun originalEventLeavesMatchTheirNativeContractFromFirstCompiledInvocation() {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes", "interfaceHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val file = File(path).let { if (it.isAbsolute) it else File(root, path) }
                assertEquals(expected, MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }, "Stale RTS event fixture $path")
            }
        val entries = (manifest["entries"] as List<List<String>>).associate { it[0] to it[1] }
        val rows = (json(File(directory, "oracle.json"))["rows"] as List<List<Any?>>).groupBy { it[0] as String }
        assertEquals(8, rows.size); assertEquals(24, rows.values.sumOf { it.size })
        for (stage in listOf("pre", "post")) for ((entry, cases) in rows)
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    assertEquals(true, json(File(directory, "$entry-$stage.audit.json"))["accepted"])
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val threads = Language.currentState().threads
                    val physicalCount = threads.cpuAffinity.count.toLong()
                    val p = program(language, backend, entry, stage)
                    val name = entries.getValue(entry)
                    val callable = context.asValue(EntryValue(p, name, 1))
                    fun invoke(row: List<Any?>) {
                        val input = (row[1] as Number).toLong()
                        val answer = (row[2] as Number).toLong()
                        assertTrue(answer > 0)
                        // JVM quotas may differ from the native GHC processor count.
                        val expected = if (entry == "processors") physicalCount else answer
                        assertEquals(expected, callable.execute(input).asLong(), "$stage/$backend/$entry")
                        if (entry == "capabilities") {
                            assertEquals(input, threads.capabilityCount())
                            val address = CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentation(
                                CoreKind.ADDRESS, evaluated = true, present = true, primReps = listOf("AddrRep")))
                            assertEquals(input, Integer.toUnsignedLong(ManagedAddressRead.WORD32.readInt(address, 0)))
                            assertEquals(physicalCount, threads.cpuAffinity.count.toLong())
                        }
                        released(language)
                    }
                    cases.forEach(::invoke)
                    assertTrue(callable.invokeMember("compile").asBoolean())
                    val target = p.entryTarget(name)
                    for (row in cases.asReversed()) {
                        val before = p.diagnostics().getValue("compiledEntries") as Long
                        invoke(row)
                        assertEquals(before + 1, p.diagnostics().getValue("compiledEntries"), "$stage/$backend/$entry exact entry")
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    }
                    if (entry == "capabilities") {
                        val previous = threads.capabilityCount()
                        assertThrows(org.graalvm.polyglot.PolyglotException::class.java) { callable.execute(0L) }
                        assertEquals(previous, threads.capabilityCount())
                        released(language)
                    }
                } finally { context.leave() }
            }
    }
}
