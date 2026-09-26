// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import thc.runtime.NativeSignalTransport
import kotlin.system.exitProcess

/** One preference order for the command line, module requests and direct Core requests. */
fun defaultBackend(): String = System.getProperty("thc.backend", System.getenv("THC_BACKEND") ?: "bytecode")

/** Fixed settings only; IO authority is chosen by the context factory. */
internal enum class ContextProfile { NATIVE, SYNCHRONOUS_TEST, LAUNCHER }

// Snapshot before launcher-created guest pins can affect HotSpot's thread-local
// availableProcessors query. Match pinned Graal's default (CompilerThreads=0)
// policy, while preserving explicit system-property settings including 0/-1.
private val launcherCompilerThreads = if (Runtime.getRuntime().availableProcessors() >= 4) "2" else "1"

internal fun Context.Builder.withContextProfile(profile: ContextProfile): Context.Builder {
    allowCreateThread(true).useSystemExit(false)
    if (profile == ContextProfile.NATIVE) return this
    allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
    if (profile == ContextProfile.SYNCHRONOUS_TEST) return this
    return allowEnvironmentAccess(EnvironmentAccess.INHERIT)
        .option("engine.CompilerThreads", System.getProperty("polyglot.engine.CompilerThreads") ?: launcherCompilerThreads)
        .option("engine.TraceCompilation", System.getProperty("thc.traceCompilation", "false"))
        .option("engine.SingleTierCompilationThreshold", "10000")
        .option("compiler.CompilationTimeout", "30")
        .option("compiler.MaximumGraalGraphSize", "100000")
}

/**
 * Create the experimental launcher's owning polyglot context.
 *
 * Close it after all guest [Value]s are no longer needed (for example with `use`).
 * Values, heap state and native resources must not outlive or cross contexts.
 * Native access and guest threads are enabled; this is not an untrusted-code sandbox.
 *
 * @param fileIO request host file IO and, on supported hosts, the native command-line provider.
 * The fallback still grants full polyglot file IO when this is true.
 * FFI selection follows `thc.ffiMode`, then `THC_FFI_MODE`, then native.
 * A managed request fails before context/native-provider initialization.
 */
fun executionContext(fileIO: Boolean = false): Context = executionContext(fileIO, FfiMode.configured())

internal fun executionContext(fileIO: Boolean, ffiMode: FfiMode): Context {
    if (fileIO && NativeIO.supportedHost()) return NativeIO.commandLineContext(ffiMode)
    return Context.newBuilder("thc").allowNativeAccess(true)
        .allowIO(if (fileIO) IOAccess.ALL else IOAccess.NONE)
        .withContextProfile(ContextProfile.LAUNCHER).withFfiMode(ffiMode).build()
}

/**
 * Load an accepted entry into [context], returning a value owned by that context.
 *
 * Scalar entries use the current integer-only `execute` contract. An [ioMain]
 * entry instead exposes `runIO`; invoking that member executes the accepted IO action.
 * Loading does not imply support for arbitrary Haskell types or foreign artifacts.
 *
 * @param modules individual Core JSON paths, or one `@/absolute/path/packages.json`
 * for a checked package closure with strict linking.
 * @param entry exact binder ID or unambiguous exported name.
 * @param instrument enable runtime development metrics.
 * @param backend `bytecode` or `ast`, selected when loading this entry.
 * @param ioMain select the `IO ()` entry contract and disable diagnostic unsupported traps.
 * @param shutdownEntry distinct accepted IO shutdown entry for full executable lifecycle;
 * only valid with [ioMain].
 */
@JvmOverloads
fun loadEntry(context: Context, modules: List<String>, entry: String, instrument: Boolean = true,
              backend: String = defaultBackend(), ioMain: Boolean = false, shutdownEntry: String? = null): Value =
    context.eval("thc", CoreModules.request(modules, entry, instrument,
        !ioMain && java.lang.Boolean.getBoolean("thc.diagnosticUnsupported"), backend,
        System.getProperty("thc.sourceNotesEnabled", "true").toBooleanStrict(), ioMain, shutdownEntry))

/**
 * Load one verified managed export bundle into [context]. The result has read-only
 * unit → module → declared C-symbol members. Executing a symbol marshals its boxed
 * scalar signature and runs its pure function or IO action in this context.
 * This does not install native C entrypoints. Additional loads in the same context
 * are rejected; aliases in this bundle share their program and CAFs.
 */
@JvmOverloads
fun loadManagedExports(context: Context, modules: List<String>, backend: String = defaultBackend(),
                       instrument: Boolean = true): Value =
    context.eval("thc", CoreModules.managedExportRequest(modules, backend, instrument))

/** JVM launcher implementation; ordinary package users should invoke the Haskell `thc run` driver. */
fun main(args: Array<String>) {
    try { launch(args) }
    catch (failure: FfiConfigurationException) {
        System.err.println("thc: ${failure.message}")
        exitProcess(2)
    }
    catch (exit: PolyglotException) {
        if (!exit.isExit) throw exit
        // All owning context.use scopes have closed before terminating the CLI.
        // Embedded loadEntry/runIO callers only receive the polyglot exit.
        if (exit.exitStatus < 0) NativeSignalTransport.exitBySignal(-exit.exitStatus)
        exitProcess(exit.exitStatus)
    }
}

/** The driver protocol has a fixed prefix followed by opaque guest arguments. */
internal fun launcherArguments(args: Array<String>, prefix: Int): Pair<String, Array<String>> {
    if (args.size == prefix) return "thc" to emptyArray()
    require(args.size >= prefix + 2 && args[prefix] == "--") {
        "Expected -- PROGRAM_NAME [ARG...] after executable arguments"
    }
    return args[prefix + 1] to args.copyOfRange(prefix + 2, args.size)
}

private fun initializeArguments(context: Context, arguments: Pair<String, Array<String>>) {
    context.initialize("thc")
    context.enter()
    try { Language.currentState().arguments.initialize(arguments.first, arguments.second) }
    finally { context.leave() }
}

internal fun launch(rawArgs: Array<String>) {
    var prefix = 0
    var selected: FfiMode? = null
    while (prefix < rawArgs.size) {
        val option = rawArgs[prefix]
        if (option == "--ffi") {
            if (++prefix == rawArgs.size) throw FfiConfigurationException("--ffi requires native or managed")
            selected = FfiMode.parse(rawArgs[prefix++], "--ffi")
        } else if (option.startsWith("--ffi=")) {
            selected = FfiMode.parse(option.substringAfter('='), "--ffi")
            prefix++
        } else break
    }
    val ffiMode = selected ?: FfiMode.configured()
    val args = rawArgs.copyOfRange(prefix, rawArgs.size)
    if (args.firstOrNull() == "--run-executable") {
        require(args.size >= 4) {
            "Usage: thc --run-executable MODULE.json[,MODULE.json...] ENTRY SHUTDOWN_ENTRY [-- PROGRAM_NAME ARG...]"
        }
        val arguments = launcherArguments(args, 4)
        executionContext(fileIO = true, ffiMode = ffiMode).use { context ->
            initializeArguments(context, arguments)
            val action = loadEntry(context, args[1].split(','), args[2], ioMain = true, shutdownEntry = args[3])
            check(action.invokeMember("runIO").asBoolean()) { "Executable IO did not complete" }
            if (java.lang.Boolean.getBoolean("thc.diagnostics"))
                System.err.println(action.getMember("diagnostics").asString())
        }
        return
    }
    if (args.firstOrNull() == "--run-io") {
        require(args.size >= 3) { "Usage: thc --run-io MODULE.json[,MODULE.json...] ENTRY [-- PROGRAM_NAME ARG...]" }
        val arguments = launcherArguments(args, 3)
        val modules = args[1].split(',')
        executionContext(fileIO = true, ffiMode = ffiMode).use { context ->
            initializeArguments(context, arguments)
            val action = loadEntry(context, modules, args[2], ioMain = true)
            check(action.invokeMember("runIO").asBoolean()) { "IO main did not complete" }
            if (java.lang.Boolean.getBoolean("thc.diagnostics"))
                System.err.println(action.getMember("diagnostics").asString())
        }
        return
    }
    require(args.size >= 3) { "Usage: thc MODULE.json[,MODULE.json...] ENTRY INTEGER [--compile]" }
    val modules = args[0].split(',')
    val entry = args[1]
    val input = args[2].toLong()
    executionContext(fileIO = false, ffiMode = ffiMode).use { context ->
        val function = loadEntry(context, modules, entry)
        if (args.drop(3).contains("--compile")) {
            repeat(40) { function.execute(input + (it and 3)).asLong() }
            function.invokeMember("compile")
        }
        println(function.execute(input).asLong())
        System.err.println(function.getMember("diagnostics").asString())
    }
}
