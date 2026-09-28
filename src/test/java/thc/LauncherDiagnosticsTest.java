// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.launcherJsonSidecars;
import static thc.Main.launcherArtifactVerification;

@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public class LauncherDiagnosticsTest {
    @TempDir public Path directory;
    @Test public void scalarCompileChecksTheImmediateCallWithoutAdditionalTraining() throws Exception {
        var body = List.of("app", List.of("prim", "+#"), List.of(List.of("var", "input"), List.of("lit", "int", "1")), List.of(false, false));
        var expression = List.of("lam", List.of(Map.of("id", "input", "name", "input", "lifted", false)), body);
        var module = Map.of("schema", 1, "ghc", "9.14.1", "module", "Synthetic.LauncherCompilation", "constructors", List.of(),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true, "expr", expression)));
        var source = directory.resolve("scalar.json"); Files.writeString(source, Json.stringify(module));
        var oldBackend = System.getProperty("thc.backend"); var oldOut = System.out; var oldErr = System.err;
        try {
            for (var backend : List.of("ast", "bytecode")) for (boolean compiled : new boolean[] {false, true}) {
                var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
                try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8); var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
                    System.setProperty("thc.backend", backend); System.setOut(stdout); System.setErr(stderr);
                    var args = new ArrayList<>(List.of(source.toAbsolutePath().toString(), "entry", "7")); if (compiled) args.add("--compile"); Main.main(args.toArray(String[]::new));
                }
                assertEquals("8", out.toString(StandardCharsets.UTF_8).trim()); var diagnostics = (Map<?, ?>) Json.parse(err.toString(StandardCharsets.UTF_8).trim());
                if (compiled) {
                    var call = (Map<?, ?>) diagnostics.get("firstInstalledCall");
                    assertTrue(((Number) call.get("compiledEntriesAfter")).longValue() > ((Number) call.get("compiledEntriesBefore")).longValue());
                    var installation = (Map<?, ?>) diagnostics.get("explicitCompilation"); assertEquals(true, installation.get("sameTargets")); assertEquals(true, installation.get("validLastTier"));
                } else { assertFalse(diagnostics.containsKey("firstInstalledCall")); assertFalse(diagnostics.containsKey("explicitCompilation")); }
            }
        } finally { System.setOut(oldOut); System.setErr(oldErr); property("thc.backend", oldBackend); }
    }
    private String[] concat(String[]... parts) { var result = new ArrayList<String>(); for (var part : parts) Collections.addAll(result, part); return result.toArray(String[]::new); }
    @Test public void artifactVerificationIsExplicitAndNeverConsumesGuestArguments() {
        var host = new String[] {"--ffi=native", "--run-io", "@packages.json", "main"};
        var normal = launcherArtifactVerification(host); assertFalse(normal.getVerifyArtifacts()); assertArrayEquals(host, normal.getArguments());
        var selected = launcherArtifactVerification(concat(new String[] {"--verify-artifacts"}, host, new String[] {"--", "program", "--verify-artifacts"}));
        assertTrue(selected.getVerifyArtifacts()); assertArrayEquals(concat(host, new String[] {"--", "program", "--verify-artifacts"}), selected.getArguments());
        assertFalse(launcherArtifactVerification(concat(host, new String[] {"--", "program", "--verify-artifacts"})).getVerifyArtifacts());
        assertThrows(IllegalArgumentException.class, () -> launcherArtifactVerification(concat(new String[] {"--verify-artifacts", "--verify-artifacts"}, host)));
    }
    @Test public void explicitSidecarPairsAreRepeatableAndStopAtTheGuestSeparator() {
        var parsed = launcherJsonSidecars(new String[] {"--ffi", "native", "--json-sidecar", "a.json", "a.idx", "--run-io", "a.json,b.json,@packages.json", "main",
            "--json-sidecar", "b.json", "b.idx", "--", "program", "--json-sidecar", "guest.json", "guest.idx"});
        assertArrayEquals(new String[] {"--ffi", "native", "--run-io", "a.json,b.json,@packages.json", "main", "--", "program", "--json-sidecar", "guest.json", "guest.idx"}, parsed.getArguments());
        var sidecars = new LinkedHashMap<String, String>(); sidecars.put("a.json", "a.idx"); sidecars.put("b.json", "b.idx"); assertEquals(sidecars, parsed.getSidecars());
        assertNull(launcherJsonSidecars(new String[] {"--", "--json-sidecar", "guest"}).getSidecars());
        for (var invalid : List.of(new String[] {"--json-sidecar"}, new String[] {"--json-sidecar", "a.json"},
            new String[] {"--json-sidecar", "a.json", "--"}, new String[] {"--json-sidecar", "@packages.json", "a.idx"},
            new String[] {"--json-sidecar", "a.json", "a.idx", "--json-sidecar", "a.json", "b.idx"}))
            assertThrows(IllegalArgumentException.class, () -> launcherJsonSidecars(invalid));
    }
    /** Synthetic exact IO boundary: no foreign effects, fixture export, or native process. */
    private Map<String, Object> module() {
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var unit = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var closure = new LinkedHashMap<String, Object>(unit); closure.put("kind", "closure");
        var result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(state, unit), "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var unitId = "ghc-internal:GHC.Internal.Tuple.()";
        var body = List.of("app", List.of("con", "StateUnit", 2), List.of(List.of("void", Map.of("rep", state)), List.of("con", unitId, 0, Map.of("rep", unit))), List.of(false, true), true, true, Map.of("rep", result));
        var worker = binding("worker", List.of("lam", List.of(Map.of("id", "s", "name", "s", "type", "State# RealWorld", "lifted", false, "rep", state)), body, Map.of("rep", closure, "resultRep", result)), closure);
        return Map.of("schema", 1, "ghc", "9.14.1", "bindings", List.of(worker, binding("main", List.of("var", "worker", Map.of("rep", closure)), closure),
            binding("shutdown", List.of("var", "worker", Map.of("rep", closure)), closure)), "constructors", List.of(
            Map.of("id", "StateUnit", "name", "StateUnit", "kind", "unboxed-tuple", "arity", 2),
            Map.of("id", unitId, "name", "()", "kind", "boxed", "arity", 0, "strictFields", List.of(), "fieldLifted", List.of(), "fieldReps", List.of())));
    }
    private Map<String, Object> binding(String id, Object expression, Map<String, Object> closure) {
        return Map.of("id", id, "name", id, "type", "IO ()", "arity", 0, "lifted", true, "rep", closure, "expr", expression);
    }
    private void property(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    private String launch(String mode, String backend, String diagnostics) throws Throwable { return launch(mode, backend, diagnostics, List.of(), List.of(), null); }
    private String launch(String mode, String backend, String diagnostics, List<String> ffi, List<String> guest, String async) throws Throwable {
        var source = directory.resolve("io.json"); Files.writeString(source, Json.stringify(module()));
        var old = new LinkedHashMap<String, String>(); for (var key : List.of("thc.backend", "thc.diagnostics", "thc.asyncExceptions")) old.put(key, System.getProperty(key));
        var stderr = System.err; var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            try {
                System.setProperty("thc.backend", backend); property("thc.diagnostics", diagnostics); property("thc.asyncExceptions", async); System.setErr(stream);
                var args = new ArrayList<>(ffi); args.addAll(List.of(mode, source.toString(), "main")); if (mode.equals("--run-executable")) args.add("shutdown"); args.addAll(List.of("--", "program")); args.addAll(guest);
                // Invoke the installed launcher class explicitly, preserving the original launcher boundary.
                try { Class.forName("thc.Main").getMethod("main", String[].class).invoke(null, (Object) args.toArray(String[]::new)); }
                catch (InvocationTargetException failure) { throw failure.getTargetException(); }
            } finally { System.setErr(stderr); for (var entry : old.entrySet()) property(entry.getKey(), entry.getValue()); }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test public void defaultAndExplicitFalseDoNotAppendMetrics() throws Throwable {
        for (var mode : List.of("--run-io", "--run-executable")) for (var backend : List.of("ast", "bytecode")) for (var setting : Arrays.asList(null, "false"))
            assertEquals("", launch(mode, backend, setting), mode + "/" + backend + "/" + setting);
    }
    @Test public void explicitOptInStillReportsBackendAndRuntimeMetrics() throws Throwable {
        for (var mode : List.of("--run-io", "--run-executable")) for (var backend : List.of("ast", "bytecode")) {
            var output = launch(mode, backend, "true"); long nonemptyLines = 0;
            for (var line : output.split("\\r\\n|\\r|\\n")) if (!line.isEmpty()) nonemptyLines++;
            assertEquals(1L, nonemptyLines, mode + "/" + backend);
            var metrics = (Map<?, ?>) Json.parse(output.trim()); assertEquals(backend, metrics.get("backend")); assertEquals(0L, ((Number) metrics.get("unsupportedTraps")).longValue());
        }
    }
    @Test public void executableAsyncDefaultAndOverridesReachTheRunningProgram() throws Throwable {
        for (var backend : List.of("ast", "bytecode")) {
            for (var setting : Arrays.asList(null, "true", "false")) {
                var metrics = (Map<?, ?>) Json.parse(launch("--run-executable", backend, "true", List.of(), List.of(), setting).trim());
                assertEquals(!"false".equals(setting), metrics.get("asyncExceptions"), backend + "/" + setting);
            }
            assertThrows(IllegalArgumentException.class, () -> launch("--run-executable", backend, "true", List.of(), List.of(), "enabled"));
        }
        for (var backend : List.of("ast", "bytecode")) {
            var metrics = (Map<?, ?>) Json.parse(launch("--run-io", backend, "true").trim()); assertEquals(backend.equals("bytecode"), metrics.get("asyncExceptions"), "raw IO/" + backend);
        }
    }
    @Test public void nativeFfiOverridesAmbientModeAndPreservesGuestOptions() throws Throwable {
        var old = System.getProperty("thc.ffiMode");
        try {
            System.setProperty("thc.ffiMode", "managed");
            for (var mode : List.of("--run-io", "--run-executable")) for (var backend : List.of("ast", "bytecode")) {
                assertEquals("", launch(mode, backend, null, List.of("--ffi", "native"), List.of("--ffi", "invalid-guest-value", "", "--"), null));
                assertEquals("", launch(mode, backend, null, List.of("--ffi=managed", "--ffi=native"), List.of(), null));
            }
            assertEquals("managed", System.getProperty("thc.ffiMode"), "launch does not mutate defaults");
        } finally { property("thc.ffiMode", old); }
    }
}
