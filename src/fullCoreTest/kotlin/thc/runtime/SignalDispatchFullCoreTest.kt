// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** Original Conc.Signal owns lookup, ForeignPtr lifetime and forkIO, not this test. */
@Timeout(120)
class SignalDispatchFullCoreTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/signal-dispatch")
    private fun document(path: String) = Json.parse(File(directory, path).readText()) as Map<String, Any?>
    private fun fixture(): Map<String, Any?> {
        val manifest = document("manifest.json")
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(listOf("setupHandler", "awaitHandler", CoreSignalForeign.dispatcher), manifest["entries"])
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "compiler/test-fixtures/SignalDispatchAudit.hs", "compiler/test-fixtures/SignalDispatchNative.hs",
            "test/haskell-fixtures/SignalDispatchFixtures.hs", "scripts/core-capabilities.json"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("build/signal-dispatch/oracle.txt",
            "build/signal-dispatch/pre/audit.json", "build/signal-dispatch/post/audit.json"))
        assertEquals("1\n10010\n2\n20020\n3\n30030\n15\n150150\n", File(directory, "oracle.txt").readText())
        for (stage in listOf("pre", "post")) {
            assertEquals(true, document("$stage/audit.json")["accepted"])
            assertEquals(emptyList<Any>(), document("$stage/audit.json")["missingGlobals"])
        }
        return manifest
    }

    @Test fun astOriginalHandlersSelectSignalAndInheritNativeMasking() = dispatch("ast")
    @Test fun bytecodeOriginalHandlersSelectSignalAndInheritNativeMasking() = dispatch("bytecode")

    private fun dispatch(backend: String) {
        val manifest = fixture()
        val originals = mutableListOf<Map<String, Any?>>()
        val layout = checkNotNull(CorePackageManifest.visitModules(
            File(root, manifest["packageManifest"] as String).path) { module, _ -> originals.add(module) }.targetLayout)
        val native = File(directory, "oracle.txt").readLines().map(String::toLong).chunked(2)
            .associate { it[0] to it[1] }
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val combined = CoreModules.merge(originals + paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> }) +
                ("targetLayout" to layout)
            val linked = CoreModules.reachable(combined, manifest["entries"] as List<String>, strictLink = true) +
                ("instrument" to true)
            Context.newBuilder("thc", "llvm").allowNativeAccess(true).allowIO(IOAccess.ALL).allowCreateThread(true)
                .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val owner = Language.currentState()
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked, true)
                            else BytecodeProgram(language, linked, true)
                        fun call(entry: String, argument: Long): Long {
                            val target = program.entryTarget(entry)
                            val shape = checkNotNull((target.rootNode as GuestRoot).tupleResult)
                            val result = Calls.target(target, arrayOf(0L, argument, Unit))
                            return shape.layout.getLong(ownedTupleResult(result, shape), 0)
                        }
                        val events = LinkedBlockingQueue<ProcessSignalTransport.Event>()
                        val closed = AtomicInteger()
                        val transport = object : ProcessSignalTransport {
                            override fun install(signal: Int, action: Int) = ProcessSignalTransport.Result(-1, 0)
                            override fun take() = events.take().takeUnless { it.info.isEmpty() }
                            override fun wake() { events.offer(ProcessSignalTransport.Event(0, byteArrayOf())) }
                            override fun resetWake() { events.removeIf { it.info.isEmpty() } }
                            override fun close() { closed.incrementAndGet() }
                        }
                        val service = ManagedSignals(owner, language, reducedVmSignals = true) { transport }
                        service.bind(program)
                        service.authorizeLauncher() // Test-only transport, never changes host process handlers.
                        owner.threads.enterCurrent()
                        try {
                            for ((signal, expected) in native) {
                                assertEquals(signal, call("setupHandler", signal), "$stage/$backend original setHandler")
                                assertEquals(-1L, service.install(signal, -4L, ManagedAddress.nullAddress()))
                                val info = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder()).putInt(signal.toInt()).array()
                                events.put(ProcessSignalTransport.Event(signal.toInt(), info))
                                // The original forked Haskell handler decodes the actual info pointer,
                                // observes its mask and publishes through the original MVar action.
                                assertEquals(expected, call("awaitHandler", signal), "$stage/$backend/$signal")
                                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(program.entryTarget("awaitHandler").rootNode))
                                assertEquals(0, language.handoffState.get().arguments.depth)
                                assertEquals(0, language.handoffState.get().results.depth)
                                assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                                assertEquals(0, language.handoffState.get().results.retainedReferences())
                            }
                            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        } finally {
                            try { service.close() }
                            finally { owner.threads.leaveCurrent(GuestThreadStatus.FINISHED) }
                        }
                        assertEquals(1, closed.get(), "native restoration must run exactly once")
                    } finally { context.leave() }
                }
        }
    }
}
