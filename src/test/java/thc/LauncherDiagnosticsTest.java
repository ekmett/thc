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
import static thc.Main.launcherArtifactVerification;

@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public class LauncherDiagnosticsTest {
    @TempDir public Path directory;
    @Test public void nativeImageIncludesDynamicallySelectedOriginalCResources() throws Exception {
        Map<?, ?> configuration, manifest;
        try (var input = getClass().getResourceAsStream("/META-INF/native-image/thc/runtime/resource-config.json")) {
            configuration = (Map<?, ?>) Json.parse(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var input = getClass().getResourceAsStream("/thc/cbits/manifest.json")) {
            manifest = (Map<?, ?>) Json.parse(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
        }
        var patterns = ((List<?>) ((Map<?, ?>) configuration.get("resources")).get("includes")).stream()
            .map(raw -> java.util.regex.Pattern.compile((String) ((Map<?, ?>) raw).get("pattern"))).toList();
        var resources = new ArrayList<String>();
        for (var raw : (List<?>) manifest.get("artifacts")) {
            var name = Path.of((String) ((Map<?, ?>) raw).get("path")).getFileName().toString();
            if (name.endsWith(".bc")) resources.add("thc/cbits/" + name);
        }
        assertTrue(resources.contains("thc/cbits/package-pointer.bc"));
        if (manifest.get("system").equals("Linux"))
            assertTrue(resources.contains("thc/cbits/iconv.bc")); // Actual ELF stdout initialization failure.
        for (var resource : resources) {
            assertNotNull(getClass().getResource("/" + resource), resource);
            assertTrue(patterns.stream().anyMatch(pattern -> pattern.matcher(resource).matches()), resource);
        }
        assertFalse(patterns.stream().anyMatch(pattern -> pattern.matcher("unrelated/example.bc").matches()));
    }
    @Test public void nativeImageIoMetadataCoversDeclaredUnixAbisAndCaptureOptions() throws Exception {
        var path = Path.of(System.getProperty("thc.projectRoot"),
            "research/native-image-preparation/native-io/reachability-metadata.json");
        var document = (Map<?, ?>) Json.parse(Files.readString(path));
        var foreign = (Map<?, ?>) document.get("foreign");
        assertEquals(Set.of("downcalls"), foreign.keySet());
        var calls = (List<?>) foreign.get("downcalls");
        assertEquals(calls.size(), new HashSet<>(calls).size());
        for (var raw : calls) {
            var call = (Map<?, ?>) raw;
            assertTrue(Set.of("jint", "jlong", "void*", "void").contains(call.get("returnType")));
            for (var parameter : (List<?>) call.get("parameterTypes"))
                assertTrue(Set.of("jint", "jlong", "void*").contains(parameter));
            var options = (Map<?, ?>) call.get("options");
            if (options != null) {
                assertEquals(true, options.get("captureCallState"));
                assertTrue(Set.of("captureCallState", "firstVariadicArg").containsAll(options.keySet()));
            }
        }
        assertTrue(calls.contains(Map.of("returnType", "jint", "parameterTypes", List.of("jint"),
            "options", Map.of("captureCallState", true)))); // NativeFileLease.close, actual ELF startup failure.
        assertTrue(calls.contains(Map.of("returnType", "jint", "parameterTypes", List.of("jint", "jint", "jint"),
            "options", Map.of("captureCallState", true, "firstVariadicArg", 2L)))); // fcntl readiness duplicate.
        assertTrue(calls.contains(Map.of("returnType", "jlong", "parameterTypes", List.of("jint", "void*"))),
            "NativeOpenOperation initializes the observation downcall before opening any file");
        for (var operation : thc.runtime.OriginalStdioOp.values()) if (operation.getUnixNative()) {
            var parameters = operation.getArguments().stream().filter(Objects::nonNull).map(LauncherDiagnosticsTest::foreignType).toList();
            assertTrue(calls.contains(Map.of("returnType", foreignType(operation.getResult()), "parameterTypes", parameters,
                "options", Map.of("captureCallState", true))), operation.name());
        }
    }
    private static String foreignType(String representation) {
        return switch (representation) {
            case "AddrRep" -> "void*";
            case "Int32Rep", "Word32Rep" -> "jint";
            case "Int64Rep", "Word64Rep", "IntRep", "WordRep" -> "jlong";
            default -> throw new AssertionError(representation);
        };
    }
    @Test public void nativeExecutableProfileIsSeparateFromGuestCppOptions() {
        var configuration = Json.stringify(Map.of("arguments", List.of("--run-executable", "main.cbd",
            "u:Main.main", "base:Top.flush", "--", "ghc"), "properties", Map.of("thc.byteArrayStorage", "native")));
        var old = System.getProperty("thc.byteArrayStorage");
        try {
            NativeExecutable.initializeProperties(configuration);
            assertEquals("native", System.getProperty("thc.byteArrayStorage"));
            assertArrayEquals(new String[]{"--run-executable", "main.cbd", "u:Main.main", "base:Top.flush", "--", "ghc",
                "-DDEBUG", "-DVALUE=42"}, NativeExecutable.launcherArguments(configuration, new String[]{"-DDEBUG", "-DVALUE=42"}));
        } finally { property("thc.byteArrayStorage", old); }
    }
    @Test public void nativeExecutableBindsMainAndShutdownWithoutParsingGuestOptions() {
        var binding = List.of("--run-executable", "@packages.json,main.cbd", "u:Main.main",
            "base:Top.flush", "--", "ghc", "-B/lib with spaces");
        var guest = new String[] {"--verify-artifacts", "--run-io", "", "--", "a b.hs"};
        var actual = NativeExecutable.launcherArguments(Json.stringify(binding), guest);
        assertArrayEquals(new String[] {"--run-executable", "@packages.json,main.cbd", "u:Main.main",
            "base:Top.flush", "--", "ghc", "-B/lib with spaces", "--verify-artifacts",
            "--run-io", "", "--", "a b.hs"}, actual);
        assertFalse(Main.launcherArtifactVerification(actual).verifyArtifacts());
        assertEquals("ghc", Main.launcherArguments(actual, 4).programName());
        assertArrayEquals(new String[] {"--verify-artifacts", "--run-io", "", "--", "a b.hs"}, guest);
        for (String invalid : List.of("{}", "[]", "[1]", "[null,1,2,3,4,5]", "[\"--run-io\",\"modules\",\"entry\"]",
                Json.stringify(List.of("--run-executable", "modules", "entry", "", "--", "ghc"))))
            assertThrows(IllegalArgumentException.class, () -> NativeExecutable.launcherArguments(invalid, guest));
    }
    @Test public void checkpointLauncherLeavesGuestArgumentsForRestore() {
        var failure = assertThrows(IllegalArgumentException.class, () -> Main.main(new String[]{
            "--checkpoint-executable", "unused.cbd", "u:Main.main", "base:Top.flush", "--", "prebound-name", "arg"}));
        assertTrue(failure.getMessage().contains("supply guest arguments on restore"));
        assertThrows(IllegalStateException.class, () -> CracExecutable.main(new String[]{"program", "arg"}),
            "A fresh JVM cannot prebind restore arguments outside a checkpointed launcher");
    }

    @Test public void nativeExecutableRetainsBoundArtifactVerificationAndOpaqueGuestArguments() {
        var guest = new String[] {"--verify-artifacts", "", "--", "a b.hs"};
        for (var binding : List.of(
                List.of("--verify-artifacts", "--run-executable", "@packages.json", "u:Main.main", "base:Top.flush", "--", "ghc"),
                List.of("--run-executable", "--verify-artifacts", "@packages.json", "u:Main.main", "base:Top.flush", "--", "ghc"),
                List.of("--run-executable", "@packages.json", "u:Main.main", "base:Top.flush", "--verify-artifacts", "--", "ghc"))) {
            var actual = NativeExecutable.launcherArguments(Json.stringify(binding), guest);
            assertArrayEquals(concat(binding.toArray(String[]::new), guest), actual);
            var selected = launcherArtifactVerification(actual);
            assertTrue(selected.verifyArtifacts());
            assertArrayEquals(new String[] {"--run-executable", "@packages.json", "u:Main.main", "base:Top.flush", "--", "ghc",
                "--verify-artifacts", "", "--", "a b.hs"}, selected.arguments());
            assertEquals("ghc", Main.launcherArguments(selected.arguments(), 4).programName());
        }
        assertArrayEquals(new String[] {"--verify-artifacts", "", "--", "a b.hs"}, guest);
    }
    @Test public void nativeExecutableRejectsInvalidVerifiedBindingsBeforeGuestArguments() {
        for (var binding : List.of(
                List.of("--verify-artifacts", "--run-executable", "@packages.json", "u:Main.main", "base:Top.flush", "--verify-artifacts", "--", "ghc"),
                List.of("--verify-artifacts", "--run-executable", "@packages.json", "u:Main.main", "", "--", "ghc"),
                List.of("--verify-artifacts", "--run-executable", "@packages.json", "u:Main.main", "base:Top.flush", "--", ""),
                List.of("--verify-artifacts", "--run-executable", "@packages.json", "u:Main.main", "base:Top.flush", "--", "ghc\0")))
            assertThrows(IllegalArgumentException.class, () -> NativeExecutable.launcherArguments(Json.stringify(binding), new String[] {"--verify-artifacts"}));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    public void shellWrapperPreservesExplicitModulesAndEveryArgument() throws Exception {
        var script = directory.resolve("bin/run.sh");
        Files.createDirectories(script.getParent());
        Files.copy(Path.of(System.getProperty("thc.projectRoot"), "bin/run.sh"), script);
        // Observe the real wrapper's process boundary without requiring a second JVM.
        Files.writeString(directory.resolve("Makefile"), "check-java:\n\t@true\n");
        var child = directory.resolve("build/install/thc/bin/thc");
        Files.createDirectories(child.getParent());
        Files.writeString(child, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        assertTrue(child.toFile().setExecutable(true));
        var arguments = List.of("path with spaces/core.cbd", "entry", "", "--", "guest", "--compile");
        var command = new ArrayList<>(List.of("sh", script.toString())); command.addAll(arguments);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertEquals(arguments, output.lines().toList());
    }
    @Test public void scalarCompileChecksTheImmediateCallWithoutAdditionalTraining() throws Exception {
        var body = List.of("app", List.of("prim", "+#", Map.of()),
            List.of(List.of("var", "input", Map.of()), List.of("lit", "int", "1", Map.of())), List.of(false, false), false, false, Map.of());
        var expression = List.of("lam", List.of(Map.of("id", "input", "name", "input", "lifted", false)), body, Map.of());
        var module = Map.of("schema", 1, "ghc", "9.14.1", "unit", "synthetic", "module", "Synthetic.LauncherCompilation",
            "boundary", "synthetic", "constructors", List.of(),
            "bindings", List.of(Map.of("id", "entry", "name", "entry", "arity", 1, "lifted", true, "expr", expression)));
        var source = CoreCbdTestSupport.writeModel(directory.resolve("scalar.cbd"), module);
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
        var host = new String[] {"--run-io", "@packages.json", "main"};
        var normal = launcherArtifactVerification(host); assertFalse(normal.getVerifyArtifacts()); assertArrayEquals(host, normal.getArguments());
        var selected = launcherArtifactVerification(concat(new String[] {"--verify-artifacts"}, host, new String[] {"--", "program", "--verify-artifacts"}));
        assertTrue(selected.getVerifyArtifacts()); assertArrayEquals(concat(host, new String[] {"--", "program", "--verify-artifacts"}), selected.getArguments());
        assertFalse(launcherArtifactVerification(concat(host, new String[] {"--", "program", "--verify-artifacts"})).getVerifyArtifacts());
        assertThrows(IllegalArgumentException.class, () -> launcherArtifactVerification(concat(new String[] {"--verify-artifacts", "--verify-artifacts"}, host)));
    }
    @Test public void retiredSidecarOptionIsRejectedOnlyBeforeGuestArguments() {
        assertThrows(IllegalArgumentException.class, () -> launcherArtifactVerification(new String[]{"--json-sidecar", "a.json", "a.idx"}));
        var guest = new String[]{"--run-io", "a.json", "main", "--", "program", "--json-sidecar", "guest"};
        assertArrayEquals(guest, launcherArtifactVerification(guest).getArguments());
    }
    /** Synthetic exact IO boundary: no foreign effects, fixture export, or native process. */
    private Map<String, Object> module() {
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var unit = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var closure = new LinkedHashMap<String, Object>(unit); closure.put("kind", "closure");
        var result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(state, unit), "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var unitId = "ghc-internal:GHC.Internal.Tuple.()";
        var body = List.of("app", List.of("con", "StateUnit", 2, Map.of()), List.of(List.of("void", Map.of("rep", state)), List.of("con", unitId, 0, Map.of("rep", unit))), List.of(false, true), true, true, Map.of("rep", result));
        var worker = binding("worker", List.of("lam", List.of(Map.of("id", "s", "name", "s", "type", "State# RealWorld", "lifted", false, "rep", state)), body, Map.of("rep", closure, "resultRep", result)), closure);
        return Map.of("schema", 1, "ghc", "9.14.1", "unit", "synthetic", "module", "Synthetic.LauncherIO", "boundary", "synthetic",
            "bindings", List.of(worker, binding("main", List.of("var", "worker", Map.of("rep", closure)), closure),
            binding("shutdown", List.of("var", "worker", Map.of("rep", closure)), closure)), "constructors", List.of(
            Map.of("id", "StateUnit", "name", "StateUnit", "kind", "unboxed-tuple", "arity", 2, "tag", 1,
                "strictFields", List.of(false, false), "fieldLifted", List.of(false, true),
                "fieldReps", List.of(List.of(), List.of("BoxedRep (Just Lifted)")), "fieldTypes", List.of(state, unit)),
            Map.of("id", unitId, "name", "()", "kind", "boxed", "arity", 0, "tag", 1,
                "strictFields", List.of(), "fieldLifted", List.of(), "fieldReps", List.of(), "fieldTypes", List.of())));
    }
    private Map<String, Object> binding(String id, Object expression, Map<String, Object> closure) {
        return Map.of("id", id, "name", id, "type", "IO ()", "arity", 0, "lifted", true, "rep", closure, "expr", expression);
    }
    private void property(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    private String launch(String mode, String backend, String diagnostics) throws Throwable { return launch(mode, backend, diagnostics, List.of(), null); }
    private String launch(String mode, String backend, String diagnostics, List<String> guest, String async) throws Throwable {
        var source = CoreCbdTestSupport.writeModel(directory.resolve("io.cbd"), module());
        var old = new LinkedHashMap<String, String>(); for (var key : List.of("thc.backend", "thc.diagnostics", "thc.asyncExceptions")) old.put(key, System.getProperty(key));
        var stderr = System.err; var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            try {
                System.setProperty("thc.backend", backend); property("thc.diagnostics", diagnostics); property("thc.asyncExceptions", async); System.setErr(stream);
                var args = new ArrayList<>(List.of(mode, source.toString(), "main")); if (mode.equals("--run-executable")) args.add("shutdown"); args.addAll(List.of("--", "program")); args.addAll(guest);
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
        for (var mode : List.of("--run-executable", "--run-io")) for (var backend : List.of("ast", "bytecode")) {
            for (var setting : Arrays.asList(null, "true", "false")) {
                var metrics = (Map<?, ?>) Json.parse(launch(mode, backend, "true", List.of(), setting).trim());
                assertEquals("true".equals(setting), metrics.get("asyncExceptions"), mode + "/" + backend + "/" + setting);
            }
            assertThrows(IllegalArgumentException.class, () -> launch(mode, backend, "true", List.of(), "enabled"));
        }
    }
    @Test public void guestOptionsRemainOpaqueToTheLauncher() throws Throwable {
        for (var mode : List.of("--run-io", "--run-executable")) for (var backend : List.of("ast", "bytecode"))
            assertEquals("", launch(mode, backend, null, List.of("--guest-option", "value", "", "--"), null));
    }
}
