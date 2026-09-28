// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import thc.Main.withContextProfile

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class UnixLibcTest {
    @TempDir lateinit var temporary: Path
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/unix-libc")
    private val entries = mapOf("unixClose" to "close", "unixDup" to "dup", "unixIsatty" to "isatty", "unixGetenv" to "getenv")
    private fun json(name: String) = Json.parse(File(directory, name).readText()) as Map<String, Any?>
    private fun copy(value: Any?) = Json.parse(Json.stringify(value))
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun inside(context: Context, action: (Language) -> Unit) {
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun install(targets: Collection<RootCallTarget>) {
        val runtime = Truffle.getRuntime()
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        for (target in targets) {
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target)
            runtime.javaClass.getMethod("bypassedInstalledCode", type).invoke(runtime, target)
            valid(target)
        }
    }
    private fun address(text: String) = ManagedAddress.fromByteArray(text.toByteArray() + byteArrayOf(0))
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.results.depth)
        assertEquals(0, state.arguments.retainedReferences()); assertEquals(0, state.results.retainedReferences())
    }
    private fun validate(call: List<Any?>) {
        val metadata = call[6] as Map<*, *>
        val descriptor = metadata["foreignCall"] as Map<*, *>
        val symbol = (descriptor["target"] as Map<*, *>)["symbol"]
        val arguments = (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
        if (symbol == "getenv") assertEquals(EnvironmentOp.GET,
            CoreEnvironmentForeign.validate(metadata, arguments, call[3] as List<*>, metadata["rep"]))
        else assertEquals(symbol,
            CoreOriginalStdio.validate(metadata, arguments, call[3] as List<*>, metadata["rep"])?.symbol)
    }
    private fun fixture() {
        val manifest = json("manifest.json")
        assertEquals("9.14.1", manifest["ghc"])
        val installedUnit = manifest["unixUnit"]
        assertTrue(CoreOriginalStdio.isOriginalUnixUnit(installedUnit))
        assertEquals(entries.keys, (manifest["entries"] as List<String>).toSet())
        for (key in listOf("inputHashes", "artifactHashes")) {
            val hashes = manifest[key] as Map<String, String>
            assertFalse(hashes.isEmpty())
            for ((path, expected) in hashes) {
                val file = if (File(path).isAbsolute) File(path) else File(root, path)
                assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes())), path)
            }
        }
        assertEquals(false, manifest["installedArtifactsHashed"])
        assertEquals(listOf("System/Posix/IO/Common.hi", "System/Posix/Terminal/Common.hi", "System/Posix/Env/PosixString.hi"),
            (manifest["interfaces"] as List<String>).map { "System/" + it.substringAfterLast("/System/") })
        val retained = Json.parse(File(root, "src/test/resources/core/original-unix-libc-descriptors.json").readText()) as Map<*, *>
        for (stage in listOf("pre", "post")) {
            val module = json("$stage.json")
            val calls = OriginalStdioChecks.foreignCalls(module)
            assertEquals(4, calls.size)
            for (call in calls) {
                val actual = (call[6] as Map<*, *>)["foreignCall"] as Map<*, *>
                val target = actual["target"] as Map<*, *>
                assertEquals(installedUnit, target["unit"], "Preserve the original installed owner")
                val expected = retained[target["symbol"]] as Map<*, *>
                assertEquals("unix-2.8.8.0-inplace", (expected["target"] as Map<*, *>)["unit"])
                // Compare all ABI fields; only the recorded installation owner differs.
                assertEquals(expected.filterKeys { it != "target" }, actual.filterKeys { it != "target" })
                assertEquals((expected["target"] as Map<*, *>).filterKeys { it != "unit" }, target.filterKeys { it != "unit" })
            }
            calls.forEach(::validate)
            for (entry in entries.keys) {
                val audit = json("$stage-$entry.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
                assertEquals(emptyList<Any?>(), audit["missingGlobals"])
                val proof = ArrayCoreEvidence(module, entry)
                assertEquals(1, proof.bindings.size)
                assertEquals(1, proof.guestLambdas(proof.root["expr"]).size, "One original typed consumer root")
            }
        }
    }

    @Test fun originalUnixDeclarationsMatchNativeFileLifecycleOnFirstInstalledCalls() {
        fixture()
        val oracle = json("oracle.json")
        val expected = oracle["lifecycle"] as Map<*, *>
        assertEquals(mapOf("dupSucceeded" to true, "closeSource" to 0L, "bytes" to "Z", "count" to 1L,
            "isatty" to 0L, "closeAlias" to 0L, "closeAgain" to -1L), expected)
        val invalid = oracle["invalid"] as List<Map<String, Any?>>
        assertEquals(15, invalid.size)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST).use { context -> inside(context) { language ->
                val module = CoreModules.reachable(json("$stage.json"), entries.keys.filter { it != "unixGetenv" }, true) + ("instrument" to true)
                val p = program(language, module, backend)
                val targets = entries.keys.filter { it != "unixGetenv" }.associateWith(p::entryTarget)
                val state = Language.currentState()
                var compiled = false
                fun call(name: String, input: Long): Long {
                    val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                    if (compiled) targets.values.forEach(::valid)
                    val answer = Calls.target(targets.getValue(name), arrayOf(0L, input)) as Long
                    if (compiled) {
                        assertEquals(before + 1, (p.diagnostics().getValue("compiledEntries") as Number).toLong(), "$stage/$backend/$name first and subsequent entries")
                        targets.values.forEach(::valid)
                    }
                    released(language)
                    return answer
                }
                fun exercise() {
                    val file = Files.createTempFile(temporary, "unix-", ".txt")
                    Files.write(file, byteArrayOf(90))
                    val fd = state.files.open(address(file.toString()), 0)
                    assertTrue(fd >= 3)
                    val alias = call("unixDup", fd)
                    assertEquals(true, alias >= 3 && alias != fd)
                    assertEquals(expected["closeSource"], call("unixClose", fd))
                    val output = ManagedAddress.fromByteArray(ByteArray(1))
                    assertEquals(expected["count"], state.stdio.read(alias, output, 1))
                    assertEquals(90L, output.readWord8(0))
                    assertEquals(expected["isatty"], call("unixIsatty", alias))
                    assertEquals(expected["closeAlias"], call("unixClose", alias))
                    assertEquals(expected["closeAgain"], call("unixClose", alias))
                    Files.delete(file)
                    for (row in invalid) {
                        assertEquals(row["result"], call(row["entry"] as String, row["input"] as Long))
                        assertEquals(row["errno"], state.stdio.errno())
                    }
                }
                exercise()
                install(targets.values)
                compiled = true
                exercise()
            } }
    }

    @Test fun originalUnixGetenvMatchesPresentEmptyAndMissingNativeValues() {
        fixture()
        val rows = json("oracle.json")["environment"] as List<List<String?>>
        assertEquals(listOf(listOf("THC_UNIX_FFI_VALUE", "present-value"), listOf("THC_UNIX_FFI_EMPTY", ""),
            listOf("THC_UNIX_FFI_ABSENT", null)), rows)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode"))
            Context.newBuilder("thc").allowNativeAccess(true).allowEnvironmentAccess(EnvironmentAccess.NONE)
                .environment("THC_UNIX_FFI_VALUE", "present-value").environment("THC_UNIX_FFI_EMPTY", "")
                .let { withContextProfile(it, ContextProfile.SYNCHRONOUS_TEST) }.build().use { context -> inside(context) { language ->
                    val module = CoreModules.reachable(json("$stage.json"), "unixGetenv", true) + ("instrument" to true)
                    val p = program(language, module, backend)
                    val target = p.entryTarget("unixGetenv")
                    var compiled = false
                    fun exercise() {
                        for ((name, expected) in rows) {
                            val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                            val result = Calls.target(target, arrayOf(0L, address(name!!))) as ManagedAddress
                            val actual = if (result === ManagedAddress.nullAddress()) null else
                                ByteArray(result.cStringLength().toInt()) { result.readWord8(it.toLong()).toByte() }.toString(Charsets.UTF_8)
                            assertEquals(expected, actual)
                            if (compiled) {
                                assertEquals(before + 1, (p.diagnostics().getValue("compiledEntries") as Number).toLong())
                                valid(target)
                            }
                            released(language)
                        }
                    }
                    exercise(); install(listOf(target)); compiled = true; exercise()
                } }
    }

    @Test fun exactUnixUnitAbiAndRawStateRemainRequired() {
        fixture()
        val source = json("pre.json")
        for (call in OriginalStdioChecks.foreignCalls(source)) {
            fun reject(action: (MutableList<Any?>, MutableMap<String, Any?>) -> Unit) {
                val changed = copy(call) as MutableList<Any?>
                val descriptor = (changed[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
                action(changed, descriptor)
                assertThrows(RuntimeFault::class.java) { validate(changed) }
                for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> inside(context) { language ->
                    assertThrows(RuntimeFault::class.java) { program(language, OriginalStdioChecks.rawModule(changed, source), backend) }
                } }
            }
            for (unit in listOf("unix", "unix-2.8.8.1-inplace", "unix-2.8.8.0-forged", "unix-2.8.8.0", "unix-2.8.8.0-", "unix-2.8.8.0-460b-extra", "other")) reject { _, d ->
                (d["target"] as MutableMap<String, Any?>)["unit"] = unit }
            for (safety in listOf("safe", "interruptible")) reject { _, d -> d["safety"] = safety }
            reject { _, d -> d["arity"] = 1L }
            reject { _, d -> (d["argumentReps"] as List<MutableMap<String, Any?>>)[0]["primReps"] = listOf("WordRep") }
            reject { _, d -> ((d["resultRep"] as MutableMap<String, Any?>)["components"] as MutableList<Any?>).reverse() }
            for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").build().use { context -> inside(context) { language ->
                val p = program(language, OriginalStdioChecks.rawModule(call, source), backend)
                val symbol = (((call[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"]
                val argument: Any = if (symbol == "getenv") address("THC_UNIX_FFI_ABSENT") else -1L
                assertThrows(RuntimeFault::class.java) { Calls.target(p.entryTarget("entry"), arrayOf(0L, argument, 7L)) }
                released(language)
            } }
        }
    }
}
