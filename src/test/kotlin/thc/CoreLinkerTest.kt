package thc

import org.junit.jupiter.api.Assertions.assertEquals
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
