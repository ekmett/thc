// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.RuntimeFault;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreUnitManagedExportTest {
    @TempDir Path directory;
    @AfterEach void releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path fixtures = root.resolve("build/interface-core");
    private final String unit = "thc-interface-fixture-0.1", module = "ForeignExportManaged";
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private byte[] verified(String relative) throws Exception {
        var file = fixtures.resolve(relative); var receipt = document(Files.readString(fixtures.resolve("manifest.json"))); var bytes = Files.readAllBytes(file);
        assertEquals(((Map<?, ?>) receipt.get("artifactHashes")).get(root.relativize(file).toString()), hash(bytes)); return bytes;
    }
    private record Row(String id, long offset) {}
    /** Test-only packaging of unchanged genuine GHC output; the runtime consumes only the text directory. */
    private Path manifest(String label, boolean corrupt) throws Exception {
        var original = verified("typed-foreign-exports/managed.json"); var source = document(new String(original, StandardCharsets.UTF_8));
        var fields = Set.of("schema", "ghc", "unit", "module", "boundary", "providedModules", "constructors", "foreign", "foreignLink",
            "staticForeignImportStubs", "staticForeignImports", "staticForeignExports", "staticForeignExportRegistration", "packageScalarLink",
            "packageNativeLink", "packageNativeArchive", "foreignExceptionBridge", "foreignExceptionBridgeUnit");
        var metadata = new LinkedHashMap<String, Object>(); source.forEach((key, value) -> { if (fields.contains(key)) metadata.put(key, value); });
        var admitted = corrupt ? with(metadata, "staticForeignExportRegistration", with((Map<?, ?>) metadata.get("staticForeignExportRegistration"), "status", "unclassified")) : metadata;
        var encoded = bytes(Json.stringify(admitted)); var notes = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> { if (key.equals("sourceFiles") || key.equals("sourceSpans")) notes.put(key, value); });
        var encodedNotes = notes.isEmpty() ? new byte[0] : bytes(Json.stringify(notes));
        var out = new ByteArrayOutputStream(); out.writeBytes(original); out.write(10); out.writeBytes(encoded);
        if (!notes.isEmpty()) { out.write(10); out.writeBytes(encodedNotes); } var bytes = out.toByteArray();
        var json = directory.resolve(label + ".jsons"); var symbols = directory.resolve(label + ".symbols"); Map<String, Object> record;
        try (var index = CoreJsonIndex.fromBytes(original)) {
            var bindings = Objects.requireNonNull(index.getRoot().member("bindings")); var rows = new ArrayList<Row>();
            for (var span : bindings.elements()) rows.add(new Row((String) Objects.requireNonNull(span.member("id")).decode(), span.getStart()));
            rows.sort((a, b) -> Arrays.compareUnsigned(bytes(a.id()), bytes(b.id()))); var text = new StringBuilder();
            for (var row : rows) text.append(row.id()).append(' ').append(row.offset()).append('\n'); Files.writeString(symbols, text);
            boolean mainAlias = false;
            for (var binding : (List<?>) source.get("bindings")) if (Objects.equals(((Map<?, ?>) binding).get("id"), "main::" + module + ".main")) mainAlias = true;
            var imports = source.get("staticForeignImports") instanceof Map<?, ?> proof ? proof.get("imports") : null;
            record = map("name", module, "path", "core/" + module + ".json", "sha256", hash(original), "boundary", source.get("boundary"),
                "start", 0, "end", original.length, "bindingsStart", bindings.getStart(), "bindingsEnd", bindings.getEndExclusive(),
                "metadataStart", original.length + 1, "metadataEnd", original.length + 1 + encoded.length,
                "containsDelimitedControl", thc.runtime.DelimitedControl.INSTANCE.contains(source.get("bindings")),
                "registrationObligations", CoreForeignArtifacts.hasRegistrationObligations(source), "mainAlias", mainAlias,
                "packageScalarDeclarations", imports instanceof List<?> values && !values.isEmpty());
            if (!notes.isEmpty()) record.putAll(map("sourceMetadataStart", original.length + encoded.length + 2, "sourceMetadataEnd", bytes.length));
        }
        Files.write(json, bytes);
        var active = map("id", unit, "depends", List.of(), "json", map("path", json.toString(), "sha256", hash(bytes)),
            "symbols", map("path", symbols.toString(), "sha256", hash(Files.readAllBytes(symbols))), "modules", list(record));
        var cold = map("id", "cold", "depends", List.of(), "json", map("path", directory.resolve("absent.jsons").toString(), "sha256", "0".repeat(64)),
            "symbols", map("path", directory.resolve("absent.symbols").toString(), "sha256", "0".repeat(64)),
            "modules", list(with(record, "name", "Unused", "registrationObligations", false)));
        return Files.writeString(directory.resolve(label + "-packages.json"), Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(active, cold))));
    }
    private Value exports(Value value) { return value.getMember(unit).getMember(module); }
    private CoreUnitProgram program(Context context) {
        context.enter();
        try { var programs = Language.currentState(null).getCoreUnitPrograms(); assertEquals(1, programs.size()); return programs.getFirst(); }
        finally { context.leave(); }
    }
    @Test void pairedGenuineExportsInvokeAndShareCafsWithoutOpeningUnrelatedUnits() throws Exception {
        var path = manifest("valid", false); var nativeRows = new String(verified("logs/managed-export-native-oracle.stdout"), StandardCharsets.UTF_8).trim().lines().toList();
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var symbols = exports(Main.loadManagedExports(context, List.of("@" + path), backend));
            assertEquals(Integer.parseInt(nativeRows.get(0)), symbols.getMember("thc_add_one").execute(-19).asInt());
            assertEquals(Integer.parseInt(nativeRows.get(0)), symbols.getMember("thc_add_alias").execute(-19).asInt());
            assertEquals(Integer.parseInt(nativeRows.get(3)), symbols.getMember("thc_constant").execute().asInt());
            assertEquals(Integer.parseInt(nativeRows.get(4)), symbols.getMember("thc_next").execute(3).asInt());
            assertEquals(Integer.parseInt(nativeRows.get(5)), symbols.getMember("thc_next_alias").execute(4).asInt());
            var counts = program(context).diagnostics(); assertEquals(1L, counts.get("coreUnitSourceOpens")); assertEquals(1L, counts.get("coreUnitDirectoryOpens"));
            assertEquals(0L, counts.get("coreUnitHashBytesScanned")); assertEquals(0L, counts.get("unsupportedTraps"));
        }
        assertFalse(Files.exists(directory.resolve("absent.jsons")));
    }
    @Test void failedRegistrationClosesSourcesAndDoesNotPublishOrPoisonRetry() throws Exception {
        var bad = manifest("bad", true); var good = manifest("good", false);
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            assertThrows(RuntimeException.class, () -> Main.loadManagedExports(context, List.of("@" + bad), backend));
            assertTrue(context.getBindings("thc").getMemberKeys().isEmpty()); context.enter();
            try { assertTrue(Language.currentState(null).getCoreUnitPrograms().isEmpty()); assertEquals(0, Language.currentState(null).getForeignRoots().size()); }
            finally { context.leave(); }
            assertEquals(1, CoreFileMappings.shared.evictIdleBelow(directory.resolve("bad.jsons")), "Failed registration must release its source lease before retry");
            var symbols = exports(Main.loadManagedExports(context, List.of("@" + good), backend)); var next = symbols.getMember("thc_next");
            assertThrows(RuntimeException.class, () -> next.execute("bad")); assertEquals(1, next.execute(1).asInt());
            assertThrows(RuntimeException.class, () -> Main.loadManagedExports(context, List.of("@" + good), backend)); assertEquals(2, symbols.getMember("thc_next_alias").execute(1).asInt());
        }
    }
    @Test void explicitVerificationStillChecksTheOriginalModuleAndProjectedMetadata() throws Exception {
        var good = manifest("verified", false);
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var symbols = exports(Main.loadManagedExports(context, List.of("@" + good), backend, true, true));
            assertEquals(42, symbols.getMember("thc_add_one").execute(41).asInt()); var counts = program(context).diagnostics();
            assertTrue((Long) counts.get("coreUnitHashBytesScanned") > 0); assertTrue((Long) counts.get("coreUnitVerifiedModuleBytes") > 0); assertEquals(1L, counts.get("coreUnitSourceOpens"));
        }
    }
    @Test void sharedEngineLoadKeepsRegistrationAndMemoizedStateContextOwned() throws Exception {
        var path = manifest("shared", false);
        for (var backend : List.of("ast", "bytecode")) try (var engine = Engine.newBuilder().build()) {
            var request = Source.newBuilder("thc", CoreModules.managedExportRequest(List.of("@" + path), backend, true, false), "paired-managed-exports").cached(true).buildLiteral();
            var first = Context.newBuilder("thc").engine(engine).build(); var second = Context.newBuilder("thc").engine(engine).build();
            try {
                var a = exports(first.eval(request)).getMember("thc_next"); var b = exports(second.eval(request)).getMember("thc_next_alias");
                assertEquals(3, a.execute(3).asInt()); assertEquals(4, b.execute(4).asInt()); assertNotSame(program(first), program(second));
                first.enter(); ManagedExportNamespace scope;
                try { scope = Language.currentState(null).getManagedExports().getScope(); } finally { first.leave(); }
                second.enter(); try { assertThrows(RuntimeFault.class, scope::hasMembers); } finally { second.leave(); }
                first.close(); assertThrows(RuntimeException.class, () -> a.execute(1)); assertEquals(5, b.execute(1).asInt());
            } finally { first.close(); second.close(); }
        }
    }
}
