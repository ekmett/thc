package thc

import org.graalvm.polyglot.Context

/** Profile the whole family before requesting its one explicit compilation. */
fun primopTestContext(): Context = Context.newBuilder("thc")
    .allowExperimentalOptions(true)
    .option("engine.BackgroundCompilation", "false")
    .option("engine.TraceCompilation", System.getProperty("thc.traceCompilation", "false"))
    .option("engine.MultiTier", "false")
    .option("engine.SingleTierCompilationThreshold", "10000000")
    .option("engine.CompilationFailureAction", "Throw")
    .option("compiler.CompilationTimeout", "30")
    .option("compiler.MaximumGraalGraphSize", "100000")
    .build()
