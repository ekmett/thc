package thc

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotAccess
import thc.runtime.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs actual exported IO, including its result check, with an optional language. */
@Suppress("UNCHECKED_CAST")
fun main(arguments: Array<String>) {
    val directory = File(arguments.firstOrNull() ?: "build/polyglot")
    val stages = listOf("pre-core", "post-core")
    val moduleNames = listOf("THC.Polyglot.json", "THC.PolyglotDemo.json", "THC.InterfaceClosure.json")
    for (stage in stages) for (backend in listOf("ast", "bytecode")) {
        val modules = moduleNames.map { name ->
            val file = File(File(directory, stage), name)
            require(file.isFile) { "Missing $file; export the demo first: scripts/polyglot-demo.sh" }
            Json.parse(file.readText()) as Map<String, Any?>
        }
        val output = ByteArrayOutputStream()
        Context.newBuilder("thc", "js")
            .allowExperimentalOptions(true)
            .allowPolyglotAccess(PolyglotAccess.ALL)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.TraceCompilation", System.getProperty("thc.traceCompilation", "false"))
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .option("engine.CompilationFailureAction", "Throw")
            .out(output).build().use { context ->
                context.initialize("thc")
                context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val entry = "main:THC.PolyglotDemo.main"
                    val linked = CoreModules.reachable(CoreModules.merge(modules), entry) + ("instrument" to true)
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val binding = bindings.single { it["id"] == entry }
                    val result = CoreRepresentations.ioUnitMainResult(binding, bindings)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    val target = IoMainRoot(language, result).callTarget
                    val action = program.entryValue(entry)
                    fun run() {
                        output.reset()
                        Calls.target(target, arrayOf(action))
                        check(output.toString(Charsets.UTF_8) == "THC polyglot result: 42\n") {
                            "Unexpected guest output: $output"
                        }
                        check((program.diagnostics()["unsupportedTraps"] as Number).toLong() == 0L)
                    }
                    repeat(3) { run() }
                    val original = program.entryTarget(entry)
                    val active = NodeUtil.findAllNodeInstances(target.rootNode, DirectCallNode::class.java)
                        .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                        .ifEmpty { listOf(original) }
                    active.distinct().forEach(::compilePolyglotDemo)
                    val before = (program.diagnostics()["compiledEntries"] as Number).toLong()
                    run()
                    check((program.diagnostics()["compiledEntries"] as Number).toLong() > before) {
                        "The demo did not enter compiled Haskell code"
                    }
                    println("$stage / $backend: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)")
                } finally { context.leave() }
            }
    }
}

private fun compilePolyglotDemo(target: RootCallTarget) {
    val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
    check(type.isInstance(target)) { "The demo needs the optimizing Truffle runtime" }
    // GraalJS can load another node subclass during its first compilation,
    // invalidating a class-hierarchy dependency before code installation.
    // Retry explicit compilation, never guest effects, after that bailout.
    repeat(3) {
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        if (type.getMethod("isValidLastTier").invoke(target) == true) return
    }
    error("No compiled demo target was installed: ${target.rootNode.name}")
}
