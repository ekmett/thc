// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import thc.runtime.Program
import thc.runtime.BytecodeProgram
import java.security.MessageDigest

class CoreForeignArtifactsTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun linkedCapiNeedsExactArchivedSourceSymbolsAndNoCallbacks() {
        val unit = "base-test-unit"
        val name = "System.CPUTime.Posix.ClockGetTime"
        val symbols = (0..2).map { "exact_generated_wrapper_$it" }
        val source = "/* original C source */\n"
        val bytecode = byteArrayOf(0x42, 0x43)
        val calls = symbols.map { symbol -> mapOf("foreignCall" to mapOf(
            "target" to mapOf("kind" to "static", "isFunction" to true,
                "unit" to unit, "symbol" to symbol), "convention" to "capi", "safety" to "unsafe")) }
        val archive = mapOf("schema" to 1L, "execution" to "not-linked", "stubs" to
            mapOf("header" to "", "source" to source, "initializers" to emptyList<Any>(),
                "finalizers" to emptyList<Any>()), "files" to emptyList<Any>())
        val link = mapOf("schema" to 1L, "format" to "llvm-bitcode", "unit" to unit,
            "module" to name, "target" to if (System.getProperty("os.name").startsWith("Mac"))
                "${if (System.getProperty("os.arch") == "aarch64") "arm64" else "x86_64"}-apple-darwin"
                else "${if (System.getProperty("os.arch") == "amd64") "x86_64" else "aarch64"}-unknown-linux-gnu",
            "symbols" to symbols, "sourceSha256" to sha(source.toByteArray()),
            "bitcodeSha256" to sha(bytecode), "bitcodeHex" to "4243")
        val linked = mapOf("schema" to 2L, "unit" to unit, "module" to name,
            "foreign" to archive, "foreignLink" to link, "bindings" to calls)
        assertEquals(symbols.toSet(), CoreForeignArtifacts.linked(linked)!!.symbols)
        CoreForeignArtifacts.requireExecutable(linked)
        val originalStubs = archive["stubs"] as Map<String, Any?>
        val initializer = mapOf("isInitializer" to true, "unit" to unit,
            "module" to name, "name" to "boot")
        val withInitializer = linked + ("foreign" to
            (archive + ("stubs" to (originalStubs + ("initializers" to listOf(initializer))))))
        for (bad in listOf(
            linked + ("foreignLink" to (link + ("bitcodeSha256" to "0".repeat(64)))),
            linked + ("foreignLink" to (link + ("target" to 17L))),
            linked + ("foreignLink" to (link + ("target" to "riscv64-unknown-linux-gnu"))),
            linked + ("foreignLink" to (link + ("symbols" to symbols.take(2)))),
            linked + ("foreignLink" to (link + ("sourceSha256" to "0".repeat(64)))),
            withInitializer))
            assertThrows(IllegalArgumentException::class.java) { CoreForeignArtifacts.requireExecutable(bad) }
    }
    private val label = mapOf("isInitializer" to false, "unit" to "pkg", "module" to "M", "name" to "exit")
    private val stubs = mapOf("header" to "", "source" to "", "initializers" to emptyList<Any>(),
        "finalizers" to listOf(label))
    private val foreign = mapOf("schema" to 1L, "execution" to "not-linked", "stubs" to stubs,
        "files" to emptyList<Any>())
    private val module = mapOf("schema" to 2L, "ghc" to "9.14.1", "unit" to "pkg", "module" to "M",
        "foreign" to foreign, "bindings" to emptyList<Any>(), "constructors" to emptyList<Any>())

    @Test fun finalizersAndRawObjectContentsAreArchiveOnly() {
        CoreForeignArtifacts.validateArchive(module)
        val file = mapOf("language" to "RawObject", "source" to "\u0000\u00ff\u03bb\n", "extension" to ".o")
        val withFile = module + ("foreign" to (foreign + mapOf("stubs" to null, "files" to listOf(file))))
        val roundTrip = Json.parse(Json.stringify(withFile)) as Map<*, *>
        assertEquals(withFile, roundTrip)
        CoreForeignArtifacts.validateArchive(roundTrip)
        for (value in listOf(module, withFile)) {
            val error = assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(value)) }
            assertTrue(error.message!!.contains("Unsupported foreign code/registration for pkg:M"))
        }
    }

    @Test fun malformedOrDowngradedForeignArtifactsFailClosed() {
        val variants = listOf(
            module + ("schema" to 1L), module - "foreign", module + ("schema" to 2.5),
            module + ("foreign" to (foreign + ("execution" to "linked"))),
            module + ("foreign" to (foreign + ("schema" to 1.5))),
            module + ("foreign" to (foreign + ("unknown" to true))),
            module + ("foreign" to (foreign + ("stubs" to null))),
            module + ("foreign" to (foreign + ("files" to listOf("missing content")))),
            module + ("foreign" to (foreign + ("stubs" to (stubs +
                ("finalizers" to listOf(label + ("isInitializer" to true))))))))
        for (value in variants)
            assertThrows(IllegalArgumentException::class.java) { CoreForeignArtifacts.validateArchive(value) }
    }

    @Test fun ordinarySchemaOneHasNoForeignRegistrationObligations() {
        for (schema in listOf(1, 1L))
            CoreForeignArtifacts.requireExecutable(mapOf("schema" to schema))
        assertThrows(IllegalArgumentException::class.java) {
            CoreForeignArtifacts.validateArchive(mapOf("schema" to 1L, "foreign" to null))
        }
    }

    @Test fun directBackendConstructorsRejectBeforeInitializingBindingsEvenInDiagnosticMode() {
        Context.newBuilder("thc").allowExperimentalOptions(true).build().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (diagnostic in listOf(false, true)) for (async in listOf(false, true)) {
                    // Invalid bindings make initialization order observable: the
                    // foreign guard must run before any binding decoding too.
                    for (data in listOf(module, module + ("bindings" to "must not inspect"))) {
                        val input = data + ("diagnosticUnsupported" to diagnostic)
                        val ast = assertThrows(IllegalArgumentException::class.java) { Program(language, input, async) }
                        val bytecode = assertThrows(IllegalArgumentException::class.java) { BytecodeProgram(language, input, async) }
                        for (failure in listOf(ast, bytecode))
                            assertTrue(failure.message!!.contains("Unsupported foreign code/registration for pkg:M"))
                    }
                }
                val synthetic = mapOf("bindings" to listOf(mapOf("id" to "entry", "name" to "entry",
                    "type" to "Int#", "arity" to 0L, "lifted" to false, "expr" to listOf("lit", "int", "7"))),
                    "constructors" to emptyList<Any>())
                assertEquals(7L, Program(language, synthetic).entryValue("entry"))
                assertEquals(7L, BytecodeProgram(language, synthetic).entryValue("entry"))
            } finally { context.leave() }
        }
    }

    @Test fun reachabilityCannotPruneForeignRegistrationObligations() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CoreModules.reachable(module, "entry")
        }
        assertTrue(failure.message!!.contains("Unsupported foreign code/registration for pkg:M"))
        assertThrows(IllegalArgumentException::class.java) {
            CoreForeignArtifacts.requireExecutableInput(module - "schema")
        }
    }
}
