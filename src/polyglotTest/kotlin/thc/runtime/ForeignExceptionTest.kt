// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*

/** Genuine compiler-produced Exception dictionaries; no synthetic SomeException
 * constructors or representation witnesses occur in this integration test. */
@org.junit.jupiter.api.Tag("foreign-exceptions-full-core")
class ForeignExceptionTest {
    private fun source(stage: String) = ForeignExceptionFixtureSupport.source(stage)
    private fun context(): Context = Context.newBuilder("thc", "js").allowExperimentalOptions(true)
        .allowNativeAccess(true).allowPolyglotAccess(PolyglotAccess.ALL)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()

    @Test fun foreignExecutionRequiresAnUnambiguousGenuineLinkedBridge() {
        val source = source("post")
        val id = "main:ForeignExceptionAudit.caught"
        val proof = (source["foreignExceptionBridges"] as List<Map<String, Any?>>).single()
        for (invalid in listOf(source - "foreignExceptionBridges",
                source + ("foreignExceptionBridgeUnit" to "missing-runtime-unit"),
                source + ("foreignExceptionBridges" to listOf(proof, proof)))) {
            val failure = assertThrows(RuntimeFault::class.java) { CoreModules.reachable(invalid, id, true) }
            assertTrue(failure.message!!.contains("Foreign execution requires") ||
                failure.message!!.contains("ambiguous foreign exception bridge"), failure.message)
        }
        val linked = CoreModules.reachable(source, id, true) - "selectedForeignExceptionBridge"
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) {
                    val failure = assertThrows(RuntimeFault::class.java) {
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked)
                            else BytecodeProgram(language, linked)
                        program.entryValue(id)
                    }
                    assertTrue(failure.message!!.contains("linked genuine THC.Exception"), failure.message)
                }
            } finally { context.leave() }
        }
    }

    @Test fun genuineCatchCleanupInspectionAndLazyOrdinaryExceptions() {
        val cases = mapOf("caught" to 42L, "cleanup" to 142L, "metadata" to 19L,
            "displayIsInert" to 17L, "parseCleanup" to 142L, "lazyOrdinary" to 99L, "ordinary" to 0L)
        for (stage in listOf("pre", "post")) {
            val source = source(stage)
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.initialize("js"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for ((entry, expected) in cases) {
                        val linked = CoreModules.reachable(source, "main:ForeignExceptionAudit.$entry", true) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val function = context.asValue(EntryValue(program, "main:ForeignExceptionAudit.$entry", 1))
                        repeat(2) { assertEquals(expected, function.execute(0L).asLong(), "$stage/$backend/$entry") }
                        assertTrue(function.invokeMember("compile").asBoolean())
                        assertEquals(expected, function.execute(0L).asLong(), "$stage/$backend/$entry installed")
                        assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun ordinarySomeExceptionRethrowRetainsExactForeignIdentityAfterCatch() {
        for (stage in listOf("pre", "post")) {
            val source = source(stage)
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.initialize("js"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val original = context.eval("js", "globalThis.thcFailure = new Error('identity retained')")
                    for (entry in listOf("rethrowNow", "rethrowLater")) {
                        val linked = CoreModules.reachable(source, entry, true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val function = context.asValue(EntryValue(program, entry, 1))
                        assertEquals(8L, function.execute(1L).asLong())
                        assertTrue(function.invokeMember("compile").asBoolean())
                        val failure = assertThrows(PolyglotException::class.java) { function.execute(0L) }
                        assertTrue(failure.isGuestException)
                        assertEquals(original, failure.guestObject, "$stage/$backend/$entry exact identity: $failure / ${failure.polyglotStackTrace.take(12)}")
                    }
                } finally { context.leave() }
            }
        }
    }

    @Test fun registeredProjectorDoesNotInspectRawPrimitiveValuesOrBottoms() {
        val source = source("post")
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.initialize("js"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(source, "main:ForeignExceptionAudit.caught", true)
                val registered: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                assertEquals(42L, context.asValue(EntryValue(registered, "main:ForeignExceptionAudit.caught", 1)).execute(0L).asLong())
                assertFalse(Language.currentState(null).foreignExceptionRegistry.snapshot().isEmpty())
                for (bottom in listOf(false, true)) {
                    fun binding(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id,
                        "type" to "opaque primitive control", "lifted" to true, "arity" to 0, "expr" to body)
                    val payload = if (bottom) listOf("var", "payload") else listOf("lit", "int", "17")
                    val data = mapOf("schema" to 1L, "ghc" to "9.14.1", "module" to "PrimitiveControl",
                        "constructors" to emptyList<Any>(),
                        "bindings" to listOf(binding("payload", payload),
                            binding("entry", listOf("app", listOf("prim", "raise#"),
                                listOf(listOf("var", "payload")), listOf(true)))))
                    val raw: ExecutableProgram = if (backend == "ast") Program(language, data) else BytecodeProgram(language, data)
                    val value = raw.entryValue("payload")
                    repeat(2) {
                        assertThrows(PolyglotException::class.java) {
                            context.asValue(EntryValue(raw, "entry", 0)).execute()
                        }
                        if (bottom) assertEquals(0, (value as Thunk).state, "Previously registered projector must not force raw bottom")
                    }
                    val failure = assertThrows(GuestException::class.java) {
                        Calls.target(raw.hostEntryTarget(), arrayOf(raw.entryValue("entry"), emptyArray<Any?>()))
                    }
                    assertFalse(failure.someException, "Memoized primitive failures retain opaque provenance")
                    assertEquals(0L, raw.diagnostics()["blackholes"])
                }
            } finally { context.leave() }
        }
    }
    @Test fun explicitMetadataSupportsReverseEntryAndNewFailureCleanup() {
        val source = source("post")
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.initialize("js"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun entry(name: String): org.graalvm.polyglot.Value {
                    val id = "main:ForeignExceptionAudit.$name"
                    val linked = CoreModules.reachable(source, id, true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    return context.asValue(EntryValue(program, id, 1))
                }
                val callback = entry("ordinary")
                assertEquals(31L, callback.execute(31L).asLong())
                assertTrue(callback.invokeMember("compile").asBoolean())
                val count = java.util.concurrent.atomic.AtomicInteger()
                val outer = MetadataProtocolFailure {
                    count.incrementAndGet()
                    assertEquals(31L, callback.execute(31L).asLong(), "Metadata reverse entry must retain guest behavior")
                    "reentered"
                }
                context.getBindings("js").putMember("thcHostFailure", ForeignThrower(outer))
                val inspect = entry("hostMetadata")
                assertEquals(9L, inspect.execute(0L).asLong())
                assertEquals(1, count.get(), "Length and indexed reads share one published text snapshot")
                assertTrue(inspect.invokeMember("compile").asBoolean())
                assertEquals(9L, inspect.execute(0L).asLong())
                assertEquals(2, count.get())

                val metadataFailure = MetadataProtocolFailure { "secondary metadata failure" }
                context.getBindings("js").putMember("thcHostFailure",
                    ForeignThrower(MetadataProtocolFailure { throw metadataFailure }))
                val cleanup = entry("hostMetadataCleanup")
                assertEquals(142L, cleanup.execute(0L).asLong(), "A thrown metadata failure is newly catchable after cleanup")
                assertTrue(cleanup.invokeMember("compile").asBoolean())
                assertEquals(142L, cleanup.execute(0L).asLong())

                val caught = entry("hostCatch")
                for (kind in listOf(com.oracle.truffle.api.interop.ExceptionType.EXIT,
                                    com.oracle.truffle.api.interop.ExceptionType.INTERRUPT)) {
                    context.getBindings("js").putMember("thcHostFailure",
                        ForeignThrower(MetadataProtocolFailure(kind) { "excluded control" }))
                    val failure = assertThrows(PolyglotException::class.java) { caught.execute(0L) }
                    if (kind == com.oracle.truffle.api.interop.ExceptionType.EXIT) assertTrue(failure.isExit, "$failure / ${failure.polyglotStackTrace.take(8)}")
                    else assertTrue(failure.isInterrupted, "$failure / ${failure.polyglotStackTrace.take(8)}")
                }
            } finally { context.leave() }
        }
    }

}

@com.oracle.truffle.api.library.ExportLibrary(com.oracle.truffle.api.interop.InteropLibrary::class)
class MetadataProtocolFailure(
    private val kind: com.oracle.truffle.api.interop.ExceptionType = com.oracle.truffle.api.interop.ExceptionType.RUNTIME_ERROR,
    private val messageAction: () -> String) :
    com.oracle.truffle.api.exception.AbstractTruffleException("inert metadata test failure") {
    @com.oracle.truffle.api.library.ExportMessage fun getExceptionType() = kind
    @com.oracle.truffle.api.library.ExportMessage fun hasExceptionMessage() = true
    @com.oracle.truffle.api.library.ExportMessage fun getExceptionMessage(): Any = messageAction()
    @com.oracle.truffle.api.library.ExportMessage fun getExceptionExitStatus(): Int {
        if (kind != com.oracle.truffle.api.interop.ExceptionType.EXIT)
            throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
        return 7
    }
}

@com.oracle.truffle.api.library.ExportLibrary(com.oracle.truffle.api.interop.InteropLibrary::class)
class ForeignThrower(private val failure: MetadataProtocolFailure) : com.oracle.truffle.api.interop.TruffleObject {
    @com.oracle.truffle.api.library.ExportMessage fun isExecutable() = true
    @com.oracle.truffle.api.library.ExportMessage fun execute(arguments: Array<Any?>): Any = throw failure
}
