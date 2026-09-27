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
 * @param modules individual Core JSON paths, optionally accompanied by one
 * `@/absolute/path/packages.json` for checked dependency bundles and strict linking.
 * @param entry exact binder ID or unambiguous exported name.
 * @param instrument enable runtime development metrics.
 * @param backend `bytecode` or `ast`, selected when loading this entry.
 * @param ioMain select the `IO ()` entry contract and disable diagnostic unsupported traps.
 * @param shutdownEntry distinct accepted IO shutdown entry for full executable lifecycle;
 * only valid with [ioMain].
 * @param asyncExceptions explicit asynchronous mode, or the optional strict Boolean
 * `thc.asyncExceptions` launcher property. When absent, AST defaults to false and bytecode to true.
 * @param jsonSidecars explicit index paths for every listed loose Core JSON input;
 * an optional package manifest keeps its own module/index inventory.
 * @param verifyArtifacts opt into complete artifact hashes and source/index verification.
 * Normal loading trusts supplied artifacts and retains local format and ABI checks.
 */
@JvmOverloads
fun loadEntry(context: Context, modules: List<String>, entry: String, instrument: Boolean = true,
              backend: String = defaultBackend(), ioMain: Boolean = false, shutdownEntry: String? = null,
              asyncExceptions: Boolean? = System.getProperty("thc.asyncExceptions")?.toBooleanStrict(),
              jsonSidecars: Map<String, String>? = null, verifyArtifacts: Boolean = false): Value =
    context.eval("thc", CoreModules.request(modules, entry, instrument,
        !ioMain && java.lang.Boolean.getBoolean("thc.diagnosticUnsupported"), backend,
        System.getProperty("thc.sourceNotesEnabled", "true").toBooleanStrict(), ioMain, shutdownEntry, asyncExceptions, jsonSidecars, verifyArtifacts))

/**
 * Load one managed export bundle into [context]. The result has read-only
 * unit → module → declared C-symbol members. Executing a symbol marshals its boxed
 * scalar signature and runs its pure function or IO action in this context.
 * This does not install native C entrypoints. Additional loads in the same context
 * are rejected; aliases share program/CAFs. [verifyArtifacts] opts into file checks.
 */
@JvmOverloads
fun loadManagedExports(context: Context, modules: List<String>, backend: String = defaultBackend(),
                       instrument: Boolean = true, verifyArtifacts: Boolean = false): Value =
    context.eval("thc", CoreModules.managedExportRequest(modules, backend, instrument, verifyArtifacts))

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

/** Extract only explicit host pairs; anything after the guest separator is opaque. */
internal fun launcherJsonSidecars(rawArgs: Array<String>): Pair<Array<String>, Map<String, String>?> {
    val arguments = ArrayList<String>()
    val sidecars = linkedMapOf<String, String>()
    var position = 0
    while (position < rawArgs.size) {
        val argument = rawArgs[position]
        if (argument == "--") {
            arguments.addAll(rawArgs.asList().subList(position, rawArgs.size))
            break
        }
        if (argument == "--json-sidecar") {
            require(position + 2 < rawArgs.size && rawArgs[position + 1] != "--" && rawArgs[position + 2] != "--") {
                "--json-sidecar requires JSON_PATH INDEX_PATH before guest arguments"
            }
            val json = rawArgs[position + 1]
            val index = rawArgs[position + 2]
            require(json.isNotBlank() && index.isNotBlank() && !json.startsWith("@") && sidecars.putIfAbsent(json, index) == null) {
                "Invalid or duplicate --json-sidecar source: $json"
            }
            position += 3
        } else {
            arguments += argument
            position++
        }
    }
    return arguments.toTypedArray() to sidecars.takeIf { it.isNotEmpty() }
}

internal fun launcherArtifactVerification(arguments: Array<String>): Pair<Array<String>, Boolean> {
    val selected = ArrayList<String>()
    var verify = false
    var guest = false
    for (argument in arguments) {
        if (argument == "--") guest = true
        if (!guest && argument == "--verify-artifacts") {
            require(!verify) { "Duplicate --verify-artifacts" }
            verify = true
        } else selected += argument
    }
    return selected.toTypedArray() to verify
}

internal fun launch(arguments: Array<String>) {
    val (withVerification, sidecars) = launcherJsonSidecars(arguments)
    val (rawArgs, verifyArtifacts) = launcherArtifactVerification(withVerification)
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
            val action = loadEntry(context, args[1].split(','), args[2], ioMain = true, shutdownEntry = args[3], jsonSidecars = sidecars,
                verifyArtifacts = verifyArtifacts,
                asyncExceptions = System.getProperty("thc.asyncExceptions")?.toBooleanStrict() ?: true)
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
            val action = loadEntry(context, modules, args[2], ioMain = true, jsonSidecars = sidecars, verifyArtifacts = verifyArtifacts)
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
        val function = loadEntry(context, modules, entry, jsonSidecars = sidecars, verifyArtifacts = verifyArtifacts)
        fun installed(diagnostics: Map<*, *>) {
            val observation = diagnostics["explicitCompilation"] as Map<*, *>
            check((observation["targetCount"] as Number).toInt() > 0 &&
                observation["sameTargets"] == true && observation["validLastTier"] == true) {
                "Explicitly installed guest targets changed or became invalid"
            }
        }
        var before: Long? = null
        if (args.drop(3).contains("--compile")) {
            // Existing profile training precedes installation; never settle or retry
            // the first public call after the explicit compile request.
            repeat(40) { function.execute(input + (it and 3)).asLong() }
            function.invokeMember("compile")
            val diagnostics = Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>
            installed(diagnostics)
            before = (diagnostics["compiledEntries"] as Number).toLong()
        }
        val result = function.execute(input).asLong()
        var diagnostics = function.getMember("diagnostics").asString()
        if (before != null) {
            @Suppress("UNCHECKED_CAST")
            val after = Json.parse(diagnostics) as Map<String, Any?>
            val entries = (after["compiledEntries"] as Number).toLong()
            check(entries > before) {
                "First post-install call did not enter compiled guest code"
            }
            installed(after)
            diagnostics = Json.stringify(after + ("firstInstalledCall" to mapOf(
                "compiledEntriesBefore" to before, "compiledEntriesAfter" to entries)))
        }
        println(result)
        System.err.println(diagnostics)
    }
}
