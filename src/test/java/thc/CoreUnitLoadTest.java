// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.UnsupportedCore;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreUnitLoadTest {
    @TempDir Path directory;
    @AfterEach void releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final String boundary = "optimized-Core-after-Tidy-before-CorePrep";
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private List<Object> literal(int value) { return list("lit", "int", Integer.toString(value)); }
    private List<Object> call(String id, Object argument) { return list("app", list("var", id), list(argument), list(false)); }
    private List<Object> choice(Object zero, Object nonzero) { return list("case", list("var", "x"), "scrutinee", list(list("lit", list("int", "0"), List.of(), zero), list("default", null, List.of(), nonzero))); }
    private Map<String, Object> binding(String id, Object body) { return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "type", "Int# -> Int#", "lifted", true, "arity", 1, "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false)), body)); }
    private Map<String, Object> unit(String name, Object body) throws Exception { return unit(name, body, "", false); }
    /** Model writer for focused runtime tests, not the production publisher. */
    private Map<String, Object> unit(String name, Object body, String padding, boolean diagnostics) throws Exception {
        // Windows cannot replace mapped files. Evict only idle fixture leases.
        CoreFileMappings.shared.evictIdleBelow(directory);
        var id = "u" + name + ":" + name + ".entry";
        var ignored = diagnostics ? "\"sourceCore\":" + Json.stringify("x".repeat(1024 * 1024)) + "," : "";
        var prefix = "{" + ignored + "\"schema\":1,\"ghc\":\"9.14.1\",\"unit\":\"u" + name + "\",\"module\":\"" + name + "\",\"boundary\":\"" + boundary + "\",\"bindings\":";
        var expr = Json.stringify(list(binding(id, body), binding("u" + name + ":" + name + ".unused", list("unsupported", padding))));
        var notes = map("sourceFiles", list(map("id", "source", "content", "original source")));
        var suffix = ",\"constructors\":[]" + (diagnostics ? ",\"groups\":" + Json.stringify(Collections.nCopies(10000, id)) + ",\"sourceFiles\":" + Json.stringify(notes.get("sourceFiles")) + "}" : "}");
        var original = (prefix + expr + suffix).getBytes(UTF_8);
        var metadata = Json.stringify(map("schema", 1, "ghc", "9.14.1", "unit", "u" + name, "module", name, "boundary", boundary, "constructors", List.of())).getBytes(UTF_8);
        var source = diagnostics ? Json.stringify(notes).getBytes(UTF_8) : new byte[0];
        var output = new ByteArrayOutputStream(); output.write(original); output.write(10); output.write(metadata); output.write(10); output.write(source); var bytes = output.toByteArray();
        var json = directory.resolve(name + ".jsons"); var symbols = directory.resolve(name + ".symbols"); Files.write(json, bytes);
        int first = prefix.getBytes(UTF_8).length + 1, unused = first + Json.stringify(binding(id, body)).getBytes(UTF_8).length + 1;
        var rows = (id + " " + first + "\nu" + name + ":" + name + ".unused " + unused + "\n").getBytes(UTF_8); Files.write(symbols, rows);
        var module = map("name", name, "path", name + ".json", "sha256", hash(original), "boundary", boundary, "start", 0, "end", original.length,
            "bindingsStart", prefix.getBytes(UTF_8).length, "bindingsEnd", prefix.getBytes(UTF_8).length + expr.getBytes(UTF_8).length,
            "metadataStart", original.length + 1, "metadataEnd", original.length + 1 + metadata.length, "containsDelimitedControl", false, "registrationObligations", false, "mainAlias", false, "packageScalarDeclarations", false);
        if (diagnostics) { module.put("sourceMetadataStart", original.length + metadata.length + 2); module.put("sourceMetadataEnd", bytes.length); }
        return map("id", "u" + name, "depends", List.of(), "json", map("path", json.toString(), "sha256", hash(bytes)), "symbols", map("path", symbols.toString(), "sha256", hash(rows)), "modules", list(module));
    }
    private Path fixture() throws Exception { return fixture(false, false); }
    private Path fixture(boolean badB, boolean cycle) throws Exception {
        var a = unit("A", choice(literal(7), call("uB:B.entry", list("var", "x"))));
        var b = unit("B", badB ? list("unsupported", "demanded bad B") : cycle ? call("uA:A.entry", literal(0)) : choice(call("uC:C.entry", literal(0)), list("app", list("prim", "+#"), list(list("var", "x"), literal(1)), list(false, false))));
        var c = unit("C", list("unsupported", "untouched C"), "x".repeat(1024 * 1024), false); var manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(a, b, c)))); return manifest;
    }
    private String request(Path path, String backend) { return request(path, backend, false); }
    private String request(Path path, String backend, boolean async) { return CoreModules.request(List.of("@" + path), "uA:A.entry", true, false, backend, false, false, null, async, null, false); }
    private long count(Value value, String field) { return ((Number) ((Map<?, ?>) Json.parse(value.getMember("diagnostics").asString())).get(field)).longValue(); }
    private Context executionContext() { return Main.executionContext(false); }

    @Test void explicitFixedDigestDirectoryKeepsTheSameColdBindingPath() throws Exception {
        var text = fixture(); var document = (Map<?, ?>) Json.parse(Files.readString(text)); var units = new ArrayList<Map<String, Object>>();
        for (var item : (List<?>) document.get("units")) {
            var unit = (Map<?, ?>) item; var original = (Map<?, ?>) unit.get("symbols"); var path = Path.of((String) original.get("path")); var records = new ArrayList<byte[]>();
            for (var line : Files.readAllLines(path)) { int separator = line.lastIndexOf(' '); var digest = MessageDigest.getInstance("MD5").digest(line.substring(0, separator).getBytes(UTF_8)); records.add(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).put(digest).putLong(Long.parseLong(line.substring(separator + 1))).array()); }
            records.sort((a, b) -> Arrays.compareUnsigned(a, 0, 16, b, 0, 16)); var output = new ByteArrayOutputStream(); for (var record : records) output.write(record); var bytes = output.toByteArray();
            var binary = path.resolveSibling(path.getFileName() + ".md5"); Files.write(binary, bytes); units.add(with(unit, "symbols", map("path", binary.toString(), "sha256", hash(bytes), "format", CoreJsonSymbols.MD5_FORMAT)));
        }
        var manifest = directory.resolve("md5-packages.json"); Files.writeString(manifest, Json.stringify(with(document, "units", units))); Files.delete(directory.resolve("C.jsons")); Files.delete(directory.resolve("C.symbols.md5"));
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) try (var context = executionContext()) {
            var entry = context.eval("thc", request(manifest, backend, async)); assertEquals(1L, count(entry, "coreUnitSourceOpens")); assertEquals(7L, entry.execute(0).asLong()); assertEquals(1L, count(entry, "coreUnitSourceOpens")); assertEquals(2L, entry.execute(1).asLong()); assertEquals(2L, count(entry, "coreUnitSourceOpens")); long reads = count(entry, "coreUnitSourceByteReads"); assertEquals(3L, entry.execute(2).asLong()); assertEquals(reads, count(entry, "coreUnitSourceByteReads")); assertEquals(2L, count(entry, "coreUnitDirectoryOpens")); assertEquals(0L, count(entry, "coreUnitHashBytesScanned"));
        }
        var unknown = units.stream().map(it -> with(it, "symbols", with((Map<?, ?>) it.get("symbols"), "format", "unknown"))).toList(); assertThrows(IllegalArgumentException.class, () -> CoreUnitDirectory.read(with(document, "units", unknown)));
    }
    @Test void explicitLooseConsumersKeepPairedDependenciesCold() throws Exception {
        var manifest = fixture(); var plain = directory.resolve("Main.json"); Files.writeString(plain, Json.stringify(map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "Main", "boundary", boundary, "constructors", List.of(), "bindings", list(binding("main:Main.entry", choice(literal(41), call("uB:B.entry", list("var", "x"))))))));
        var nativeJson = directory.resolve("native.json"); var nativeIndex = directory.resolve("native.idx");
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/core/lazy-json-module.json"))) { Files.copy(in, nativeJson); } try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/core/lazy-json-module.idx"))) { Files.copy(in, nativeIndex); }
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) {
            for (var order : List.of(List.of(plain.toString(), "@" + manifest), List.of("@" + manifest, plain.toString()))) try (var context = executionContext()) {
                var entry = context.eval("thc", CoreModules.request(order, "main:Main.entry", true, false, backend, false, false, null, async, null, false));
                assertEquals(0L, count(entry, "coreUnitSourceOpens")); assertEquals(41L, entry.execute(0).asLong()); assertEquals(0L, count(entry, "coreUnitSourceOpens")); assertEquals(2L, entry.execute(1).asLong()); assertEquals(1L, count(entry, "coreUnitDecodedBindings")); long reads = count(entry, "coreUnitSourceByteReads"); assertEquals(3L, entry.execute(2).asLong()); assertEquals(reads, count(entry, "coreUnitSourceByteReads"));
            }
            try (var context = executionContext()) {
                // Actual native-produced sidecar, not a runtime reference-index fallback.
                var entry = context.eval("thc", CoreModules.request(List.of(nativeJson.toString(), "@" + manifest), "synthetic:LazyJson.entry", true, false, backend, false, false, null, async, Map.of(nativeJson.toString(), nativeIndex.toString()), false));
                assertEquals(0L, count(entry, "coreUnitSourceOpens")); assertEquals(1L, count(entry, "jsonBodyMaterializations")); assertEquals(7L, entry.execute(5).asLong()); assertEquals(2L, count(entry, "jsonBodyMaterializations")); assertEquals(1L, entry.execute(0).asLong()); assertEquals(3L, count(entry, "jsonBodyMaterializations")); assertEquals(0L, count(entry, "coreUnitSourceOpens"));
            }
        }
    }
    @Test void looseModuleCollisionsFailWithoutOpeningPackageSources() throws Exception {
        var manifest = fixture(); var loose = directory.resolve("duplicate.json"); Files.writeString(loose, Json.stringify(map("schema", 1, "ghc", "9.14.1", "unit", "uA", "module", "A", "boundary", boundary, "bindings", list(binding("uA:A.entry", literal(99)))))); Files.delete(directory.resolve("A.jsons"));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", CoreFormatTestSupport.request(List.of(loose.toString(), "@" + manifest), "uA:A.entry", backend, true, null, null, false)));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("Duplicate GHC module"), failure.getMessage()); context.enter(); try { assertTrue(Language.currentState(null).getCoreUnitPrograms().isEmpty()); } finally { context.leave(); }
        }
    }
    @Test void foreignHeadsUseExactSymbolMembershipWithoutDecodingShadowBodies() throws Exception {
        var state = map("kind", "void", "primReps", List.of(), "evaluated", true); var address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true); var closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
        var result = map("kind", "unknown", "primReps", List.of(), "evaluated", true, "aggregate", "unboxed-tuple", "components", list(state));
        var descriptor = map("schema", 1, "target", map("kind", "static", "symbol", "getProgArgv", "unit", "ghc-internal", "isFunction", true), "convention", "ccall", "safety", "unsafe", "arity", 3, "suppliedArity", 3,
            "argumentReps", List.of(address, address, state).stream().map(it -> with(it, "evaluated", false)).toList(), "resultRep", with(result, "evaluated", false));
        var nil = list("lit", "null-addr", "0", map("rep", address));
        for (boolean affinity : new boolean[]{false, true}) for (var head : List.of("uB:B.originalFCall", "uB:B.{__ffi_static_ccall_unsafe ghc-internal:getProgArgv :: Addr#\n -> Addr#\n -> State# RealWorld}", "uB:B.unused")) {
            // Only unused has a row; rejecting shadowing must not parse its body.
            boolean shadow = head.equals("uB:B.unused");
            var selectedResult = affinity ? with(result, "primReps", list("Int32Rep"), "components", list(state, map("kind", "long", "primReps", list("Int32Rep"), "evaluated", true))) : result;
            var selectedDescriptor = affinity ? with(descriptor, "target", map("kind", "static", "symbol", "thc_cpu_affinity_v1_support", "unit", "ghc-internal", "isFunction", true), "arity", 1, "suppliedArity", 1, "argumentReps", list(with(state, "evaluated", false)), "resultRep", with(selectedResult, "evaluated", false)) : descriptor;
            var args = new ArrayList<Object>(); if (!affinity) { args.add(nil); args.add(nil); } args.add(list("void", map("rep", state)));
            var foreign = list("app", list("var", head, map("rep", closure)), args, Collections.nCopies(args.size(), false), false, false, map("rep", selectedResult, "foreignCall", selectedDescriptor));
            var body = list("case", foreign, "state-tuple", list(list("default", null, List.of(), literal(7))), map("rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true), "binder", map("id", "state-tuple", "name", "state-tuple", "lifted", false, "rep", selectedResult)));
            var units = list(unit("A", body), unit("B", literal(0))); var manifest = directory.resolve("foreign-" + shadow + ".json"); Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", units)));
            Files.delete(directory.resolve("B.jsons")); // Membership needs only B's directory.
            for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                if (shadow) { var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request(manifest, backend))); assertTrue(Objects.toString(failure.getMessage(), "").contains("unresolved"), failure.getMessage()); }
                else { var entry = context.eval("thc", request(manifest, backend)); assertEquals(1L, count(entry, "coreUnitSourceOpens")); assertEquals(head.indexOf('\n') >= 0 ? 1L : 2L, count(entry, "coreUnitDirectoryOpens"), "An unrepresentable foreign declaration cannot require a directory probe"); assertEquals(1L, count(entry, "coreUnitDecodedBindings")); if (affinity) assertEquals(7L, entry.execute(0).asLong()); }
            }
        }
    }
    @Test void projectedMetadataSkipsOriginalPrettyCoreGroupsAndDisabledSourceTables() throws Exception {
        var unit = unit("A", literal(7), "", true); var document = (Map<?, ?>) Json.parse(Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(unit)))); var index = Objects.requireNonNull(CoreUnitDirectory.read(document)); assertEquals(1, index.getModules().size());
        for (boolean notes : new boolean[]{false, true}) try (var sources = index.open(false, notes)) { var metadata = sources.metadata(index.getModules().getFirst()); assertFalse(metadata.containsKey("sourceCore")); assertFalse(metadata.containsKey("groups")); assertEquals(notes, metadata.containsKey("sourceFiles")); var counters = sources.counters(); assertEquals(1, counters.size()); assertEquals(0L, counters.getFirst().statistics().getDecodedBindings()); assertTrue(counters.getFirst().statistics().getMetadataBytes() < 1024); assertTrue(counters.getFirst().statistics().getSourceByteReads() < 1024); }
        try (var sources = index.open(true, false)) { sources.verifyModule(index.getModules().getFirst()); assertEquals(1, sources.counters().size()); assertTrue(sources.counters().getFirst().statistics().getVerifiedModuleBytes() > 1024 * 1024); long reads = sources.counters().getFirst().statistics().getSourceByteReads(); sources.verifyModule(index.getModules().getFirst()); assertEquals(reads, sources.counters().getFirst().statistics().getSourceByteReads()); }
    }
    @Test void modulelessLegacyUnitsKeepIdentityWithoutOpeningTheirZip() throws Exception {
        var manifest = fixture(); var original = (Map<?, ?>) Json.parse(Files.readString(manifest)); var empty = map("id", "reexports-only", "depends", list("uA"), "bundle", map("path", directory.resolve("not-present.zip").toString(), "sha256", "a".repeat(64)), "modules", List.of());
        var noBundle = map("id", "empty-dependency", "depends", list("reexports-only"), "modules", List.of()); var units = new ArrayList<Object>(list(empty, noBundle)); units.addAll((List<?>) original.get("units")); var updated = with(original, "units", units); Files.writeString(manifest, Json.stringify(updated));
        var index = Objects.requireNonNull(CoreUnitDirectory.read((Map<?, ?>) Json.parse(Json.stringify(updated)))); assertEquals(list("uA"), index.getUnits().getFirst().getDepends()); assertNotNull(index.getUnits().getFirst().getLegacyBundle()); assertEquals("empty-dependency", index.getUnits().get(1).getId()); assertNull(index.getUnits().get(1).getJson());
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) { var entry = context.eval("thc", request(manifest, backend)); assertEquals(7L, entry.execute(0).asLong()); assertEquals(1L, count(entry, "coreUnitSourceOpens")); }
        var malformedUnits = new ArrayList<Object>(list(with(empty, "json", empty.get("bundle")))); malformedUnits.addAll((List<?>) original.get("units")); var malformed = with(updated, "units", malformedUnits); assertThrows(IllegalArgumentException.class, () -> CoreUnitDirectory.read((Map<?, ?>) Json.parse(Json.stringify(malformed))));
    }
    @Test void coldReferencesDoNotOpenOtherUnitsAndFirstDemandReusesTheBinding() throws Exception {
        var manifest = fixture(); for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) try (var context = executionContext()) {
            var entry = context.eval("thc", request(manifest, backend, async)); assertEquals(1L, count(entry, "coreUnitSourceOpens")); assertEquals(1L, count(entry, "coreUnitDecodedModules")); assertEquals(1L, count(entry, "coreUnitDecodedBindings")); assertEquals(1L, count(entry, "loweredRootCount")); assertEquals(7L, entry.execute(0).asLong()); assertEquals(1L, count(entry, "coreUnitSourceOpens")); assertEquals(2L, entry.execute(1).asLong()); assertEquals(2L, count(entry, "coreUnitSourceOpens")); assertEquals(2L, count(entry, "coreUnitDecodedBindings")); long reads = count(entry, "coreUnitSourceByteReads"); assertEquals(3L, entry.execute(2).asLong()); assertEquals(reads, count(entry, "coreUnitSourceByteReads")); assertEquals(0L, count(entry, "coreUnitHashBytesScanned"));
        }
    }
    @Test void missingOrBadColdUnitFailsOnlyAtDemandAndDoesNotTouchThirdUnit() throws Exception {
        var manifest = fixture(true, false); Files.delete(directory.resolve("C.jsons")); Files.delete(directory.resolve("C.symbols"));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) { var entry = context.eval("thc", request(manifest, backend)); assertEquals(7L, entry.execute(0).asLong()); var failure = assertThrows(PolyglotException.class, () -> entry.execute(1)); assertTrue(Objects.toString(failure.getMessage(), "").toLowerCase(Locale.ROOT).contains("unsupported")); long decoded = count(entry, "coreUnitDecodedBindings"); assertThrows(PolyglotException.class, () -> entry.execute(1)); assertEquals(decoded, count(entry, "coreUnitDecodedBindings")); assertEquals(2L, count(entry, "coreUnitSourceOpens")); }
    }
    private long statistic(CoreUnitProgram program, String name) { return ((Number) Objects.requireNonNull(program.diagnostics().get(name))).longValue(); }
    @Test void demandedBytecodeFailureNamesOnlyItsOwnerAndKeepsOtherUnitsUnopened() throws Exception {
        var manifest = fixture(true, false); Files.delete(directory.resolve("C.jsons")); Files.delete(directory.resolve("C.symbols"));
        for (boolean async : new boolean[]{false, true}) try (var context = executionContext()) {
            @SuppressWarnings("unchecked") var input = (Map<String, Object>) Json.parse(request(manifest, "bytecode", async)); var units = Objects.requireNonNull(CoreModules.unitDirectory(input)); context.initialize("thc"); context.enter(); var owner = Language.currentState(null); owner.getThreads().enterCurrent(null, false, true, null);
            try { var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                try (var program = new CoreUnitProgram(language, units, input, "uA:A.entry", "bytecode", async, owner)) { program.entryValue("uA:A.entry"); assertEquals(1L, statistic(program, "coreUnitSourceOpens")); assertEquals(1L, statistic(program, "coreUnitDecodedBindings")); var failure = assertThrows(UnsupportedCore.class, () -> program.entryValue("uB:B.entry")); assertEquals(list("While preparing Core binding uB:B.entry"), Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList()); assertSame(failure, assertThrows(UnsupportedCore.class, () -> program.entryValue("uB:B.entry"))); assertEquals(1, failure.getSuppressed().length); assertEquals(2L, statistic(program, "coreUnitSourceOpens")); assertEquals(2L, statistic(program, "coreUnitDirectoryOpens")); assertEquals(2L, statistic(program, "coreUnitDecodedBindings")); }
            } finally { owner.getThreads().leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @Test void mutualFunctionReferencesPrepareWithoutRecursingThroughColdBodies() throws Exception {
        var manifest = fixture(false, true); for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) { var entry = context.eval("thc", request(manifest, backend)); assertEquals(7L, entry.execute(1).asLong()); assertEquals(2L, count(entry, "coreUnitDecodedBindings")); }
    }
    @Test void sharedMappingsKeepDecodedContextStateSeparateAndSurviveClosingEitherContext() throws Exception {
        var manifest = fixture(); try (var engine = Engine.create()) { var source = Source.newBuilder("thc", request(manifest, "bytecode"), "unit-model").cached(true).build(); var first = Context.newBuilder("thc").engine(engine).build(); var second = Context.newBuilder("thc").engine(engine).build();
            try { var a = first.eval(source); var b = second.eval(source); assertEquals(2L, count(a, "coreUnitPhysicalMappingOpens")); assertEquals(0L, count(a, "coreUnitMappingCacheHits")); assertEquals(0L, count(b, "coreUnitPhysicalMappingOpens")); assertEquals(2L, count(b, "coreUnitMappingCacheHits")); assertEquals(2L, a.execute(1).asLong()); assertEquals(1L, count(b, "coreUnitDecodedBindings")); first.close(); assertEquals(3L, b.execute(2).asLong()); assertEquals(2L, count(b, "coreUnitDecodedBindings")); } finally { first.close(); second.close(); }
        }
        try (var context = executionContext()) { var entry = context.eval("thc", request(manifest, "bytecode")); assertEquals(0L, count(entry, "coreUnitPhysicalMappingOpens"), "closed contexts release leases into bounded idle reuse"); assertEquals(2L, count(entry, "coreUnitMappingCacheHits")); assertEquals(1L, count(entry, "coreUnitDecodedBindings"), "decoded state is not process-shared"); assertEquals(7L, entry.execute(0).asLong()); }
    }
}
