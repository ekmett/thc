// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import thc.Language
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** A real blocked MVar cut, owned thunk, and continuation resumed on another Java thread. */
class AstContinuationTest {
    private val stateRep = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val mvarRep = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
    private val dataRep = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
    private val longRep = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val tupleRep = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to listOf("BoxedRep (Just Lifted)"), "components" to listOf(stateRep, dataRep), "evaluated" to false)
    private val nestedTupleRep = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
        "primReps" to listOf("BoxedRep (Just Lifted)", "IntRep"),
        "components" to listOf(tupleRep, longRep), "evaluated" to false)

    private fun directMVarModule(wrap: Boolean = false, strict: Boolean = false,
                                 caseLiteral: Boolean = false, casePayload: Boolean = false,
                                 wrongCaseResult: Boolean = false,
                                 unevaluatedCell: Boolean = false, nestedTuple: Boolean = false): Map<String, Any?> {
        val cell = listOf("var", "cell", mapOf("rep" to mvarRep))
        val state = listOf("void", mapOf("rep" to stateRep))
        val read = listOf("app", listOf("prim", "takeMVar#"), listOf(cell, state),
            listOf(false, false), false, false, mapOf("rep" to tupleRep))
        val body: Any = when {
            nestedTuple -> listOf("app", listOf("con", "Outer", 2),
                listOf(read, listOf("lit", "int", "7", mapOf("rep" to longRep))),
                listOf(false, false), false, false, mapOf("rep" to nestedTupleRep))
            wrap -> listOf("case", read, "returned", emptyList<Any>())
            caseLiteral || casePayload -> listOf("case", read, "returned", listOf(listOf("data", "Pair",
                listOf("stateOut", "payload"), if (casePayload) listOf("var", "payload", mapOf("rep" to dataRep))
                    else listOf("lit", "int", "7", mapOf("rep" to longRep)),
                mapOf("binders" to listOf(
                    mapOf("id" to "stateOut", "rep" to stateRep),
                    mapOf("id" to "payload", "rep" to dataRep))))),
                mapOf("rep" to if (casePayload) dataRep else longRep,
                    "binder" to mapOf("id" to "returned", "rep" to tupleRep)))
            else -> read
        }
        val parameters = listOf(
            mapOf("id" to "cell", "name" to "cell", "lifted" to false, "coercion" to false,
                "rep" to if (unevaluatedCell) mvarRep + ("evaluated" to false) else mvarRep),
            mapOf("id" to "state", "name" to "state", "lifted" to false, "coercion" to false, "rep" to stateRep))
        val lambda = listOf("lam", parameters, body, mapOf("resultRep" to when {
            nestedTuple -> nestedTupleRep
            wrongCaseResult || casePayload -> dataRep; caseLiteral -> longRep; else -> tupleRep },
            "entryStrict" to listOf(strict, false)))
        return mapOf("bindings" to listOf(mapOf("id" to "direct", "name" to "direct",
            "lifted" to true, "expr" to lambda)), "instrument" to true,
            "constructors" to listOf(
                mapOf("id" to "Pair", "name" to "Pair", "kind" to "unboxed-tuple", "arity" to 2),
                mapOf("id" to "Outer", "name" to "Outer", "kind" to "unboxed-tuple", "arity" to 2)))
    }

    @Test fun ordinaryCallerAndEntryRoutesAreCapturedAndConflictingProofsStillFail() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                Program(language, directMVarModule(), true)
                Program(language, directMVarModule(strict = true), true)
                Program(language, directMVarModule(casePayload = true), true)
                assertThrows(RuntimeFault::class.java) {
                    Program(language, directMVarModule(caseLiteral = true, wrongCaseResult = true), true)
                }
                Program(language, directMVarModule(unevaluatedCell = true), true)
                assertThrows(UnsupportedCore::class.java) {
                    AstAsyncAdmission.validate(listOf(mapOf("id" to "unsupported", "expr" to listOf("unknown"))))
                }
            } finally { context.leave() }
        }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun admittedProgramCompiledMVarCutResumesItsTupleOnAnotherJavaThread(nested: Boolean) {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
            context.initialize("thc"); context.enter()
            val program: Program
            val target: com.oracle.truffle.api.RootCallTarget
            val state: Language.State
            val shape: TupleShape
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                state = Language.currentState()
                program = Program(language, directMVarModule(nestedTuple = nested), true)
                target = program.entryTarget("direct")
                assertNull((target.rootNode as FunctionRoot).handoff,
                    "An async AST root must not receive a typed caller loan without caller capture")
                shape = TupleShape(CoreRepresentations.parse(if (nested) nestedTupleRep else tupleRep), language)
                repeat(5) {
                    val ready = ManagedMVar()
                    assertTrue(ready.tryPut("one"))
                    val result = Calls.target(target, arrayOf(0L, ready, Unit))
                    val owned = ownedTupleResult(result, shape)
                    assertEquals("one", shape.layout.getObject(owned, 0))
                    if (nested) assertEquals(7L, shape.layout.getLong(owned, 1))
                }
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            } finally { context.leave() }

            val cell = ManagedMVar()
            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            val answer = CompletableFuture<AstContinuation>()
            val thread = Thread {
                context.enter()
                state.threads.enterCurrent()
                try {
                    val result = Calls.target(target, arrayOf(0L, cell, Unit)) as AstContinuation
                    (result.yielded as AsyncRequest).acknowledge()
                    answer.complete(result)
                } catch (failure: Throwable) { answer.completeExceptionally(failure) }
                finally { state.threads.leaveCurrent(); context.leave() }
            }
            thread.start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (cell.pendingCounts().takers != 1 && !answer.isDone && System.nanoTime() < deadline)
                    Thread.sleep(1)
                assertEquals(1, cell.pendingCounts().takers)
                state.threads.send(thread.threadId(), "cut")
                val continuation = answer.get(10, TimeUnit.SECONDS)
                thread.join(5000)
                assertFalse(thread.isAlive)
                assertEquals(before + 1,
                    (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                    "The admitted AST entry must be compiled at the captured cut")

                context.enter()
                try {
                    assertTrue(cell.tryPut("forty-one"))
                    val completed = continuation.continueWith(Unit)
                    val owned = ownedTupleResult(completed, shape)
                    assertEquals("forty-one", shape.layout.getObject(owned, 0))
                    if (nested) assertEquals(7L, shape.layout.getLong(owned, 1))
                    assertThrows(RuntimeFault::class.java) { continuation.continueWith(Unit) }
                    val handoff = shape.language.handoffState.get()
                    assertEquals(0, handoff.results.depth)
                    assertEquals(0, handoff.results.retainedReferences())
                    assertNull(handoff.pending)
                } finally { context.leave() }
            } finally {
                if (thread.isAlive) context.close(true)
                thread.join(5000)
            }
        }
    }

    @Test fun admittedTupleCaseKeepsItsLongSuffixAfterCompiledMVarCut() {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
            context.initialize("thc"); context.enter()
            val program: Program
            val target: com.oracle.truffle.api.RootCallTarget
            val state: Language.State
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                state = Language.currentState()
                program = Program(language, directMVarModule(caseLiteral = true), true)
                target = program.entryTarget("direct")
                assertNull((target.rootNode as FunctionRoot).handoff)
                repeat(5) {
                    val ready = ManagedMVar()
                    assertTrue(ready.tryPut("discarded"))
                    assertEquals(7L, Calls.target(target, arrayOf(0L, ready, Unit)))
                }
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            } finally { context.leave() }

            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
            val cell = ManagedMVar()
            val answer = CompletableFuture<AstContinuation>()
            val thread = Thread {
                context.enter(); state.threads.enterCurrent()
                try {
                    val captured = Calls.target(target, arrayOf(0L, cell, Unit)) as AstContinuation
                    (captured.yielded as AsyncRequest).acknowledge()
                    answer.complete(captured)
                } catch (failure: Throwable) { answer.completeExceptionally(failure) }
                finally { state.threads.leaveCurrent(); context.leave() }
            }
            thread.start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (cell.pendingCounts().takers != 1 && !answer.isDone && System.nanoTime() < deadline)
                    Thread.sleep(1)
                assertEquals(1, cell.pendingCounts().takers)
                state.threads.send(thread.threadId(), "cut")
                val captured = answer.get(10, TimeUnit.SECONDS)
                thread.join(5000)
                assertFalse(thread.isAlive)
                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                context.enter()
                try {
                    assertTrue(cell.tryPut("unused"))
                    assertEquals(7L, captured.continueWith(Unit))
                    assertTrue(cell.isEmpty(), "The tuple scrutinee must complete before its saved case suffix")
                    val handoff = TruffleLanguage.LanguageReference.create(Language::class.java).get(null).handoffState.get()
                    assertEquals(0, handoff.results.depth)
                    assertEquals(0, handoff.results.retainedReferences())
                } finally { context.leave() }
            } finally {
                if (thread.isAlive) context.close(true)
                thread.join(5000)
            }
        }
    }

    private class SharedBody(language: Language, private val cell: ManagedMVar,
                             private val prefix: AtomicInteger, private val unmask: Boolean) : GuestRoot(language, FrameLayout().build()) {
        val compiledEntry = AtomicBoolean()
        override fun bloom(frame: VirtualFrame) = 0L

        private class Read(private val owner: SharedBody, private val cell: ManagedMVar) : AstResumeStep {
            override fun resume(frame: VirtualFrame, input: Any?): Any? {
                if (input !== Unit) fault("Invalid resumed MVar input")
                return try { cell.take(owner, true) }
                catch (blocked: AsyncBlocked) {
                    throw AstCapture(blocked.request, SynchronousMasking.current(owner)).append(this)
                }
            }
        }

        override fun execute(frame: VirtualFrame): Any? {
            val prior = SynchronousMasking.current(this)
            if (unmask) SynchronousMasking.set(this, MaskingState.UNMASKED)
            return try {
                if (CompilerDirectives.inCompiledCode()) compiledEntry.set(true)
                prefix.incrementAndGet()
                try { cell.take(this, true) }
                catch (blocked: AsyncBlocked) {
                    throw AstCapture(blocked.request, SynchronousMasking.current(this)).append(Read(this, cell))
                }
            } catch (cut: AstCapture) { cut.freeze(this, frame.materialize()) }
            finally { SynchronousMasking.set(this, prior) }
        }
    }

    private class MaskedCaller(language: Language, private val child: SharedBody,
                               private val prefix: AtomicInteger, private val suffix: AtomicInteger) :
        GuestRoot(language, FrameLayout().build()) {
        override fun bloom(frame: VirtualFrame) = 0L

        private class ResumeChild(private val child: AstContinuation, private val suffix: AtomicInteger,
                                  private val owner: MaskedCaller) : AstResumeStep {
            override fun resume(frame: VirtualFrame, input: Any?): Any? {
                if (input !== Unit) fault("Invalid masked AST caller resume value")
                val result = child.continueWith(Unit)
                if (result is AstContinuation)
                    throw AstCapture(result.yielded, SynchronousMasking.current(owner)).append(ResumeChild(result, suffix, owner))
                suffix.incrementAndGet()
                return result
            }
        }

        override fun execute(frame: VirtualFrame): Any? {
            val prior = SynchronousMasking.current(this)
            SynchronousMasking.set(this, MaskingState.MASKED_UNINTERRUPTIBLE)
            return try {
                prefix.incrementAndGet()
                val result = Calls.target(child.callTarget, arrayOf(0L))
                if (result is AstContinuation)
                    throw AstCapture(result.yielded, SynchronousMasking.current(this)).append(ResumeChild(result, suffix, this))
                suffix.incrementAndGet()
                result
            } catch (cut: AstCapture) { cut.freeze(this, frame.materialize()) }
            finally { SynchronousMasking.set(this, prior) }
        }
    }

    private class ForceRoot(language: Language, private val thunk: Thunk) : RootNode(language, FrameLayout().build()) {
        @Child private var force = Force(Metrics(false), true)
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, thunk)
    }

    private fun exercise(maskedCaller: Boolean) {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
            .build().use { context ->
            context.initialize("thc")
            context.enter()
            val language: Language
            val state: Language.State
            val cell = ManagedMVar()
            val prefix = AtomicInteger()
            val callerPrefix = AtomicInteger()
            val callerSuffix = AtomicInteger()
            val thunk: Thunk
            val force: ForceRoot
            val body: SharedBody
            try {
                language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                state = Language.currentState()
                body = SharedBody(language, cell, prefix, maskedCaller)
                val target = if (maskedCaller) MaskedCaller(language, body, callerPrefix, callerSuffix).callTarget else body.callTarget
                repeat(5) {
                    cell.put(1L, body)
                    assertEquals(1L, Calls.target(target, arrayOf(0L)))
                }
                val compiled = body.callTarget
                compiled.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(compiled, true)
                assertEquals(true, compiled.javaClass.getMethod("isValidLastTier").invoke(compiled))
                body.compiledEntry.set(false)
                prefix.set(0); callerPrefix.set(0); callerSuffix.set(0)
                thunk = Thunk(target, null)
                force = ForceRoot(language, thunk)
            } finally { context.leave() }

            val targets = ArrayList<Thread>()
            fun startTarget(): Pair<Thread, CompletableFuture<Any?>> {
                val answer = CompletableFuture<Any?>()
                val target = Thread {
                    context.enter()
                    state.threads.enterCurrent()
                    try {
                        try { answer.complete(force.callTarget.call()) }
                        catch (suspended: ThunkSuspended) {
                            try { AsyncContinuations.publicSuspension(suspended, force) }
                            catch (guest: GuestException) { answer.complete(guest.payload) }
                        }
                    } catch (failure: Throwable) { answer.completeExceptionally(failure) }
                    finally { state.threads.leaveCurrent(); context.leave() }
                }
                targets.add(target)
                target.start()
                return target to answer
            }
            try {
                for (round in 1..2) {
                    val (target, answer) = startTarget()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline && target.state != Thread.State.WAITING) Thread.sleep(1)
                    assertEquals(Thread.State.WAITING, target.state, "AST target did not enter MVar wait $round")
                    val request = state.threads.send(target.threadId(), "stop $round")
                    assertEquals("stop $round", answer.get(10, TimeUnit.SECONDS))
                    target.join(5000)
                    assertFalse(target.isAlive)
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                    assertEquals(5, thunk.state)
                    assertEquals(1, prefix.get())
                    assertTrue(body.compiledEntry.get(), "The installed AST body must run before the cold cut")
                }

                context.enter()
                try {
                    cell.put(41L, force)
                    assertEquals(41L, force.callTarget.call())
                } finally { context.leave() }
                assertEquals(2, thunk.state)
                assertEquals(1, prefix.get(), "An observer must resume the saved MVar cut, not restart the body")
                if (maskedCaller) {
                    assertEquals(1, callerPrefix.get(), "A masked caller must not replay before the unmasking child")
                    assertEquals(1, callerSuffix.get(), "The caller suffix must run exactly once after the child")
                }
            } finally {
                if (targets.any { it.isAlive }) context.close(true)
                for (target in targets) target.join(5000)
            }
        }
    }

    @Test fun blockedAstThunkResumesOnAnotherJavaThreadWithoutReplayingPrefix() = exercise(false)

    @Test fun maskedCallerStillCapturesAnUnmaskingChild() = exercise(true)
}
