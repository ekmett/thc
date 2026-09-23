package thc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CoreLinkerTest {
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
}
