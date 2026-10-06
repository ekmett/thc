// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Real registered retained/thin interfaces, through the public package demand path. */
class CoreInterfaceLoadTest {
    private static final Path FIXTURE = Path.of("build/record-fields-demand");
    private static final String UNIT = "thc-record-demand-0.1";
    @TempDir Path directory;
    @AfterEach void releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private Context context(boolean processes) {
        return Main.executionContext(false, processes);
    }
    private String request(Path manifest, String module, String entry, String backend) {
        return CoreModules.request(List.of("@" + manifest.toAbsolutePath()), UNIT + ":" + module + "." + entry,
            true, false, backend, true, false, null, null, false);
    }
    private long conversions(Value entry) {
        return ((Number) object(Json.parse(entry.getMember("diagnostics").asString())).get("coreInterfaceModuleConversions")).longValue();
    }
    private List<long[]> nativeRepresentativeRows() throws Exception {
        var rows = Files.readAllLines(FIXTURE.resolve("native.tsv")).stream()
            .map(line -> Arrays.stream(line.strip().split("\\s+")).mapToLong(Long::parseLong).toArray()).toList();
        return List.of(rows.stream().filter(row -> row[0] < 0).findFirst().orElseThrow(),
            rows.stream().filter(row -> row[0] > 0).findFirst().orElseThrow());
    }
    // Commit witness: real declared GHC helper acquisition, demand and reuse.
    // The scheduled matrix additionally checks both backends and the cold strict wrapper.
    @Test void retainedRecordHelperExecutesAndReusesDemandedLibrary() throws Exception {
        var rows = nativeRepresentativeRows();
        try (var context = context(true)) {
            var entry = context.eval("thc", request(FIXTURE.resolve("packages.json"), "RecordFieldClient", "duplicateFields", "ast"));
            long before = conversions(entry);
            for (var row : rows) {
                assertEquals(4 * row[0] + 7, row[2], "native oracle agrees with the arithmetic model");
                assertEquals(row[2], entry.execute(row[0]).asLong());
            }
            long demanded = conversions(entry);
            assertTrue(demanded > before, "guest use demands the registered library");
            assertEquals(rows.getLast()[2], entry.execute(rows.getLast()[0]).asLong());
            assertEquals(demanded, conversions(entry), "repeated use reuses converted CBD readers");
        }
    }
    @Test void retainedRecordsConvertOnDemandAndReuseWithinTheirProgram() throws Exception {
        var manifest = FIXTURE.resolve("packages.json");
        var rows = nativeRepresentativeRows();
        for (String backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            var entry = context.eval("thc", request(manifest, "RecordFieldClient", "duplicateFields", backend));
            assertEquals(1L, conversions(entry), "only the selected module is converted before guest use");
            for (var row : rows) {
                assertEquals(4 * row[0] + 7, row[2], "native oracle agrees with the independent arithmetic model");
                assertEquals(row[2], entry.execute(row[0]).asLong());
            }
            assertEquals(2L, conversions(entry), "library is demanded; complete cold module stays unconverted");
            assertEquals(rows.getLast()[2], entry.execute(rows.getLast()[0]).asLong());
            assertEquals(2L, conversions(entry), "repeated use reuses context-owned converted CBD readers");
            // Demand the formerly cold module in an independent program. Its
            // real helper header must match the producer's four false facts too.
            var cold = context.eval("thc", request(manifest, "RecordFieldCold", "cold", backend));
            long beforeCold = conversions(cold), original = conversions(entry);
            assertEquals(19L, cold.execute(19).asLong());
            assertTrue(conversions(cold) > beforeCold, "the strict cold wrapper demands its dependencies");
            long coldDemanded = conversions(cold);
            assertEquals(19L, cold.execute(19).asLong());
            assertEquals(coldDemanded, conversions(cold), "cold program reuses its demanded readers");
            assertEquals(original, conversions(entry), "programs retain independent demand state");
        }
    }
    private Map<String,Object> manifest() throws Exception {
        return object(Json.parse(Files.readString(FIXTURE.resolve("packages.json"))));
    }
    private Path copyLibrary(Map<String,Object> manifest) throws Exception {
        var unit = objects(manifest.get("units")).getFirst();
        var library = objects(unit.get("modules")).stream().filter(item -> item.get("name").equals("RecordFieldLibrary")).findFirst().orElseThrow();
        var artifact = object(library.get("interface"));
        Path source = Path.of((String) artifact.get("path")), copy = directory.resolve("RecordFieldLibrary.hi");
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        for (var input : objects(manifest.get("interfaceInputs"))) if (input.get("path").equals(source.toString())) input.put("path", copy.toString());
        artifact.put("path", copy.toString());
        return copy;
    }
    @Test void changedOrMissingPublishedInterfaceFailsAtActualDemand() throws Exception {
        for (boolean missing : new boolean[]{false, true}) {
            var document = manifest(); Path library = copyLibrary(document), manifest = directory.resolve("packages.json");
            Files.writeString(manifest, Json.stringify(document));
            try (var context = context(true)) {
                var entry = context.eval("thc", request(manifest, "RecordFieldClient", "fieldAlias", "ast"));
                assertEquals(1L, conversions(entry));
                if (missing) Files.delete(library); else Files.write(library, new byte[]{0}, StandardOpenOption.APPEND);
                var failure = assertThrows(PolyglotException.class, () -> entry.execute(1));
                assertTrue(failure.getMessage().contains(library.toString()), failure.getMessage());
                assertTrue(failure.getMessage().contains("after publication"), failure.getMessage());
                assertEquals(1L, conversions(entry), "failed source never becomes an admitted CBD reader");
            }
        }
    }
    @Test void helperCapabilityIsExplicitAndContentBound() throws Exception {
        Path manifest = directory.resolve("packages.json"); var document = manifest();
        Files.writeString(manifest, Json.stringify(document));
        try (var context = context(false)) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request(manifest, "RecordFieldClient", "fieldAlias", "ast")));
            assertTrue(failure.getMessage().contains("explicit context process permission"), failure.getMessage());
        }
        String request = request(manifest, "RecordFieldClient", "fieldAlias", "ast");
        object(objects(document.get("units")).getFirst().get("interfaceSource")).put("helper", directory.resolve("unapproved-helper").toString());
        Files.writeString(manifest, Json.stringify(document));
        try (var context = context(true)) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request));
            assertTrue(failure.getMessage().contains("manifest"), failure.getMessage());
        }
        try (var sources = CoreUnitDirectory.read(manifest()).open(false)) {
            var failure = assertThrows(IllegalArgumentException.class, () -> sources.binding(UNIT + ":RecordFieldClient.fieldAlias"));
            assertTrue(failure.getMessage().contains("entered THC context"), failure.getMessage());
        }
    }
    // Scheduled provider controls protect real acquisition/content boundaries; no fixture booleans.
    @Test void linkedToolchainInputsRemainBoundAtDemand() throws Exception {
        var manifest = directory.resolve("packages.json");
        var output = directory.resolve("inventory.stdout"); var error = directory.resolve("inventory.stderr");
        var process = new ProcessBuilder(CoreCbdTestSupport.fixtures(), "record-fields-demand-inventory", "linked-toolchain", manifest.toString())
            .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "bounded linked toolchain inventory");
            assertEquals(0, process.exitValue(), Files.readString(output) + Files.readString(error));
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
        try (var context = context(true)) {
            var entry = context.eval("thc", request(manifest, "RecordFieldClient", "duplicateFields", "bytecode"));
            assertEquals(19L, entry.execute(3).asLong());
            var replacement = directory.resolve("changed-settings");
            Files.writeString(replacement, Files.readString(directory.resolve("settings")) + "\n");
            var settings = directory.resolve("lib/settings");
            Files.delete(settings);
            Files.createSymbolicLink(settings, replacement);
            var failure = assertThrows(PolyglotException.class,
                () -> context.eval("thc", request(manifest, "RecordFieldCold", "cold", "bytecode")));
            assertTrue(failure.getMessage().contains("after publication"), failure.getMessage());
            assertTrue(failure.getMessage().contains(settings.toString()), failure.getMessage());
        }
    }
    @Test void annotationEligibilityUsesValidatedEmptyProofs() throws Exception {
        var result = directory.resolve("annotations.json");
        var output = directory.resolve("annotations.stdout"); var error = directory.resolve("annotations.stderr");
        var process = new ProcessBuilder(CoreCbdTestSupport.fixtures(), "record-fields-demand-inventory", "annotations", result.toString())
            .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "bounded annotation eligibility controls");
            assertEquals(0, process.exitValue(), Files.readString(output) + Files.readString(error));
            assertEquals(Map.of("empty-provenance", true, "unannotated", true,
                "runtime-policy", false, "duplicate-proof", false, "named-proof", false,
                "wrong-owner", false, "foreign-product", false), object(Json.parse(Files.readString(result))));
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }
    @Test void thinRequestedUnitFailsRealAcquisitionActionably() throws Exception {
        var output = directory.resolve("thin.stdout"); var error = directory.resolve("thin.stderr");
        var process = new ProcessBuilder(CoreCbdTestSupport.fixtures(), "record-fields-demand-inventory", "thin", directory.resolve("thin-packages.json").toString())
            .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "bounded installed inventory probe");
            assertNotEquals(0, process.exitValue());
            String diagnostic = Files.readString(output) + Files.readString(error);
            assertTrue(diagnostic.contains("complete-interface-core unavailable"), diagnostic);
            assertTrue(diagnostic.contains(UNIT) && diagnostic.contains(".hi") && diagnostic.contains("-fwrite-if-simplified-core"), diagnostic);
            assertFalse(Files.exists(directory.resolve("thin-packages.json")));
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }
}
