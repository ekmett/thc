// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest

class CoreLinkerTest {
    @TempDir lateinit var temporary: Path
    private fun binding(id: String, expression: List<Any?>): Map<String, Any?> =
        mapOf("id" to id, "name" to id, "expr" to expression)
    private fun variable(id: String): List<Any?> = listOf("var", id)
    private fun literal(): List<Any?> = listOf("lit", "int", "0")
    @Suppress("UNCHECKED_CAST")
    private fun selected(expression: List<Any?>, vararg globals: Map<String, Any?>): List<String> {
        val module = mapOf("bindings" to listOf(binding("root", expression), *globals))
        return (CoreModules.reachable(module, "root")["bindings"] as List<Map<String, Any?>>).map { it["id"] as String }
    }

    @Test fun linkedModulesPreserveSourceIdentityAndRejectConflictingText() {
        val file = mapOf("id" to "shared", "path" to "Shared.hs", "content" to "entry = 0\n")
        val span = mapOf("id" to "entry-span", "file" to "shared", "startLine" to 1,
            "startColumn" to 1, "endLine" to 1, "endColumn" to 10, "charIndex" to 0, "charLength" to 9)
        fun module(id: String, source: Map<String, Any?> = file) = mapOf("schema" to 1,
            "ghc" to "9.14.1", "bindings" to listOf(binding(id, literal())),
            "constructors" to emptyList<Any?>(), "sourceFiles" to listOf(source), "sourceSpans" to listOf(span))
        val linked = CoreModules.reachable(CoreModules.merge(listOf(module("entry"), module("unused"))), "entry")
        assertEquals(listOf(file), linked["sourceFiles"])
        assertEquals(listOf(span), linked["sourceSpans"])
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(module("entry"), module("other", file + ("content" to "different source"))))
        }
    }

    @Test fun sameRelativeSourcePathCanBelongToDistinctGhcUnits() {
        fun module(unit: String) = mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to unit,
            "module" to "Shared", "bindings" to listOf(binding("$unit:Shared.entry", literal())),
            "constructors" to emptyList<Any?>(),
            "sourceFiles" to listOf(mapOf("id" to "($unit,src/Shared.hs)",
                "path" to "src/Shared.hs", "content" to "$unit source")))
        val linked = CoreModules.merge(listOf(module("pkg-a"), module("pkg-b")))
        val files = linked["sourceFiles"] as List<*>
        assertEquals(2, files.size)
        assertEquals(2, files.map { (it as Map<*, *>)["id"] }.toSet().size)
    }

    @Test fun separateInterfaceClosureFragmentsMergeWithoutAdmittingDuplicateDefinitions() {
        fun fragment(id: String) = mapOf("schema" to 1, "ghc" to "9.14.1",
            "unit" to "dependency-closure", "module" to "THC.InterfaceClosure",
            "boundary" to "actual-interface-unfoldings",
            "bindings" to listOf(binding(id, literal())), "constructors" to emptyList<Any?>())
        assertEquals(2, (CoreModules.merge(listOf(fragment("a"), fragment("b")))["bindings"] as List<*>).size)
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(fragment("a"), fragment("a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.merge(listOf(fragment("a"), fragment("b")).map {
                it + ("boundary" to "optimized-Core-after-Tidy-before-CorePrep")
            })
        }
    }

    @Test fun shadowedGlobalsDoNotBringTheirUnsupportedDependenciesIntoTheProgram() {
        val lambda = listOf("lam", listOf(mapOf("id" to "x")), variable("x"))
        assertEquals(listOf("root"), selected(lambda, binding("x", variable("unavailable"))))
        val recursive = listOf("let", true,
            listOf(binding("x", variable("y")), binding("y", variable("x"))), variable("x"))
        assertEquals(listOf("root"), selected(recursive, binding("x", variable("unavailable")), binding("y", literal())))
    }

    @Test fun nonrecursiveRhsAndCaseScrutineeRetainTheirOuterDependencies() {
        val nonrecursive = listOf("let", false, listOf(binding("x", variable("x"))), variable("x"))
        assertEquals(listOf("root", "x"), selected(nonrecursive, binding("x", literal())))
        val case = listOf("case", variable("x"), "x", listOf(listOf("default", null, emptyList<String>(), variable("x"))))
        assertEquals(listOf("root", "x"), selected(case, binding("x", literal())))
    }

    @Test fun recursiveGlobalClosureTerminatesAndKeepsAllTransitiveDependencies() {
        assertEquals(listOf("root", "x", "y"), selected(variable("x"),
            binding("x", variable("y")), binding("y", variable("root"))))
    }

    @Test fun strictPackageLinkRejectsMissingGlobalAndConstructorBeforeExecution() {
        val missingGlobal = mapOf("bindings" to listOf(binding("dep:App.entry", variable("dep:Lib.missing"))),
            "constructors" to emptyList<Any?>())
        val globalError = assertThrows(IllegalArgumentException::class.java) {
            CoreModules.reachable(missingGlobal, "dep:App.entry", strictLink = true)
        }
        assertTrue(globalError.message!!.contains("dep:Lib.missing"))
        val missingConstructor = mapOf("bindings" to listOf(binding("dep:App.entry", listOf("con", "dep:Lib.C", 0))),
            "constructors" to emptyList<Any?>())
        val constructorError = assertThrows(IllegalArgumentException::class.java) {
            CoreModules.reachable(missingConstructor, "dep:App.entry", strictLink = true)
        }
        assertTrue(constructorError.message!!.contains("dep:Lib.C"))
    }

    @Test fun packageManifestLinksSameModuleNameInDistinctUnitsAndRejectsTampering() {
        val boundary = "optimized-Core-after-Tidy-before-CorePrep"
        fun source(unit: String) = mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to unit,
            "module" to "Shared", "boundary" to boundary,
            "bindings" to listOf(binding("$unit:Shared.entry", literal())), "constructors" to emptyList<Any?>())
        fun record(unit: String): Map<String, Any> {
            val path = temporary.resolve("$unit.json")
            val bytes = Json.stringify(source(unit)).toByteArray()
            Files.write(path, bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return mapOf("id" to unit, "depends" to emptyList<String>(), "modules" to
                listOf(mapOf("name" to "Shared", "boundary" to boundary, "path" to path.fileName.toString(), "sha256" to hash)))
        }
        val units = listOf(record("pkg-a"), record("pkg-b"))
        val manifest = temporary.resolve("packages.json")
        fun write(units: List<Map<String, Any>>) = Files.writeString(manifest, Json.stringify(mapOf(
            "format" to "thc-core-packages", "schema" to 1, "ghc" to "9.14.1", "units" to units)))
        write(units)
        val request = Json.parse(CoreModules.request(listOf("@$manifest"), "pkg-a:Shared.entry")) as Map<*, *>
        assertEquals(true, request["strictLink"])
        assertEquals(2, (request["modules"] as List<*>).size)
        Files.writeString(temporary.resolve("pkg-b.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.request(listOf("@$manifest"), "pkg-a:Shared.entry")
        }
        write(listOf(units[0], units[0]))
        assertThrows(IllegalArgumentException::class.java) {
            CoreModules.request(listOf("@$manifest"), "pkg-a:Shared.entry")
        }
    }
}
