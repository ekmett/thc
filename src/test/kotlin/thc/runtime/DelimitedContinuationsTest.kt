// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
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
                    val labels = lambdas.map { lambda -> "lambda " + (lambda[1] as List<*>).joinToString {
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
                    ThreadInventoryCoreEvidence.install(targets)
                    assertTrue(function.invokeMember("compile").asBoolean())
                    assertEquals(before, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                    val handoff = language.handoffState.get()
                    val allocations = handoff.arguments.allocations to handoff.results.allocations
                    assertEquals(expected(entry, 11), function.execute(11L).asLong(), "$stage/$backend/$entry first installed call")
                    assertEquals(calls.getValue(entry), (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before,
                        "$stage/$backend/$entry: resumes do not restart original Core roots")
                    assertEquals(interpreted, ThreadInventoryCoreEvidence.interpretedCalls(targets), "No interpreted settling call")
                    ThreadInventoryCoreEvidence.released(language)
                    assertEquals(allocations, handoff.arguments.allocations to handoff.results.allocations)
                    assertEquals(MaskingState.UNMASKED, Language.currentState(null).maskingState.get())
                } finally { context.leave() }
            }
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
