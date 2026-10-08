// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.CoreRepresentations;
import thc.runtime.UnsupportedCore;

import static org.junit.jupiter.api.Assertions.*;

/** Native Windows packaging and authority regressions; never a full-platform parity gate. */
@EnabledOnOs(OS.WINDOWS)
@SuppressWarnings("unchecked")
class WindowsDistributionTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    @TempDir Path temporary;

    private String digest(Path file) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private Map<String, Object> document(Path path) throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(path));
    }
    private Map<String, Object> document(ZipFile zip, String path) throws Exception {
        try (var input = zip.getInputStream(zip.getEntry(path))) {
            return (Map<String, Object>) Json.INSTANCE.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private Map<String, Object> single(List<Map<String, Object>> rows, String key, Object value) {
        var matches = rows.stream().filter(row -> value.equals(row.get(key))).toList();
        assertEquals(1, matches.size());
        return matches.getFirst();
    }
    private Map<String, Object> verifiedReceipt(String path) throws Exception {
        var receipt = document(root.resolve(path));
        assertEquals("9.14.1", receipt.get("ghc"));
        assertEquals("mingw32", receipt.get("system"));
        for (var field : List.of("inputHashes", "artifactHashes")) {
            var hashes = (Map<String, String>) receipt.get(field);
            assertFalse(hashes.isEmpty(), field);
            for (var entry : hashes.entrySet()) assertEquals(entry.getValue(), digest(root.resolve(entry.getKey())), entry.getKey());
        }
        for (var command : (List<Map<String, Object>>) receipt.get("commands"))
            assertEquals(command.get("expectedExit"), command.get("exit"), command.toString());
        return receipt;
    }

    @Test void nativeSmokeInputsAndArtifactsHaveCurrentProvenance() throws Exception {
        var receipt = verifiedReceipt("build/windows-smoke/provenance.json");
        assertEquals(133L, ((Number) receipt.get("nativeRows")).longValue());
        assertEquals(19, ((List<?>) receipt.get("entries")).size());
        var commands = (List<Map<String, Object>>) receipt.get("commands");
        assertTrue(commands.stream().anyMatch(command -> ((Number) command.get("expectedExit")).longValue() == 1L));
        var helperArguments = commands.stream().map(command -> (List<String>) command.get("argv"))
            .filter(argv -> argv.contains("--interface") && argv.contains("GHC.Internal.CString")).toList();
        assertEquals(1, helperArguments.size());
        var selectedBuild = System.getenv("THC_CABAL_BUILD_DIR");
        if (selectedBuild != null)
            assertTrue(Path.of(helperArguments.getFirst().getFirst()).startsWith(root.resolve(selectedBuild).normalize()),
                "CString must use the helper from the selected Cabal build");
        try (var input = getClass().getResourceAsStream("/thc/native/stdio-host-abi.json")) {
            assertNotNull(input);
            var abi = (Map<String, Object>) Json.INSTANCE.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Windows", abi.get("system"));
            assertEquals("windows-managed-descriptors", abi.get("profile"));
            assertEquals(2L, abi.get("schema"));
            assertEquals(digest(root.resolve("src/main/c/windows-stdio-abi-probe.c")), abi.get("sourceSha256"));
        }
        for (var path : List.of("/thc/cbits/iconv.bc",
            "/thc/native/posix-stat-abi.json", "/thc/native/termios-abi.json"))
            assertNull(getClass().getResource(path), path);
    }

    @Test void publicDriverMatchesNativeCompletionInEveryBackendAndHandoffMode() throws Exception {
        var receipt = verifiedReceipt("build/windows-driver/provenance.json");
        assertEquals(4L, ((Number) receipt.get("runs")).longValue());
        var commands = (List<Map<String, Object>>) receipt.get("commands");
        assertEquals(7, commands.size());
        var rejected = commands.get(2);
        assertEquals(1L, rejected.get("expectedExit"));
        assertEquals(10L, rejected.get("timeoutSeconds"));
        var rejectedEnvironment = (Map<String, String>) rejected.get("environment");
        assertFalse(Files.exists(Path.of(rejectedEnvironment.get("GHC"))));
        var rejectedDiagnostics = ((Map<String, String>) receipt.get("artifactHashes")).keySet().stream()
            .filter(path -> Path.of(path).getFileName().toString().equals("missing-selected-ghc.stderr")).toList();
        assertEquals(1, rejectedDiagnostics.size());
        assertTrue(Files.readString(root.resolve(rejectedDiagnostics.getFirst()))
            .contains("selected GHC compiler not found"));
        var runs = commands.subList(3, commands.size());
        assertEquals(List.of(true, false, false, false), runs.stream()
            .map(command -> ((List<?>) command.get("argv")).contains("--verify-artifacts")).toList());
        var environments = new HashSet<List<String>>();
        for (var command : runs) {
            var environment = (Map<String, String>) command.get("environment");
            environments.add(List.of(environment.get("THC_BACKEND"), environment.get("JAVA_OPTS")));
        }
        assertEquals(Set.of(
            List.of("ast", "-Dthc.diagnostics=true -Dthc.handoffSlabs=false"),
            List.of("ast", "-Dthc.diagnostics=true -Dthc.handoffSlabs=true"),
            List.of("bytecode", "-Dthc.diagnostics=true -Dthc.handoffSlabs=false"),
            List.of("bytecode", "-Dthc.diagnostics=true -Dthc.handoffSlabs=true")), environments);
    }

    @Test void genuineGhcGeneratedEntryPreservesTheSelectedActionAndIoSignature() throws Exception {
        var receipt = verifiedReceipt("build/windows-driver/provenance.json");
        var manifests = (List<String>) receipt.get("supportManifests");
        var audited = (List<String>) receipt.get("auditedManifests");
        assertEquals(4, manifests.size());
        assertEquals(manifests.subList(0, 1), audited);
        for (var manifest : manifests) {
            var output = root.resolve(manifest).getParent().getParent();
            var original = (List<Map<String, Object>>) CoreCbdFixtures.read(output.resolve("core/Main.cbd")).get("bindings");
            var main = single(original, "id", "main:Main.main");
            assertEquals(0L, main.get("arity"), "The actual no-interface-pragmas main remains a thunk");
            var entry = single(original, "id", "main::Main.main");
            var bindings = new ArrayList<>(original);
            try (var coreFiles = Files.list(output.resolve("core"))) {
                for (var core : coreFiles.filter(path -> path.getFileName().toString().endsWith(".cbd")).toList())
                    if (!core.getFileName().toString().equals("Main.cbd"))
                        bindings.addAll((List<Map<String, Object>>) CoreCbdFixtures.read(core).get("bindings"));
            }
            var result = CoreRepresentations.ioMainResult(entry, bindings);
            assertEquals(2, result.getComponents().size());
            if (audited.contains(manifest)) {
                var audit = document(output.resolve("audit.json"));
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(entry.get("id")), audit.get("roots"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
            } else assertFalse(Files.exists(output.resolve("audit.json")), "Auditing must remain opt-in: " + manifest);
        }
    }

    @Test void nativeSourceArchiveAndRuntimeProjectionKeepExactModuleBytes() throws Exception {
        var receipt = verifiedReceipt("build/windows-driver/provenance.json");
        var manifests = (List<String>) receipt.get("supportManifests");
        assertEquals(4, manifests.size());
        var units = new ArrayList<Map<String, Object>>();
        for (var path : manifests) {
            var manifest = document(root.resolve(path));
            units.add(single((List<Map<String, Object>>) manifest.get("units"), "id", "ghc-internal"));
        }
        assertEquals(1, new HashSet<>(units).size(), "All CLI modes must reuse the same exact support publication");
        var unit = units.getFirst();
        for (var legacy : List.of("bundle", "json", "symbols")) assertFalse(unit.containsKey(legacy));
        var selected = (List<Map<String, Object>>) unit.get("modules");
        assertFalse(selected.isEmpty());
        var first = (Map<String, String>) selected.getFirst().get("compact");
        var publication = document(Path.of(first.get("path")).getParent().resolve("publication.json"));
        var published = (Map<String, Object>) publication.get("unit");
        for (var key : List.of("id", "modules", "targetLayout")) assertEquals(unit.get(key), published.get(key), key);
        var bundle = (Map<String, String>) publication.get("source");
        assertEquals(bundle.get("sha256"), digest(Path.of(bundle.get("path"))));
        try (var projected = new ZipFile(bundle.get("path"))) {
            var inputs = document(projected, "inplace-manifest.json");
            var complete = (Map<String, String>) inputs.get("completeSourceBundle");
            var excluded = (List<Map<String, String>>) inputs.get("archiveOnlyModules");
            assertEquals(List.of("GHC.Internal.Conc.Bound"), excluded.stream().map(module -> module.get("module")).toList());
            assertEquals(complete.get("sha256"), digest(Path.of(complete.get("path"))));
            try (var full = new ZipFile(complete.get("path"))) {
                var fullManifest = document(full, "manifest.json");
                var fullInputs = document(full, "inplace-manifest.json");
                assertEquals(fullInputs.getOrDefault("generatedSources", List.of()), inputs.get("generatedSources"));
                var component = (Map<String, Object>) fullInputs.get("component");
                assertEquals("installed-interface", component.get("kind"));
                var registration = (Map<String, Object>) component.get("registration");
                assertEquals("registered-owned-modules", registration.get("coverage"));
                var alias = single((List<Map<String, Object>>) document(root.resolve(manifests.getFirst())).get("units"),
                    "id", registration.get("registeredUnit"));
                assertEquals(List.of(), alias.get("modules"), "Keep the actual registered unit beside the wired owner");
                var interfaces = (List<Map<String, Object>>) registration.get("interfaces");
                var registeredNames = interfaces.stream().map(value -> (String) value.get("module")).sorted().toList();
                assertFalse(registeredNames.isEmpty());
                assertEquals(registeredNames.size(), new HashSet<>(registeredNames).size());
                assertTrue(registeredNames.contains("GHC.Internal.Prim"));
                var generated = (List<Map<String, Object>>) fullInputs.get("generatedCore");
                assertEquals(registeredNames, generated.stream().map(value -> (String) value.get("module")).sorted().toList());
                var nativeInputs = (List<Map<String, Object>>) fullInputs.get("nativeArtifacts");
                var cProducts = nativeInputs.stream().filter(value -> value.containsKey("objectSha256")).toList();
                for (var product : cProducts) assertTrue(((String) product.get("objectSha256")).matches("[0-9a-f]{64}"));
                assertFalse(cProducts.isEmpty(), "Installed GHC libraries retain their native C products");
                for (var input : nativeInputs) {
                    var path = Path.of((String) input.get("path"));
                    assertTrue(path.isAbsolute(), path.toString());
                    assertEquals(input.get("sha256"), digest(path), path.toString());
                }
                var fullModules = (List<Map<String, Object>>) fullManifest.get("modules");
                var projectedModules = new LinkedHashMap<Object, Map<String, Object>>();
                for (var module : (List<Map<String, Object>>) document(projected, "manifest.json").get("modules"))
                    projectedModules.put(module.get("name"), module);
                assertEquals(registeredNames, fullModules.stream().map(value -> (String) value.get("name")).sorted().toList());
                assertEquals(registeredNames.stream().filter(name -> !name.equals("GHC.Internal.Conc.Bound")).toList(),
                    selected.stream().map(value -> (String) value.get("name")).sorted().toList());
                assertEquals(document(projected, "manifest.json").get("modules"), publication.get("modules"));
                for (var module : selected) {
                    var original = single(fullModules, "name", module.get("name"));
                    assertEquals(original, projectedModules.get(module.get("name")), "original source projection retains its full record");
                    for (var field : original.entrySet()) if (!field.getKey().equals("index"))
                        assertEquals(field.getValue(), module.get(field.getKey()), field.getKey());
                    var compact = (Map<String, String>) module.get("compact");
                    assertEquals("thc-cbd-v1", compact.get("format"));
                    assertEquals(module.get("sha256"), compact.get("sha256"));
                    assertEquals(compact.get("sha256"), digest(Path.of(compact.get("path"))));
                    var member = (String) module.get("path");
                    // Source-rich genuine modules can each exceed the test heap.
                    // Compare every byte and the checked digest with bounded buffers.
                    var hash = MessageDigest.getInstance("SHA-256");
                    try (var expected = full.getInputStream(full.getEntry(member));
                         var actual = projected.getInputStream(projected.getEntry(member));
                         var compactInput = Files.newInputStream(Path.of(compact.get("path")))) {
                        var left = new byte[64 * 1024];
                        var right = new byte[left.length];
                        var publishedBytes = new byte[left.length];
                        while (true) {
                            int count = expected.readNBytes(left, 0, left.length);
                            assertEquals(count, actual.readNBytes(right, 0, right.length), member);
                            assertEquals(count, compactInput.readNBytes(publishedBytes, 0, publishedBytes.length), member);
                            if (count == 0) break;
                            assertEquals(-1, Arrays.mismatch(left, 0, count, right, 0, count), member);
                            assertEquals(-1, Arrays.mismatch(left, 0, count, publishedBytes, 0, count), member);
                            hash.update(right, 0, count);
                        }
                    }
                    assertEquals(module.get("sha256"), HexFormat.of().formatHex(hash.digest()));
                }
                var archived = single(fullModules, "name", "GHC.Internal.Conc.Bound");
                assertNull(projected.getEntry((String) archived.get("path")));
                var archivedFile = temporary.resolve("GHC.Internal.Conc.Bound.cbd");
                try (var input = full.getInputStream(full.getEntry((String) archived.get("path")))) {
                    Files.copy(input, archivedFile);
                }
                var module = CoreCbdFixtures.read(archivedFile);
                assertTrue(assertThrows(IllegalArgumentException.class, () -> CoreModules.INSTANCE.merge(List.of(module)))
                    .getMessage().contains("Unsupported foreign"));
            }
        }
    }

    @Test void relocatedBatchLauncherAcceptsPathsWithSpacesOnBothBackends() throws Exception {
        var destination = temporary.resolve("THC distribution with spaces");
        var installed = root.resolve("build/install/thc");
        try (var paths = Files.walk(installed)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(installed.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        var core = Files.createDirectories(temporary.resolve("Core inputs with spaces"));
        var modules = new ArrayList<String>();
        for (var module : List.of("THC.Prim.Test", "Fixtures"))
            modules.add(Files.copy(root.resolve("build/core/" + module + ".cbd"), core.resolve(module + ".cbd")).toAbsolutePath().toString());
        var evidence = Files.createDirectories(root.resolve("build/windows-launcher/" + UUID.randomUUID()));
        var oracle = Files.readAllLines(root.resolve("build/native/oracle.tsv")).stream().map(line -> line.split("\t", -1)).toList();
        for (var backend : List.of("ast", "bytecode")) {
            var output = evidence.resolve(backend + ".stdout");
            var errors = evidence.resolve(backend + ".stderr");
            var command = List.of("cmd.exe", "/d", "/c", "call", destination.resolve("bin/thc.bat").toAbsolutePath().toString(),
                String.join(",", modules), "main:Fixtures.sumLoop", "10");
            var builder = new ProcessBuilder(command).directory(temporary.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
            builder.environment().put("THC_BACKEND", backend);
            builder.environment().put("JAVA_OPTS", "-Dthc.handoffSlabs=" + System.getProperty("thc.handoffSlabs", "false"));
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("argv", command);
            receipt.put("backend", backend);
            receipt.put("handoffSlabs", System.getProperty("thc.handoffSlabs"));
            Files.writeString(evidence.resolve(backend + ".command.json"), Json.INSTANCE.stringify(receipt));
            var child = builder.start();
            try {
                assertTrue(child.waitFor(60, TimeUnit.SECONDS), "Launcher timed out: " + evidence);
                Files.writeString(evidence.resolve(backend + ".exit"), Integer.toString(child.exitValue()));
                assertEquals(0, child.exitValue(), Files.readString(errors));
                var expected = oracle.stream().filter(row -> row[0].equals("sumLoop") && row[1].equals("10")).toList();
                assertEquals(1, expected.size());
                assertEquals(expected.getFirst()[2], Files.readString(output).trim());
                var diagnostics = Files.readAllLines(errors).stream().filter(line -> line.startsWith("{")).toList().getLast();
                assertEquals(backend, ((Map<?, ?>) Json.INSTANCE.parse(diagnostics)).get("backend"));
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
        }
    }

}
