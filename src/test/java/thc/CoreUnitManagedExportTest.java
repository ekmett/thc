// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

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
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private byte[] verified(String relative) throws Exception {
        var file = fixtures.resolve(relative); var receipt = document(Files.readString(fixtures.resolve("manifest.json"))); var bytes = Files.readAllBytes(file);
        assertEquals(((Map<?, ?>) receipt.get("artifactHashes")).get(root.relativize(file).toString()), hash(bytes)); return bytes;
    }
    /** Verified genuine GHC CBD, with registration mutations for negative controls. */
    private Path manifest(String label, boolean corrupt) throws Exception {
        verified("typed-foreign-exports/managed.cbd");
        var source = CoreCbdFixtures.read(fixtures.resolve("typed-foreign-exports/managed.cbd"));
        var admitted = corrupt ? with(source, "staticForeignExportRegistration",
            with(without((Map<?, ?>) source.get("staticForeignExportRegistration"), "roots", "wordBits", "expectedForeign", "expectedExports"),
                "status", "unclassified", "reason", "Unclassified registration test")) : source;
        var record = CoreCbdFixtures.module(directory.resolve(label + ".cbd"), admitted);
        var active = map("id", unit, "depends", List.of(), "modules", list(record));
        var cold = map("id", "cold", "depends", List.of(), "modules", list(with(record,
            "name", "Unused", "registrationObligations", false,
            "compact", map("path", directory.resolve("absent.cbd").toString(), "sha256", "0".repeat(64), "format", CoreCompactFormat.NAME))));
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
            var counts = program(context).diagnostics(); assertEquals(1L, counts.get("coreCompactModuleOpens"));
            assertTrue((Long) counts.get("coreCompactDirectoryBytesRead") > 0);
            assertEquals(0L, counts.get("coreCompactHashBytesScanned")); assertEquals(0L, counts.get("unsupportedTraps"));
        }
        assertFalse(Files.exists(directory.resolve("absent.cbd")));
    }
    @Test void failedRegistrationClosesSourcesAndDoesNotPublishOrPoisonRetry() throws Exception {
        var bad = manifest("bad", true); var good = manifest("good", false);
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            assertThrows(RuntimeException.class, () -> Main.loadManagedExports(context, List.of("@" + bad), backend));
            assertTrue(context.getBindings("thc").getMemberKeys().isEmpty()); context.enter();
            try { assertTrue(Language.currentState(null).getCoreUnitPrograms().isEmpty()); assertEquals(0, Language.currentState(null).getForeignRoots().size()); }
            finally { context.leave(); }
            assertEquals(1, CoreFileMappings.shared.evictIdleBelow(directory.resolve("bad.cbd")), "Failed registration must release its source lease before retry");
            var symbols = exports(Main.loadManagedExports(context, List.of("@" + good), backend)); var next = symbols.getMember("thc_next");
            assertThrows(RuntimeException.class, () -> next.execute("bad")); assertEquals(1, next.execute(1).asInt());
            assertThrows(RuntimeException.class, () -> Main.loadManagedExports(context, List.of("@" + good), backend)); assertEquals(2, symbols.getMember("thc_next_alias").execute(1).asInt());
        }
    }
    @Test void explicitVerificationStillChecksTheOriginalModuleAndCbdMetadata() throws Exception {
        var good = manifest("verified", false);
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var symbols = exports(Main.loadManagedExports(context, List.of("@" + good), backend, true, true));
            assertEquals(42, symbols.getMember("thc_add_one").execute(41).asInt()); var counts = program(context).diagnostics();
            assertTrue((Long) counts.get("coreCompactHashBytesScanned") > 0);
            assertTrue((Long) counts.get("coreCompactVerifiedStoredBytes") > 0); assertEquals(1L, counts.get("coreCompactModuleOpens"));
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
