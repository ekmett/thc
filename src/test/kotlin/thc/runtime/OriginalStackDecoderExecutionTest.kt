// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

/** The unchanged GHC decoder and formatter consume a detached THC call-site snapshot. */
class OriginalStackDecoderExecutionTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private val installedAbi by lazy {
        val command = System.getenv("GHC_PKG") ?: "ghc-pkg"
        val process = ProcessBuilder(command, "field", "ghc-internal", "abi", "--simple-output")
            .redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText().trim() }
        require(process.waitFor() == 0 && result.matches(Regex("[0-9a-f]+"))) {
            "Cannot identify the installed ghc-internal ABI: $result"
        }
        result
    }

    private fun linked(stage: String): Map<String, Any?> {
        val consumer = json("build/original-stack/manifest.json")
        val path = (consumer["stages"] as Map<String, List<String>>).getValue(stage)
            .single { it.endsWith("/OriginalStackAudit.json") }
        val fresh = json(path)
        val entry = "${fresh.getValue("unit")}:OriginalStackAudit.renderOriginalNames"
        val source = json("build/original-stack-formatter/manifest.json")
        val originals = (source["originals"] as List<String>).map(::json)
            .filter { it["schema"] == 1L || it["foreignLink"] != null }
        val attempt = (source["nativeOutput"] as String).substringBefore("/logs/")
        val fields = json("$attempt/originals/target-layout.json")
        val layout = TargetLayout.fromDocument(mapOf("format" to "thc-target-layout", "schema" to 1,
            "compiler" to mapOf("id" to "ghc-${source["ghc"]}", "abi" to installedAbi,
                "platform" to fields["targetPlatform"], "way" to "dynamic-nonprofiling"),
            "layout" to fields))
        // The original decoder retains native-frame branches that are not valid
        // for THC's zero-payload RET_SMALL snapshots. Keep those operations as
        // diagnostic traps, and prove that this actual frame path never enters one.
        return CoreModules.reachable(CoreModules.merge(originals + fresh),
            entry, strictLink = true) + ("entry" to entry) + ("targetLayout" to layout) +
            ("instrument" to true) + ("diagnosticUnsupported" to true)
    }

    private class CaptureRoot(language: Language) : GuestRoot(language, FrameLayout().build()) {
        @Child private var capture = object : Expr() {
            override fun execute(frame: VirtualFrame): Any = ManagedStackSnapshot.capture(this)
        }
        init { configureCoreIdentity(CoreFunctionIdentity("test:StackProbe.capture", "test", "StackProbe", "capture")) }
        override fun execute(frame: VirtualFrame): Any = capture.execute(frame)
        override fun bloom(frame: VirtualFrame) = 0L
        override fun getName() = "StackProbe.capture"
    }

    private class ForceActionRoot(language: Language) : GuestRoot(language, FrameLayout().build()) {
        @Child private var force = Force(Metrics(false))
        override fun bloom(frame: VirtualFrame) = 0L
        override fun execute(frame: VirtualFrame): Any? = force.execute(frame, frame.arguments[0])
    }

    private class RunNamesRoot(language: Language, shape: TupleShape,
        layout: FrameLayout = FrameLayout(), private val fields: IntArray = IntArray(shape.width) { layout.bind("IO result $it") }
    ) : GuestRoot(language, layout.build()) {
        @Child private var dispatch = TupleDispatch(object : TupleDestination(shape) {
            override fun consume(frame: VirtualFrame, node: com.oracle.truffle.api.nodes.Node, result: Any?) =
                shape.consume(frame, result, fields, 0)
        }, Metrics(false), 1, false)
        @Child private var force = Force(Metrics(false))
        override fun bloom(frame: VirtualFrame) = 0L

        private fun elements(frame: VirtualFrame, initial: Any?, limit: Int): List<Any?> {
            val output = ArrayList<Any?>()
            var cursor = initial
            while (true) {
                val value = force.execute(frame, cursor) as? DataValue ?: fault("Original decoder returned a non-list")
                if (value.layout.name == "[]" && value.layout.arity == 0) return output
                if (value.layout.name != ":" || value.layout.arity != 2 || output.size >= limit)
                    fault("Invalid or unbounded original decoder list")
                output += value.layout.read(value, 0)
                cursor = value.layout.read(value, 1)
            }
        }

        override fun execute(frame: VirtualFrame): Any {
            dispatch.execute(frame, frame.arguments[0] as Closure, arrayOf<Any?>(Unit))
            if (fields.size != 1) fault("Original decoder IO result is not one lifted list")
            return elements(frame, FrameAccess.read(frame, fields[0]), 128).map { word ->
                val result = StringBuilder()
                for (char in elements(frame, word, 4096)) {
                    val value = force.execute(frame, char) as? DataValue ?: fault("Original decoder returned a non-Char")
                    if (value.layout.name != "C#" || value.layout.arity != 1)
                        fault("Original decoder returned a non-Char")
                    result.appendCodePoint(value.layout.readLong(value, 0).toInt())
                }
                result.toString()
            }
        }
    }

    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    }

    @Test fun originalDecoderRendersCapturedNamesThroughCompiledAstAndBytecode() {
        val context = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build()
        context.use {
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val snapshot = CaptureRoot(language).callTarget.call() as ManagedStackSnapshot
                assertEquals("capture", snapshot.frames.single().coreIdentity?.occurrence)
                for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) {
                    val input = linked(stage)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, input)
                        else BytecodeProgram(language, input)
                    assertEquals("diagnostic-traps", program.diagnostics()["unsupportedPolicy"])
                    val target = program.entryTarget(input.getValue("entry") as String)
                    val action = ForceActionRoot(language).callTarget.call(
                        Calls.target(target, arrayOf(0L, snapshot))) as? Closure
                        ?: fault("Original decoder did not return an IO action")
                    val shape = (action.target.rootNode as? GuestRoot)?.tupleResult
                        ?: fault("Original decoder IO action has no tuple result")
                    val runner = RunNamesRoot(language, shape).callTarget
                    val expected = runner.call(action) as List<String>
                    assertTrue(expected.any { "capture" in it }, "$stage/$backend: $expected")
                    compile(target); compile(action.target); compile(runner)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(expected, runner.call(action), "$stage/$backend")
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"])
                    assertEquals(true, runner.javaClass.getMethod("isValidLastTier").invoke(runner))
                }
            } finally { context.leave() }
        }
    }
}
