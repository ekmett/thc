// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class UnixWaitStatusTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/unix-wait-status")
    private val operations = OriginalStdioOp.entries.filter { it.waitStatus }
    private fun json(name: String) = Json.parse(File(directory, name).readText()) as Map<String, Any?>
    private data class Row(val name: String, val input: Long, val result: Long)
    private fun rows(): List<Row> = File(directory, "oracle.tsv").readLines().map {
        val fields = it.split('\t')
        assertEquals(3, fields.size)
        Row(fields[0], fields[1].toLong(), fields[2].toLong())
    }.also {
        assertEquals(280, it.size)
        assertEquals(7, it.groupBy { row -> row.name }.size)
        for (corpus in it.groupBy { row -> row.name }.values) assertEquals(40, corpus.size)
    }
    private fun context(inlining: Boolean = true, native: Boolean = true) = Context.newBuilder("thc")
        .allowNativeAccess(native).withContextProfile(ContextProfile.SYNCHRONOUS_TEST)
        .option("compiler.Inlining", inlining.toString()).build()
    private fun program(language: Language, source: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
    private fun compiled(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun originalCallsMatchNativeWithInlining() = native(true)
    @Test fun originalCallsMatchNativeAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        originalDeclarationsAndOracleStayExact()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((name, corpus) in rows().groupBy { it.name }) {
                    val source = CoreModules.reachable(json("$stage.json"), name) + ("instrument" to true)
                    val bindings = source["bindings"] as List<Map<String, Any?>>
                    assertEquals(1, bindings.size)
                    assertEquals(1, OriginalStdioChecks.nodes(bindings.single()["expr"]).count { it.firstOrNull() == "lam" })
                    val program = program(language, source, backend)
                    val entry = program.entryValue(name)
                    val host = program.hostEntryTarget(1)
                    val targets = (source["bindings"] as List<Map<String, Any?>>).map { program.entryTarget(it["id"] as String) }
                    fun call(row: Row) = assertEquals(row.result,
                        Calls.target(host, arrayOf(entry, arrayOf(row.input))),
                        "$stage/$backend/inlining=$inlining/$name/${row.input}")
                    corpus.forEach(::call)
                    for (target in targets.asReversed()) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        compiled(target)
                    }
                    for (row in corpus.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        call(row)
                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                            "first and subsequent installed calls: $stage/$backend/$name")
                        targets.forEach(::compiled)
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                }
            } finally { context.leave() }
        }
    }

    @Test fun originalDeclarationsAndOracleStayExact() {
        val manifest = json("manifest.json")
        assertEquals("9.14.1", manifest["ghc"])
        assertTrue(isOriginalUnixUnit(manifest["unixUnit"]))
        assertEquals(280L, manifest["nativeRows"]); assertEquals(true, manifest["strictAccepted"])
        val owner = manifest["unixUnit"] as String
        assertEquals(operations.map { "wait${it.name}" }.toSet(), (manifest["entries"] as List<*>).toSet())
        assertEquals(setOf("compiler/test-fixtures/UnixWaitStatusAudit.hs", "test/haskell-fixtures/UnixWaitStatusFixtures.hs",
            "compiler/THC/Plugin.hs", "test/haskell-fixtures/FixtureSupport.hs", "scripts/core_original_foreign.py",
            "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/c/wait-status-api.c", "scripts/build-cbits.py"),
            (manifest["inputHashes"] as Map<*, *>).keys)
        for (key in listOf("inputHashes", "artifactHashes", "interfaceHashes")) {
            val hashes = manifest[key] as Map<String, String>
            assertFalse(hashes.isEmpty())
            for ((path, hash) in hashes) assertEquals(hash,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (if (File(path).isAbsolute) File(path) else File(root, path)).readBytes())), path)
        }
        for (stage in listOf("pre", "post")) {
            val calls = foreignApps(json("$stage.json"))
            assertEquals(operations.map { it.symbol.replace("unixzm2zi8zi8zi0zminplace", owner.replace("-", "zm").replace(".", "zi")) }.toSet(), calls.map {
                (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] }.toSet())
            assertTrue(calls.all { (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"] == owner })
            for (operation in operations) {
                val audit = json("$stage-wait${operation.name}.audit.json")
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertEquals(emptyList<Any>(), audit["issues"])
            }
        }
        val corpus = rows().associate { (it.name to it.input) to it.result }
        // Actual C macro values: WCOREDUMP is the raw 0x80 mask, not 1.
        assertEquals(128L, corpus["waitWCOREDUMP" to 137L])
        assertEquals(9L, corpus["waitWTERMSIG" to 137L])
        assertEquals(255L, corpus["waitWEXITSTATUS" to 0xff00L])
        assertEquals(127L, corpus["waitWSTOPSIG" to 0x7f7fL])
        assertEquals(1L, corpus["waitWIFSTOPPED" to 0x7f7fL])
        assertEquals(0L, corpus["waitWIFSIGNALED" to 0xffffL])
        assertEquals(corpus["waitWIFEXITED" to 0L], corpus["waitWIFEXITED" to 4294967296L])
    }
    private fun foreignApps(value: Any?): List<MutableList<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::foreignApps)
        is List<*> -> (if (value.firstOrNull() == "app" && (value.getOrNull(6) as? Map<*, *>)?.containsKey("foreignCall") == true)
            listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::foreignApps)
        else -> emptyList()
    }

    @Test fun exactOriginalUnitConventionStateAndWidthAreRequired() {
        for (backend in listOf("ast", "bytecode")) for (variant in listOf("unit", "safety", "convention", "arity", "width", "result", "head", "flags"))
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source = CoreModules.reachable(json("post.json"), "waitWCOREDUMP")
                    val app = foreignApps(source).single()
                    val call = (app[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
                    when (variant) {
                        "unit" -> (call["target"] as MutableMap<String, Any?>)["unit"] = "ghc-internal"
                        "safety" -> call["safety"] = "safe"
                        "convention" -> call["convention"] = "ccall"
                        "arity" -> call["suppliedArity"] = 1L
                        "width" -> ((call["argumentReps"] as MutableList<Any?>)[0] as MutableMap<String, Any?>)["primReps"] = listOf("IntRep")
                        "result" -> (call["resultRep"] as MutableMap<String, Any?>)["primReps"] = listOf("Word32Rep")
                        "head" -> ((app[1] as MutableList<Any?>)[2] as MutableMap<String, Any?>)["rep"] = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
                        "flags" -> (app[3] as MutableList<Any?>)[0] = true
                    }
                    assertThrows(RuntimeFault::class.java, { program(language, source, backend) }, "$backend/$variant")
                } finally { context.leave() }
            }
    }

    @Test fun installedSymbolsKeepExactOwnerIndexModuleAndFunction() {
        for (operation in operations) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                fun module(owner: String, symbol: String): Map<String, Any?> {
                    val source = CoreModules.reachable(json("post.json"), "wait${operation.name}")
                    val target = ((foreignApps(source).single()[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>
                    target["unit"] = owner; target["symbol"] = symbol
                    return source
                }
                fun symbol(owner: String) = operation.symbol.replace("unixzm2zi8zi8zi0zminplace", owner.replace("-", "zm").replace(".", "zi"))
                for (owner in listOf("unix-2.8.8.0-inplace", "unix-2.8.8.0-460b", "unix-2.8.8.0-deadbeef"))
                    program(language, module(owner, symbol(owner)), backend)
                val owner = "unix-2.8.8.0-460b"
                for ((badOwner, badSymbol) in listOf(
                    "unix-2.8.8.0-inplace" to symbol(owner), owner to operation.symbol,
                    "unix-2.8.8.0-ABCD" to symbol("unix-2.8.8.0-ABCD"),
                    "unix-2.8.8.0-nothex" to symbol("unix-2.8.8.0-nothex"),
                    "unix-2.8.7.0-460b" to symbol("unix-2.8.7.0-460b"),
                    owner to symbol(owner).replace("ghczuwrapperZC${operations.indexOf(operation)}ZC", "ghczuwrapperZC7ZC"),
                    owner to symbol(owner).replace("ProcessziInternals", "ProcessziByteString"),
                    owner to (symbol(owner) + "Extra")))
                    assertThrows(RuntimeFault::class.java) { program(language, module(badOwner, badSymbol), backend) }
            } finally { context.leave() }
        }
    }

    @Test fun storedOperandsAndStateCarrierRemainCheckedBeforeNativeAccess() {
        for (backend in listOf("ast", "bytecode")) context(native = false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) {
                    val source = CoreModules.reachable(json("post.json"), "wait${operation.name}")
                    val call = foreignApps(source).single()
                    for (index in 0..1) assertThrows(RuntimeFault::class.java) {
                        program(language, OriginalStdioChecks.rawModule(call, source, index), backend)
                    }
                    val raw = OriginalStdioChecks.rawModule(call, source)
                    val target = program(language, raw, backend).entryTarget("entry")
                    val failure = assertThrows(RuntimeFault::class.java) { Calls.target(target, arrayOf(0L, 137L, 9L)) }
                    assertTrue(failure.message.orEmpty().contains("zero-width scalar carrier"), failure.message)
                    val shadowed = OriginalStdioChecks.rawModule(call, source)
                    val app = foreignApps(shadowed).single()
                    app[1] = listOf("var", "p0", (app[1] as List<*>)[2])
                    assertThrows(RuntimeFault::class.java) { program(language, shadowed, backend) }
                }
                assertEquals(0, language.handoffState.get().arguments.depth)
                assertEquals(0, language.handoffState.get().results.depth)
            } finally { context.leave() }
        }
    }

    @Test fun statusTransportUsesCurrentNativeCapability() {
        context(native = false).use { context ->
            context.initialize("thc"); context.enter()
            try { assertThrows(RuntimeFault::class.java) { CoreOriginalStdio.waitStatus(null, OriginalStdioOp.WCOREDUMP, 137) } }
            finally { context.leave() }
        }
        repeat(2) {
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    assertEquals(128L, CoreOriginalStdio.waitStatus(null, OriginalStdioOp.WCOREDUMP, 137))
                    assertThrows(RuntimeFault::class.java) { CoreOriginalStdio.waitStatus(null, OriginalStdioOp.F_GETFL, 0) }
                } finally { context.leave() }
            }
        }
    }
}
