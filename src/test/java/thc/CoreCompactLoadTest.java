// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreCompactLoadTest {
    @TempDir Path directory;
    @AfterEach void releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final String boundary = "optimized-Core-after-Tidy-before-CorePrep";
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    /** Small independently encoded model; genuine Haskell converter controls
     * are separate. Only the expression forms used by these tests are emitted. */
    private final class Model {
        final String name;
        final ByteArrayOutputStream strings = new ByteArrayOutputStream();
        final Bytes data = new Bytes();
        final List<Key> keys = new ArrayList<>();
        Map<String,Object> backendPolicy;
        record Key(String id, long offset) {}
        Model(String name) { this.name = name; }
        final class Bytes extends ByteArrayOutputStream {
            void u(long value) {
                long remaining = value;
                do { int b = (int) (remaining & 127); remaining >>>= 7;
                    write(b | (remaining == 0 ? 0 : 128)); } while (remaining != 0);
            }
            void text(String value) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                u(strings.size()); u(bytes.length); strings.writeBytes(bytes);
            }
            void binder(long ordinal) { u(ordinal); writeBytes(new byte[]{0, 2, 0, 2, 0, 0, 0}); }
            void expr(int tag) { write(tag); writeBytes(new byte[10]); }
            void literal(long value) { expr(2); write(0); u((value << 1) ^ (value >> 63)); }
            void variable() { expr(0); write(1); u(0); }
            void call(String id) {
                expr(5); expr(0); write(0); text(id);
                u(1); variable(); u(1); writeBytes(new byte[]{2, 0, 0, 0});
            }
            void choice(Consumer<Bytes> zero, Consumer<Bytes> nonzero) {
                expr(7); variable(); u(1); write(2); binder(1); u(2);
                write(2); write(0); u(0); u(0); zero.accept(this);
                write(0); u(0); nonzero.accept(this);
            }
        }
        void function(String label, Consumer<Bytes> body) {
            String id = "unit:" + name + "." + label;
            keys.add(new Key(id, data.size()));
            data.write(0); data.text(id); data.writeBytes(new byte[]{0, 2, 1, 1}); data.writeBytes(new byte[6]);
            data.expr(3); data.u(1); data.binder(0); body.accept(data);
        }
        Map<String, Object> write(String headerName, boolean badProvenance) throws Exception {
            var facts = new Bytes();
            facts.u(1); facts.text("9.14.1"); facts.text("unit"); facts.text(headerName); facts.text(boundary);
            facts.writeBytes(new byte[6]); // provided/layout/constructors/foreign/bridge/bridgeUnit.
            facts.writeBytes(badProvenance ? new byte[]{2} : new byte[8]);
            if (backendPolicy != null) {
                facts.write(2);
                facts.write(backendPolicy.get("default") == null ? 0 : backendPolicy.get("default").equals("ast") ? 1 : 2);
                var policies = new TreeMap<String,String>((Map<String,String>) backendPolicy.get("bindings"));
                facts.u(policies.size());
                policies.forEach((id, backend) -> { facts.text(id); facts.write(backend.equals("ast") ? 1 : 2); });
            }
            var rows = new ArrayList<byte[]>();
            for (var key : keys) rows.add(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .put(MessageDigest.getInstance("MD5").digest(key.id().getBytes(StandardCharsets.UTF_8))).putLong(key.offset()).array());
            rows.sort((a, b) -> Arrays.compareUnsigned(a, 0, 16, b, 0, 16));
            var symbols = ByteBuffer.allocate(rows.size() * 24);
            for (var row : rows) symbols.put(row);
            var bytes = CoreCbdTestSupport.archive(CoreCbdTestSupport.header(facts.toByteArray(), strings.toByteArray(), rows.size(), 0, 7),
                List.of(data.toByteArray(), strings.toByteArray(), new byte[]{-1}, new byte[]{-1}, new byte[]{-1}, symbols.array()), Set.of(), false);
            var path = directory.resolve(name + ".cbd"); Files.write(path, bytes);
            return map("name", name, "boundary", boundary, "sha256", "a".repeat(64),
                "compact", map("path", path.toString(), "sha256", hash(bytes), "format", CoreCompactFormat.NAME),
                "containsDelimitedControl", false, "registrationObligations", false, "mainAlias", false, "packageScalarDeclarations", false);
        }
    }
    private Path fixture() throws Exception { return fixture(false, false, false, false); }
    private Path fixture(boolean badB, boolean recursive, boolean wrongIdentity, boolean badProvenance) throws Exception {
        return fixture(badB, recursive, wrongIdentity, badProvenance, null, null);
    }
    private Path fixture(boolean badB, boolean recursive, boolean wrongIdentity, boolean badProvenance,
            Map<String,Object> aPolicy, Map<String,Object> bPolicy) throws Exception {
        var a = new Model("A");
        a.backendPolicy = aPolicy;
        a.function("entry", out -> out.choice(zero -> zero.literal(7), nonzero -> nonzero.call("unit:B.entry")));
        a.function("untouched", out -> out.write(255));
        var aModule = a.write("A", false);
        var b = new Model("B");
        b.backendPolicy = bPolicy;
        b.function("entry", out -> {
            if (badB) out.write(255);
            else if (recursive) {
                out.expr(5); out.expr(0); out.write(0); out.text("unit:A.entry"); out.u(1); out.literal(0);
                out.u(1); out.writeBytes(new byte[]{2, 0, 0, 0});
            } else {
                out.expr(5); out.expr(1); out.text("+#"); out.u(2); out.variable(); out.literal(1);
                out.u(2); out.writeBytes(new byte[]{2, 0, 2, 0, 0, 0});
            }
        });
        var bModule = b.write(wrongIdentity ? "Wrong" : "B", badProvenance);
        var c = new Model("C"); c.function("entry", out -> out.literal(99));
        var cModule = c.write("C", false); Files.delete(directory.resolve("C.cbd"));
        var path = directory.resolve("packages.json");
        Files.writeString(path, Json.stringify(map("format", "thc-core-packages", "schema", 1,
            "ghc", "9.14.1", "units", List.of(map("id", "unit", "depends", List.of(), "modules", List.of(aModule, bModule, cModule))))));
        return path;
    }
    private String request(Path path, String backend, boolean verify) {
        return CoreFormatTestSupport.request(List.of("@" + path), "unit:A.entry", backend, false, false, verify);
    }
    private long count(Value entry, String key) { return ((Number) document(entry.getMember("diagnostics").asString()).get(key)).longValue(); }
    @Test void intrinsicCaseResultSurvivesCompactLoadingAndSelection() throws Exception {
        for (boolean conflicting : List.of(false, true)) {
            var model = new Model(conflicting ? "Conflict" : "Intrinsic");
            model.function("entry", out -> {
                out.write(7); // Case: UNKNOWN outer rep, separate exact intrinsic resultRep.
                out.writeBytes(new byte[]{2, 0, 9, 1, 0, 0, 0, 0, 0, 0, 2, 0});
                out.writeBytes(new byte[]{2, 0, (byte)(conflicting ? 2 : 0), 2, 1,
                    (byte)(conflicting ? 11 : 0), 0, 0, 0, 0, 0, 0, 2, 1});
                out.writeBytes(new byte[8]);
                out.variable(); out.u(1); out.write(2); out.binder(1); out.u(1);
                out.write(0); out.u(0); out.literal(47);
            });
            var module = model.write(model.name, false);
            var manifest = directory.resolve(model.name + ".json");
            Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1,
                "ghc", "9.14.1", "units", list(map("id", "unit", "depends", list(), "modules", list(module))))));
            String entryName = "unit:" + model.name + ".entry";
            var detached = CoreModules.selectedModules(document(NativeCache.request(List.of("@" + manifest), entryName)), entryName);
            var detachedModule = (Map<?,?>)((List<?>)detached.get("modules")).getFirst();
            var binding = (Map<?,?>)((List<?>)detachedModule.get("bindings")).getFirst();
            var body = (List<?>)((List<?>)binding.get("expr")).get(2);
            assertEquals(thc.runtime.CoreKind.UNKNOWN, thc.runtime.CoreRepresentations.expression(body).getKind());
            assertEquals(conflicting ? thc.runtime.CoreKind.DOUBLE : thc.runtime.CoreKind.LONG,
                thc.runtime.CoreRepresentations.caseResult(body).getKind());
            for (String backend : List.of("ast", "bytecode")) {
                String request = CoreFormatTestSupport.request(List.of("@" + manifest), entryName, backend, false, false, false);
                try (var context = Main.executionContext(false)) {
                    if (conflicting) assertThrows(PolyglotException.class, () -> context.eval("thc", request).execute(0L));
                    else assertEquals(47L, context.eval("thc", request).execute(0L).asLong());
                }
            }
        }
    }
    @Test void cachedSelectionDetachesOnlyReachableCompactBodiesBeforeReadersClose() throws Exception {
        var path = fixture();
        String selected = NativeCache.request(List.of("@" + path), "unit:A.entry");
        var request = CoreModules.selectedModules(document(selected), "unit:A.entry");
        assertFalse(request.containsKey("packageManifest"));
        assertEquals(false, request.get("verifyArtifacts"));
        var ids = ((List<Map<String,Object>>) request.get("modules")).stream()
            .flatMap(module -> ((List<Map<String,Object>>) module.get("bindings")).stream()).map(binding -> binding.get("id")).toList();
        assertFalse(ids.contains("unit:A.untouched")); assertTrue(ids.contains("unit:B.entry"));
        assertTrue(assertThrows(RuntimeException.class,
            () -> CoreModules.selectedModules(document(NativeCache.request(List.of("@" + path), "unit:A.entry", true)), "unit:A.entry"))
            .getMessage().contains("Invalid compact Core expression tag"));
        CoreFileMappings.shared.evictIdleBelow(directory);
        Files.delete(directory.resolve("A.cbd"));
        Files.delete(directory.resolve("B.cbd"));
        Files.delete(path);
        // This fixture's case and minimal binders test detachment, not the
        // admitted reusable AST family; the genuine exporter exercises both.
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(null); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var linked = CoreModules.merge((List<Map<String,Object>>) request.get("modules"));
                var program = new thc.runtime.Program(language, linked);
                assertEquals(6L, thc.runtime.Calls.target(program.hostEntryTarget(1),
                    new Object[]{program.entryValue("unit:A.entry"), new Object[]{5L}}));
            } finally { owner.getThreads().leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @Test void selectedBindingAndCrossModuleDemandLeaveColdBodiesFilesAndDebugUnread() throws Exception {
        var path = fixture();
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var entry = context.eval("thc", request(path, backend, false));
            assertEquals(1L, count(entry, "coreCompactModuleOpens")); assertEquals(1L, count(entry, "coreCompactDecodedBindings"));
            assertEquals(7L, entry.execute(0).asLong()); assertEquals(1L, count(entry, "coreCompactModuleOpens"));
            assertEquals(6L, entry.execute(5).asLong()); assertEquals(2L, count(entry, "coreCompactModuleOpens"));
            assertEquals(2L, count(entry, "coreCompactDecodedBindings")); assertEquals(2L, count(entry, "coreCompactDecodedModules"));
            long decoded = count(entry, "coreCompactDataBytesRead");
            assertEquals(10L, entry.execute(9).asLong()); assertEquals(decoded, count(entry, "coreCompactDataBytesRead"));
            assertEquals(0L, count(entry, "coreCompactDebugBytesRead")); assertEquals(0L, count(entry, "coreCompactHashBytesScanned"));
        }
    }
    @Test void mutuallyReferencingModulesRegisterBeforeFollowingRuntimeDemand() throws Exception {
        var path = fixture(false, true, false, false);
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var entry = context.eval("thc", request(path, backend, false));
            assertEquals(7L, entry.execute(1).asLong()); assertEquals(2L, count(entry, "coreCompactDecodedBindings"));
        }
    }
    @Test void backendPoliciesSelectColdBindingsAndKeepUnusedBodiesCold() throws Exception {
        for (String fallback : List.of("ast", "bytecode")) for (boolean override : List.of(false, true)) {
            CoreFileMappings.shared.evictIdleBelow(directory);
            String other = fallback.equals("ast") ? "bytecode" : "ast";
            var aPolicy = map("default", other, "bindings", override ? map("unit:A.entry", fallback) : map());
            var bPolicy = map("default", override ? other : fallback, "bindings", map());
            var path = fixture(false, false, false, false, aPolicy, bPolicy);
            try (var context = Main.executionContext(false)) {
                var entry = context.eval("thc", CoreFormatTestSupport.request(List.of("@" + path), "unit:A.entry", fallback, false, false, false));
                String initial = override ? fallback : other;
                assertEquals(initial, document(entry.getMember("diagnostics").asString()).get("backend"));
                assertEquals(initial.equals("bytecode"), entry.hasMember("bytecode"));
                assertEquals(7L, entry.execute(0).asLong());
                assertEquals(1L, count(entry, "coreCompactDecodedBindings"));
                assertEquals(6L, entry.execute(5).asLong());
                assertEquals("mixed", document(entry.getMember("diagnostics").asString()).get("backend"));
                assertTrue(entry.hasMember("bytecode"));
                assertEquals(2L, count(entry, "coreCompactDecodedBindings"));
                long read = count(entry, "coreCompactDataBytesRead");
                assertEquals(10L, entry.execute(9).asLong());
                assertEquals(read, count(entry, "coreCompactDataBytesRead"));
            }
        }
    }
    @Test void reusablePreparationRejectsReachableBytecodePolicy() throws Exception {
        var path = fixture(false, false, false, false, null, map("default", "bytecode", "bindings", map()));
        try (var context = Main.executionContext(false)) {
            var failure = assertThrows(RuntimeException.class,
                () -> context.eval("thc", NativeCache.request(List.of("@" + path), "unit:A.entry")));
            assertTrue(failure.getMessage().contains("Reusable AST code cannot honor bytecode backend policy"), failure.getMessage());
        }
    }
    @Test void sourceEnabledOrdinaryLoadAndExecutionDoNotReadOptionalDebugTables() throws Exception {
        var path = fixture();
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var entry = context.eval("thc", CoreFormatTestSupport.request(List.of("@" + path), "unit:A.entry", backend, true, false, false));
            assertEquals(0L, count(entry, "coreCompactDebugBytesRead"));
            assertEquals(7L, entry.execute(0).asLong()); assertEquals(6L, entry.execute(5).asLong());
            assertEquals(0L, count(entry, "coreCompactDebugBytesRead")); assertEquals(2L, count(entry, "coreCompactModuleOpens"));
        }
    }
    @Test void malformedSelectedBodyIdentityAndTruncatedProvenanceRejectOnDemand() throws Exception {
        for (int variant = 0; variant <= 2; variant++) {
            CoreFileMappings.shared.evictIdleBelow(directory);
            var path = fixture(variant == 0, false, variant == 1, variant == 2);
            for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                var entry = context.eval("thc", request(path, backend, false));
                assertEquals(7L, entry.execute(0).asLong());
                var failure = assertThrows(RuntimeException.class, () -> entry.execute(1));
                var expected = List.of("Invalid compact Core expression tag", "identity differs from package directory",
                    "Truncated compact Core record").get(variant);
                assertTrue(Objects.toString(failure.getMessage(), "").contains(expected), failure.getMessage());
                assertEquals(7L, entry.execute(0).asLong());
            }
        }
    }
    @Test void concurrentLooseSignatureSelectionOwnsBlobDecodingAlongsideDemand() throws Exception {
        var paths = new ArrayList<String>();
        for (String name : List.of("Left", "Right", "Demand")) {
            var model = new Model(name);
            model.function("entry", out -> {
                if (name.equals("Demand")) out.literal(7);
                else {
                    out.expr(5); out.expr(1); out.text("indexWord8OffAddr#"); out.u(2);
                    out.expr(2); out.write(12);
                    var bytes = name.getBytes(StandardCharsets.UTF_8); out.u(bytes.length); out.writeBytes(bytes);
                    out.literal(0); out.u(2); out.writeBytes(new byte[]{2, 0, 2, 0, 0, 0});
                }
            });
            model.function("unused", out -> out.write(255));
            model.write(name, false); paths.add(directory.resolve(name + ".cbd").toString());
        }
        var input = document(CoreFormatTestSupport.request(paths, "unit:Demand.entry", "ast", false, false, false));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(null); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                try (var program = new CoreUnitProgram(language, CoreModules.unitDirectory(input), input,
                        "unit:Demand.entry", "ast", false, owner);
                     var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
                    var loaded = new ProgramValue(program, language, owner, false);
                    var start = new java.util.concurrent.CountDownLatch(1);
                    var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
                    for (String name : List.of("Left", "Right", "Demand")) futures.add(pool.submit(() -> {
                        start.await(); context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                        try {
                            if (name.equals("Demand")) {
                                return Objects.equals(7L, thc.runtime.Calls.target(program.hostEntryTarget(1),
                                    new Object[]{program.entryValue("unit:Demand.entry"), new Object[]{5L}}));
                            }
                            var interop = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
                            var selected = interop.invokeMember(loaded, "entry", "unit:" + name + ".entry");
                            return interop.asLong(interop.execute(selected, 5L)) == name.charAt(0);
                        } finally { owner.getThreads().leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
                    }));
                    start.countDown();
                    for (var future : futures) assertTrue(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
                }
            } finally { owner.getThreads().leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @Test void looseConsumersDetachSelectedBodiesAndStrictlyRejectMalformedUnselectedBodies() throws Exception {
        fixture();
        var paths = List.of(directory.resolve("A.cbd").toString(), directory.resolve("B.cbd").toString());
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var entry = context.eval("thc", CoreFormatTestSupport.request(paths, "unit:A.entry", backend, false, false, false));
            assertEquals(7L, entry.execute(0).asLong());
            assertEquals(6L, entry.execute(5).asLong());
            var failure = assertThrows(RuntimeException.class, () -> context.eval("thc",
                CoreFormatTestSupport.request(paths, "unit:A.entry", backend, false, false, true)));
            assertTrue(failure.getMessage().contains("Invalid compact Core expression tag"), failure.getMessage());
        }
        var request = CoreModules.selectedModules(document(NativeCache.request(paths, "unit:A.entry")), "unit:A.entry");
        var modules = (List<Map<String,Object>>) request.get("modules");
        var bindings = modules.stream().flatMap(m -> ((List<Map<String,Object>>) m.get("bindings")).stream()).toList();
        assertFalse(bindings.stream().anyMatch(b -> b.get("id").equals("unit:A.untouched")));
        CoreFileMappings.shared.evictIdleBelow(directory);
        Files.delete(directory.resolve("A.cbd")); Files.delete(directory.resolve("B.cbd"));
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            var owner = Language.currentState(null); owner.getThreads().enterCurrent(null, false, true, null);
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new thc.runtime.Program(language, CoreModules.merge(modules));
                assertEquals(6L, thc.runtime.Calls.target(program.hostEntryTarget(1),
                    new Object[]{program.entryValue("unit:A.entry"), new Object[]{5L}}));
            } finally { owner.getThreads().leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @Test void explicitVerificationWalksColdBindingsOnlyWhenRequested() throws Exception {
        var path = fixture();
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            // A.untouched deliberately has an unknown expression tag.
            var failure = assertThrows(RuntimeException.class, () -> context.eval("thc", request(path, backend, true)));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("Invalid compact Core expression tag"), failure.getMessage());
        }
    }
    @Test void sharedEngineContextsReuseOnlyImmutableMappingsAndClosingOnePreservesOther() throws Exception {
        var path = fixture();
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).build()) {
            var first = Context.newBuilder("thc").engine(engine).build();
            try (var second = Context.newBuilder("thc").engine(engine).build()) {
                try {
                    var one = first.eval("thc", request(path, "ast", false));
                    var two = second.eval("thc", request(path, "bytecode", false));
                    assertEquals("ast", document(one.getMember("diagnostics").asString()).get("backend"));
                    assertEquals("bytecode", document(two.getMember("diagnostics").asString()).get("backend"));
                    assertEquals(1L, count(one, "coreCompactPhysicalMappingOpens")); assertEquals(1L, count(two, "coreCompactMappingCacheHits"));
                    assertEquals(1L, count(two, "coreCompactDecodedBindings")); first.close();
                    assertEquals(4L, two.execute(3).asLong()); assertEquals(2L, count(two, "coreCompactDecodedBindings"));
                } finally { first.close(); }
            }
        }
    }
}
