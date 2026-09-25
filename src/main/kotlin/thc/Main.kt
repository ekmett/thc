// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess

/** One preference order for the command line, module requests and direct Core requests. */
fun defaultBackend(): String = System.getProperty("thc.backend", System.getenv("THC_BACKEND") ?: "bytecode")

/** Fixed settings only; IO authority is chosen by the context factory. */
internal enum class ContextProfile { NATIVE, SYNCHRONOUS_TEST, LAUNCHER }

internal fun Context.Builder.withContextProfile(profile: ContextProfile): Context.Builder {
    allowCreateThread(true)
    if (profile == ContextProfile.NATIVE) return this
    allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
    if (profile == ContextProfile.SYNCHRONOUS_TEST) return this
    return option("engine.TraceCompilation", System.getProperty("thc.traceCompilation", "false"))
        .option("engine.SingleTierCompilationThreshold", "10000")
        .option("compiler.CompilationTimeout", "30")
        .option("compiler.MaximumGraalGraphSize", "100000")
}

fun executionContext(fileIO: Boolean = false): Context {
    if (fileIO && NativeIO.supportedHost()) return NativeIO.commandLineContext()
    return Context.newBuilder("thc").allowNativeAccess(true)
        .allowIO(if (fileIO) IOAccess.ALL else IOAccess.NONE)
        .withContextProfile(ContextProfile.LAUNCHER).build()
}

@JvmOverloads
fun loadEntry(context: Context, modules: List<String>, entry: String, instrument: Boolean = true,
              backend: String = defaultBackend(), ioMain: Boolean = false, shutdownEntry: String? = null): Value =
    context.eval("thc", CoreModules.request(modules, entry, instrument,
        !ioMain && java.lang.Boolean.getBoolean("thc.diagnosticUnsupported"), backend,
        System.getProperty("thc.sourceNotesEnabled", "true").toBooleanStrict(), ioMain, shutdownEntry))

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--run-executable") {
        require(args.size == 4) {
            "Usage: thc --run-executable MODULE.json[,MODULE.json...] ENTRY SHUTDOWN_ENTRY"
        }
        executionContext(fileIO = true).use { context ->
            val action = loadEntry(context, args[1].split(','), args[2], ioMain = true, shutdownEntry = args[3])
            check(action.invokeMember("runIO").asBoolean()) { "Executable IO did not complete" }
            System.err.println(action.getMember("diagnostics").asString())
        }
        return
    }
    if (args.firstOrNull() == "--run-io") {
        require(args.size == 3) { "Usage: thc --run-io MODULE.json[,MODULE.json...] ENTRY" }
        val modules = args[1].split(',')
        executionContext(fileIO = true).use { context ->
            val action = loadEntry(context, modules, args[2], ioMain = true)
            check(action.invokeMember("runIO").asBoolean()) { "IO main did not complete" }
            System.err.println(action.getMember("diagnostics").asString())
        }
        return
    }
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
