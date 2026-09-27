// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

@Timeout(120)
class DelimitedContinuationsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val entries = listOf("promptPure", "abortSuffix", "resumeTwice", "nestedPrompts", "sameTagNearest",
        "capturedCatch", "capturedMask", "escapedResume", "ambientMask", "resumedTail", "resumedJoin", "resumedScalar", "recapturedMask", "resumedApplication",
        "resumedScalarApplication", "polymorphicApplications", "polymorphicScalarApplications")
    private val calls = mapOf("promptPure" to 3L, "abortSuffix" to 4L, "resumeTwice" to 6L,
        "nestedPrompts" to 7L, "sameTagNearest" to 5L, "capturedCatch" to 7L, "capturedMask" to 7L,
        "escapedResume" to 6L, "ambientMask" to 7L, "resumedTail" to 6L, "resumedJoin" to 5L,
        "resumedScalar" to 6L, "recapturedMask" to 10L, "resumedApplication" to 7L,
        "resumedScalarApplication" to 7L, "polymorphicApplications" to 22L, "polymorphicScalarApplications" to 22L)

    private fun provenance(): List<Long> {
        val manifest = Json.parse(File(root, "build/delimited-continuations/manifest.json").readText()) as Map<*, *>
        assertEquals(entries, manifest["entries"])
        for (group in listOf("inputHashes", "artifactHashes")) for ((path, hash) in manifest[group] as Map<*, *>) {
            val bytes = File(root, path as String).readBytes()
            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, path)
        }
        val native = (manifest["native"] as List<*>).map { (it as Number).toLong() }
        assertEquals(listOf(-2L, 0L, 7L).flatMap { n -> entries.map { expected(it, n) } }, native,
            "Independent arithmetic/state model agrees with actual native GHC")
        return native
    }

    private fun expected(entry: String, n: Long): Long = when (entry) {
        "promptPure" -> n + 7
        "abortSuffix" -> n
        "resumeTwice" -> ((n + 1) * 100 + (n + 4)) * 10 + 3
        "nestedPrompts" -> n + 111
        "sameTagNearest" -> n + 100
        "capturedCatch" -> n + 17
        "capturedMask" -> n + 21
        "escapedResume" -> 2 * n + 14
        "ambientMask" -> n
        "resumedTail", "resumedJoin", "resumedScalar", "resumedApplication", "resumedScalarApplication" -> n + 117
        "polymorphicApplications", "polymorphicScalarApplications" -> 4 * n + 174
        "recapturedMask" -> n + 1
        else -> error(entry)
    }

    @Test fun originalCoreRunsSavedSuffixesOnBothBackends() = runEntries(entries, true)

    @Test fun unsplitFourTargetApplicationsExerciseBothGenericResultPaths() =
        runEntries(listOf("polymorphicApplications", "polymorphicScalarApplications"), false)

    private fun runEntries(selected: List<String>, splitting: Boolean) {
        val native = provenance()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) for (entry in selected) {
            Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.WarnInterpreterOnly", "false")
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
                .option("engine.Splitting", splitting.toString())
                .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    @Suppress("UNCHECKED_CAST")
                    val module = Json.parse(File(root, "build/delimited-continuations/$stage/core/DelimitedContinuations.json").readText()) as Map<String, Any?>
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module, entry)
                    val source = ArrayCoreEvidence(module, entry)
                    val nodes = source.bindings.flatMap { source.nodes(it["expr"]) }
                    val joins = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<List<*>, Boolean>())
                    for (binding in nodes.filter { it.firstOrNull() == "let" }.flatMap { it[2] as List<*> }) {
                        binding as Map<*, *>
                        val arity = binding["joinValueArity"] as? Number ?: continue
                        val rhs = binding["expr"] as List<*>
                        assertEquals("lam", rhs[0])
                        assertEquals(arity.toInt(), (rhs[1] as List<*>).size)
                        joins.add(rhs)
                    }
                    val lambdas = nodes.filter { it.firstOrNull() == "lam" && it !in joins }
                    val polymorphic = entry in setOf("polymorphicApplications", "polymorphicScalarApplications")
                    assertEquals(if (polymorphic) 19L else calls.getValue(entry), lambdas.size.toLong(),
                        "Every reachable lambda runs once, except applyWorker runs four times; resumes never restart them")
                    // The exported runRW# wrapper is now beta-reduced during
                    // lowering. Prove that exact single State# application from
                    // original Core, independently of runtime target discovery.
                    val entryLambda = source.root["expr"] as List<*>
                    val stateCall = entryLambda[2] as List<*>
                    assertEquals("app", stateCall[0])
                    assertEquals(listOf(listOf(false), false, false), stateCall.subList(3, 6))
                    val stateLambda = stateCall[1] as List<*>
                    assertEquals("lam", stateLambda[0])
                    val stateFormal = (stateLambda[1] as List<*>).single() as Map<*, *>
                    val voidRep = mapOf("primReps" to emptyList<String>(), "kind" to "void", "evaluated" to true)
                    assertEquals("State# RealWorld", stateFormal["type"])
                    assertEquals(false, stateFormal["lifted"])
                    assertEquals(false, stateFormal["coercion"])
                    assertEquals(voidRep, stateFormal["rep"])
                    val stateArgument = (stateCall[2] as List<*>).single() as List<*>
                    assertEquals("void", stateArgument[0])
                    assertEquals(voidRep, (stateArgument.last() as Map<*, *>)["rep"])
                    val immediateLambdas = nodes.filter { it.firstOrNull() == "app" &&
                        (it.getOrNull(1) as? List<*>)?.firstOrNull() == "lam" }
                    assertEquals(1, immediateLambdas.size, "Exactly one immediate lambda can be eliminated")
                    assertSame(stateCall, immediateLambdas.single())
                    assertTrue(lambdas.any { it === stateLambda })
                    val executableLambdas = lambdas.filter { it !== stateLambda }
                    for (worker in source.bindings.filter { it["name"] in setOf("applicationWorker", "applicationWorker2",
                            "applicationWorker3", "applicationWorker4", "scalarApplicationWorker", "scalarApplicationWorker2",
                            "scalarApplicationWorker3", "scalarApplicationWorker4") }) {
                        assertEquals(2, (worker["arity"] as Number).toInt(), "A genuine overapplication, not an eta-expanded worker")
                        val lambda = worker["expr"] as List<*>
                        assertEquals(2, (lambda[1] as List<*>).size)
                        val body = lambda[2] as List<*>
                        assertEquals("case", body[0])
                        val returned = ((body[3] as List<*>).single() as List<*>)[3] as List<*>
                        assertEquals("lam", returned[0]); assertEquals(1, (returned[1] as List<*>).size)
                    }
                    if (polymorphic) {
                        val apply = source.bindings.single { it["name"] ==
                            if (entry == "polymorphicApplications") "applyWorker" else "applyScalarWorker" }
                        val formals = ((apply["expr"] as List<*>)[1] as List<*>).map { it as Map<*, *> }
                        assertEquals(listOf("worker", "tag", "n", "s"), formals.map { it["name"] })
                        val body = (apply["expr"] as List<*>)[2] as List<*>
                        val call = if (body[0] == "case") body[1] as List<*> else body
                        assertEquals(listOf("var", formals[0]["id"]), (call[1] as List<*>).take(2))
                        assertEquals(3, (call[2] as List<*>).size)
                        assertEquals(4, nodes.count { it.firstOrNull() == "app" &&
                            (it.getOrNull(1) as? List<*>)?.take(2) == listOf("var", apply["id"]) })
                    }
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    assertTrue((program.entryTarget(entry).rootNode as GuestRoot).delimitedControlEnabled,
                        "$stage/$backend/$entry: root policy is prepared before the first guest call")
                    val function = context.asValue(EntryValue(program, entry, 1))
                    for ((index, n) in listOf(-2L, 0L, 7L).withIndex()) {
                        assertEquals(native[index * entries.size + entries.indexOf(entry)], function.execute(n).asLong(), "$stage/$backend/$entry/$n")
                        val handoff = language.handoffState.get()
                        assertEquals(0, handoff.arguments.depth)
                        assertEquals(0, handoff.results.depth)
                        assertEquals(0, handoff.arguments.retainedReferences())
                        assertEquals(0, handoff.results.retainedReferences())
                        assertNull(handoff.pending)
                        assertEquals(MaskingState.UNMASKED, Language.currentState(null).maskingState.get())
                    }
                    // Megamorphic calls do not retain the fourth target in a
                    // DirectCallNode. Seed all actual global lambdas, as the
                    // thread inventory's floated-child proof already does.
                    val seen = java.util.Collections.newSetFromMap(
                        java.util.IdentityHashMap<com.oracle.truffle.api.RootCallTarget, Boolean>())
                    val targets = source.bindings.filter { (it["expr"] as List<*>)[0] == "lam" }
                        .flatMap { ThreadInventoryCoreEvidence.targets(program.entryTarget(it["id"] as String)) }
                        .filter { (it.rootNode is FunctionRoot || it.rootNode is BytecodeRoot) && seen.add(it) }
                    val labels = executableLambdas.map { lambda -> "lambda " + (lambda[1] as List<*>).joinToString {
                        (it as Map<*, *>)["name"].toString()
                    } }.sorted()
                    val functions = targets.filter { it.rootNode.name.startsWith("lambda ") }
                    // Truffle may split the higher-order helper at its four
                    // callers. Install every clone, but compare source bodies
                    // using the runtime's existing exact clone identity.
                    val bodies = mutableListOf<com.oracle.truffle.api.RootCallTarget>()
                    for (target in functions) if (bodies.none { (it.rootNode as GuestRoot).isSelf(target) }) bodies.add(target)
                    assertEquals(labels, bodies.map { it.rootNode.name }.sorted(), "$stage/$backend/$entry exact source function bodies")
                    val cafLabels = source.bindings.filter { (it["expr"] as List<*>)[0] != "lam" }.map { it["name"] }.toSet()
                    for (target in targets - functions)
                        assertTrue(target.rootNode.name in cafLabels,
                            "Only already-forced source CAFs may accompany the exact function inventory")
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    val interpreted = ThreadInventoryCoreEvidence.interpretedCalls(targets)
                    try { ThreadInventoryCoreEvidence.install(targets) }
                    catch (failure: Throwable) { throw AssertionError("$stage/$backend/$entry first installation", failure) }
                    assertTrue(function.invokeMember("compile").asBoolean())
                    assertEquals(before, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    val handoff = language.handoffState.get()
                    val allocations = handoff.arguments.allocations to handoff.results.allocations
                    assertEquals(expected(entry, 11), function.execute(11L).asLong(), "$stage/$backend/$entry first installed call")
                    assertEquals(calls.getValue(entry) - 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before,
                        "$stage/$backend/$entry: resumes do not restart original Core roots")
                    assertEquals(interpreted, ThreadInventoryCoreEvidence.interpretedCalls(targets), "No interpreted settling call")
                    ThreadInventoryCoreEvidence.released(language)
                    assertEquals(allocations, handoff.arguments.allocations to handoff.results.allocations)
                    assertEquals(MaskingState.UNMASKED, Language.currentState(null).maskingState.get())
                } finally { context.leave() }
            }
        }
    }

    @Test fun rootPoliciesArePreparedWithoutExecutingBodiesAndRetainedByClones() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (enabled in listOf(false, true)) {
                    val body = object : Expr() {
                        override fun execute(frame: VirtualFrame): Any? = error("Metadata preparation executed guest code")
                    }
                    val source = FunctionRoot(language, FrameLayout().build(), "unexecuted policy", null,
                        intArrayOf(), intArrayOf(), intArrayOf(), body,
                        Metrics(false), emptyArray(), body.representation, body.coreSourceLocation,
                        booleanArrayOf(), null, null, intArrayOf(),
                        null, false, emptyArray(), enabled,
                        FunctionRootRole.FUNCTION, false)
                    val clone = NodeUtil.cloneNode(source)
                    for (root in listOf(source, clone)) {
                        assertSame(root, root.callTarget.rootNode)
                        assertEquals(enabled, root.delimitedControlEnabled)
                        assertEquals(enabled, DelimitedControl.enabled(root))
                    }
                }
                val other = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any? = error("Metadata preparation executed guest code")
                }
                other.callTarget
                assertFalse(DelimitedControl.enabled(other), "Unrecognized roots do not gain continuation authority")
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val value = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = value.primReps,
                    components = listOf(state, value)), language)
                val cut = DelimitedCut(PromptTag(Language.currentState(null)), null, shape,
                    MaskingState.UNMASKED, other)
                val continuation = DelimitedStack(cut, shape).closure(language, Metrics(false)).target.rootNode as GuestRoot
                assertTrue(continuation.delimitedControlEnabled)
                assertTrue(DelimitedControl.enabled(continuation))
                ThreadInventoryCoreEvidence.released(language)
            } finally { context.leave() }
        }
    }

    @Test fun liveAndSavedCatchAcknowledgeEachSelfRequestAndKeepItsPayloadLazy() {
        Context.newBuilder("thc").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val threads = Language.currentState().threads
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val value = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = value.primReps,
                    components = listOf(state, value)), language)
                val payload = Thunk(object : GuestRoot(language, FrameLayout().build()) {
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any = error("Delimited catch forced the payload")
                }.callTarget, null)
                val deliveryTarget = AtomicReference<GuestThreadId>()
                val seen = AtomicReference<AsyncRequest>()
                var calls = 0
                var handlerMask = MaskingState.UNMASKED
                var failHandler = false
                var redeliver = false
                val handlerFailure = RuntimeFault("handler failure")
                val action = Closure(null, 1, object : GuestRoot(language, FrameLayout().build()) {
                    init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any = try {
                        GuestThreadOps.killSelf(this, deliveryTarget.get(), payload)
                    } catch (delivered: AsyncDelivery) {
                        seen.set(delivered.request)
                        throw delivered // Preserve the real async-origin control object.
                    }
                }.callTarget)
                val handler = Closure(null, 2, object : GuestRoot(language, FrameLayout().build()) {
                    init { configureEntry(booleanArrayOf(false, false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        calls++
                        assertSame(payload, frame.arguments[1])
                        assertSame(payload, seen.get().payload)
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, seen.get().state)
                        assertEquals(handlerMask, SynchronousMasking.current(this))
                        if (failHandler) throw handlerFailure
                        if (redeliver) {
                            redeliver = false
                            try { GuestThreadOps.killSelf(this, deliveryTarget.get(), payload) }
                            catch (delivered: AsyncDelivery) {
                                seen.set(delivered.request)
                                throw delivered
                            }
                        }
                        return shape.layout.create().also { shape.layout.setObject(it, 0, payload) }
                    }
                }.callTarget)
                val root = object : GuestRoot(language, FrameLayout().build()) {
                    @field:Child private var site = DelimitedActionSite(language, Metrics(false))
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any? = site.caught(frame, action, handler, Unit, shape)
                    fun save(catches: Int = 1): DelimitedStack {
                        val frame = Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor)
                        val cut = DelimitedCut(PromptTag(Language.currentState(this)), null, shape,
                            SynchronousMasking.current(this), this)
                        repeat(catches) { cut.frames.add(DelimitedFrame(frame, DelimitedCatchStep(site, handler, shape))) }
                        return DelimitedStack(cut, shape)
                    }
                    fun resumeSaved(stack: DelimitedStack): Any? {
                        val frame = Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor)
                        return stack.resume(site, frame, action)
                    }
                }
                threads.enterCurrent(externalAsync = false)
                try {
                    val self = threads.currentIdentity()
                    deliveryTarget.set(self)
                    for (mask in MaskingState.entries) {
                        SynchronousMasking.set(root, mask)
                        handlerMask = if (mask == MaskingState.UNMASKED) MaskingState.MASKED_INTERRUPTIBLE else mask
                        val result = Calls.target(root.callTarget, arrayOf(0L)) as HandoffStorage
                        assertSame(payload, shape.layout.getObject(result, 0))
                        assertTrue(seen.get().forceSelf)
                        assertEquals(self.logicalId, seen.get().targetId)
                        assertEquals(mask, SynchronousMasking.current(root))
                        assertNull(threads.poll(root))
                        assertEquals(0, payload.state)
                    }
                    assertEquals(3, calls, "Exactly one handler invocation per self request")
                    SynchronousMasking.set(root, MaskingState.UNMASKED)
                    handlerMask = MaskingState.MASKED_INTERRUPTIBLE
                    failHandler = true
                    assertSame(handlerFailure, assertThrows(RuntimeFault::class.java) {
                        Calls.target(root.callTarget, arrayOf(0L))
                    })
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root))
                    assertEquals(4, calls)

                    // Reuse the SAME immutable image, never the one-shot request.
                    failHandler = false
                    val saved = root.save()
                    var previous = seen.get()
                    for (mask in MaskingState.entries) {
                        SynchronousMasking.set(root, mask)
                        handlerMask = if (mask == MaskingState.UNMASKED) MaskingState.MASKED_INTERRUPTIBLE else mask
                        repeat(2) {
                            val result = root.resumeSaved(saved) as HandoffStorage
                            assertSame(payload, shape.layout.getObject(result, 0))
                            assertNotSame(previous, seen.get(), "Every self send owns a fresh request")
                            assertEquals(AsyncRequestState.ACKNOWLEDGED, previous.state)
                            assertEquals(AsyncRequestState.ACKNOWLEDGED, seen.get().state)
                            assertEquals(self.logicalId, seen.get().targetId)
                            assertTrue(seen.get().forceSelf)
                            assertEquals(mask, SynchronousMasking.current(root))
                            assertNull(threads.poll(root))
                            assertEquals(0, payload.state)
                            previous = seen.get()
                        }
                    }
                    assertEquals(10, calls)

                    // The inner saved handler acknowledges its request before
                    // throwing a new self request to the outer saved handler.
                    SynchronousMasking.set(root, MaskingState.UNMASKED)
                    handlerMask = MaskingState.MASKED_INTERRUPTIBLE
                    redeliver = true
                    assertSame(payload, shape.layout.getObject(root.resumeSaved(root.save(2)) as HandoffStorage, 0))
                    assertEquals(12, calls)
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, seen.get().state)
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root))

                    // With no saved catch, preserve the original delivery for an
                    // outer handler; neither the image nor its runner may ACK it.
                    SynchronousMasking.set(root, MaskingState.MASKED_UNINTERRUPTIBLE)
                    val escaped = assertThrows(AsyncDelivery::class.java) { root.resumeSaved(root.save(0)) }
                    assertSame(seen.get(), escaped.request)
                    assertTrue(escaped.request.forceSelf)
                    assertEquals(AsyncRequestState.CLAIMED, escaped.request.state)
                    assertEquals(12, calls, "Uncaught delivery must not become GuestException")
                    assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(root))
                    escaped.request.acknowledge()
                    SynchronousMasking.set(root, MaskingState.UNMASKED)

                    // The same carrier may host a distinct callback identity.
                    // Its external send must not enter the caller's mailbox or handler.
                    val foreign = threads.enterForeign(ForeignSafety.SAFE)
                    try {
                        threads.enterCurrent(externalAsync = false)
                        try {
                            assertNotSame(self, threads.currentIdentity())
                            assertThrows(UnsupportedCore::class.java) { Calls.target(root.callTarget, arrayOf(0L)) }
                            assertEquals(12, calls)
                        } finally { threads.leaveCurrent() }
                    } finally { threads.leaveForeign(foreign) }
                    assertSame(self, threads.currentIdentity())
                    assertNull(threads.poll(root), "Rejected external send never entered the mailbox")
                    assertEquals(0, payload.state)
                    ThreadInventoryCoreEvidence.released(language)
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun savedExternalDeliveryKeepsTheAbandonedChildOneShotAndTheImageReusable() {
        Context.create("thc").use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val threads = Language.currentState().threads
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val value = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = value.primReps,
                    components = listOf(state, value)), language)
                val payload = Any()
                val requests = ArrayList<AsyncRequest>()
                val children = ArrayList<AstContinuation>()
                var resumedChildren = 0
                var handled = 0
                val action = Closure(null, 1, object : GuestRoot(language, FrameLayout().build()) {
                    init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        val target = threads.currentId()
                        val submitted = AtomicReference<AsyncRequest>()
                        val sender = Thread { submitted.set(threads.send(target, payload)) }
                        sender.start(); sender.join(5000); assertFalse(sender.isAlive)
                        val request = threads.poll(this, true)!!
                        assertSame(submitted.get(), request)
                        assertFalse(request.forceSelf)
                        requests += request
                        return AstCapture(request, SynchronousMasking.current(this)).append(object : AstResumeStep {
                            override fun resume(frame: VirtualFrame, input: Any?): Any {
                                assertSame(Unit, input)
                                resumedChildren++
                                return Unit
                            }
                        }).freeze(this, frame.materialize()).also { children += it }
                    }
                }.callTarget)
                val handler = Closure(null, 2, object : GuestRoot(language, FrameLayout().build()) {
                    init { configureEntry(booleanArrayOf(false, false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        handled++
                        assertSame(payload, frame.arguments[1])
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, requests.last().state)
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this))
                        return shape.layout.create().also { shape.layout.setObject(it, 0, payload) }
                    }
                }.callTarget)
                val root = object : GuestRoot(language, FrameLayout().build()) {
                    @field:Child private var site = DelimitedActionSite(language, Metrics(false))
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Nothing = error("model root is not an action")
                    fun image(caught: Boolean = true): DelimitedStack {
                        val frame = Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor)
                        val cut = DelimitedCut(PromptTag(Language.currentState(this)), null, shape,
                            MaskingState.UNMASKED, this)
                        if (caught) cut.frames += DelimitedFrame(frame, DelimitedCatchStep(site, handler, shape))
                        return DelimitedStack(cut, shape)
                    }
                    fun resume(image: DelimitedStack): Any? = image.resume(site,
                        Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor), action)
                    fun deliver(request: AsyncRequest): Any? = site.handleException(
                        Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor), handler,
                        AsyncDelivery(request, this), shape)
                }
                threads.enterCurrent()
                try {
                    val image = root.image()
                    repeat(2) {
                        val result = root.resume(image) as HandoffStorage
                        assertSame(payload, shape.layout.getObject(result, 0))
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root))
                    }
                    assertEquals(2, handled)
                    assertNotSame(requests[0], requests[1]); assertNotSame(children[0], children[1])
                    assertEquals(0, resumedChildren, "Catch abandons each interrupted child, not the reusable image")
                    assertThrows(IllegalStateException::class.java) { root.deliver(requests[0]) }
                    Calls.target(action.target, arrayOf(0L, Unit))
                    val pending = requests.last()
                    val foreign = threads.enterForeign(ForeignSafety.SAFE)
                    try {
                        threads.enterCurrent()
                        try {
                            assertNotEquals(pending.targetId, threads.currentId())
                            assertThrows(IllegalStateException::class.java) { root.deliver(pending) }
                            assertEquals(AsyncRequestState.CLAIMED, pending.state)
                        } finally { threads.leaveCurrent() }
                    } finally { threads.leaveForeign(foreign) }
                    root.deliver(pending)
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, pending.state)
                    val escaped = assertThrows(AsyncDelivery::class.java) { root.resume(root.image(false)) }
                    assertSame(requests.last(), escaped.request)
                    assertEquals(AsyncRequestState.CLAIMED, escaped.request.state)
                    assertEquals(3, handled, "Only a reached catch acknowledges the original delivery")
                    escaped.request.acknowledge()
                    for (child in children) {
                        assertSame(Unit, child.continueWith(Unit))
                        assertThrows(RuntimeFault::class.java) { child.continueWith(Unit) }
                    }
                    assertEquals(4, resumedChildren, "An independent owner may resume each child once only")
                    assertEquals(3, handled)
                    assertNull(threads.poll(root))
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun eachImageInvocationDrainsItsOwnCutsAndNeverStoresTheirOwners() {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val threads = Language.currentState().threads
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val value = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = value.primReps,
                    components = listOf(state, value)), language)
                val payload = Any()
                val children = ArrayList<AstContinuation>()
                val requests = ArrayList<AsyncRequest>()
                var effects = 0
                var handled = 0
                var mode = 0
                val handler = Closure(null, 2, object : GuestRoot(language, FrameLayout().build()) {
                    init { configureEntry(booleanArrayOf(false, false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        handled++
                        assertSame(payload, frame.arguments[1])
                        assertEquals(AsyncRequestState.ACKNOWLEDGED, requests.last().state)
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(this))
                        return shape.layout.create().also { shape.layout.setObject(it, 0, payload) }
                    }
                }.callTarget)
                val root = object : GuestRoot(language, FrameLayout().build()) {
                    @field:Child private var site = DelimitedActionSite(language, Metrics(false))
                    init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    fun cut(frame: VirtualFrame, marker: Any?, next: () -> Any?): AstContinuation =
                        AstCapture(marker, SynchronousMasking.current(this)).append(object : AstResumeStep {
                            override fun resume(frame: VirtualFrame, input: Any?): Any? {
                                assertSame(Unit, input)
                                effects++
                                return next()
                            }
                        }).freeze(this, frame.materialize()).also { children += it }
                    override fun execute(frame: VirtualFrame): Any = cut(frame, AstStackSpill.INSTANCE) {
                        when (mode) {
                            0 -> cut(frame, Unit) {
                                shape.layout.create().also { shape.layout.setObject(it, 0, payload) }
                            }
                            1 -> {
                                val submitted = AtomicReference<AsyncRequest>()
                                val target = threads.currentId()
                                val sender = Thread { submitted.set(threads.send(target, payload)) }
                                sender.start(); sender.join(5000); assertFalse(sender.isAlive)
                                val request = threads.poll(this, true)!!
                                assertSame(submitted.get(), request)
                                requests += request
                                cut(frame, request) { error("A reached catch must not resume its abandoned child") }
                            }
                            2 -> throw DelimitedCut(PromptTag(Language.currentState(this)), null, shape,
                                SynchronousMasking.current(this), this)
                            else -> cut(frame, DelimitedCut(PromptTag(Language.currentState(this)), null, shape,
                                SynchronousMasking.current(this), this)) { error("A recapture marker is not a Unit cut") }
                        }
                    }
                    fun image(): DelimitedStack {
                        val node = this
                        val frame = Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor)
                        val cut = DelimitedCut(PromptTag(Language.currentState(this)), null, shape,
                            MaskingState.UNMASKED, this)
                        cut.frames += DelimitedFrame(frame, object : DelimitedStep {
                            override fun resume(frame: com.oracle.truffle.api.frame.MaterializedFrame,
                                                input: DelimitedResume, ambient: MaskingState,
                                                outerMask: DelimitedStep?): Any? {
                                val result = input.get()
                                throw AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(node))
                                    .append(object : AstResumeStep {
                                        override fun resume(frame: VirtualFrame, input: Any?): Any? {
                                            assertSame(Unit, input)
                                            effects++
                                            return result
                                        }
                                    })
                            }
                        })
                        cut.frames += DelimitedFrame(frame, DelimitedCatchStep(site, handler, shape))
                        return DelimitedStack(cut, shape)
                    }
                    fun resume(image: DelimitedStack): Any? = image.resume(site,
                        Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor),
                        Closure(null, 1, callTarget))
                }
                threads.enterCurrent()
                try {
                    val image = root.image()
                    repeat(2) {
                        val answer = root.resume(image) as HandoffStorage
                        assertSame(payload, shape.layout.getObject(answer, 0))
                    }
                    assertEquals(6, effects, "Two action cuts and the saved suffix run once per invocation")
                    assertEquals(4, children.size)
                    for (child in children) assertThrows(RuntimeFault::class.java) { child.continueWith(Unit) }
                    mode = 1
                    repeat(2) { assertSame(payload, shape.layout.getObject(root.resume(image) as HandoffStorage, 0)) }
                    assertEquals(8, effects, "Delivery must not replay or resume the interrupted action")
                    assertEquals(2, handled)
                    assertNotSame(requests[0], requests[1])
                    assertTrue(requests.all { it.state == AsyncRequestState.ACKNOWLEDGED })
                    for (recapture in 2..3) {
                        mode = recapture
                        assertEquals("control0# cannot recapture a parked one-shot invocation chain",
                            assertThrows(UnsupportedCore::class.java) { root.resume(image) }.message)
                    }
                    mode = 0
                    assertSame(payload, shape.layout.getObject(root.resume(image) as HandoffStorage, 0))
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(root))
                    assertEquals(0, AstStackKt.astStackScope(root).depth)
                    assertNull(threads.poll(root))
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }

    @Test fun savedBytecodeSuffixDrainsRepeatedUnitCutsInFreshOwners() {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val value = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = value.primReps,
                    components = listOf(state, value)), language)
                val marker = Any()
                val answer = shape.layout.create().also { shape.layout.setObject(it, 0, marker) }
                val owner = object : GuestRoot(language, FrameLayout().build()) {
                    @field:Child private var site = DelimitedActionSite(language, Metrics(false))
                    init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any = answer
                    fun resume(image: DelimitedStack, action: Any? = Closure(null, 1, callTarget)): Any? = image.resume(site,
                        Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor),
                        action)
                    fun finish(result: Any?, target: com.oracle.truffle.api.RootCallTarget): Any? =
                        site.finish(result, shape, target)
                }
                val cut = DelimitedCut(PromptTag(Language.currentState()), null, shape,
                    MaskingState.UNMASKED, owner)
                val bytecode = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    b.beginYield(); b.emitLoadConstant(cut); b.endYield()
                    repeat(2) { b.beginYield(); b.emitLoadConstant(Unit); b.endYield() }
                    b.beginReturn(); b.emitLoadConstant(answer); b.endReturn()
                    b.endRoot()
                }.getNode(0)
                bytecode.configureTupleResult(shape)
                val saved = Calls.target(bytecode.callTarget, arrayOf(0L)) as ContinuationResult
                assertSame(cut, saved.result)
                cut.append(saved.frame, DelimitedBytecodeStep(saved, shape))
                val image = DelimitedStack(cut, shape)
                repeat(2) { assertSame(marker, shape.layout.getObject(owner.resume(image) as HandoffStorage, 0)) }
                val closure = Closure(null, 1, owner.callTarget)
                val head = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    repeat(2) { b.beginYield(); b.emitLoadConstant(Unit); b.endYield() }
                    b.beginReturn(); b.emitLoadConstant(closure); b.endReturn()
                    b.endRoot()
                }.getNode(0)
                val lazyAction = Thunk(head.callTarget, null)
                repeat(2) {
                    assertSame(marker, shape.layout.getObject(owner.resume(image, lazyAction) as HandoffStorage, 0))
                    assertEquals(2, lazyAction.state)
                    assertSame(closure, lazyAction.value)
                    assertNull(lazyAction.owner)
                }
                val unrelated = saved.continueWith(DelimitedResume(answer))
                assertEquals("Delimited invocation returned an unrelated continuation",
                    assertThrows(RuntimeFault::class.java) { owner.finish(unrelated, owner.callTarget) }.message)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(owner))
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }

    @Test fun savedScalarJoinTransferRetainsCompletionAfterScheduling() = scheduledJoinTransfer(false, false)
    @Test fun savedTupleJoinTransferRetainsCompletionAfterScheduling() = scheduledJoinTransfer(true, false)
    @Test fun savedScalarJoinTransferRetainsItsNextLexicalJump() = scheduledJoinTransfer(false, true)
    @Test fun savedTupleJoinTransferRetainsItsNextLexicalJump() = scheduledJoinTransfer(true, true)

    private fun scheduledJoinTransfer(tupleResult: Boolean, nextJump: Boolean) {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val long = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
                val tupleProof = CoreRepresentation(CoreKind.UNKNOWN, true, true, long.primReps, listOf(long))
                val shape = TupleShape(tupleProof, language)
                val layout = FrameLayout()
                val selector = layout.bind("join selector")
                val result = layout.bind("join scalar result")
                val source = layout.bind("join private tuple")
                val destination = layout.bind("join caller tuple")
                val group = Any()
                val targets = (1..2).map { LocalJoinTarget(group, it, intArrayOf(), emptyArray()) }
                val events = ArrayList<String>()
                val initial = object : Expr() {
                    override fun execute(frame: VirtualFrame): Nothing {
                        events += "capture"
                        throw DelimitedCut(PromptTag(Language.currentState()), null, shape,
                            SynchronousMasking.current(this), this).append(frame, object : DelimitedStep {
                            override fun resume(frame: com.oracle.truffle.api.frame.MaterializedFrame,
                                                input: DelimitedResume, ambient: MaskingState,
                                                outerMask: DelimitedStep?): Nothing {
                                input.get()
                                throw targets[0].jump
                            }
                        })
                    }
                    override fun executeLong(frame: VirtualFrame): Long = execute(frame)
                    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? = execute(frame)
                }
                val scheduled = object : Expr() {
                    private fun cut(slots: IntArray? = null, offset: Int = 0): Nothing {
                        events += "join prefix"
                        throw AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append(object : AstResumeStep {
                            override fun resume(frame: VirtualFrame, input: Any?): Any? {
                                assertSame(Unit, input)
                                events += "join suffix"
                                if (nextJump) throw targets[1].jump
                                if (slots == null) return 42L
                                FrameAccess.writeLong(frame, slots[offset], 42L)
                                return null
                            }
                        })
                    }
                    override fun execute(frame: VirtualFrame): Any = cut()
                    override fun executeLong(frame: VirtualFrame): Long = cut()
                    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? = cut(slots, offset)
                }
                val after = object : Expr() {
                    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
                    override fun executeLong(frame: VirtualFrame): Long { events += "second join"; return 43L }
                    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                        FrameAccess.writeLong(frame, slots[offset], executeLong(frame)); return null
                    }
                }
                val owner = object : GuestRoot(language, layout.build()) {
                    @field:Child private var region = LocalJoinRegion(group, selector, result,
                        arrayOf(initial, scheduled, after), if (tupleResult) tupleProof else long,
                        nextJump, if (tupleResult) shape else null, if (tupleResult) intArrayOf(source) else intArrayOf(), true)
                    @field:Child private var site = DelimitedActionSite(language, Metrics(false))
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any? {
                        FrameAccess.writeLong(frame, destination, -99L)
                        return if (tupleResult) region.executeTuple(frame, intArrayOf(destination), 0) else region.execute(frame)
                    }
                    fun resume(image: DelimitedStack, action: Closure): Any? = image.resume(site,
                        Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor), action)
                }
                val cut = assertThrows(DelimitedCut::class.java) { Calls.target(owner.callTarget, arrayOf(0L)) }
                if (tupleResult) cut.frames += DelimitedFrame(cut.frames.last().frame, object : DelimitedStep {
                    override fun resume(frame: com.oracle.truffle.api.frame.MaterializedFrame,
                                        input: DelimitedResume, ambient: MaskingState,
                                        outerMask: DelimitedStep?): Any {
                        input.get()
                        return frame.getLong(destination)
                    }
                })
                val image = DelimitedStack(cut, shape)
                val action = Closure(null, 1, object : GuestRoot(language, FrameLayout().build()) {
                    init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any = shape.layout.create().also { shape.layout.setLong(it, 0, 7L) }
                }.callTarget)
                repeat(2) { assertEquals(if (nextJump) 43L else 42L, owner.resume(image, action)) }
                val one = listOf("join prefix", "join suffix") + if (nextJump) listOf("second join") else emptyList()
                assertEquals(listOf("capture") + one + one, events)
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(owner))
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }

    @Test fun savedRootTransferDetachesItsTupleBeforeTheNextSavedStep() {
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.Compilation", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val long = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
                val proof = CoreRepresentation(CoreKind.UNKNOWN, true, true, long.primReps, listOf(long))
                val shape = TupleShape(proof, language)
                val layout = FrameLayout(); val slot = layout.bind("root tuple result")
                var effects = 0
                val body = object : Expr() {
                    init { representation = proof }
                    override fun execute(frame: VirtualFrame): Nothing = error("tuple-only model")
                    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
                        effects++
                        throw AstCapture(AstStackSpill.INSTANCE, SynchronousMasking.current(this)).append(object : AstResumeStep {
                            override fun resume(frame: VirtualFrame, input: Any?): Any? {
                                assertSame(Unit, input)
                                FrameAccess.writeLong(frame, slots[offset], 42L)
                                return null
                            }
                        })
                    }
                }
                val function = FunctionRoot(language, layout.build(), "saved tuple root", null,
                    intArrayOf(), intArrayOf(), intArrayOf(), body,
                    Metrics(false), emptyArray(), proof, body.coreSourceLocation,
                    booleanArrayOf(), null, shape, intArrayOf(slot),
                    null, true, emptyArray(), true,
                    FunctionRootRole.FUNCTION, false)
                function.callTarget // Adopt the real FunctionBody and its completion step.
                val owner = object : GuestRoot(language, FrameLayout().build()) {
                    @field:Child private var site = DelimitedActionSite(language, Metrics(false))
                    init { configureEntry(booleanArrayOf(false), false); configureTupleResult(shape) }
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any = shape.layout.create().also { shape.layout.setLong(it, 0, 7L) }
                    fun resume(image: DelimitedStack): Any? = image.resume(site,
                        Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), frameDescriptor),
                        Closure(null, 1, callTarget))
                }
                val frame = Truffle.getRuntime().createMaterializedFrame(arrayOf(0L), function.frameDescriptor)
                val cut = DelimitedCut(PromptTag(Language.currentState()), null, shape, MaskingState.UNMASKED, owner)
                cut.frames += DelimitedFrame(frame, object : DelimitedStep {
                    override fun resume(frame: com.oracle.truffle.api.frame.MaterializedFrame,
                                        input: DelimitedResume, ambient: MaskingState,
                                        outerMask: DelimitedStep?): Nothing { input.get(); throw AstSelfCall.INSTANCE }
                })
                cut.frames += DelimitedFrame(frame, DelimitedRootStep(function))
                cut.frames += DelimitedFrame(frame, object : DelimitedStep {
                    override fun resume(frame: com.oracle.truffle.api.frame.MaterializedFrame,
                                        input: DelimitedResume, ambient: MaskingState,
                                        outerMask: DelimitedStep?): Any {
                        val answer = input.get()
                        assertEquals(0, language.handoffState.get().results.depth,
                            "The transferred root must detach its tuple before another saved step runs")
                        FrameAccess.writeLong(frame, slot, 99L)
                        ownedTupleResult(shape.finish(frame, intArrayOf(slot)), shape)
                        return shape.layout.getLong(answer as HandoffStorage, 0)
                    }
                })
                val image = DelimitedStack(cut, shape)
                repeat(2) { assertEquals(42L, owner.resume(image)) }
                assertEquals(2, effects)
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }

    @Test fun frameImagesCopyControlLocalsButShareHeapReferences() {
        val builder = FrameDescriptor.newBuilder()
        val scalar = builder.addSlot(FrameSlotKind.Long, null, null)
        val reference = builder.addSlot(FrameSlotKind.Object, null, null)
        val floating = builder.addSlot(FrameSlotKind.Double, null, null)
        val descriptor = builder.build()
        val auxiliary = descriptor.findOrAddAuxiliarySlot("continuation-test")
        val heap = mutableListOf(1L)
        val original = Truffle.getRuntime().createMaterializedFrame(arrayOf(heap, 7L), descriptor)
        original.setLong(scalar, Long.MIN_VALUE)
        original.setObject(reference, heap)
        original.setDouble(floating, Double.fromBits(0x7ff8000000000042L))
        original.setAuxiliarySlot(auxiliary, heap)
        val image = copyContinuationFrame(original)
        original.setLong(scalar, 11)
        original.arguments[1] = 12L
        val first = copyContinuationFrame(image)
        val second = copyContinuationFrame(image)
        first.setLong(scalar, 13)
        first.arguments[1] = 14L
        assertEquals(Long.MIN_VALUE, second.getLong(scalar))
        assertEquals(7L, second.arguments[1])
        assertEquals(0x7ff8000000000042L, second.getDouble(floating).toRawBits())
        assertSame(heap, second.getObject(reference))
        assertSame(heap, second.getAuxiliarySlot(auxiliary))
        heap[0] = 15L
        assertEquals(listOf(15L), second.getObject(reference))
        first.setObject(reference, null)
        assertSame(heap, image.getObject(reference))
    }

    @Test fun scalarEntryRejectsTupleOnlyOperationsBeforeEvaluatingOperands() {
        Context.newBuilder("thc").option("engine.WarnInterpreterOnly", "false").build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val value = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = value.primReps,
                    components = listOf(state, value)), language)
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
                fun operands(): Array<Expr> = Array(3) {
                    object : Expr() {
                        override fun execute(frame: VirtualFrame): Any? = error("scalar rejection evaluated an operand")
                    }
                }
                for (name in listOf("newPromptTag#", "prompt#", "control0#")) {
                    val node = DelimitedPrimitive(name, shape, operands(), language, Metrics(false))
                    assertEquals("$name requires a tuple destination",
                        assertThrows(RuntimeFault::class.java) { node.execute(frame) }.message)
                }
                for (name in listOf("catch#", "maskAsyncExceptions#", "maskUninterruptible#", "unmaskAsyncExceptions#")) {
                    val node = DelimitedIOBoundary(name, shape, operands(), language, Metrics(false))
                    assertEquals("$name requires a tuple destination",
                        assertThrows(RuntimeFault::class.java) { node.execute(frame) }.message)
                }
            } finally { context.leave() }
        }
    }

    @Test fun continuationLoweringRejectsWrongCarrierAndTupleContracts() {
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val tag = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Unlifted)"))
        val closure = CoreRepresentation(CoreKind.CLOSURE, primReps = listOf("BoxedRep (Just Lifted)"))
        val integer = CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
        fun tuple(vararg fields: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN,
            primReps = fields.flatMap { it.primReps!! }, components = fields.toList())
        DelimitedControl.validate("newPromptTag#", listOf(state), listOf(false), tuple(state, tag))
        assertThrows(RuntimeFault::class.java) {
            DelimitedControl.validate("newPromptTag#", listOf(state), listOf(false), tuple(state, integer))
        }
        for (name in listOf("prompt#", "control0#")) {
            DelimitedControl.validate(name, listOf(tag, closure, state), listOf(false, true, false), tuple(state, tag))
            assertThrows(RuntimeFault::class.java) {
                DelimitedControl.validate(name, listOf(integer, closure, state), listOf(false, true, false), tuple(state, tag))
            }
            assertThrows(RuntimeFault::class.java) {
                DelimitedControl.validate(name, listOf(tag, tag, state), listOf(false, true, false), tuple(state, tag))
            }
            assertThrows(RuntimeFault::class.java) {
                DelimitedControl.validate(name, listOf(tag, closure, state), listOf(false, true, false), tuple(tag, state))
            }
        }
    }

    @Test fun closedContextPromptAndContinuationCannotEnterAnotherContext() {
        lateinit var foreignTag: PromptTag
        lateinit var foreignStack: DelimitedStack
        Context.newBuilder("thc").option("engine.WarnInterpreterOnly", "false").build().use { first ->
            first.initialize("thc"); first.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                foreignTag = PromptTag(Language.currentState(null))
                assertNotSame(foreignTag, PromptTag(Language.currentState(null)))
                val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
                val value = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Lifted)"))
                val shape = TupleShape(CoreRepresentation(CoreKind.UNKNOWN, primReps = value.primReps,
                    components = listOf(state, value)), language)
                foreignStack = DelimitedStack(DelimitedCut(foreignTag, null, shape,
                    MaskingState.UNMASKED, object : Node() {}), shape)
            } finally { first.leave() }
        }
        Context.newBuilder("thc").option("engine.WarnInterpreterOnly", "false").build().use { second ->
            second.initialize("thc"); second.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val local = PromptTag(Language.currentState(null))
                val probe = object : GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Child private var site = DelimitedActionSite(language, Metrics(false))
                    override fun bloom(frame: VirtualFrame): Long = 0L
                    override fun execute(frame: VirtualFrame): Any? = when (frame.arguments[0]) {
                        0 -> DelimitedControl.tag(this, local)
                        1 -> DelimitedControl.tag(this, foreignTag)
                        2 -> foreignStack.resume(site, frame.materialize(), null)
                        else -> DelimitedControl.tag(this, 0L)
                    }
                }.callTarget
                assertSame(local, probe.call(0))
                for (operation in 1..3) assertThrows(RuntimeFault::class.java) { probe.call(operation) }
                ThreadInventoryCoreEvidence.released(language)
                assertEquals(MaskingState.UNMASKED, Language.currentState(null).maskingState.get())
            } finally { second.leave() }
        }
    }
}
