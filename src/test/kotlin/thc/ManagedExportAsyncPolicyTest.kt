// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import com.oracle.truffle.api.TruffleLanguage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

/** Model export protocol controls, not native registration or ABI evidence. */
class ManagedExportAsyncPolicyTest {
    private val dataRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val closureRep = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val stateRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val scalar = mapOf("kind" to "tycon", "name" to mapOf("unit" to "ghc-internal",
        "module" to "GHC.Internal.Int", "occurrence" to "Int32", "namespace" to "type"), "arguments" to emptyList<Any>())

    private fun exported(context: Context, backend: String, async: Boolean): Value {
        context.initialize("thc")
        context.enter()
        try {
            val owner = Language.currentState()
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val body = listOf("case", listOf("app", listOf("prim", "traceEvent#"),
                listOf(listOf("lit", "string-bytes", "706f6c696379"), listOf("void", mapOf("rep" to stateRep))),
                listOf(false, false), false, false, mapOf("rep" to stateRep)), "traced",
                listOf(listOf("default", null, emptyList<String>(), listOf("var", "x", mapOf("rep" to dataRep)))),
                mapOf("rep" to dataRep, "binder" to mapOf("id" to "traced", "lifted" to false, "rep" to stateRep)))
            val module = mapOf("instrument" to true, "bindings" to listOf(mapOf("id" to "model:Export.identity",
                "name" to "identity", "type" to "Int32 -> Int32", "lifted" to true, "arity" to 1, "rep" to closureRep,
                "expr" to listOf("lam", listOf(mapOf("id" to "x", "name" to "x", "type" to "Int32",
                    "lifted" to true, "coercion" to false, "rep" to dataRep)), body,
                    mapOf("rep" to closureRep, "resultRep" to dataRep)))),
                "constructors" to listOf(mapOf("id" to "ghc-internal:GHC.Internal.Int.I32#", "name" to "I32#",
                    "kind" to "boxed", "arity" to 1, "fieldReps" to listOf(listOf("Int32Rep")),
                    "strictFields" to listOf(false), "fieldLifted" to listOf(false))))
            val program: ExecutableProgram = if (backend == "ast") Program(language, module, async)
                else BytecodeProgram(language, module, async)
            val signature = ManagedExportSignature("model", "Export", "identity", "model:Export.identity",
                listOf(scalar), scalar, false, 64)
            return context.asValue(ManagedExportValue(owner.managedExports, owner, language, program, signature))
        } finally { context.leave() }
    }

    private fun checkPolicy(imported: Boolean) {
        for (backend in listOf("ast", "bytecode")) for (async in listOf(false, true)) {
            var observed = 0
            var active: GuestThreadId? = null
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, start: Int, length: Int) {
                    if (length > 0) {
                        observed++
                        val threads = Language.currentState().threads
                        val slot = threads.pollState(Thread.currentThread()).current!!
                        active = slot.identity
                        assertEquals(async, slot.externalAsync, "$backend async=$async imported=$imported")
                        CompletableFuture.runAsync {
                            if (async) {
                                val request = threads.send(slot.identity, "external policy probe")
                                try {
                                    assertFalse(request.forceSelf, "Another carrier cannot use self delivery")
                                    assertEquals(AsyncRequestState.PENDING, request.state)
                                } finally { assertTrue(request.cancel()) }
                                assertEquals(AsyncRequestState.CANCELLED, request.state)
                            } else assertThrows(UnsupportedCore::class.java) {
                                threads.send(slot.identity, "must not wait on a nonpolling export")
                            }
                        }.get(5, TimeUnit.SECONDS)
                    }
                    super.write(bytes, start, length)
                }
            }
            Context.newBuilder("thc").err(output).build().use { exporter ->
                val value = exported(exporter, backend, async)
                assertEquals(0, observed, "Creating an exported value must not execute it")
                if (!imported) assertEquals(19, value.execute(19).asInt())
                else Context.newBuilder("thc").build().use { importer ->
                    importer.initialize("thc")
                    importer.enter()
                    try {
                        val owner = Language.currentState()
                        owner.threads.enterCurrent(externalAsync = true)
                        val caller = owner.threads.currentIdentity()
                        try {
                            val foreign = owner.threads.enterForeign(ForeignSafety.SAFE)
                            try { assertEquals(19, value.execute(19).asInt()) }
                            finally { owner.threads.leaveForeign(foreign) }
                            assertSame(owner, Language.currentState())
                            assertSame(caller, owner.threads.currentIdentity())
                            assertNotSame(caller, active)
                            assertEquals(GuestThreadStatus.RUNNING, owner.threads.status(caller))
                        } finally { owner.threads.leaveCurrent() }
                        assertNull(owner.threads.pollState(Thread.currentThread()).current)
                    } finally { importer.leave() }
                }
                assertTrue(observed > 0)
                assertEquals("[thc trace event] policy\n", output.toString(Charsets.UTF_8))
                exporter.enter()
                try {
                    val threads = Language.currentState().threads
                    assertNull(threads.pollState(Thread.currentThread()).current,
                        "Export return must restore the prior guest slot")
                    // Ordinary host identities remain available for re-entry;
                    // a reverse callback has its own completed guest lifetime.
                    assertEquals(if (imported) GuestThreadStatus.FINISHED else GuestThreadStatus.FOREIGN,
                        threads.status(active!!))
                    val late = CompletableFuture.supplyAsync { threads.send(active!!, "after return") }.get(5, TimeUnit.SECONDS)
                    assertEquals(AsyncRequestState.TARGET_FINISHED, late.state)
                } finally { exporter.leave() }
                exporter.close()
                assertThrows(RuntimeException::class.java) { value.execute(1) }
            }
        }
    }

    @Test fun directExportsPreserveTheirExecutableAsyncPolicy() = checkPolicy(false)
    @Test fun safeCrossContextImportsPreserveExporterPolicyAndRestoreCaller() = checkPolicy(true)
}
