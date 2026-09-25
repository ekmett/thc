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
    /** Inspect exported Core structure, not the Haskell source spelling. */
    private fun retainedHeapCore(source: Map<String, Any?>) {
        val constructors = source["constructors"] as List<Map<String, Any?>>
        val heap = constructors.single { it["name"] == "Heap" }
        val heapId = heap["id"]
        assertEquals(2L, (heap["arity"] as Number).toLong())
        assertEquals(listOf("VecRep 8 Int16ElemRep"), (heap["fieldReps"] as List<*>)[0])
        val bindings = source["bindings"] as List<Map<String, Any?>>
        val expressions = nodes(bindings)
        assertTrue(expressions.any { it.getOrNull(0) == "app" &&
            (it.getOrNull(1) as? List<*>)?.let { head -> head.getOrNull(0) == "con" && head.getOrNull(1) == heapId } == true &&
            (it.getOrNull(2) as? List<*>)?.size == 1 }, "Exported Core lacks a vector constructor PAP")
        assertTrue(nodes(bindings).any { it.getOrNull(0) == "data" && it.getOrNull(1) == heapId },
            "Exported Core lacks the real vector constructor case")
        fun vectorVar(value: Any?): Boolean = nodes(value).any { node ->
            node.getOrNull(0) == "var" &&
                ((node.getOrNull(2) as? Map<*, *>)?.get("rep") as? Map<*, *>)?.get("kind") == "vector"
        }
        assertTrue(nodes(bindings).any { node -> node.getOrNull(0) == "lam" &&
            vectorVar(node.getOrNull(2)) && (node.getOrNull(1) as? List<*>)?.none { formal ->
                (formal as? Map<*, *>)?.get("rep")?.let { (it as? Map<*, *>)?.get("kind") } == "vector"
            } == true }, "Exported Core lacks a vector captured by a closure")
        assertTrue(nodes(bindings).any { node -> node.getOrNull(0) == "let" &&
            (node.getOrNull(2) as? List<*>)?.any { rhs ->
                (rhs as? Map<*, *>)?.let { it["lifted"] == true && vectorVar(it["expr"]) } == true
            } == true }, "Exported Core lacks a lifted thunk using a vector capture")
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
