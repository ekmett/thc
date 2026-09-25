// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

/** Untouched original foreign applications, not a replacement Haskell decoder or native frames. */
class OriginalStackDecoderCallTest {
    private fun resource() = Json.parse(javaClass.getResource("/core/original-stack-decoder-calls.json")!!.readText())
        as Map<String, Any?>
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val hot = setOf("getStackFieldszh", "getSmallBitmapzh", "advanceStackFrameLocationzh", "getWordzh")
    private val cold = setOf("getStackClosurezh", "getLargeBitmapzh", "getBCOLargeBitmapzh",
        "getRetFunLargeBitmapzh", "getRetFunSmallBitmapzh", "isArgGenBigRetFunTypezh", "getUnderflowFrameNextChunkzh")
    private fun name(symbol: String, field: Int) = "$symbol-$field"

    /** Each tuple component is independently returned; no getter's result is replaced by a constant. */
    private fun module(): Map<String, Any?> {
        val original = resource()
        val bindings = (original.getValue("calls") as List<Map<String, Any?>>).flatMap { record ->
            val call = record.getValue("application") as List<Any?>
            val result = (call[6] as Map<String, Any?>).getValue("rep") as Map<String, Any?>
            val descriptor = (call[6] as Map<String, Any?>).getValue("foreignCall") as Map<String, Any?>
            val symbol = (descriptor.getValue("target") as Map<String, Any?>).getValue("symbol") as String
            val components = result["components"] as? List<Map<String, Any?>>
            val formals = (call[2] as List<List<Any?>>).map { argument ->
                mapOf("id" to argument[1], "name" to argument[1], "lifted" to false,
                    "rep" to (argument.last() as Map<String, Any?>).getValue("rep"))
            }
            (components?.indices ?: 0..0).map { choice ->
                val name = name(symbol, choice)
                val scalar = components?.get(choice) ?: result
                val body = if (components == null) call else {
                    val ids = components.indices.map { "$name-result-$it" }
                    val constructor = "ghc-internal:GHC.Internal.Types.(#${",".repeat(components.size - 1)}#)"
                    listOf("case", call, "$name-tuple", listOf(listOf("data", constructor, ids,
                        listOf("var", ids[choice], mapOf("rep" to scalar)),
                        mapOf("binders" to components.mapIndexed { i, rep -> mapOf("id" to ids[i], "lifted" to false, "rep" to rep) }))),
                        mapOf("rep" to scalar, "binder" to mapOf("id" to "$name-tuple", "lifted" to false, "rep" to result)))
                }
                mapOf("id" to "test:OriginalStackDecoder.$name", "name" to name, "arity" to formals.size,
                    "lifted" to true, "rep" to closure,
                    "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to scalar)))
            }
        }
        return mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to "test", "module" to "OriginalStackDecoder",
            "instrument" to true, "targetLayout" to StackInfoTestLayout.layout(), "bindings" to bindings,
            "sourceFiles" to original.getValue("sourceFiles"), "sourceSpans" to original.getValue("sourceSpans"),
            "constructors" to (2..3).map { arity ->
                val name = "(#${",".repeat(arity - 1)}#)"
                mapOf("id" to "ghc-internal:GHC.Internal.Types.$name", "name" to name,
                    "kind" to "unboxed-tuple", "arity" to arity, "tag" to 1)
            })
    }

    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget, label: String) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    private class Capture : Expr() {
        override fun execute(frame: VirtualFrame): Any = ManagedStackSnapshot.capture(this)
    }
    private class CaptureRoot(language: Language) : GuestRoot(language, FrameLayout().build()) {
        @field:Child private var body = Capture()
        override fun execute(frame: VirtualFrame): Any = body.execute(frame)
        override fun bloom(frame: VirtualFrame) = 0L
        override fun getName() = "decoder-leaf"
    }
    private class CallerRoot(language: Language, target: RootCallTarget) : GuestRoot(language, FrameLayout().build()) {
        @field:Child private var call = DirectCallNode.create(target)
        override fun execute(frame: VirtualFrame): Any = call.call()
        override fun bloom(frame: VirtualFrame) = 0L
        override fun getName() = "decoder-caller"
    }

    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun checkProvenance(resource: Map<String, Any?>) {
        val text = File(System.getProperty("thc.projectRoot"), "compiler/test-fixtures/OriginalStackProof.json").readText()
        assertEquals("db63661c12a6ecb757697e759fcb95e4d51f3689619bdb7682a041788eb41d4f", digest(text))
        val proof = Json.parse(text) as Map<String, Any?>
        fun symbol(app: List<Any?>) = (((app[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"]
        val source = "GHC.Internal.Stack.Decode/GHC.Internal.Stack.Decode.json"
        val expected = (proof.getValue("calls") as List<Map<String, Any?>>)
            .filter { symbol(it["expression"] as List<Any?>) in hot + cold }
            .distinctBy { symbol(it["expression"] as List<Any?>) }.map { record ->
                mapOf("source" to source, "owner" to record["owner"], "application" to record["expression"],
                    "path" to "/" + (record["path"] as List<*>).drop(2).joinToString("/"))
            }
        assertEquals("902339d332fb4ce2b3c87dcac1ee6495d41ad886", resource["ghcRevision"])
        assertEquals("62e3400c5b889d3971cb4047709c408fd270255f", resource["exporterRevision"])
        val calls = resource.getValue("calls") as List<Map<String, Any?>>
        assertEquals(expected, calls) // Identity, owner-relative path and raw application together.
        val symbols = calls.map { record ->
            val app = record.getValue("application") as List<Any?>
            val meta = app[6] as Map<String, Any?>
            val descriptor = meta.getValue("foreignCall") as Map<String, Any?>
            val symbol = (descriptor.getValue("target") as Map<String, Any?>).getValue("symbol") as String
            if (symbol == "getStackClosurezh") assertEquals(
                mapOf("kind" to "object", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to false), descriptor["resultRep"])
            symbol
        }
        assertEquals(hot + cold, symbols.toSet()); assertEquals(11, symbols.size)
        val files = resource.getValue("sourceFiles") as List<Map<String, Any?>>
        assertEquals(1, files.size)
        val file = files.single()
        assertEquals(setOf("id", "path", "content"), file.keys)
        assertEquals(file["id"], file["path"])
        val content = file["content"] as String
        assertEquals("0ea6a82ea41bdf14b28aec5cb36a586ed86eb6f87f373ea21095d2b1b018089f",
            digest(content))
        val referenced = linkedSetOf<String>()
        fun references(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    (value["source"] as? String)?.let(referenced::add)
                    (value["sourceNotes"] as? List<String>)?.let(referenced::addAll)
                    value.values.forEach(::references)
                }
                is List<*> -> value.forEach(::references)
            }
        }
        calls.forEach { references(it["application"]) }
        val spans = resource.getValue("sourceSpans") as List<Map<String, Any?>>
        assertEquals(referenced, spans.map { it["id"] }.toSet())
        assertEquals(referenced.size, spans.size)
        assertEquals(files.map { it["id"] }.toSet(), spans.map { it["file"] }.toSet())
        // Pin records COPIED from the sourceFiles/sourceSpans tables of the full
        // c3762b0e... export, not reconstructed from note IDs. Sorted JSON object
        // keys make formatting irrelevant while preserving original record order.
        assertEquals("a3c80947a99ee3a53fc4bbe415ad20b21f9a76852b8324a73d14baf240379dd2",
            digest(Json.stringify(mapOf("sourceFiles" to files.map { it.toSortedMap() },
                "sourceSpans" to spans.map { it.toSortedMap() }))))
        assertEquals(listOf(mapOf("file" to source, "sha256" to "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42",
            "boundary" to "optimized-Core-after-Tidy-before-CorePrep")), resource["sources"])
    }

    @Test fun retainedApplicationsAreExactOriginalProofExcerptsIncludingLiftedClosureResult() { checkProvenance(resource()) }

    @Test fun retainedOwnerPathsAndSourceProjectionRejectMutation() {
        val original = resource()
        val calls = original["calls"] as List<Map<String, Any?>>
        for (field in listOf("owner", "path", "source")) assertThrows(AssertionError::class.java) {
            checkProvenance(original + ("calls" to (listOf(calls[0] + (field to "forged")) + calls.drop(1))))
        }
        val spans = original["sourceSpans"] as List<Map<String, Any?>>
        for (changed in listOf(spans.drop(1), spans + spans[0],
                listOf(spans[0] + ("label" to "forged")) + spans.drop(1),
                listOf(spans[0] + ("charIndex" to -1L)) + spans.drop(1)))
            assertThrows(AssertionError::class.java) { checkProvenance(original + ("sourceSpans" to changed)) }
        val files = original["sourceFiles"] as List<Map<String, Any?>>
        for (changed in listOf(emptyList(), files + files[0], listOf(files[0] + ("path" to "forged"))))
            assertThrows(AssertionError::class.java) { checkProvenance(original + ("sourceFiles" to changed)) }
    }

    @Test fun originalAstGettersExecuteInFirstInstalledCompiledEntries() { exerciseBackend("ast") }
    @Test fun originalBytecodeGettersExecuteInFirstInstalledCompiledEntries() { exerciseBackend("bytecode") }

    private fun exerciseBackend(backend: String) {
        for (inlining in listOf(false, true)) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val capture = CaptureRoot(language).callTarget
                val single = capture.call() as ManagedStackSnapshot
                val multiple = CallerRoot(language, capture).callTarget.call() as ManagedStackSnapshot
                assertEquals(1, single.frames.size); assertEquals(2, multiple.frames.size)
                val input = module()
                val program: ExecutableProgram = if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
                val targets = (input["bindings"] as List<Map<String, Any?>>).associate {
                    (it["name"] as String) to program.entryTarget(it["name"] as String)
                }
                var compiled = false
                fun call(symbol: String, field: Int, vararg args: Any?): Any? {
                    val name = name(symbol, field)
                    val target = targets.getValue(name)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    try {
                        return Calls.target(target, arrayOf(0L, *args))
                    } finally {
                        if (compiled) {
                            assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), name)
                            valid(target, "$backend/inlining=$inlining/$name")
                        }
                        released(language)
                    }
                }
                fun exercise() {
                    for (snapshot in listOf(single, multiple)) {
                        assertEquals(snapshot.frames.size.toLong(), call("getStackFieldszh", 0, snapshot))
                        for (offset in snapshot.frames.indices) {
                            val key = ManagedStackRuntime.frameInfo(snapshot, offset.toLong(),
                                StackInfoTestLayout.layout()).second
                            val bits = call("getWordzh", 0, snapshot, offset.toLong()) as Long
                            assertEquals(key.toNativeBits(), bits)
                            assertTrue(key.sameLocation(NativeAddresses.current(null).recover(bits)))
                            assertEquals(0L, call("getSmallBitmapzh", 0, snapshot, offset.toLong()))
                            assertEquals(0L, call("getSmallBitmapzh", 1, snapshot, offset.toLong()))
                            val more = offset + 1 < snapshot.frames.size
                            val next = call("advanceStackFrameLocationzh", 0, snapshot, offset.toLong())
                            if (more) assertSame(snapshot, next) else assertNull(next)
                            assertEquals(if (more) offset + 1L else 0L, call("advanceStackFrameLocationzh", 1, snapshot, offset.toLong()))
                            assertEquals(if (more) 1L else 0L, call("advanceStackFrameLocationzh", 2, snapshot, offset.toLong()))
                        }
                    }
                }
                repeat(3) { exercise() }
                val hotTargets = targets.filterKeys { key -> hot.any { key.startsWith("$it-") } }
                hotTargets.forEach { (name, target) ->
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    valid(target, "$backend/inlining=$inlining/$name installation")
                }
                compiled = true
                exercise() // First entry after installation, no settling or recompilation.
                exercise()
                compiled = false
                for (symbol in cold) assertThrows(RuntimeFault::class.java, {
                    call(symbol, 0, multiple, 0L)
                }, "$backend/$symbol must reject absent payload or incompatible frame kind")
                for (symbol in listOf("getSmallBitmapzh", "advanceStackFrameLocationzh", "getWordzh"))
                    for (offset in listOf(-1L, 2L, Long.MAX_VALUE, Long.MIN_VALUE))
                        assertThrows(RuntimeFault::class.java) { call(symbol, 0, multiple, offset) }
                assertThrows(RuntimeFault::class.java) { call("getStackFieldszh", 0, null) }
                assertThrows(RuntimeFault::class.java) { call("advanceStackFrameLocationzh", 0, null, 0L) }
                released(language)
            } finally { context.leave() }
        }
    }
}
