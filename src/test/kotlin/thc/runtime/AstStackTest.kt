// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class AstStackTest {
    @Test fun synchronousCaptureCapabilityDrainsSpillsWithoutEnablingDeliveryOrReplayingPrefixes() {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = Language.currentState()
                state.threads.enterCurrent(externalAsync = false)
                try {
                    state.maskingState.set(MaskingState.MASKED_INTERRUPTIBLE)
                    val proof = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
                    val layout = FrameLayout(); val argument = layout.bind("depth")
                    var prefixes = 0
                    var suffixes = 0
                    val body = object : Expr() {
                        @field:Child private var caller: DirectCallerNode? = null
                        init { representation = proof }
                        fun install(target: com.oracle.truffle.api.RootCallTarget) {
                            caller = insert(DirectCallerNode(target, Metrics(false)))
                        }
                        override fun execute(frame: VirtualFrame): Any = executeLong(frame)
                        override fun executeLong(frame: VirtualFrame): Long {
                            assertTrue(AstControl.captures(this))
                            assertFalse(AstControl.enabled(this), "Internal spilling does not grant external delivery")
                            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this))
                            prefixes++
                            val n = frame.getLong(argument)
                            if (n == 0L) return 1L
                            val result = try { caller!!.call(frame, arrayOf(0L, n - 1), false) }
                            catch (cut: AstCapture) {
                                throw cut.append(object : AstResumeStep {
                                    override fun resume(frame: VirtualFrame, input: Any?): Any {
                                        suffixes++
                                        return (input as Long) + 1
                                    }
                                })
                            }
                            suffixes++
                            return (result as Long) + 1
                        }
                    }
                    val root = FunctionRoot(language, layout.build(), "sync spilled suffix", null,
                        intArrayOf(), intArrayOf(argument), intArrayOf(0), body,
                        Metrics(false), arrayOf(proof), proof, body.coreSourceLocation,
                        booleanArrayOf(), null, null, intArrayOf(),
                        null, false, emptyArray(), false,
                        FunctionRootRole.FUNCTION, true)
                    body.install(root.callTarget)
                    assertFalse(root.enableAsync)
                    assertEquals(4097L, Calls.target(root.callTarget, arrayOf(0L, 4096L)))
                    assertEquals(4097, prefixes); assertEquals(4096, suffixes)
                    val stack = state.threadPollState.get().astStack
                    assertTrue(stack.spills > 0); assertEquals(0, stack.depth); assertFalse(stack.driving)
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.maskingState.get())
                    assertEquals(0, language.handoffState.get().results.depth)
                } finally { state.threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun synchronousPassThroughEntrySpillResumesOnceWithoutInstallingATailCatcher() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val body = object : Expr() {
                    lateinit var transfer: TailCall
                    var entries = 0
                    override fun execute(frame: VirtualFrame): Nothing {
                        entries++
                        check(entries == 1) { "Pass-through resume installed a loop" }
                        throw transfer
                    }
                }
                val root = FunctionRoot(language, FrameLayout().build(), "sync parked side exit", null,
                    intArrayOf(), intArrayOf(), intArrayOf(), body,
                    Metrics(false), emptyArray(), body.representation, body.coreSourceLocation,
                    booleanArrayOf(), null, null, intArrayOf(),
                    null, false, emptyArray(), false,
                    FunctionRootRole.PASS_THROUGH, true)
                body.transfer = TailCall(root.callTarget, arrayOf(0L))
                val stack = AstStackKt.astStackScope(root)
                stack.depth = AstStackScope.MAX_DEPTH - 1
                stack.driving = true
                val saved = try { Calls.target(root.callTarget, arrayOf(0L)) as AstContinuation }
                    finally { stack.depth = 0; stack.driving = false }
                assertTrue(saved.stackSpill()); assertNull(saved.asyncRequest())
                assertEquals(0, body.entries, "Entry spill precedes the side body's effects")
                assertSame(body.transfer, assertThrows(TailCall::class.java) { saved.continueWith(Unit) })
                assertEquals(1, body.entries); assertEquals(0, stack.depth); assertFalse(stack.driving)
                assertThrows(RuntimeFault::class.java) { saved.continueWith(Unit) }
            } finally { context.leave() }
        }
    }

    @Test fun stackSpillRecognizesOnlyThePrivateIdentityAndTypedSuspensions() {
        fun saved(marker: Any?) = object : SavedGuestContinuation {
            override fun getIdentity(): Any = this
            override fun getYielded(): Any? = marker
            private val savedRoot = Any()
            override fun getSourceRoot(): Any = savedRoot
            override fun continueWith(input: Any?): Any? = error("Classification must not resume a continuation")
        }
        val spoof = object {
            override fun equals(other: Any?): Boolean = true
            override fun hashCode(): Int = 0
        }
        val hostile = object {
            override fun equals(other: Any?): Boolean = error("Classification must not invoke guest equality")
            override fun hashCode(): Int = 0
        }
        assertTrue(saved(AstStackSpill.INSTANCE).stackSpill())
        for (marker in listOf(null, Any(), spoof, hostile)) assertFalse(saved(marker).stackSpill())
        val thunk = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any? = error("Classification must not force a thunk")
        }.callTarget, null)
        val segment = CallSegment(saved(null))
        for (spill in listOf(false, true)) {
            assertEquals(spill, saved(ThunkSuspended(thunk, null, spill)).stackSpill())
            assertEquals(spill, saved(CallSegmentSuspended(segment, null, SavedGuestContinuationKt.savedGuestContinuation(segment.value)?.asyncRequest(), spill)).stackSpill())
        }
    }

    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun variable(id: String) = listOf("var", id)
    private fun literal(value: Int) = listOf("lit", "int", value.toString())
    private fun apply(function: Any, vararg arguments: Any) =
        listOf("app", function, arguments.toList(), List(arguments.size) { false })
    private fun prim(name: String, vararg arguments: Any) = apply(listOf("prim", name), *arguments)
    private fun bind(value: Any, name: String, body: Any) =
        listOf("case", value, name, listOf(listOf("default", null, emptyList<String>(), body)))
    private fun module(): Map<String, Any?> {
        val recurse = bind(apply(variable("loop"), prim("-#", variable("n"), literal(1)), variable("tick")), "answer",
            prim("+#", variable("answer"), variable("prefix")))
        val choice = listOf("case", variable("n"), "choice", listOf(
            listOf("lit", listOf("int", "0"), emptyList<String>(), variable("prefix")),
            listOf("default", null, emptyList<String>(), recurse)))
        val body = bind(apply(variable("tick"), variable("n")), "prefix", choice)
        val parameters = listOf("n" to long, "tick" to closure).map { (name, rep) ->
            mapOf("id" to name, "name" to name, "lifted" to (name == "tick"), "rep" to rep)
        }
        return mapOf("bindings" to listOf(mapOf("id" to "loop", "name" to "loop", "lifted" to true,
            "expr" to listOf("lam", parameters, body, mapOf("rep" to closure, "resultRep" to long)))))
    }

    private fun caughtModule(): Map<String, Any?> {
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val boxed = closure + ("kind" to "data")
        val io = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
            "primReps" to listOf("BoxedRep (Just Lifted)"), "components" to listOf(state, boxed), "evaluated" to true)
        fun binder(id: String, rep: Map<String, Any>, lifted: Boolean = false) =
            mapOf("id" to id, "name" to id, "lifted" to lifted, "rep" to rep)
        fun lambda(parameters: List<Any>, body: Any, result: Map<String, Any>) =
            listOf("lam", parameters, body, mapOf("rep" to closure, "resultRep" to result))
        fun caseOf(value: Any, rep: Map<String, Any>, body: Any, result: Map<String, Any>) =
            listOf("case", value, "ignored", listOf(listOf("default", null, emptyList<String>(), body)),
                mapOf("rep" to result, "binder" to binder("ignored", rep)))
        val void = listOf("void", mapOf("rep" to state))
        val unit = listOf("con", "Unit", 0, mapOf("rep" to boxed))
        val pair = listOf("app", listOf("con", "Pair", 2), listOf(void, unit),
            listOf(false, true), false, false, mapOf("rep" to io))
        val action = lambda(listOf(binder("s", state)),
            caseOf(apply(variable("loop"), variable("n"), variable("tick")), long, pair, io), io)
        val handler = lambda(listOf(binder("e", boxed, true), binder("t", state)), pair, io)
        val caught = listOf("app", listOf("prim", "catch#"), listOf(action, handler, void),
            listOf(true, true, false), false, false, mapOf("rep" to io))
        val body = caseOf(caught, io, literal(777), long)
        return module() + mapOf("constructors" to listOf(
            mapOf("id" to "Unit", "name" to "()", "arity" to 0, "tag" to 1,
                "fieldReps" to emptyList<List<String>>(), "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>()),
            mapOf("id" to "Pair", "name" to "(#,#)", "arity" to 2, "tag" to 1, "kind" to "unboxed-tuple")),
            "bindings" to (module().getValue("bindings") as List<*>) + listOf(
                binder("caught", closure, true) + ("expr" to lambda(
                    listOf(binder("n", long), binder("tick", closure, true)), body, long))))
    }

    @Test fun spillsRetainSharedUpdatesPrefixesMasksAndMemoizedFailure() {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = Language.currentState()
                state.threads.enterCurrent()
                try {
                    state.maskingState.set(MaskingState.MASKED_INTERRUPTIBLE)
                    val program = Program(language, module(), true)
                    var effects = 0
                    var failAt = -1
                    val tick = Closure(environment = null, arity = 1, target = object : RootNode(language) {
                        override fun execute(frame: VirtualFrame): Any {
                            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.maskingState.get())
                            effects++
                            if (effects == failAt) throw GuestException("expected failure", this)
                            return 1L
                        }
                    }.callTarget)
                    fun shared() = Thunk(object : RootNode(language) {
                        override fun execute(frame: VirtualFrame): Any? = Calls.target(program.hostEntryTarget(2),
                            arrayOf(program.entryValue("loop"), arrayOf(4096L, tick)))
                    }.callTarget, null)
                    val force = object : RootNode(language) {
                        @Child var node = Force(Metrics(false), true)
                        override fun execute(frame: VirtualFrame): Any? = node.execute(frame, frame.arguments[0])
                    }.callTarget
                    val value = shared()
                    assertEquals(4097L, Calls.target(force, arrayOf(value)))
                    assertEquals(4097, effects)
                    assertEquals(4097L, Calls.target(force, arrayOf(value)))
                    assertEquals(4097, effects, "A completed shared update never replays its prefix")
                    assertEquals(2, value.state); assertNull(value.owner)
                    val scope = state.threadPollState.get().astStack
                    assertTrue(scope.spills > 0); assertEquals(0, scope.depth); assertFalse(scope.driving)
                    failAt = effects + 300
                    val failed = shared()
                    assertThrows(GuestException::class.java) { Calls.target(force, arrayOf(failed)) }
                    assertEquals(failAt, effects)
                    assertThrows(GuestException::class.java) { Calls.target(force, arrayOf(failed)) }
                    assertEquals(failAt, effects, "A guest failure remains memoized across a spilled update")
                    assertEquals(3, failed.state); assertNull(failed.owner)
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, state.maskingState.get())
                    assertEquals(0, scope.depth); assertFalse(scope.driving)
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                } finally { state.threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun autonomousDriverLeavesAsyncDeliveryForItsRealHandler() {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = Language.currentState()
                state.threads.enterCurrent()
                try {
                    val program = Program(language, module(), true)
                    var effects = 0
                    var request: AsyncRequest? = null
                    val tick = Closure(null, arity = 1, target = object : RootNode(language) {
                        override fun execute(frame: VirtualFrame): Any {
                            if (++effects == 100)
                                request = state.threads.send(state.threads.currentIdentity(), "interrupt after spill")
                            return 1L
                        }
                    }.callTarget)
                    val result = Calls.target(program.hostEntryTarget(2),
                        arrayOf(program.entryValue("loop"), arrayOf(4096L, tick)))
                    val saved = SavedGuestContinuationKt.savedGuestContinuation(result) ?: error("Expected the actual async continuation")
                    assertSame(request, saved.asyncRequest()); assertFalse(saved.stackSpill())
                    assertEquals(AsyncRequestState.CLAIMED, request!!.state)
                    assertEquals(100, effects, "The autonomous driver must stop before delivering an async request")
                    val scope = state.threadPollState.get().astStack
                    assertTrue(scope.spills > 0); assertEquals(0, scope.depth); assertFalse(scope.driving)
                    request!!.acknowledge()
                    val parked = Thunk((saved.sourceRoot as RootNode).callTarget, null).also {
                        it.value = saved.identity; it.state = 5
                    }
                    val force = object : RootNode(language) {
                        @Child var node = Force(Metrics(false), true)
                        override fun execute(frame: VirtualFrame): Any? = node.execute(frame, parked)
                    }.callTarget
                    assertEquals(4097L, Calls.target(force, emptyArray()))
                    assertEquals(4097, effects, "Resumption retains all prefixes from before the async cut")
                    assertEquals(2, parked.state); assertNull(parked.owner)
                } finally { state.threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun asyncDeliveryReachesItsCatchScopeSavedBeforeTheSpill() {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = Language.currentState()
                state.threads.enterCurrent()
                try {
                    val program = Program(language, caughtModule(), true)
                    val payload = program.constructorLayout("Unit").create(emptyArray())
                    var effects = 0
                    var request: AsyncRequest? = null
                    val tick = Closure(null, arity = 1, target = object : RootNode(language) {
                        override fun execute(frame: VirtualFrame): Any {
                            if (++effects == 100) request = state.threads.send(state.threads.currentIdentity(), payload)
                            return 1L
                        }
                    }.callTarget)
                    assertEquals(777L, Calls.target(program.hostEntryTarget(2),
                        arrayOf(program.entryValue("caught"), arrayOf(4096L, tick))))
                    assertEquals(100, effects); assertEquals(AsyncRequestState.ACKNOWLEDGED, request!!.state)
                    assertEquals(MaskingState.UNMASKED, state.maskingState.get())
                    val scope = state.threadPollState.get().astStack
                    assertTrue(scope.spills > 0); assertEquals(0, scope.depth); assertFalse(scope.driving)
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                } finally { state.threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun transactionLimitRollsBackWithoutPublishingAnAutonomousCut() {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = Language.currentState()
                state.threads.enterCurrent()
                try {
                    val program = Program(language, module(), true)
                    val tick = Closure(null, arity = 1, target = object : RootNode(language) {
                        override fun execute(frame: VirtualFrame): Any = 1L
                    }.callTarget)
                    val cell = state.stm.newTVar(17L)
                    val failure = assertThrows(UnsupportedCore::class.java) {
                        state.stm.atomically(null, { error("Unexpected nested transaction") }) {
                            state.stm.write(cell, 99L)
                            Calls.target(program.hostEntryTarget(2), arrayOf(program.entryValue("loop"), arrayOf(4096L, tick)))
                        }
                    }
                    assertTrue(failure.message!!.contains("active STM transaction"))
                    assertEquals(17L, state.stm.readIO(cell)); assertFalse(state.stm.hasTransaction())
                    val scope = state.threadPollState.get().astStack
                    assertEquals(0L, scope.spills); assertEquals(0, scope.depth); assertFalse(scope.driving)
                    assertEquals(4097L, Calls.target(program.hostEntryTarget(2),
                        arrayOf(program.entryValue("loop"), arrayOf(4096L, tick))))
                } finally { state.threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun reparkedParentForwardsTheClaimedRequestToItsSavedCaller() {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = Language.currentState()
                state.threads.enterCurrent()
                try {
                    val program = Program(language, module(), true)
                    val root = (program.entryValue("loop") as Closure).target.rootNode as GuestRoot
                    fun saved(marker: Any?, action: (Any?) -> Any?) = AstContinuation(root, marker,
                        MaskingState.UNMASKED,
                        com.oracle.truffle.api.Truffle.getRuntime().createMaterializedFrame(emptyArray(), root.frameDescriptor),
                        listOf(object : AstResumeStep {
                            override fun resume(frame: VirtualFrame, input: Any?): Any? = action(input)
                        }), StackAnnotations.current(root))
                    fun parked(record: SavedGuestContinuation) = Thunk(root.callTarget, null).also {
                        it.value = record.identity; it.state = 5
                    }
                    lateinit var parent: Thunk
                    lateinit var child: Thunk
                    lateinit var request: AsyncRequest
                    var prefixes = 0
                    val childRecord = object : SavedGuestContinuation {
                        override fun getIdentity(): Any = this
                        override fun getSourceRoot(): Any = root
                        override fun getYielded(): Any = Unit
                        override fun continueWith(input: Any?): Any? {
                            prefixes++
                            // Deterministically model another evaluator re-parking the
                            // parent after this driver recorded its previous identity.
                            synchronized(parent.monitor) {
                                parent.value = saved(ThunkSuspended(child)) { error("Reparked parent resumed before delivery") }
                            }
                            request = state.threads.send(state.threads.currentIdentity(), "race payload")
                            assertSame(request, state.threads.poll(root))
                            return saved(request) { 19L }
                        }
                    }
                    child = parked(childRecord)
                    parent = parked(saved(ThunkSuspended(child)) { error("Stale parent continuation resumed") })
                    val outer = parked(saved(ThunkSuspended(parent)) { input ->
                        val cut = input as AstChildSuspension
                        assertSame(parent, cut.child); assertSame(request, cut.request)
                        assertEquals(AsyncRequestState.CLAIMED, request.state)
                        request.acknowledge()
                        777L
                    })
                    val driver = object : RootNode(language) {
                        @Child var force = Force(Metrics(false), true)
                        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, outer)
                    }.callTarget
                    assertEquals(777L, Calls.target(driver, emptyArray()))
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                    assertEquals(1, prefixes); assertEquals(2, outer.state)
                    assertEquals(5, parent.state); assertEquals(5, child.state)
                    assertNull(parent.owner); assertNull(child.owner)
                } finally { state.threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun reentrantEntryGetsItsOwnDriverWithoutChangingTheOuterDepth() {
        Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = Program(language, module(), true)
                val tick = Closure(null, arity = 1, target = object : RootNode(language) {
                    override fun execute(frame: VirtualFrame): Any = 1L
                }.callTarget)
                val state = Language.currentState()
                state.threads.enterCurrent()
                val outer = state.threadPollState.get().astStack
                outer.depth = 17; outer.driving = true
                val foreign = state.threads.enterForeign(ForeignSafety.SAFE)
                try {
                    state.threads.enterCurrent()
                    try {
                        val inner = state.threadPollState.get().astStack
                        assertNotSame(outer, inner); assertEquals(0, inner.depth); assertFalse(inner.driving)
                        assertEquals(4097L, Calls.target(program.hostEntryTarget(2),
                            arrayOf(program.entryValue("loop"), arrayOf(4096L, tick))))
                        assertTrue(inner.spills > 0); assertEquals(0, inner.depth); assertFalse(inner.driving)
                    } finally { state.threads.leaveCurrent() }
                    assertSame(outer, state.threadPollState.get().astStack)
                    assertEquals(17, outer.depth); assertTrue(outer.driving)
                } finally {
                    state.threads.leaveForeign(foreign)
                    outer.depth = 0; outer.driving = false
                    state.threads.leaveCurrent()
                }
            } finally { context.leave() }
        }
    }
}
