// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest

class SynchronousExceptionsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/synchronous-exceptions")
    private val supported = listOf("preciseCatch", "erasedNestedCatch", "actionHeadCatch", "ignoredBottomPayload", "nestedRethrow",
        "unusedHandler", "lazyResultBoundary", "restoreAndRethrow", "handlerMaskState",
        "maskNested", "maskRethrowRestore", "noDuplicateProbe")

    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun genuineNativeExceptionsMatchBothBackends() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<*, *>
        for (key in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[key] as Map<*, *>) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash, "Stale exception fixture: $path")
        }
        @Suppress("UNCHECKED_CAST")
        val statuses = manifest["auditStatus"] as Map<String, Map<String, Any?>>
        assertTrue(statuses.values.all { it["accepted"] == true })
        val inputs = (manifest["inputs"] as List<*>).map { (it as Number).toLong() }
        val cases = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(169, inputs.size)
        assertEquals(supported.size * inputs.size, cases.values.sumOf { it.size })
        for (stage in listOf("pre", "post")) {
            @Suppress("UNCHECKED_CAST")
            val paths = (manifest["stages"] as Map<String, List<String>>).getValue(stage)
            @Suppress("UNCHECKED_CAST")
            val module = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
            for (backend in listOf("ast", "bytecode")) for (name in supported) {
                val selected = cases.getValue(name).map { it[1].toLong() to it[2].toLong() }
                assertEquals(inputs, selected.map { it.first })
                Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                    context.initialize("thc")
                    context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val function = context.asValue(EntryValue(program, name, 1))
                        val label = "$stage/$backend/$name"
                        for ((input, expected) in selected)
                            assertEquals(expected, function.execute(input).asLong(), "$label/$input")
                        compile(program.entryTarget(name))
                        assertTrue(function.invokeMember("compile").asBoolean(), "$label host compilation")
                        for ((input, expected) in selected.take(2)) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(expected, function.execute(input).asLong(), "$label compiled/$input")
                            val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertTrue(after > before, "$label entered compiled guest code")
                        }
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(program.entryTarget(name).rootNode),
                            "$label restored caller masking state")
                        if (name == "handlerMaskState") {
                            val node = program.entryTarget(name).rootNode
                            // A top-level guest exit clears its Java-thread mask. Keep an
                            // outer guest entry alive while checking nested restoration.
                            val threads = Language.currentState(node).threads
                            threads.enterCurrent()
                            try {
                                SynchronousMasking.set(node, MaskingState.MASKED_UNINTERRUPTIBLE)
                                assertEquals(34L, function.execute(0L).asLong(), "$label nested unmask")
                                assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(node),
                                    "$label restored masked caller")
                            } finally { threads.leaveCurrent() }
                            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(node),
                                "$label completed outer guest entry")
                        }
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong(), label)
                        assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong(), label)
                    } finally { context.leave() }
                }
            }
        }
    }
}
