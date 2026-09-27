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
                                identityTail = true)
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
                        identityTail = true)
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
                            outerTarget, identityTail = true)
                    }
                })
                assertEquals(77L, Calls.target(outer.callTarget, arrayOf(0L)))
                assertEquals(2, entries); assertEquals(1, suffixes)
                assertEquals(2L, astStackScope(outer).tailAnchors)
                assertNull(astStackScope(outer).tailAnchor)
                assertEquals(0, astStackScope(outer).depth)
            } finally { context.leave() }
        }
    }

    private fun chain(nonTail: Boolean = false, cleanup: Int = -1, compiled: Boolean = false,
                      shared: Boolean = false, failure: Boolean = false, caught: Boolean = false) {
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
                val count = 1024
                val laps = if (compiled) 1 else 20
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
                lateinit var owner: FunctionRoot
                var next: RootCallTarget? = null
                for (index in count - 1 downTo 0) {
                    val following = next
                    val body = object : Expr() {
                        @field:Child private var call = following?.let { DirectCallNode.create(it) }
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
                                    return 73L
                                }
                                tail.check(frame, owner.callTarget, arrayOf(bloom))
                                fail<Any>("The exact retained owner must catch the next transfer")
                            }
                            if (index != cleanup) return AstControl.complete(this,
                                Calls.direct(call!!, arrayOf(bloom)), following, identityTail = true)
                            var captured = false
                            SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE)
                            try {
                                return AstControl.complete(this, Calls.direct(call!!, arrayOf(bloom)), following,
                                    identityTail = true)
                            } catch (cut: AstCapture) {
                                captured = true
                                throw cut.enclose { steps -> object : AstResumeStep {
                                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                                        SynchronousMasking.set(owner, MaskingState.MASKED_INTERRUPTIBLE)
                                        try { return resumeAstSteps(frame, steps, input) }
                                        catch (guest: GuestException) {
                                            if (!caught) throw guest
                                            assertSame(payload, guest.payload); catches++; return 73L
                                        }
                                        finally { cleanups++; SynchronousMasking.set(owner, ambient) }
                                    }
                                } }
                            } catch (guest: GuestException) {
                                if (!caught) throw guest
                                assertSame(payload, guest.payload); catches++; return 73L
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
                            identityTail = true)
                    }
                }
                owner = FunctionRoot(language, FrameLayout().build(), "retained owner", null, intArrayOf(),
                    intArrayOf(), intArrayOf(), body, metrics, resultProof = proof, stackCapture = true)
                val scope = astStackScope(owner)
                val target = if (!nonTail) owner.callTarget else {
                    val outer = object : Expr() {
                        @field:Child private var caller = DirectCallerNode(owner.callTarget, metrics)
                        init { representation = proof }
                        override fun execute(frame: VirtualFrame): Any? {
                            outerPrefixes++
                            val answer = try { caller.call(frame, arrayOf(0L), false) }
                            catch (cut: AstCapture) { throw cut.append(object : AstResumeStep {
                                override fun resume(frame: VirtualFrame, input: Any?): Any {
                                    outerSuffixes++; return (input as Long) + 4L
                                }
                            }) }
                            outerSuffixes++; return (answer as Long) + 4L
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
                        else assertEquals(73L, Calls.target(force, emptyArray()))
                    }
                    assertEquals(if (failure) 3 else 2, thunk.state); assertNull(thunk.owner)
                } else assertEquals(if (nonTail) 77L else 73L, Calls.target(target, arrayOf(0L)))
                if (compiled) {
                    assertEquals(beforeCompiled + 1, metrics.compiledEntries, "The first invocation entered the installed root")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target),
                        "No settling invocation or compilation retry is allowed")
                }
                assertEquals(laps, iterations)
                assertTrue(prefixes.all { it == laps }, "Every side prefix executes exactly once per real loop iteration")
                assertTrue(scope.compactedFrames > count * laps - 128 * laps)
                assertEquals(1L, scope.tailAnchors, "One saved-suffix catcher, not one wrapper per spill/lap")
                assertTrue(scope.maxParkedSpillParents <= 2,
                    "Identity sides are omitted before publication; only the real cleanup/non-tail parent may park")
                assertEquals(0L, metrics.trampolineIterations)
                assertEquals(if (nonTail) 1 else 0, outerPrefixes)
                assertEquals(if (nonTail) 1 else 0, outerSuffixes)
                assertEquals(if (cleanup >= 0) laps else 0, cleanups)
                assertEquals(if (caught) 1 else 0, catches)
                assertEquals(0, payload.state, "Catch/update transport never enters the exception payload")
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(owner))
                assertNull(scope.tailAnchor)
                assertEquals(0, scope.depth); assertFalse(scope.driving)
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }
}
