package thc

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value

/** One preference order for the command line, module requests and direct Core requests. */
fun defaultBackend(): String = System.getProperty("thc.backend", System.getenv("THC_BACKEND") ?: "bytecode")

fun executionContext(): Context = Context.newBuilder("thc").allowNativeAccess(true)
    .allowExperimentalOptions(true)
    .option("engine.BackgroundCompilation", "false")
    .option("engine.TraceCompilation", System.getProperty("thc.traceCompilation", "false"))
    .option("engine.MultiTier", "false")
    .option("engine.SingleTierCompilationThreshold", "10000")
    .option("engine.CompilationFailureAction", "Throw")
    .option("compiler.CompilationTimeout", "30")
    .option("compiler.MaximumGraalGraphSize", "100000")
    .build()

@JvmOverloads
fun loadEntry(context: Context, modules: List<String>, entry: String, instrument: Boolean = true,
              backend: String = defaultBackend()): Value =
    context.eval("thc", CoreModules.request(modules, entry, instrument, java.lang.Boolean.getBoolean("thc.diagnosticUnsupported"), backend,
        System.getProperty("thc.sourceNotesEnabled", "true").toBooleanStrict()))

fun main(args: Array<String>) {
    require(args.size >= 3) { "Usage: thc MODULE.json[,MODULE.json...] ENTRY INTEGER [--compile]" }
    val modules = args[0].split(',')
    val entry = args[1]
    val input = args[2].toLong()
    executionContext().use { context ->
        val function = loadEntry(context, modules, entry)
        if (args.drop(3).contains("--compile")) {
            repeat(40) { function.execute(input + (it and 3)).asLong() }
            function.invokeMember("compile")
        }
        println(function.execute(input).asLong())
        System.err.println(function.getMember("diagnostics").asString())
    }
}
