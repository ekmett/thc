// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class StackAnnotationsTest {
    private val stateRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val objectRep = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val functionRep = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val tupleRep = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "components" to listOf(stateRep, objectRep), "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun variable(id: String, rep: Map<String, Any>) = listOf("var", id, mapOf("rep" to rep))
    private fun binder(id: String, rep: Map<String, Any>, lifted: Boolean) =
        mapOf("id" to id, "name" to id, "rep" to rep, "lifted" to lifted, "coercion" to false)
    private fun module(pause: Boolean = false): Map<String, Any?> {
        val callback = variable("action", functionRep)
        val state = variable("s", stateRep)
        val action = if (!pause) callback else {
            val token = variable("t", stateRep)
            val checkpoint = listOf("app", listOf("prim", "noDuplicate#"), listOf(token),
                listOf(false), false, false, mapOf("rep" to stateRep))
            val call = listOf("app", callback, listOf(token), listOf(false), false, false, mapOf("rep" to tupleRep))
            val body = listOf("case", checkpoint, "ignored", listOf(listOf("default", null, emptyList<String>(), call)),
                mapOf("rep" to tupleRep, "binder" to binder("ignored", stateRep, false)))
            listOf("lam", listOf(binder("t", stateRep, false)), body,
                mapOf("rep" to functionRep, "resultRep" to tupleRep, "entryStrict" to listOf(false)))
        }
        val body = listOf("app", listOf("prim", "annotateStack#"),
            listOf(variable("ann", objectRep), action, state), listOf(true, true, false),
            false, false, mapOf("rep" to tupleRep))
        return mapOf("instrument" to true, "bindings" to listOf(mapOf("id" to "entry", "name" to "entry", "lifted" to true,
            "expr" to listOf("lam", listOf(binder("ann", objectRep, true), binder("action", functionRep, true),
                binder("s", stateRep, false)), body, mapOf("resultRep" to tupleRep, "entryStrict" to listOf(false, false, false))))))
    }
    private fun <T> entered(block: (Language) -> T): T = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw")
        .build().use { context ->
            context.initialize("thc"); context.enter()
            try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private class Probe(language: Language, private val shape: TupleShape) : GuestRoot(language, FrameLayout().build()) {
        init { configureTupleResult(shape) }
        var failure = false
        var suspend = false
        fun finish(): Any {
            val snapshot = if (suspend) StackAnnotations.current(this).values() else ManagedStackSnapshot.capture(this)
            if (failure) throw GuestException(snapshot, this)
            return shape.layout.create().also { shape.layout.setObject(it, 0, snapshot) }
        }
        override fun execute(frame: VirtualFrame): Any {
            if (suspend) return AstCapture(Unit, SynchronousMasking.current(this)).append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any = finish()
            }).freeze(this, frame.materialize())
            return finish()
        }
        override fun bloom(frame: VirtualFrame) = 0L
    }
    private fun snapshot(result: Any?, shape: TupleShape): ManagedStackSnapshot =
        shape.layout.getObject(ownedTupleResult(result, shape), 0) as ManagedStackSnapshot
    private fun install(target: com.oracle.truffle.api.RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun lazyPayloadIsVisibleInSnapshotAndReturnsOnSuccessAndException() = entered { language ->
        val shape = TupleShape(CoreRepresentations.parse(tupleRep), language)
        val probe = Probe(language, shape)
        val action = Closure(null, arity = 1, target = probe.callTarget)
        val never = object : GuestRoot(language, FrameLayout().build()) {
            override fun execute(frame: VirtualFrame): Nothing = error("annotation was forced")
            override fun bloom(frame: VirtualFrame) = 0L
        }
        val payload = Thunk(never.callTarget, null)
        for (backend in listOf("ast", "bytecode")) {
            val program: ExecutableProgram = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
            val target = program.entryTarget("entry")
            val ambient = StackAnnotationState.EMPTY.push("outside")
            StackAnnotations.set(null, ambient)
            fun run(): ManagedStackSnapshot = snapshot(Calls.target(target, arrayOf(0L, payload, action, Unit)), shape)
            assertEquals(listOf(payload, "outside"), run().annotations)
            assertSame(ambient, StackAnnotations.current(null)); assertEquals(0, payload.state)
            install(target)
            val before = (program.diagnostics()["compiledEntries"] as Number).toLong()
            val saved = run()
            assertEquals(before + 1, (program.diagnostics()["compiledEntries"] as Number).toLong())
            assertEquals(listOf(payload, "outside"), saved.annotations)
            assertSame(ambient, StackAnnotations.current(null)); assertEquals(0, payload.state)
            probe.failure = true
            val failure = assertThrows(GuestException::class.java) { run() }
            assertEquals(listOf(payload, "outside"), (failure.payload as ManagedStackSnapshot).annotations)
            assertSame(ambient, StackAnnotations.current(null)); probe.failure = false
            StackAnnotations.set(null, StackAnnotationState.EMPTY)
            assertEquals(listOf(payload, "outside"), saved.annotations)
        }
    }

    @Test fun oneShotActionsParkAnnotationsAndRestoreCapturedStateOnResume() = entered { language ->
        val shape = TupleShape(CoreRepresentations.parse(tupleRep), language)
        val probe = Probe(language, shape)
        val action = Closure(null, arity = 1, target = probe.callTarget)
        val outside = StackAnnotationState.EMPTY.push("original")
        val resumer = StackAnnotationState.EMPTY.push("resumer")
        StackAnnotations.set(null, outside)
        val ast = Program(language, module(), true)
        probe.suspend = true
        val saved = Calls.target(ast.entryTarget("entry"), arrayOf(0L, "inside", action, Unit)) as AstContinuation
        assertSame(outside, StackAnnotations.current(null))
        val astSuspended = saved.yielded as CallSegmentSuspended
        // The same ownership driver used below completes the action segment
        // before giving the annotated caller its validated ChildResume.
        val astThunk = Thunk(ast.entryTarget("entry"), null).also { it.value = saved; it.state = 5 }
        val astDriver = object : GuestRoot(language, FrameLayout().build()) {
            @Child private var force = Force(Metrics(false))
            override fun bloom(frame: VirtualFrame) = 0L
            override fun execute(frame: VirtualFrame): Any? = force.execute(frame, astThunk)
        }
        StackAnnotations.set(null, resumer)
        val astResult = Calls.target(astDriver.callTarget, arrayOf(0L))
        assertEquals(listOf("inside", "original"), shape.layout.getObject(ownedTupleResult(astResult, shape), 0))
        assertEquals(2, astSuspended.segment.state)
        assertEquals(2, astThunk.state)
        assertSame(resumer, StackAnnotations.current(null))
        probe.suspend = false

        val checkpoint = BytecodeCheckpoint().also { it.armed = true }
        val bytecode = BytecodeProgram(language, module(pause = true), checkpoint)
        StackAnnotations.set(null, outside)
        val parent = Calls.target(bytecode.entryTarget("entry"), arrayOf(0L, "inside", action, Unit)) as ContinuationResult
        assertSame(outside, StackAnnotations.current(null))
        val suspended = parent.result as CallSegmentSuspended
        // Give the real update/continuation driver ownership of this saved root;
        // it commits the child segment before supplying the parent's ChildResume.
        val thunk = Thunk(bytecode.entryTarget("entry"), null).also { it.value = parent; it.state = 5 }
        val driver = object : GuestRoot(language, FrameLayout().build()) {
            @Child private var force = Force(Metrics(false))
            override fun bloom(frame: VirtualFrame) = 0L
            override fun execute(frame: VirtualFrame): Any? = force.execute(frame, thunk)
        }
        StackAnnotations.set(null, resumer)
        val result = Calls.target(driver.callTarget, arrayOf(0L))
        assertEquals(listOf("inside", "original"), snapshot(result, shape).annotations)
        assertEquals(2, suspended.segment.state)
        assertEquals(2, thunk.state)
        assertEquals(1, checkpoint.visits.get())
        assertSame(resumer, StackAnnotations.current(null))
        StackAnnotations.set(null, StackAnnotationState.EMPTY)
    }

    @Test fun multiShotAnnotationReturnRebasesOnResumerAmbientAndRecapture() = entered { language ->
        val shape = TupleShape(CoreRepresentations.parse(tupleRep), language)
        val probe = Probe(language, shape)
        val action = Closure(null, arity = 1, target = probe.callTarget)
        val owner = object : GuestRoot(language, FrameLayout().build()) {
            @Child var site = DelimitedActionSite(language, Metrics(false))
            lateinit var stack: DelimitedStack
            override fun bloom(frame: VirtualFrame) = 0L
            override fun execute(frame: VirtualFrame): Any? = stack.resume(site, frame.materialize(), frame.arguments[1])
        }
        val frame = Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), owner.frameDescriptor)
        val outside = StackAnnotationState.EMPTY.push("outside prompt")
        StackAnnotations.set(null, outside.push("captured"))
        val cut = DelimitedCut(PromptTag(Language.currentState()), action, shape, MaskingState.UNMASKED, owner)
        cut.append(frame, DelimitedAnnotationStep(owner, outside))
        owner.stack = DelimitedStack(cut, shape)
        val installed = owner.stack.closure(language, Metrics(false)).target
        for (label in listOf("first", "second")) {
            val ambient = StackAnnotationState.EMPTY.push(label)
            StackAnnotations.set(null, ambient)
            if (label == "second") install(installed)
            val calls = installed.javaClass.getMethod("getCallCount").invoke(installed)
            assertEquals(listOf("captured", label), snapshot(Calls.target(installed, arrayOf(0L, action, Unit)), shape).annotations)
            if (label == "second") {
                assertEquals(calls, installed.javaClass.getMethod("getCallCount").invoke(installed), "First call enters the installed continuation root")
                assertEquals(true, installed.javaClass.getMethod("isValidLastTier").invoke(installed))
            }
            assertSame(ambient, StackAnnotations.current(null))
        }
        val recapture = object : GuestRoot(language, FrameLayout().build()) {
            override fun bloom(frame: VirtualFrame) = 0L
            override fun execute(frame: VirtualFrame): Nothing =
                throw DelimitedCut(PromptTag(Language.currentState()), action, shape, MaskingState.UNMASKED, this)
        }
        val second = assertThrows(DelimitedCut::class.java) {
            Calls.target(owner.callTarget, arrayOf(0L, Closure(null, arity = 1, target = recapture.callTarget)))
        }
        assertEquals(listOf("second"), StackAnnotations.current(null).values())
        owner.stack = DelimitedStack(second, shape)
        val ambient = StackAnnotationState.EMPTY.push("third")
        StackAnnotations.set(null, ambient)
        assertEquals(listOf("captured", "third"), snapshot(Calls.target(owner.callTarget, arrayOf(0L, action)), shape).annotations)
        probe.failure = true
        assertThrows(GuestException::class.java) { Calls.target(owner.callTarget, arrayOf(0L, action)) }
        assertSame(ambient, StackAnnotations.current(null))
        StackAnnotations.set(null, StackAnnotationState.EMPTY)
    }

    @Test fun nestedRebaseSharesPrefixesAndContextsRemainIsolated() = entered { _ ->
        val outside = StackAnnotationState.EMPTY.push("outside")
        val middle = outside.push("middle")
        val inner = middle.push("inner")
        val ambient = StackAnnotationState.EMPTY.push("ambient")
        val copies = java.util.IdentityHashMap<StackAnnotationState, StackAnnotationState>()
        val returned = middle.rebase(outside, ambient, copies)
        val active = inner.rebase(outside, ambient, copies)
        assertSame(returned, active.prior, "A recapture at an intervening prompt finds the same lexical prefix")
        assertSame(ambient, returned.prior)
        StackAnnotations.set(null, active)
        entered { assertTrue(StackAnnotations.current(null).values().isEmpty()) }
        assertSame(active, StackAnnotations.current(null))
        val state = Language.currentState()
        val threadValue = java.util.concurrent.CompletableFuture<StackAnnotationState>()
        val thread = Thread { threadValue.complete(state.stackAnnotations.get()) }
        thread.start()
        assertSame(StackAnnotationState.EMPTY, threadValue.get(10, java.util.concurrent.TimeUnit.SECONDS))
        thread.join()
        StackAnnotations.set(null, StackAnnotationState.EMPTY)
    }
}
