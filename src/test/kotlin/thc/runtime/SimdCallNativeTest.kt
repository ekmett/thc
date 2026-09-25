// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class SimdCallNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-calls")
    private fun model(name: String, x: Long): Long = if (name == "overCase")
        if (x.toShort().toLong() == 0L) 30L else 44L
        else x.toShort().toLong() + 13L
    private fun valid(target: Any) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun nodes(value: Any?): Sequence<List<*>> = sequence {
        when (value) {
            is List<*> -> {
                yield(value)
                for (member in value) yieldAll(nodes(member))
            }
            is Map<*, *> -> for (member in value.values) yieldAll(nodes(member))
        }
    }
    private fun binderId(value: Any?): String? = (value as? Map<*, *>)?.get("id") as? String
    private fun vectorBinder(value: Any?): String? = (value as? Map<*, *>)?.let { binder ->
        binderId(binder).takeIf { (binder["rep"] as? Map<*, *>)?.get("kind") == "vector" }
    }
    private fun freeVectorIds(value: Any?, bound: Set<String> = emptySet()): Set<String> {
        val node = value as? List<*> ?: return emptySet()
        return when (node.firstOrNull()) {
            "var" -> (node.getOrNull(1) as? String)?.takeIf { id -> id !in bound &&
                ((node.getOrNull(2) as? Map<*, *>)?.get("rep") as? Map<*, *>)?.get("kind") == "vector" }
                ?.let { setOf(it) } ?: emptySet()
            "lam" -> freeVectorIds(node.getOrNull(2), bound +
                (node.getOrNull(1) as? List<*>)?.mapNotNull(::binderId).orEmpty())
            "let" -> {
                val group = (node.getOrNull(2) as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
                val ids = group.mapNotNull(::binderId).toSet()
                group.flatMap { freeVectorIds(it["expr"], if (node.getOrNull(1) == true) bound + ids else bound) }.toSet() +
                    freeVectorIds(node.getOrNull(3), bound + ids)
            }
            "case" -> {
                val caseId = node.getOrNull(2) as? String
                freeVectorIds(node.getOrNull(1), bound) +
                    (node.getOrNull(3) as? List<*>)?.flatMap { alternative ->
                        val arm = alternative as? List<*> ?: return@flatMap emptySet<String>()
                        freeVectorIds(arm.getOrNull(3), bound + listOfNotNull(caseId) +
                            (arm.getOrNull(2) as? List<*>)?.filterIsInstance<String>().orEmpty())
                    }.orEmpty()
            }
            "app" -> freeVectorIds(node.getOrNull(1), bound) +
                (node.getOrNull(2) as? List<*>)?.flatMap { freeVectorIds(it, bound) }.orEmpty()
            else -> emptySet()
        }
    }
    private fun retainedVectorCaptures(value: Any?): Pair<Boolean, Boolean> {
        val node = value as? List<*> ?: return false to false
        fun walk(expr: Any?, outerVectors: Set<String>): Pair<Boolean, Boolean> {
            val current = expr as? List<*> ?: return false to false
            return when (current.firstOrNull()) {
                "lam" -> {
                    val formals = (current.getOrNull(1) as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
                    val body = current.getOrNull(2)
                    val captured = freeVectorIds(body, formals.mapNotNull(::binderId).toSet()).any { it in outerVectors }
                    val nested = walk(body, outerVectors + formals.mapNotNull(::vectorBinder))
                    (captured || nested.first) to nested.second
                }
                "let" -> {
                    val group = (current.getOrNull(2) as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
                    val localVectors = group.mapNotNull(::vectorBinder).toSet()
                    val liftedCapture = group.any { it["lifted"] == true &&
                        freeVectorIds(it["expr"]).any { id -> id in outerVectors } }
                    val rhs = group.map { walk(it["expr"], outerVectors + localVectors) }
                    val body = walk(current.getOrNull(3), outerVectors + localVectors)
                    (body.first || rhs.any { it.first }) to (liftedCapture || body.second || rhs.any { it.second })
                }
                "case" -> {
                    val scrutinee = walk(current.getOrNull(1), outerVectors)
                    val caseVector = vectorBinder((current.getOrNull(4) as? Map<*, *>)?.get("binder"))
                    val arms = (current.getOrNull(3) as? List<*>)?.mapNotNull { it as? List<*> }.orEmpty().map { arm ->
                        val binders = ((arm.getOrNull(4) as? Map<*, *>)?.get("binders") as? List<*>)
                            ?.mapNotNull(::vectorBinder).orEmpty()
                        walk(arm.getOrNull(3), outerVectors + listOfNotNull(caseVector) + binders)
                    }
                    (scrutinee.first || arms.any { it.first }) to (scrutinee.second || arms.any { it.second })
                }
                "app" -> {
                    val parts = listOf(current.getOrNull(1)) + (current.getOrNull(2) as? List<*>).orEmpty()
                    val found = parts.map { walk(it, outerVectors) }
                    found.any { it.first } to found.any { it.second }
                }
                else -> false to false
            }
        }
        return walk(node, emptySet())
    }
    /** GHC may pass a lifted thunk directly instead of naming it with a let. */
    private fun retainedLazyVectorArgument(value: Any?, consumerId: String): Boolean =
        nodes(value).filter { it.getOrNull(0) == "case" }.any { case ->
            val vector = vectorBinder((case.getOrNull(4) as? Map<*, *>)?.get("binder"))
                ?: return@any false
            (case.getOrNull(3) as? List<*>)?.any { alternative ->
                val body = (alternative as? List<*>)?.getOrNull(3)
                nodes(body).any { call ->
                    val head = call.getOrNull(1) as? List<*>
                    val arguments = call.getOrNull(2) as? List<*>
                    val metadata = call.getOrNull(6) as? Map<*, *>
                    val demand = metadata?.get("callDemand") as? Map<*, *>
                    val delayed = arguments?.getOrNull(1) as? List<*>
                    val proof = ((delayed?.lastOrNull() as? Map<*, *>)?.get("rep") as? Map<*, *>)
                    call.getOrNull(0) == "app" && head?.getOrNull(0) == "var" &&
                        head?.getOrNull(1) == consumerId && arguments?.size == 2 &&
                        call.getOrNull(3) == listOf(false, true) &&
                        (demand?.get("arity") as? Number)?.toInt() == 2 &&
                        demand?.get("strictArgs") == listOf(true, false) &&
                        proof?.get("kind") == "data" &&
                        proof?.get("primReps") == listOf("BoxedRep (Just Lifted)") &&
                        proof?.get("evaluated") == false && freeVectorIds(delayed).contains(vector)
                }
            } == true
        }

    @Test fun exportedLazyVectorArgumentRequiresExactDemandAndOuterCapture() {
        val vector = mapOf("kind" to "vector", "primReps" to listOf("VecRep 8 Int16ElemRep"),
            "evaluated" to true)
        val lazyBox = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"),
            "evaluated" to false)
        fun exported(flags: List<Boolean> = listOf(false, true), strict: List<Boolean> = listOf(true, false),
            box: Map<String, Any?> = lazyBox, captured: String = "vector"): List<Any?> {
            val delayed = listOf("case", listOf("var", captured, mapOf("rep" to vector)), "inner",
                listOf(listOf("default", null, emptyList<Any>(), listOf("con", "I#", 1),
                    mapOf("binders" to emptyList<Any>()))), mapOf("rep" to box))
            val call = listOf("app", listOf("var", "selectBox"),
                listOf(listOf("var", "x"), delayed), flags, false, false,
                mapOf("callDemand" to mapOf("arity" to 2, "strictArgs" to strict)))
            return listOf("case", listOf("var", "source"), "vector",
                listOf(listOf("default", null, emptyList<Any>(), call,
                    mapOf("binders" to emptyList<Any>()))),
                mapOf("binder" to mapOf("id" to "vector", "rep" to vector)))
        }
        assertTrue(retainedLazyVectorArgument(exported(), "selectBox"))
        assertFalse(retainedLazyVectorArgument(exported(flags = listOf(false, false)), "selectBox"))
        assertFalse(retainedLazyVectorArgument(exported(strict = listOf(true, true)), "selectBox"))
        assertFalse(retainedLazyVectorArgument(exported(box = lazyBox + ("evaluated" to true)), "selectBox"))
        assertFalse(retainedLazyVectorArgument(exported(captured = "unrelated"), "selectBox"))
    }
    /** Inspect exported Core structure, not the Haskell source spelling. */
    private fun retainedHeapCore(source: Map<String, Any?>) {
        val constructors = source["constructors"] as List<Map<String, Any?>>
        val heap = constructors.single { it["name"] == "Heap" }
        val heapId = heap["id"]
        assertEquals(2L, (heap["arity"] as Number).toLong())
        assertEquals(listOf("VecRep 8 Int16ElemRep"), (heap["fieldReps"] as List<*>)[0])
        val bindings = source["bindings"] as List<Map<String, Any?>>
        val heapPapRoot = bindings.single { it["name"] == "heapPapCase" }
        val expressions = nodes(heapPapRoot["expr"])
        // GHC Core saturates data constructors: the source-level Heap vector
        // PAP is a one-argument closure retaining that vector, then making Heap.
        val partial = bindings.single { it["name"] == "applyHeapPartial" }
        val partialId = partial["id"]
        val papCalls = expressions.filter { it.getOrNull(0) == "app" &&
            (it.getOrNull(1) as? List<*>)?.getOrNull(1) == partialId }.toList()
        assertTrue(papCalls.any { call ->
            val argument = (call.getOrNull(2) as? List<*>)?.singleOrNull()
            val closures = nodes(argument).filter { it.getOrNull(0) == "lam" &&
                (it.getOrNull(1) as? List<*>)?.size == 1 }
            closures.any { closure ->
                val formal = binderId((closure[1] as List<*>)[0])
                val constructorsInBody = nodes(closure.getOrNull(2)).filter { it.getOrNull(0) == "app" &&
                    (it.getOrNull(1) as? List<*>)?.let { head ->
                        head.getOrNull(0) == "con" && head.getOrNull(1) == heapId } == true &&
                    (it.getOrNull(2) as? List<*>)?.size == 2 }
                formal != null && constructorsInBody.any { construction ->
                    val fields = construction[2] as List<*>
                    freeVectorIds(fields[0]).isNotEmpty() &&
                        (fields[1] as? List<*>)?.let { it.getOrNull(0) == "var" && it.getOrNull(1) == formal } == true
                }
            }
        } && retainedVectorCaptures(heapPapRoot["expr"]).first,
            "Exported Core lacks the vector-retaining constructor partial application")
        val partialFormal = binderId(((partial["expr"] as List<*>)[1] as List<*>).single())
        assertTrue(nodes(partial["expr"]).any { it.getOrNull(0) == "app" &&
            (it.getOrNull(1) as? List<*>)?.let { head ->
                head.getOrNull(0) == "var" && head.getOrNull(1) == partialFormal } == true &&
            (it.getOrNull(2) as? List<*>)?.size == 1 },
            "Exported Core never applies the retained constructor closure")
        val consumer = bindings.single { it["name"] == "consumeHeap" }
        assertTrue(nodes(consumer["expr"]).any { it.getOrNull(0) == "data" && it.getOrNull(1) == heapId },
            "Exported Core lacks the real vector constructor case")
        val closureRoot = bindings.single { it["name"] == "capturedCase" }
        val thunkRoot = bindings.single { it["name"] == "thunkCase" }
        val thunkConsumer = bindings.single { it["name"] == "selectBox" }
        assertTrue(retainedVectorCaptures(closureRoot["expr"]).first,
            "Exported capturedCase lacks a free vector from an outer lexical binder")
        assertTrue(retainedVectorCaptures(thunkRoot["expr"]).second ||
            retainedLazyVectorArgument(thunkRoot["expr"], thunkConsumer["id"] as String),
            "Exported thunkCase lacks a lazy lifted argument capturing an outer vector")
    }

    @Test fun nativeVectorCallsPapAndJoinsKeepCompiledAstAndBytecodeResults() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        for ((path, want) in ((manifest["inputHashes"] as Map<String, String>) +
                (manifest["artifactHashes"] as Map<String, String>))) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(want, hash, "Stale SIMD call artifact $path")
        }
        val entries = manifest["entries"] as List<String>
        assertEquals(setOf("directCase", "papCase", "nestedTupleCase", "joinCase", "overCase",
            "heapCase", "heapPapCase", "capturedCase", "thunkCase"), entries.toSet())
        val cases = entries.flatMap { name -> (manifest["inputs"] as List<Number>).map { input -> name to input.toLong() } }
        assertEquals(81, cases.size)
        val nativeRows = manifest["nativeRows"] as Number?
        val rows = if (nativeRows != null) File(directory, "oracle.tsv").readLines().map { line ->
            val parts = line.split('\t')
            assertEquals(3, parts.size)
            Triple(parts[0], parts[1].toLong(), parts[2].toLong())
        } else cases.map { (name,x) -> Triple(name,x,model(name,x)) }
        assertEquals(cases, rows.map { it.first to it.second })
        if (nativeRows != null) assertEquals(rows.size.toLong(), nativeRows.toLong())
        for ((name,x,want) in rows) assertEquals(model(name,x), want, "${if (nativeRows == null) "Model" else "Native"} $name/$x")
        for (stage in manifest["stages"] as List<String>) for (backend in listOf("ast","bytecode"))
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val source = Json.parse(File(directory, "$stage-core/SimdCallAudit.json").readText()) as Map<String, Any?>
                        retainedHeapCore(source)
                        for (name in entries) {
                            val linked = CoreModules.reachable(source, name)
                            val program = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            val function = context.asValue(EntryValue(program, name, 1))
                            val cases = rows.filter { it.first == name }
                            for ((_,x,want) in cases) assertEquals(want, function.execute(x).asLong(), "$stage/$backend/$name/$x interpreted")
                            assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend/$name compile")
                            val target = program.entryTarget(name)
                            for ((_,x,want) in cases) {
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(want, function.execute(x).asLong(), "$stage/$backend/$name/$x compiled")
                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                                valid(target)
                                assertEquals(0, language.handoffState.get().arguments.depth)
                                assertEquals(0, language.handoffState.get().results.depth)
                            }
                        }
                    } finally { context.leave() }
                }
    }
}
