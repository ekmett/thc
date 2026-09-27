// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class BytecodeColdApplicationTest {
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }

    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", type).invoke(runtime, target)
    }
    private fun valid(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))

    private fun target(language: Language, body: (VirtualFrame) -> Any?) = object : RootNode(language) {
        override fun execute(frame: VirtualFrame): Any? = body(frame)
    }.callTarget

    private fun root(language: Language, closure: Any?, metrics: Metrics,
                     checkpoint: Boolean = false, layout: ArgumentLayout? = null,
                     sourceRead: () -> Unit = {}) = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
        if (b.isParsingSources()) {
            sourceRead(); b.beginSource(Source.newBuilder("thc", "call f 42", "ColdApply.hs").build())
            b.beginSourceSection(0, 9)
        }
        b.beginRoot(); b.emitEnterRoot(metrics); b.beginReturn()
        if (layout == null) b.beginApply(1, false, metrics, booleanArrayOf(true))
        else b.beginApplyCompact(layout, false, metrics, BooleanArray(layout.logicalArity) { true })
        if (closure == null) b.emitLoadNull() else b.emitLoadConstant(closure)
        b.emitLoadConstant(42L)
        if (layout == null) b.endApply() else b.endApplyCompact()
        b.endReturn(); b.endRoot()
        if (b.isParsingSources()) { b.endSourceSection(); b.endSource() }
    // Use the production capture policy, not the factoring lane's private hook.
    }.getNode(0).also { it.configureDelimited(checkpoint) }

    @Test fun branchFreeApplyEntersItsFirstInstalledCallWithEitherCapturePolicy() = withLanguage { language ->
        for (checkpoint in listOf(false, true)) {
            var calls = 0
            val closure = Closure(null, arity = 1, target = target(language) { calls++; it.arguments[1] })
            val metrics = Metrics(true)
            val root = root(language, closure, metrics, checkpoint)
            compile(root.callTarget)
            assertEquals(0, calls); assertEquals(0L, metrics.compiledEntries)
            assertEquals(42L, Calls.target(root.callTarget, emptyArray()))
            assertEquals(1, calls); assertEquals(1L, metrics.compiledEntries)
            valid(root.callTarget)
        }
    }

    @Test fun compactEmptyTupleSourceReplayAndCloneKeepIndependentColdChildren() = withLanguage { language ->
        val layout = checkNotNull(ArgumentLayout.fromProofs(listOf(
            CoreRepresentation(CoreKind.UNKNOWN, present = true, primReps = emptyList(), components = emptyList()),
            CoreRepresentation(CoreKind.LONG))))
        var calls = 0
        var sources = 0
        val callee = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
            override fun bloom(frame: VirtualFrame) = 0L
            override fun execute(frame: VirtualFrame): Any? {
                calls++; assertEquals(2, frame.arguments.size); return frame.arguments[1]
            }
        }.also { it.configureInput(layout) }
        val closure = Closure(null, arity = 2, target = callee.callTarget)
        val metrics = Metrics(true)
        val root = root(language, closure, metrics, checkpoint = true, layout = layout) { sources++ }
        compile(root.callTarget)
        assertEquals(0, calls); assertEquals(0, sources)
        assertEquals(42L, Calls.target(root.callTarget, emptyArray()))
        assertEquals(1L, metrics.compiledEntries); valid(root.callTarget)
        fun code() = root.bytecodeNode.instructions.map { it.name to it.arguments.map(Any::toString) }
        val before = code()
        root.bytecodeNode.ensureSourceInformation()
        assertEquals(1, sources); assertEquals(before, code())
        assertEquals(42L, Calls.target(root.callTarget, emptyArray()))
        assertEquals(2L, metrics.compiledEntries); valid(root.callTarget)
        val clone = root.javaClass.getDeclaredMethod("cloneUninitialized").apply { isAccessible = true }
            .invoke(root) as BytecodeRoot
        assertNotSame(root, clone)
        compile(clone.callTarget)
        assertEquals(2, calls)
        assertEquals(42L, Calls.target(clone.callTarget, emptyArray()))
        assertEquals(3, calls); assertEquals(3L, metrics.compiledEntries)
        valid(clone.callTarget); valid(root.callTarget)
    }

    @Test fun coldPapDoesNotEnterItsUnsaturatedFunction() = withLanguage { language ->
        var calls = 0
        val closure = Closure(null, arity = 2, target = target(language) { calls++; error("PAP entered") })
        val metrics = Metrics(true)
        val root = root(language, closure, metrics, checkpoint = true)
        compile(root.callTarget)
        val pap = Calls.target(root.callTarget, emptyArray()) as Closure
        assertEquals(0, calls); assertEquals(1, pap.arity); assertSame(closure.target, pap.target)
        assertArrayEquals(arrayOf<Any?>(42L), pap.supplied)
        assertEquals(1L, metrics.compiledEntries); valid(root.callTarget)
    }

    @Test fun coldZeroArityPrefixKeepsTheRemainingArgumentAndRunsOnce() = withLanguage { language ->
        var first = 0
        var final = 0
        val finish = Closure(null, arity = 1, target = target(language) { final++; it.arguments[1] })
        val closure = Closure(null, arity = 0, target = target(language) { first++; finish })
        val metrics = Metrics(true)
        val root = root(language, closure, metrics, checkpoint = true)
        compile(root.callTarget)
        assertEquals(0, first); assertEquals(0, final)
        assertEquals(42L, Calls.target(root.callTarget, emptyArray()))
        assertEquals(1, first); assertEquals(1, final)
        assertEquals(1L, metrics.compiledEntries); valid(root.callTarget)
    }

    @Test fun ordinaryInterpreterCallsStillPopulateTheObservedTargetPic() = withLanguage { language ->
        var calls = 0
        val closure = Closure(null, arity = 1, target = target(language) { calls++; it.arguments[1] })
        val metrics = Metrics(true)
        val root = root(language, closure, metrics)
        assertEquals(42L, Calls.target(root.callTarget, emptyArray()))
        assertEquals(1, calls); assertEquals(0L, metrics.compiledEntries)
        assertEquals(0L, metrics.indirectCalls)
        compile(root.callTarget)
        assertEquals(42L, Calls.target(root.callTarget, emptyArray()))
        assertEquals(2, calls); assertEquals(0L, metrics.indirectCalls)
        assertEquals(1L, metrics.compiledEntries); valid(root.callTarget)
    }

    @Test fun malformedFunctionKeepsTheOriginalGeneratedTypeFailure() = withLanguage { language ->
        for (invalid in listOf(null, 7L)) {
            val metrics = Metrics(true)
            val root = root(language, invalid, metrics)
            compile(root.callTarget)
            if (invalid == null) {
                val failure = assertThrows(com.oracle.truffle.api.dsl.UnsupportedSpecializationException::class.java) {
                    Calls.target(root.callTarget, emptyArray())
                }
                assertEquals(6, failure.suppliedValues.size)
                assertNull(failure.suppliedValues[4])
                // Preserve the original invalidating null-specialization failure.
                // A constant-invalid root can compile to a start-frame deopt stub;
                // the valid first-entry controls above must never do so.
                assertEquals(0L, metrics.compiledEntries)
            } else assertThrows(ClassCastException::class.java) { Calls.target(root.callTarget, emptyArray()) }
        }
    }

    @Test fun outlinedNullDiagnosticsRetainTypesMessagesAndNonNullIdentity() {
        val values = arrayOf<Any?>(Any(), null)
        assertSame(values, ColdCallChecks.values(values))
        val target = object : RootNode(null) { override fun execute(frame: VirtualFrame): Any? = null }.callTarget
        val transfer = TailCall(target, values)
        assertSame(target, ColdCallChecks.target(target))
        assertSame(transfer, ColdCallChecks.transfer(transfer))
        fun missing(message: String?, action: () -> Unit) {
            val failure = assertThrows(NullPointerException::class.java, action)
            assertEquals(NullPointerException::class.java, failure.javaClass)
            assertEquals(message, failure.message)
        }
        missing(null) { ColdCallChecks.values(null) }
        missing(null) { ColdCallChecks.typedInput(null) }
        missing("null cannot be cast to non-null type thc.runtime.GuestRoot") { ColdCallChecks.guestRoot(null) }
        missing("null cannot be cast to non-null type com.oracle.truffle.api.RootCallTarget") { ColdCallChecks.target(null) }
        missing("null cannot be cast to non-null type thc.runtime.TailCall") { ColdCallChecks.transfer(null) }
        assertThrows(ClassCastException::class.java) { ColdCallChecks.target(Any()) }
        assertThrows(ClassCastException::class.java) { ColdCallChecks.transfer(Any()) }
        assertThrows(ClassCastException::class.java) { ColdCallChecks.guestRoot(target.rootNode) }
        assertEquals(2, values.size)
        assertNull(values[1])
    }
}
