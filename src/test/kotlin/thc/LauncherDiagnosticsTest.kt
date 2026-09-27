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
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LauncherDiagnosticsTest {
    @TempDir lateinit var directory: Path

    @Test fun scalarCompileChecksTheImmediateCallWithoutAdditionalTraining() {
        val body = listOf("app", listOf("prim", "+#"),
            listOf(listOf("var", "input"), listOf("lit", "int", "1")), listOf(false, false))
        val expression = listOf("lam", listOf(mapOf("id" to "input", "name" to "input", "lifted" to false)), body)
        val module = mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.LauncherCompilation",
            "constructors" to emptyList<Any>(), "bindings" to listOf(mapOf(
                "id" to "entry", "name" to "entry", "arity" to 1, "lifted" to true, "expr" to expression)))
        val source = directory.resolve("scalar.json").toFile().also { it.writeText(Json.stringify(module)) }
        val oldBackend = System.getProperty("thc.backend")
        val oldOut = System.out; val oldErr = System.err
        try {
            for (backend in listOf("ast", "bytecode")) for (compiled in listOf(false, true)) {
                val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
                PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                    PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                        System.setProperty("thc.backend", backend)
                        System.setOut(stdout); System.setErr(stderr)
                        main(arrayOf(source.absolutePath, "entry", "7") + if (compiled) arrayOf("--compile") else emptyArray())
                    }
                }
                assertEquals("8", out.toString(Charsets.UTF_8).trim())
                val diagnostics = Json.parse(err.toString(Charsets.UTF_8).trim()) as Map<*, *>
                if (compiled) {
                    val call = diagnostics["firstInstalledCall"] as Map<*, *>
                    assertTrue((call["compiledEntriesAfter"] as Number).toLong() >
                        (call["compiledEntriesBefore"] as Number).toLong())
                    val installation = diagnostics["explicitCompilation"] as Map<*, *>
                    assertEquals(true, installation["sameTargets"])
                    assertEquals(true, installation["validLastTier"])
                } else {
                    assertFalse(diagnostics.containsKey("firstInstalledCall"))
                    assertFalse(diagnostics.containsKey("explicitCompilation"))
                }
            }
        } finally {
            System.setOut(oldOut); System.setErr(oldErr)
            if (oldBackend == null) System.clearProperty("thc.backend") else System.setProperty("thc.backend", oldBackend)
        }
    }

    @Test fun artifactVerificationIsExplicitAndNeverConsumesGuestArguments() {
        val host = arrayOf("--ffi=native", "--run-io", "@packages.json", "main")
        val (normal, default) = launcherArtifactVerification(host)
        assertFalse(default)
        assertArrayEquals(host, normal)
        val (selected, verify) = launcherArtifactVerification(arrayOf("--verify-artifacts") + host +
            arrayOf("--", "program", "--verify-artifacts"))
        assertTrue(verify)
        assertArrayEquals(host + arrayOf("--", "program", "--verify-artifacts"), selected)
        assertFalse(launcherArtifactVerification(host + arrayOf("--", "program", "--verify-artifacts")).second)
        assertThrows(IllegalArgumentException::class.java) {
            launcherArtifactVerification(arrayOf("--verify-artifacts", "--verify-artifacts") + host)
        }
    }

    @Test fun explicitSidecarPairsAreRepeatableAndStopAtTheGuestSeparator() {
        val (arguments, pairs) = launcherJsonSidecars(arrayOf("--ffi", "native", "--json-sidecar", "a.json", "a.idx",
            "--run-io", "a.json,b.json,@packages.json", "main", "--json-sidecar", "b.json", "b.idx",
            "--", "program", "--json-sidecar", "guest.json", "guest.idx"))
        assertArrayEquals(arrayOf("--ffi", "native", "--run-io", "a.json,b.json,@packages.json", "main",
            "--", "program", "--json-sidecar", "guest.json", "guest.idx"), arguments)
        assertEquals(linkedMapOf("a.json" to "a.idx", "b.json" to "b.idx"), pairs)
        assertNull(launcherJsonSidecars(arrayOf("--", "--json-sidecar", "guest")).second)
        for (invalid in listOf(arrayOf("--json-sidecar"), arrayOf("--json-sidecar", "a.json"),
                arrayOf("--json-sidecar", "a.json", "--"), arrayOf("--json-sidecar", "@packages.json", "a.idx"),
                arrayOf("--json-sidecar", "a.json", "a.idx", "--json-sidecar", "a.json", "b.idx"))) {
            assertThrows(IllegalArgumentException::class.java) { launcherJsonSidecars(invalid) }
        }
    }

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

    private fun launch(mode: String, backend: String, diagnostics: String?, ffi: List<String> = emptyList(),
                       guest: List<String> = emptyList(), async: String? = null): String {
        val source = directory.resolve("io.json").toFile()
        source.writeText(Json.stringify(module()))
        val old = listOf("thc.backend", "thc.diagnostics", "thc.asyncExceptions").associateWith(System::getProperty)
        val stderr = System.err
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
            try {
                System.setProperty("thc.backend", backend)
                if (diagnostics == null) System.clearProperty("thc.diagnostics")
                else System.setProperty("thc.diagnostics", diagnostics)
                if (async == null) System.clearProperty("thc.asyncExceptions")
                else System.setProperty("thc.asyncExceptions", async)
                System.setErr(stream)
                val args = (ffi + listOf(mode, source.path, "main") +
                    (if (mode == "--run-executable") listOf("shutdown") else emptyList()) +
                    listOf("--", "program") + guest).toTypedArray()
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

    @Test fun executableAsyncDefaultAndOverridesReachTheRunningProgram() {
        for (backend in listOf("ast", "bytecode")) {
            for ((setting, expected) in listOf(null to true, "true" to true, "false" to false)) {
                val metrics = Json.parse(launch("--run-executable", backend, "true", async = setting).trim()) as Map<*, *>
                assertEquals(expected, metrics["asyncExceptions"], "$backend/$setting")
            }
            assertThrows(IllegalArgumentException::class.java) {
                launch("--run-executable", backend, "true", async = "enabled")
            }
        }
        for ((backend, expected) in listOf("ast" to false, "bytecode" to true)) {
            val metrics = Json.parse(launch("--run-io", backend, "true").trim()) as Map<*, *>
            assertEquals(expected, metrics["asyncExceptions"], "raw IO/$backend")
        }
    }

    @Test fun nativeFfiOverridesAmbientModeAndPreservesGuestOptions() {
        val old = System.getProperty("thc.ffiMode")
        try {
            System.setProperty("thc.ffiMode", "managed")
            for (mode in listOf("--run-io", "--run-executable")) for (backend in listOf("ast", "bytecode")) {
                assertEquals("", launch(mode, backend, null, listOf("--ffi", "native"),
                    listOf("--ffi", "invalid-guest-value", "", "--")))
                assertEquals("", launch(mode, backend, null, listOf("--ffi=managed", "--ffi=native")))
            }
            assertEquals("managed", System.getProperty("thc.ffiMode"), "launch does not mutate defaults")
        } finally {
            if (old == null) System.clearProperty("thc.ffiMode") else System.setProperty("thc.ffiMode", old)
        }
    }
}
