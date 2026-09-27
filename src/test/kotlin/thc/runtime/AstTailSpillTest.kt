// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.lang.management.ManagementFactory
import com.sun.management.HotSpotDiagnosticMXBean

class AstTailSpillTest {
    private val proof = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))

    @Test fun longIdentitySideChainRetainsOneLiveAnchorAndNeverReplaysItsPrefix() = chain()

    @Test fun retainedNonTailCallerRunsItsSuffixOnceAfterTheCompactedLoop() = chain(nonTail = true)

    @Test fun savedMaskCleanupUnwindsBeforeTheMatchingTailAnchor() = chain(cleanup = 256)

    @Test fun maskAlreadyLiveAtFirstSpillRetainsTheExactOuterCatcher() = chain(cleanup = 16)

    @Test fun firstInstalledEntrySpillsWithoutASettlingCall() = chain(compiled = true)

    @Test fun sharedUpdatePublishesOnlyTheCompletedCompactedResult() = chain(shared = true)

    @Test fun failedSharedUpdateKeepsItsFailureWithoutReplayingTheCompactedPrefix() =
        chain(shared = true, failure = true, cleanup = 16)

    @Test fun savedCatchKeepsTheOriginalUnforcedPayloadAndMaskCleanup() = chain(caught = true, cleanup = 16)

    @Test fun constructorIdentityAndLazyFieldSurviveFirstInstalledOutlinedSpill() =
        chain(kind = CoreKind.DATA, compiled = true)

    @Test fun closureIdentityAndUnenteredBodySurviveFirstInstalledOutlinedSpill() =
        chain(kind = CoreKind.CLOSURE, compiled = true)

    @Test fun addressIdentityAndBackingSurviveFirstInstalledOutlinedSpill() =
        chain(kind = CoreKind.ADDRESS, compiled = true)

    @Test fun referenceLoopKeepsMaskCleanupAndNonTailSuffixOnce() =
        chain(kind = CoreKind.DATA, nonTail = true, cleanup = 16)

    @Test fun referenceSharedUpdateKeepsItsOriginalObject() = chain(kind = CoreKind.CLOSURE, shared = true)

    @Test fun referenceCatchKeepsLazyExceptionAndAddressOwnership() =
        chain(kind = CoreKind.ADDRESS, caught = true, cleanup = 16)

    @Test fun lazyReferenceResultKeepsOrdinaryForceCompletion() =
        chain(kind = CoreKind.DATA, evaluated = false, compact = false)

    @Test fun unknownObjectResultKeepsOrdinaryCompletion() = chain(kind = CoreKind.OBJECT, compact = false)

    private fun checkRequestedStackSize() {
        System.getProperty("thc.test.stackKiB")?.let { expected ->
            assertEquals(expected, ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
                .getVMOption("ThreadStackSize").value, "Observe the actual bounded test JVM, not argument order")
        }
    }

    @Test fun nestedAnchorDoesNotCaptureTailTransfersAcrossItsNonTailCaller() {
        checkRequestedStackSize()
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val metrics = Metrics(true)
                fun root(label: String, body: Expr, side: Boolean = false) = FunctionRoot(language,
                    FrameLayout().build(), label, null, intArrayOf(), intArrayOf(), intArrayOf(),
                    body, metrics, resultProof = proof, role = if (side) FunctionRootRole.PASS_THROUGH else FunctionRootRole.FUNCTION,
                    stackCapture = true)
                fun sides(label: String, last: Expr): RootCallTarget {
                    var target = root("$label-final", last, true).callTarget
                    repeat(128) { index ->
                        val child = target
                        target = root("$label-$index", object : Expr() {
                            @field:Child private var call = DirectCallNode.create(child)
                            init { representation = proof }
                            override fun execute(frame: VirtualFrame): Any? = AstControl.complete(this,
                                Calls.direct(call, arrayOf((rootNode as GuestRoot).bloom(frame))), child,
                                null, true)
                        }, true).callTarget
                    }
                    return target
                }
                lateinit var outer: FunctionRoot
                var entries = 0
                var suffixes = 0
                val innerTarget = sides("inner", object : Expr() {
                    @field:Child private var caller = IndirectCallerNode(metrics)
                    init { representation = proof }
                    override fun execute(frame: VirtualFrame): Any? = caller.call(frame, outer.callTarget,
                        arrayOf((rootNode as GuestRoot).bloom(frame)), true)
                })
                val inner = root("inner normal anchor", object : Expr() {
                    @field:Child private var call = DirectCallNode.create(innerTarget)
                    init { representation = proof }
                    override fun execute(frame: VirtualFrame): Any? = AstControl.complete(this,
                        Calls.direct(call, arrayOf((rootNode as GuestRoot).bloom(frame))), innerTarget,
                        null, true)
                })
                val outerTarget = sides("outer", object : Expr() {
                    @field:Child private var caller = DirectCallerNode(inner.callTarget, metrics)
                    init { representation = proof }
                    override fun execute(frame: VirtualFrame): Any? {
                        val value = try { caller.call(frame, arrayOf(0L), false) }
                        catch (cut: AstCapture) { throw cut.append(object : AstResumeStep {
                            override fun resume(frame: VirtualFrame, input: Any?): Any {
                                suffixes++; return (input as Long) + 4
                            }
                        }) }
                        suffixes++; return (value as Long) + 4
                    }
                })
                outer = root("outer normal anchor", object : Expr() {
                    @field:Child private var call = DirectCallNode.create(outerTarget)
                    init { representation = proof }
                    override fun execute(frame: VirtualFrame): Any? {
                        check(++entries <= 2) { "A non-tail call's saved prefix replayed" }
                        if (entries == 2) return 73L
                        return AstControl.complete(this, Calls.direct(call, arrayOf((rootNode as GuestRoot).bloom(frame))),
                            outerTarget, null, true)
                    }
                })
                assertEquals(77L, Calls.target(outer.callTarget, arrayOf(0L)))
                assertEquals(2, entries); assertEquals(1, suffixes)
                assertEquals(2L, AstStackKt.astStackScope(outer).tailAnchors)
                assertNull(AstStackKt.astStackScope(outer).tailAnchor)
                assertEquals(0, AstStackKt.astStackScope(outer).depth)
            } finally { context.leave() }
        }
    }

    private fun chain(nonTail: Boolean = false, cleanup: Int = -1, compiled: Boolean = false,
                      shared: Boolean = false, failure: Boolean = false, caught: Boolean = false,
                      kind: CoreKind = CoreKind.LONG, evaluated: Boolean = true, compact: Boolean = true) {
        checkRequestedStackSize()
        val builder = Context.newBuilder("thc").allowExperimentalOptions(true)
        if (compiled) builder.option("engine.BackgroundCompilation", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false")
        else builder.option("engine.Compilation", "false")
        builder.build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val metrics = Metrics(true)
                val proof = if (kind == CoreKind.LONG) this.proof else CoreRepresentation(kind, evaluated, true,
                    listOf(if (kind == CoreKind.ADDRESS) "AddrRep" else "BoxedRep (Just Lifted)"))
                val count = 1024
                val laps = if (compiled || !compact) 1 else 20
                val prefixes = IntArray(count)
                var iterations = 0
                var finished = 0
                var cleanups = 0
                var catches = 0
                var outerPrefixes = 0
                var outerSuffixes = 0
                val payload = Thunk(object : RootNode(language) {
                    override fun execute(frame: VirtualFrame): Nothing = error("Exception payload was forced")
                }.callTarget, null)
                val bytes = byteArrayOf(11, 37, 59)
                val expected: Any = when (kind) {
                    CoreKind.DATA, CoreKind.OBJECT -> DataLayout(language, "TailBox", "TailBox", arrayOf("LiftedRep"))
                        .create(arrayOf(payload))
                    CoreKind.CLOSURE -> Closure(null, arity = 1, target = object : RootNode(language) {
                        override fun execute(frame: VirtualFrame): Nothing = error("Returned closure was entered")
                    }.callTarget)
                    CoreKind.ADDRESS -> ManagedAddress.fromByteArray(bytes).plus(1)
                    else -> 73L
                }
                val resultThunk = if (evaluated) null else Thunk(object : RootNode(language) {
                    override fun execute(frame: VirtualFrame): Any = expected
                }.callTarget, null)
                fun checkAnswer(answer: Any?) {
                    if (kind == CoreKind.LONG) assertEquals(if (nonTail) 77L else 73L, answer)
                    else assertSame(expected, answer, "No copying, forcing, or carrier substitution at a tail return")
                }
                lateinit var owner: FunctionRoot
                var next: RootCallTarget? = null
                for (index in count - 1 downTo 0) {
                    val following = next
                    val body = object : Expr() {
                        @field:Child private var call = following?.let { DirectCallNode.create(it) }
                        @field:Child private var arm = if (kind == CoreKind.LONG) null else following?.let {
                            AstCaseArm(it, null, intArrayOf(), true)
                        }
                        @field:Child private var tail = TailCheck(metrics)
                        init { representation = proof }
                        override fun execute(frame: VirtualFrame): Any? {
                            prefixes[index]++
                            val bloom = (rootNode as GuestRoot).bloom(frame)
                            assertEquals(owner.mask, bloom, "Omitted side roots never claim catchers")
                            val ambient = SynchronousMasking.current(this)
                            assertEquals(if (cleanup >= 0 && index > cleanup) MaskingState.MASKED_INTERRUPTIBLE
                                else MaskingState.UNMASKED, ambient)
                            if (following == null) {
                                if (++finished == laps) {
                                    if (failure || caught) throw GuestException(payload, this)
                                    return resultThunk ?: expected
                                }
                                tail.check(frame, owner.callTarget, arrayOf(bloom))
                                fail<Any>("The exact retained owner must catch the next transfer")
                            }
                            if (index != cleanup) return if (arm != null) arm!!.execute(frame) else AstControl.complete(this,
                                Calls.direct(call!!, arrayOf(bloom)), following, null, true)
                            var captured = false
                            SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE)
                            try {
                                return if (arm != null) arm!!.execute(frame) else AstControl.complete(this,
                                    Calls.direct(call!!, arrayOf(bloom)), following, null, true)
                            } catch (cut: AstCapture) {
                                captured = true
                                throw cut.enclose { steps -> object : AstResumeStep {
                                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                                        SynchronousMasking.set(owner, MaskingState.MASKED_INTERRUPTIBLE)
                                        try { return AstContinuationKt.resumeAstSteps(frame, steps, input) }
                                        catch (guest: GuestException) {
                                            if (!caught) throw guest
                                            assertSame(payload, guest.payload); catches++; return expected
                                        }
                                        finally { cleanups++; SynchronousMasking.set(owner, ambient) }
                                    }
                                } }
                            } catch (guest: GuestException) {
                                if (!caught) throw guest
                                assertSame(payload, guest.payload); catches++; return expected
                            } finally {
                                if (!captured) cleanups++
                                SynchronousMasking.set(this, ambient)
                            }
                        }
                    }
                    next = FunctionRoot(language, FrameLayout().build(), "side-$index", null, intArrayOf(),
                        intArrayOf(), intArrayOf(), body, metrics, resultProof = proof,
                        role = FunctionRootRole.PASS_THROUGH, stackCapture = true).callTarget
                }
                val first = next!!
                val body = object : Expr() {
                    @field:Child private var call = DirectCallNode.create(first)
                    init { representation = proof }
                    override fun execute(frame: VirtualFrame): Any? {
                        iterations++
                        return AstControl.complete(this, Calls.direct(call, arrayOf(owner.bloom(frame))), first,
                            null, true)
                    }
                }
                owner = FunctionRoot(language, FrameLayout().build(), "retained owner", null, intArrayOf(),
                    intArrayOf(), intArrayOf(), body, metrics, resultProof = proof, stackCapture = true)
                val scope = AstStackKt.astStackScope(owner)
                val target = if (!nonTail) owner.callTarget else {
                    val outer = object : Expr() {
                        @field:Child private var caller = DirectCallerNode(owner.callTarget, metrics)
                        init { representation = proof }
                        override fun execute(frame: VirtualFrame): Any? {
                            outerPrefixes++
                            val answer = try { caller.call(frame, arrayOf(0L), false) }
                            catch (cut: AstCapture) { throw cut.append(object : AstResumeStep {
                                override fun resume(frame: VirtualFrame, input: Any?): Any {
                                    outerSuffixes++; return if (kind == CoreKind.LONG) (input as Long) + 4L else input!!
                                }
                            }) }
                            outerSuffixes++; return if (kind == CoreKind.LONG) (answer as Long) + 4L else answer
                        }
                    }
                    FunctionRoot(language, FrameLayout().build(), "non-tail outer", null, intArrayOf(),
                        intArrayOf(), intArrayOf(), outer, metrics, resultProof = proof, stackCapture = true).callTarget
                }
                if (compiled) {
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                        .invoke(runtime, target)
                }
                val beforeCompiled = metrics.compiledEntries
                if (shared) {
                    val thunk = Thunk(target, null)
                    val force = object : RootNode(language) {
                        @Child private var force = Force(metrics)
                        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, thunk)
                    }.callTarget
                    repeat(2) {
                        if (failure) assertThrows(GuestException::class.java) { Calls.target(force, emptyArray()) }
                        else checkAnswer(Calls.target(force, emptyArray()))
                    }
                    assertEquals(if (failure) 3 else 2, thunk.state); assertNull(thunk.owner)
                } else checkAnswer(Calls.target(target, arrayOf(0L)))
                if (compiled) {
                    assertEquals(beforeCompiled + 1, metrics.compiledEntries, "The first invocation entered the installed root")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target),
                        "No settling invocation or compilation retry is allowed")
                }
                assertEquals(laps, iterations)
                assertTrue(prefixes.all { it == laps }, "Every side prefix executes exactly once per real loop iteration")
                if (compact) {
                    assertTrue(scope.compactedFrames > count * laps - 128 * laps)
                    assertEquals(1L, scope.tailAnchors, "One saved-suffix catcher, not one wrapper per spill/lap")
                    assertTrue(scope.maxParkedSpillParents <= 2,
                        "Identity sides are omitted before publication; only the real cleanup/non-tail parent may park")
                } else {
                    assertEquals(0L, scope.compactedFrames)
                    assertEquals(0L, scope.tailAnchors)
                }
                assertEquals(0L, metrics.trampolineIterations)
                assertEquals(if (nonTail) 1 else 0, outerPrefixes)
                assertEquals(if (nonTail) 1 else 0, outerSuffixes)
                assertEquals(if (cleanup >= 0) laps else 0, cleanups)
                assertEquals(if (caught) 1 else 0, catches)
                assertEquals(0, payload.state, "Catch/update transport never enters the exception payload")
                if (expected is DataValue) assertSame(payload, expected.layout.read(expected, 0))
                if (expected is ManagedAddress) {
                    bytes[1] = 83
                    assertEquals(83L, expected.readWord8(0), "The exact address retains its original backing and offset")
                }
                if (resultThunk != null) assertEquals(2, resultThunk.state, "Lazy results still run their required force")
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(owner))
                assertNull(scope.tailAnchor)
                assertEquals(0, scope.depth); assertFalse(scope.driving)
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }
}
