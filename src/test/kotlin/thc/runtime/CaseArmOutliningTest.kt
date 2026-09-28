// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeUtil
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Main.executionContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private typealias ArmCore = List<Any?>

class CaseArmOutliningTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun variable(id: String, rep: Map<String, Any> = long): ArmCore = listOf("var", id, mapOf("rep" to rep))
    private fun integer(value: Long): ArmCore = listOf("lit", "int", value.toString(), mapOf("rep" to long))
    private fun parameter(id: String, rep: Map<String, Any> = long) =
        mapOf("id" to id, "name" to id, "lifted" to (rep == closure), "rep" to rep)
    private fun lambda(parameters: List<Map<String, Any>>, body: ArmCore, result: Map<String, Any> = long): ArmCore =
        listOf("lam", parameters, body, mapOf("rep" to closure, "resultRep" to result))
    private fun binding(id: String, body: ArmCore): Map<String, Any?> =
        mapOf("id" to id, "name" to id, "lifted" to true, "rep" to closure, "expr" to body)
    private fun app(function: ArmCore, vararg arguments: ArmCore): ArmCore =
        listOf("app", function, arguments.toList(), List(arguments.size) { false }, false, false, mapOf("rep" to long))
    private fun prim(name: String, vararg arguments: ArmCore) = app(listOf("prim", name), *arguments)
    private fun case(value: ArmCore, id: String, vararg arms: ArmCore, result: Map<String, Any> = long): ArmCore =
        listOf("case", value, id, arms.toList(), mapOf("rep" to result, "binder" to parameter(id)))
    private fun default(body: ArmCore): ArmCore = listOf("default", null, emptyList<String>(), body)
    private fun zero(body: ArmCore): ArmCore = listOf("lit", listOf("int", "0"), emptyList<String>(), body)
    private fun withLanguage(action: (Language) -> Unit) = executionContext().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, async: Boolean, body: ArmCore,
        parameters: List<Map<String, Any>> = listOf(parameter("x")), extra: Map<String, Any?> = emptyMap()) =
        Program(language, extra + mapOf("bindings" to listOf(binding("entry", lambda(parameters, body)))),
            async, true)
    private fun count(p: Program, key: String) = (p.diagnostics().getValue(key) as Number).toLong()
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target)
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }

    @Test fun coldSelectedArmUsesTypedCapturesAndRetainsFirstCompiledCaller() = withLanguage { language ->
        for (async in listOf(false, true)) {
            val body = case(variable("x"), "seen", default(prim("+#", variable("seen"), integer(17))))
            val p = program(language, async, body)
            val target = p.entryTarget("entry")
            val arm = NodeUtil.findAllNodeInstances(target.rootNode, AstCaseArm::class.java).single()
            assertTrue(arm.tailPosition)
            assertEquals(2L, count(p, "loweredRootCount"))
            // Normal inliner discretion may retain this edge. Install both
            // cold targets, without invoking either body, before the first call.
            compile(arm.target)
            compile(target)
            val before = count(p, "compiledEntries")
            assertEquals(9000000018L, Calls.target(target, arrayOf(0L, 9000000001L)))
            assertEquals(before + 2, count(p, "compiledEntries"))
            assertSame(target, p.entryTarget("entry"))
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            assertEquals(true, arm.target.javaClass.getMethod("isValidLastTier").invoke(arm.target))
            released(language)
        }
    }

    @Test fun scrutineeIsEvaluatedOnceAndReturningArmKeepsItsNontailSuffix() = withLanguage { language ->
        for (async in listOf(false, true)) {
            var effects = 0
            val tick = Closure(null, 1, object : RootNode(language) {
                override fun execute(frame: VirtualFrame): Any { effects++; return frame.arguments[1]!! }
            }.callTarget)
            val selected = case(app(variable("tick", closure), variable("x")), "seen",
                zero(prim("+#", variable("seen"), integer(31))),
                default(prim("+#", variable("seen"), integer(41))))
            val body = prim("+#", selected, integer(100))
            val p = program(language, async, body, listOf(parameter("x"), parameter("tick", closure)))
            val target = p.entryTarget("entry")
            val arms = NodeUtil.findAllNodeInstances(target.rootNode, AstCaseArm::class.java)
            assertEquals(2, arms.size); assertTrue(arms.none { it.tailPosition })
            for (x in listOf(0L, 2L, 0L)) {
                val before = effects
                assertEquals(100L + x + if (x == 0L) 31L else 41L, Calls.target(target, arrayOf(0L, x, tick)))
                assertEquals(before + 1, effects)
                released(language)
            }
        }
    }

    @Test fun tailArmPreservesOuterLoopOwnershipAndDoesNotCreateGenericTrampolineLaps() = withLanguage { language ->
        for (async in listOf(false, true)) {
            val body = case(variable("x"), "seen", zero(integer(73)),
                default(app(variable("entry", closure), prim("-#", variable("seen"), integer(1)))))
            val p = program(language, async, body)
            assertEquals(73L, Calls.target(p.entryTarget("entry"), arrayOf(0L, 500L)))
            assertEquals(500L, count(p, "selfTailReentries"))
            assertEquals(0L, count(p, "trampolineIterations"))
            released(language)
        }
    }

    @Test fun outerLexicalJoinRemainsInItsOwningFrame() = withLanguage { language ->
        for (async in listOf(false, true)) {
            val join = binding("finish", lambda(listOf(parameter("n")), prim("+#", variable("n"), integer(9)))) +
                mapOf("joinValueArity" to 1, "joinResultRep" to long)
            val choice = case(variable("x"), "seen", default(app(variable("finish", closure), variable("seen"))))
            val body: ArmCore = listOf("let", false, listOf(join), choice, mapOf("rep" to long))
            val p = program(language, async, body)
            val target = p.entryTarget("entry")
            assertTrue(NodeUtil.findAllNodeInstances(target.rootNode, AstCaseArm::class.java).isEmpty())
            assertEquals(38L, Calls.target(target, arrayOf(0L, 29L)))
            assertEquals(1L, count(p, "localJoinTransfers")); assertEquals(1L, count(p, "loweredRootCount"))
        }
    }

    @Test fun narrowFloatAndReferenceFieldsKeepExactTupleShapeAcrossArm() = withLanguage { language ->
        val narrow = mapOf("kind" to "long", "primReps" to listOf("Int8Rep"), "evaluated" to true)
        val float = mapOf("kind" to "float", "primReps" to listOf("FloatRep"), "evaluated" to true)
        val tuple = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to true,
            "primReps" to listOf("Int8Rep", "FloatRep", "BoxedRep (Just Lifted)"), "components" to listOf(narrow, float, closure))
        val fields = listOf(variable("narrow", narrow), variable("floating", float), variable("marker", closure))
        val construct: ArmCore = listOf("app", listOf("con", "Tuple3", 3), fields,
            listOf(false, false, true), false, false, mapOf("rep" to tuple))
        val body = case(integer(0), "seen", default(construct), result = tuple)
        val module = mapOf("constructors" to listOf(mapOf("id" to "Tuple3", "name" to "(#,,#)",
            "arity" to 3, "tag" to 1, "kind" to "unboxed-tuple")),
            "bindings" to listOf(binding("entry", lambda(listOf(parameter("narrow", narrow),
                parameter("floating", float), parameter("marker", closure)), body, tuple))))
        val marker = Closure(null, 0, object : RootNode(language) {
            override fun execute(frame: VirtualFrame): Any = error("Captured reference must not be forced")
        }.callTarget)
        for (async in listOf(false, true)) {
            val p = Program(language, module, async, true)
            val target = p.entryTarget("entry")
            val shape = (target.rootNode as GuestRoot).tupleResult!!
            val layout = FrameLayout(); val slots = IntArray(3) { layout.bind("result $it") }
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
            shape.consume(frame, callScalarTestTarget(target, arrayOf(0L, -128,
                Float.fromBits(0x80000000.toInt()), marker)), slots, 0)
            assertEquals(-128, frame.getInt(slots[0]))
            assertEquals(0x80000000.toInt(), frame.getFloat(slots[1]).toRawBits())
            assertSame(marker, frame.getObject(slots[2])); released(language)
        }
    }

    @Test fun actualBlockingCaptureResumesOutlinedTupleArmWithoutReplayingPrefix() {
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val mvar = mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Unlifted)"), "evaluated" to true)
        val data = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false)
        val pair = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to false,
            "primReps" to listOf("BoxedRep (Just Lifted)"), "components" to listOf(state, data))
        val result = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to true,
            "primReps" to listOf("IntRep"), "components" to listOf(state, long))
        fun afterTake(id: String, suffix: ArmCore): ArmCore {
            val take = listOf("app", listOf("prim", "takeMVar#"),
                listOf(variable(id, mvar), listOf("void", mapOf("rep" to state))),
                listOf(false, false), false, false, mapOf("rep" to pair))
            return listOf("case", take, "${id}Pair", listOf(listOf("data", "Pair",
                listOf("${id}State", "${id}Value"), suffix, mapOf("binders" to listOf(
                    mapOf("id" to "${id}State", "rep" to state), mapOf("id" to "${id}Value", "rep" to data))))),
                mapOf("rep" to result, "binder" to mapOf("id" to "${id}Pair", "rep" to pair)))
        }
        val tuple = listOf("app", listOf("con", "Result", 2),
            listOf(listOf("void", mapOf("rep" to state)), variable("x")),
            listOf(false, false), false, false, mapOf("rep" to result))
        val module = mapOf("bindings" to listOf(binding("entry", lambda(
            listOf(parameter("prefix", mvar), parameter("blocked", mvar), parameter("x")),
            afterTake("prefix", afterTake("blocked", tuple)), result))),
            "constructors" to listOf(mapOf("id" to "Pair", "kind" to "unboxed-tuple", "arity" to 2),
                mapOf("id" to "Result", "kind" to "unboxed-tuple", "arity" to 2)))
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            val language: Language
            val owner: Language.State
            val p: Program
            val target: RootCallTarget
            try {
                language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                owner = Language.currentState()
                p = Program(language, module, true, true)
                target = p.entryTarget("entry")
                assertEquals(3L, count(p, "loweredRootCount"))
            } finally { context.leave() }
            val prefix = ManagedMVar().also { assertTrue(it.tryPut("prefix once")) }
            val blocked = ManagedMVar()
            val answer = CompletableFuture<SavedGuestContinuation>()
            val worker = Thread {
                context.enter(); owner.threads.enterCurrent()
                try {
                    val saved = checkNotNull(SavedGuestContinuationKt.savedGuestContinuation(Calls.target(target, arrayOf(0L, prefix, blocked, 918273645L))))
                    checkNotNull(saved.asyncRequest()).acknowledge()
                    answer.complete(saved)
                } catch (failure: Throwable) { answer.completeExceptionally(failure) }
                finally { owner.threads.leaveCurrent(); context.leave() }
            }.apply { isDaemon = true; start() }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (blocked.pendingCounts().takers != 1 && !answer.isDone && System.nanoTime() < deadline) Thread.sleep(1)
                if (answer.isCompletedExceptionally) answer.get(1, TimeUnit.SECONDS)
                assertEquals(1, blocked.pendingCounts().takers); assertTrue(prefix.isEmpty())
                owner.threads.send(owner.threads.pollState(worker).current!!.identity, "outlined cut")
                val saved = answer.get(10, TimeUnit.SECONDS)
                worker.join(5000); assertFalse(worker.isAlive)
                context.enter()
                try {
                    assertTrue(blocked.tryPut("resume"))
                    val shape = (target.rootNode as GuestRoot).tupleResult!!
                    // The outer cut owns a suspended child, not a bare MVar
                    // request. Let the existing update driver resume that child
                    // before supplying ChildResume to the saved outer suffix.
                    val parked = Thunk(target, null).apply { this.value = saved.identity; this.state = 5 }
                    val driver = object : RootNode(language) {
                        @Child var force = Force(Metrics(false), true)
                        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, parked)
                    }.callTarget
                    val completed = TupleResultsKt.ownedTupleResult(Calls.target(driver, emptyArray()), shape)
                    assertEquals(918273645L, shape.layout.getLong(completed, 0))
                    assertTrue(prefix.isEmpty()); assertTrue(blocked.isEmpty())
                    assertThrows(RuntimeFault::class.java) { saved.continueWith(Unit) }
                    assertSame(target, p.entryTarget("entry")); released(language)
                } finally { context.leave() }
            } finally {
                if (worker.isAlive) context.close(true)
                worker.join(5000)
            }
        }
    }

    @Test fun genuineDelimitedResumptionKeepsTailJoinMaskCatchAndTupleResults() {
        val root = File(System.getProperty("thc.projectRoot"))
        val manifest = Json.parse(File(root, "build/delimited-continuations/manifest.json").readText()) as Map<*, *>
        for (group in listOf("inputHashes", "artifactHashes")) for ((path, hash) in manifest[group] as Map<*, *>)
            assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it) }, path)
        val entries = manifest["entries"] as List<*>
        val inputs = (manifest["arguments"] as List<*>).map { (it as Number).toLong() }
        val native = (manifest["native"] as List<*>).map { (it as Number).toLong() }
        for (stage in listOf("pre", "post")) for (async in listOf(false, true)) withLanguage { language ->
            @Suppress("UNCHECKED_CAST")
            val source = Json.parse(File(root, "build/delimited-continuations/$stage/core/DelimitedContinuations.json").readText()) as Map<String, Any?>
            for (entry in listOf("resumeTwice", "resumedTail", "resumedJoin", "resumedScalar", "capturedCatch", "capturedMask")) {
                val p = Program(language, CoreModules.reachable(source, entry), async, true)
                val function = org.graalvm.polyglot.Context.getCurrent().asValue(EntryValue(p, entry, 1))
                for ((index, input) in inputs.withIndex()) {
                    assertEquals(native[index * entries.size + entries.indexOf(entry)], function.execute(input).asLong(), "$stage/$async/$entry")
                    assertEquals(MaskingState.UNMASKED, Language.currentState().maskingState.get())
                    released(language)
                }
            }
        }
    }
}
