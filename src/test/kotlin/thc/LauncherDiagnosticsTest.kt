// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LauncherDiagnosticsTest {
    @TempDir lateinit var directory: Path

    /** Synthetic exact IO boundary: no foreign effects, fixture export, or native process. */
    private fun module(): Map<String, Any?> {
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val unit = mapOf("kind" to "data", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val closure = unit + ("kind" to "closure")
        val result = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to listOf(state, unit),
            "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val unitId = "ghc-internal:GHC.Internal.Tuple.()"
        val body = listOf("app", listOf("con", "StateUnit", 2),
            listOf(listOf("void", mapOf("rep" to state)), listOf("con", unitId, 0, mapOf("rep" to unit))),
            listOf(false, true), true, true, mapOf("rep" to result))
        fun binding(id: String, expression: Any) = mapOf("id" to id, "name" to id, "type" to "IO ()",
            "arity" to 0, "lifted" to true, "rep" to closure, "expr" to expression)
        val worker = binding("worker", listOf("lam", listOf(mapOf("id" to "s", "name" to "s",
            "type" to "State# RealWorld", "lifted" to false, "rep" to state)), body,
            mapOf("rep" to closure, "resultRep" to result)))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "bindings" to listOf(worker,
            binding("main", listOf("var", "worker", mapOf("rep" to closure))),
            binding("shutdown", listOf("var", "worker", mapOf("rep" to closure)))),
            "constructors" to listOf(
                mapOf("id" to "StateUnit", "name" to "StateUnit", "kind" to "unboxed-tuple", "arity" to 2),
                mapOf("id" to unitId, "name" to "()", "kind" to "boxed", "arity" to 0,
                    "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>(),
                    "fieldReps" to emptyList<List<String>>())))
    }

    private fun launch(mode: String, backend: String, diagnostics: String?): String {
        val source = directory.resolve("io.json").toFile()
        source.writeText(Json.stringify(module()))
        val old = listOf("thc.backend", "thc.diagnostics").associateWith(System::getProperty)
        val stderr = System.err
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
            try {
                System.setProperty("thc.backend", backend)
                if (diagnostics == null) System.clearProperty("thc.diagnostics")
                else System.setProperty("thc.diagnostics", diagnostics)
                System.setErr(stream)
                val args = (listOf(mode, source.path, "main") +
                    if (mode == "--run-executable") listOf("shutdown") else emptyList()).toTypedArray()
                // Several development executables share this Kotlin package;
                // invoke the installed launcher class, not an ambiguous main().
                try { Class.forName("thc.MainKt").getMethod("main", Array<String>::class.java).invoke(null, args) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            } finally {
                System.setErr(stderr)
                old.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    @Test fun defaultAndExplicitFalseDoNotAppendMetrics() {
        for (mode in listOf("--run-io", "--run-executable")) for (backend in listOf("ast", "bytecode"))
            for (setting in listOf(null, "false")) assertEquals("", launch(mode, backend, setting), "$mode/$backend/$setting")
    }

    @Test fun explicitOptInStillReportsBackendAndRuntimeMetrics() {
        for (mode in listOf("--run-io", "--run-executable")) for (backend in listOf("ast", "bytecode")) {
            val output = launch(mode, backend, "true")
            assertEquals(1, output.lineSequence().filter { it.isNotEmpty() }.count(), "$mode/$backend")
            val metrics = Json.parse(output.trim()) as Map<*, *>
            assertEquals(backend, metrics["backend"])
            assertEquals(0L, (metrics["unsupportedTraps"] as Number).toLong())
        }
    }
}
