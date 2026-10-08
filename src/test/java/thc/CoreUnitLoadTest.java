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
    private List<Object> literal(int value) { return list("lit", "int", Integer.toString(value), map()); }
    private List<Object> call(String id, Object argument) { return list("app", list("var", id, map()), list(argument), list(false), false, false, map()); }
    private List<Object> choice(Object zero, Object nonzero) { return list("case", list("var", "x", map()), "scrutinee", list(list("lit", list("int", "0"), List.of(), zero, map("binders", List.of())), list("default", null, List.of(), nonzero, map("binders", List.of()))), map()); }
    private Map<String, Object> binding(String id, Object body) { return map("id", id, "name", id.substring(id.lastIndexOf('.') + 1), "type", "Int# -> Int#", "lifted", true, "arity", 1, "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int#", "lifted", false, "coercion", false)), body, map())); }
    private Map<String, Object> unit(String name, Object body) throws Exception { return unit(name, body, "", false); }
    /** Model writer for focused runtime tests, not the production publisher. */
    private Map<String, Object> unit(String name, Object body, String padding, boolean diagnostics) throws Exception {
        return unit(name, body, padding, diagnostics, Map.of(), List.of());
    }
    private Map<String, Object> unit(String name, Object body, String padding, boolean diagnostics,
            Map<String,Object> extra, List<String> dependencies) throws Exception {
        CoreFileMappings.shared.evictIdleBelow(directory);
        var id = "u" + name + ":" + name + ".entry";
        var original = map("schema", 1, "ghc", "9.14.1", "unit", "u" + name, "module", name,
            "boundary", boundary, "constructors", List.of(),
            "bindings", list(binding(id, body), binding("u" + name + ":" + name + ".unused", list("unsupported", padding, map()))));
        original.putAll(extra);
        var module = CoreCbdFixtures.module(directory.resolve(name + ".cbd"), original);
        return map("id", "u" + name, "depends", dependencies, "modules", list(module));
    }

    private Map<String,Object> nativeAddressUnit(String name, String kind) throws Exception {
        return nativeAddressUnit(name, kind, false);
    }
    private Map<String,Object> nativeAddressUnit(String name, String kind, boolean corrupt) throws Exception {
        String component = hash(name.getBytes(UTF_8)), entry = "thc_native_" + component + "_0";
        var c = directory.resolve(name + ".c"); var bc = directory.resolve(name + ".bc");
        Files.writeString(c, (kind.equals("function-addr") ? "long original_label(void) { return 43; }\n"
            : "long original_label = 43;\n") + "void *" + entry + "(void) { return &original_label; }\n");
        var compiler = new ProcessBuilder(System.getenv().getOrDefault("THC_CLANG", "clang"),
            "--target=x86_64-unknown-linux-gnu", "-O1", "-emit-llvm", "-c", c.toString(), "-o", bc.toString()).redirectErrorStream(true).start();
        var output = new String(compiler.getInputStream().readAllBytes(), UTF_8); assertEquals(0, compiler.waitFor(), output);
        var bytes = Files.readAllBytes(bc);
        var type = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Ptr", "occurrence", "Ptr", "namespace", "type"), "arguments", List.of());
        var declaration = map("binder", map("unit", "u" + name, "module", name, "occurrence", "label", "namespace", "value"),
            "symbol", "original_label", "header", null, "isFunction", kind.equals("function-addr"), "convention", "ccall",
            "declaredType", type, "normalizedType", type, "normalizationRole", "representational", "callback", null);
        var product = map("schema", 1, "execution", "not-linked", "files", List.of(), "stubs", null);
        var proof = map("schema", 2, "scope", "retained-static-import-products", "execution", "not-linked", "profile", "ghc-9.14.1-thc-only-static-c-imports-v1",
            "unit", "u" + name, "module", name, "status", "verified", "wordBits", 64, "expectedForeign", product,
            "imports", List.of(), "expectedCalls", List.of(), "addresses", list(declaration));
        var link = map("schema", 1, "format", "llvm-bitcode", "profile", "thc-package-c-ffi-v1", "unit", "u" + name,
            "target", "x86_64-unknown-linux-gnu", "componentSha256", component, "bitcodeSha256", corrupt ? "0".repeat(64) : hash(bytes), "bitcodeHex", HexFormat.of().formatHex(bytes),
            "dataSymbols", list(entry), "abi", list(map("symbol", "original_label", "entry", entry, "arguments", List.of(), "result", "AddrRep", "convention", "ccall", "safety", "unsafe")));
        return unit(name, list("unsupported", "address owner body must remain cold", map()), "", false,
            map("staticForeignImports", proof, "packageNativeLink", link), List.of());
    }
    @Test void inlinedNativeLabelsAdmitOnlyDeclaredDependencyProvenance() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
        var index = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
        for (String kind : List.of("function-addr", "data-addr")) {
            var label = list("lit", kind, "original_label", map("rep", address));
            var body = kind.equals("function-addr")
                ? list("app", list("prim", "neAddr#", map()), list(label, list("lit", "null-addr", "0", map("rep", address))), list(false, false), false, false, map())
                : list("app", list("prim", "indexIntOffAddr#", map()), list(label, list("lit", "int", "0", map("rep", index))), list(false, false), false, false, map("rep", index));
            var nativeUnit = nativeAddressUnit("Native", kind);
            var unrelated = nativeAddressUnit("Unrelated", kind);
            Files.delete(directory.resolve("Unrelated.cbd"));
            var facade = map("id", "facade", "depends", list("uNative"), "modules", List.of());
            var a = unit("A", body, "", false, Map.of(), List.of("facade"));
            var manifest = directory.resolve("label-packages.json");
            Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(a, facade, nativeUnit, unrelated))));
            for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
                var entry = context.eval("thc", request(manifest, backend));
                assertEquals(kind.equals("function-addr") ? 1L : 43L, entry.execute(0).asLong());
                assertEquals(1L, count(entry, "coreCompactDecodedBindings"), "declaration lookup cannot demand the owner's body");
                assertEquals(2L, count(entry, "coreCompactModuleOpens"), "unrelated native metadata remains unopened");
                long reads = count(entry, "coreCompactDataBytesRead");
                assertEquals(kind.equals("function-addr") ? 1L : 43L, entry.execute(1).asLong());
                assertEquals(reads, count(entry, "coreCompactDataBytesRead"));
            }
        }
    }
    @Test void inlinedNativeLabelsDoNotBypassDependencyAmbiguityOrComponentProof() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var address = map("kind", "address", "primReps", list("AddrRep"), "evaluated", true);
        var label = list("lit", "function-addr", "original_label", map("rep", address));
        var body = list("app", list("prim", "neAddr#", map()), list(label, list("lit", "null-addr", "0", map("rep", address))), list(false, false), false, false, map());
        var nativeUnit = nativeAddressUnit("Native", "function-addr");
        var other = nativeAddressUnit("Other", "function-addr");
        var corrupt = nativeAddressUnit("Corrupt", "function-addr", true);
        var dependencies = List.of(List.<String>of(), List.of("uNative", "uOther"), List.of("uCorrupt"));
        var expected = List.of("Unlinked native data label", "Ambiguous native address declaration", "bitcode digest");
        for (int i = 0; i < dependencies.size(); i++) {
            var a = unit("A", body, "", false, Map.of(), dependencies.get(i));
            var manifest = directory.resolve("bad-label-packages.json");
            Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(a, nativeUnit, other, corrupt))));
            for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
                // Native labels resolve at their first guest use.
                var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request(manifest, backend)).execute(0));
                assertTrue(failure.getMessage().contains(expected.get(i)), failure.getMessage());
            }
        }
    }
    private Path fixture() throws Exception { return fixture(false, false); }
    private Path fixture(boolean badB, boolean cycle) throws Exception {
        var a = unit("A", choice(literal(7), call("uB:B.entry", list("var", "x", map()))));
        var b = unit("B", badB ? list("unsupported", "demanded bad B", map()) : cycle ? call("uA:A.entry", literal(0)) : choice(call("uC:C.entry", literal(0)), list("app", list("prim", "+#", map()), list(list("var", "x", map()), literal(1)), list(false, false), false, false, map())));
        var c = unit("C", list("unsupported", "untouched C", map()), "x".repeat(1024 * 1024), false); var manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(a, b, c)))); return manifest;
    }
    private String request(Path path, String backend) { return request(path, backend, false); }
    private String request(Path path, String backend, boolean async) { return CoreModules.request(List.of("@" + path), "uA:A.entry", true, false, backend, false, false, null, async, false); }
    private long count(Value value, String field) { return ((Number) ((Map<?, ?>) Json.parse(value.getMember("diagnostics").asString())).get(field)).longValue(); }
    private Context executionContext() { return Main.executionContext(false); }

    @Test void explicitLooseConsumersKeepPackageDependenciesCold() throws Exception {
        var manifest = fixture(); var plain = directory.resolve("Main.cbd"); CoreCbdFixtures.write(plain, map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "Main", "boundary", boundary, "constructors", List.of(), "bindings", list(binding("main:Main.entry", choice(literal(41), call("uB:B.entry", list("var", "x", map())))))));
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) {
            for (var order : List.of(List.of(plain.toString(), "@" + manifest), List.of("@" + manifest, plain.toString()))) try (var context = executionContext()) {
                var entry = context.eval("thc", CoreModules.request(order, "main:Main.entry", true, false, backend, false, false, null, async, false));
                assertEquals(1L, count(entry, "coreCompactModuleOpens"), "only the explicit CBD consumer is open"); assertEquals(41L, entry.execute(0).asLong()); assertEquals(1L, count(entry, "coreCompactModuleOpens")); assertEquals(2L, entry.execute(1).asLong()); assertEquals(1L, count(entry, "coreCompactDecodedBindings")); long reads = count(entry, "coreCompactDataBytesRead"); assertEquals(3L, entry.execute(2).asLong()); assertEquals(reads, count(entry, "coreCompactDataBytesRead"));
            }
        }
    }
    @Test void looseModuleCollisionsFailWithoutOpeningPackageSources() throws Exception {
        var manifest = fixture(); var loose = directory.resolve("duplicate.cbd"); CoreCbdFixtures.write(loose, map("schema", 1, "ghc", "9.14.1", "unit", "uA", "module", "A", "boundary", boundary, "constructors", List.of(), "bindings", list(binding("uA:A.entry", literal(99))))); Files.delete(directory.resolve("A.cbd"));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", CoreFormatTestSupport.request(List.of(loose.toString(), "@" + manifest), "uA:A.entry", backend, true, null, false)));
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
            var body = list("case", foreign, "state-tuple", list(list("default", null, List.of(), literal(7), map("binders", List.of()))), map("rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true), "binder", map("id", "state-tuple", "name", "state-tuple", "lifted", false, "rep", selectedResult)));
            var units = list(unit("A", body), unit("B", literal(0))); var manifest = directory.resolve("foreign-" + shadow + ".json"); Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", units)));
            // CBD membership reads B's symbol slab, not its body or debug tables.
            for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                if (shadow) { var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request(manifest, backend))); assertTrue(Objects.toString(failure.getMessage(), "").contains("unresolved"), failure.getMessage()); }
                else { var entry = context.eval("thc", request(manifest, backend)); assertEquals(2L, count(entry, "coreCompactModuleOpens"), "CBD fingerprints represent newline-containing foreign IDs too"); assertEquals(1L, count(entry, "coreCompactDecodedBindings")); assertEquals(0L, count(entry, "coreCompactDebugBytesRead")); if (affinity) assertEquals(7L, entry.execute(0).asLong()); }
            }
        }
    }
    @Test void projectedMetadataLeavesBodiesAndDebugTablesUnread() throws Exception {
        var unit = unit("A", literal(7), "x".repeat(1024 * 1024), false);
        var index = CoreUnitDirectory.read(document(Json.stringify(map("format", "thc-core-packages",
            "schema", 1, "ghc", "9.14.1", "units", list(unit)))));
        try (var sources = index.open(false, false)) {
            var metadata = sources.metadata(index.getModules().getFirst());
            assertFalse(metadata.containsKey("sourceCore")); assertFalse(metadata.containsKey("groups"));
            var counts = sources.compactCounters().getFirst().statistics();
            assertEquals(0L, counts.decodedBindings()); assertEquals(0L, counts.dataBytesRead());
            assertEquals(0L, counts.debugBytesRead());
        }
    }
    @Test void completeVerificationDoesNotRetainColdBindingRecords() throws Exception {
        var module = CoreCbdFixtures.module(directory.resolve("V.cbd"), map("schema", 1, "ghc", "9.14.1",
            "unit", "uV", "module", "V", "boundary", boundary, "constructors", List.of(),
            "bindings", list(binding("uV:V.entry", literal(7)), binding("uV:V.cold", literal(11)))));
        var index = CoreUnitDirectory.read(map("schema", 1L, "ghc", "9.14.1", "format", "thc-core-packages",
            "units", list(map("id", "uV", "depends", List.of(), "modules", list(module)))));
        try (var sources = index.open(true, false)) {
            var record = index.getModules().getFirst();
            sources.metadata(record);
            var counts = sources.compactCounters().getFirst();
            assertEquals(2L, counts.decodedBindings);
            var selected = sources.binding("uV:V.entry");
            assertEquals(3L, counts.decodedBindings);
            assertSame(selected, sources.binding("uV:V.entry"));
            sources.verifyModule(record);
            assertEquals(3L, counts.decodedBindings);
        }
    }
    @Test void modulelessUnitsKeepDependencyIdentityWithoutStorage() throws Exception {
        var manifest = fixture(); var original = document(Files.readString(manifest));
        var empty = map("id", "reexports-only", "depends", list("uA"), "modules", List.of());
        var units = new ArrayList<Object>(list(empty)); units.addAll((List<?>) original.get("units"));
        Files.writeString(manifest, Json.stringify(with(original, "units", units)));
        var index = CoreUnitDirectory.read(document(Files.readString(manifest)));
        assertEquals(list("uA"), index.getUnits().getFirst().getDepends());
        assertTrue(index.getUnits().getFirst().getModules().isEmpty());
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
            var entry = context.eval("thc", request(manifest, backend));
            assertEquals(7L, entry.execute(0).asLong()); assertEquals(1L, count(entry, "coreCompactModuleOpens"));
        }
    }
    @Test void coldReferencesDoNotOpenOtherUnitsAndFirstDemandReusesTheBinding() throws Exception {
        var manifest = fixture(); for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) try (var context = executionContext()) {
            var entry = context.eval("thc", request(manifest, backend, async)); assertEquals(1L, count(entry, "coreCompactModuleOpens")); assertEquals(1L, count(entry, "coreCompactDecodedModules")); assertEquals(1L, count(entry, "coreCompactDecodedBindings")); assertEquals(7L, entry.execute(0).asLong()); assertEquals(1L, count(entry, "coreCompactModuleOpens")); assertEquals(2L, entry.execute(1).asLong()); assertEquals(2L, count(entry, "coreCompactModuleOpens")); assertEquals(2L, count(entry, "coreCompactDecodedBindings")); long reads = count(entry, "coreCompactDataBytesRead"); assertEquals(3L, entry.execute(2).asLong()); assertEquals(reads, count(entry, "coreCompactDataBytesRead")); assertEquals(0L, count(entry, "coreCompactHashBytesScanned"));
        }
    }
    @Test void missingOrBadColdUnitFailsOnlyAtDemandAndDoesNotTouchThirdUnit() throws Exception {
        var manifest = fixture(true, false); Files.delete(directory.resolve("C.cbd"));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) { var entry = context.eval("thc", request(manifest, backend)); assertEquals(7L, entry.execute(0).asLong()); var failure = assertThrows(PolyglotException.class, () -> entry.execute(1)); assertTrue(Objects.toString(failure.getMessage(), "").toLowerCase(Locale.ROOT).contains("unsupported")); long decoded = count(entry, "coreCompactDecodedBindings"); assertThrows(PolyglotException.class, () -> entry.execute(1)); assertEquals(decoded, count(entry, "coreCompactDecodedBindings")); assertEquals(2L, count(entry, "coreCompactModuleOpens")); }
    }
    private long statistic(CoreUnitProgram program, String name) { return ((Number) Objects.requireNonNull(program.diagnostics().get(name))).longValue(); }
    @Test void demandedBytecodeFailureNamesOnlyItsOwnerAndKeepsOtherUnitsUnopened() throws Exception {
        var manifest = fixture(true, false); Files.delete(directory.resolve("C.cbd"));
        for (boolean async : new boolean[]{false, true}) try (var context = executionContext()) {
            @SuppressWarnings("unchecked") var input = (Map<String, Object>) Json.parse(request(manifest, "bytecode", async)); var units = Objects.requireNonNull(CoreModules.unitDirectory(input)); context.initialize("thc"); context.enter(); var owner = Language.currentState(null); owner.getThreads().enterCurrent(null, false, true, null);
            try { var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                try (var program = new CoreUnitProgram(language, units, input, "uA:A.entry", "bytecode", async, owner)) { program.entryValue("uA:A.entry"); assertEquals(1L, statistic(program, "coreCompactModuleOpens")); assertEquals(1L, statistic(program, "coreCompactDecodedBindings")); var failure = assertThrows(UnsupportedCore.class, () -> program.entryValue("uB:B.entry")); assertEquals(list("While preparing Core binding uB:B.entry"), Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList()); assertSame(failure, assertThrows(UnsupportedCore.class, () -> program.entryValue("uB:B.entry"))); assertEquals(1, failure.getSuppressed().length); assertEquals(2L, statistic(program, "coreCompactModuleOpens")); assertEquals(2L, statistic(program, "coreCompactModuleOpens")); assertEquals(2L, statistic(program, "coreCompactDecodedBindings")); }
            } finally { owner.getThreads().leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
        }
    }
    @Test void mutualFunctionReferencesPrepareWithoutRecursingThroughColdBodies() throws Exception {
        var manifest = fixture(false, true); for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) { var entry = context.eval("thc", request(manifest, backend)); assertEquals(7L, entry.execute(1).asLong()); assertEquals(2L, count(entry, "coreCompactDecodedBindings")); }
    }
    @Test void explicitProgramAndItsEntryViewsBecomeUnusableWhenTheirContextCloses() throws Exception {
        var paths = List.of("@" + fixture());
        try (var context = executionContext()) {
            var program = Main.loadProgram(context, paths, true, "bytecode", false, false);
            var entry = Main.loadEntry(program, "uA:A.entry");
            assertEquals(2L, entry.execute(1).asLong());
            context.close();
            assertThrows(IllegalStateException.class, () -> Main.loadEntry(program, "uB:B.entry"));
            assertThrows(IllegalStateException.class, () -> entry.execute(0));
        }
    }
    @Test void sharedMappingsKeepDecodedContextStateSeparateAndSurviveClosingEitherContext() throws Exception {
        var manifest = fixture(); try (var engine = Engine.create()) { var source = Source.newBuilder("thc", request(manifest, "bytecode"), "unit-model").cached(true).build(); var first = Context.newBuilder("thc").engine(engine).build(); var second = Context.newBuilder("thc").engine(engine).build();
            try { var a = first.eval(source); var b = second.eval(source); assertEquals(1L, count(a, "coreCompactPhysicalMappingOpens")); assertEquals(0L, count(a, "coreCompactMappingCacheHits")); assertEquals(0L, count(b, "coreCompactPhysicalMappingOpens")); assertEquals(1L, count(b, "coreCompactMappingCacheHits")); assertEquals(2L, a.execute(1).asLong()); assertEquals(1L, count(b, "coreCompactDecodedBindings")); first.close(); assertEquals(3L, b.execute(2).asLong()); assertEquals(2L, count(b, "coreCompactDecodedBindings")); } finally { first.close(); second.close(); }
        }
        try (var context = executionContext()) { var entry = context.eval("thc", request(manifest, "bytecode")); assertEquals(0L, count(entry, "coreCompactPhysicalMappingOpens"), "closed contexts release leases into bounded idle reuse"); assertEquals(1L, count(entry, "coreCompactMappingCacheHits")); assertEquals(1L, count(entry, "coreCompactDecodedBindings"), "decoded state is not process-shared"); assertEquals(7L, entry.execute(0).asLong()); }
    }
    @Test void demandedBindingsShareModuleSourceSectionsWithoutSharingAcrossContexts() throws Exception {
        var manifest = fixture(); var loose = directory.resolve("SourceNotes.cbd");
        var span = map("id", "span", "file", "source", "startLine", 1, "startColumn", 1,
            "endLine", 1, "endColumn", 6, "charIndex", 0, "charLength", 5);
        var first = with(binding("main:Main.entry", literal(7)), "source", "span");
        var second = with(binding("main:Main.second", literal(8)), "source", "span");
        var model = map("schema", 1, "ghc", "9.14.1", "unit", "main", "module", "Main", "boundary", boundary,
            "bindings", list(first, second), "constructors", List.of(),
            "sourceFiles", list(map("id", "source", "path", "Main.hs", "content", "entry\n")), "sourceSpans", list(span));
        CoreCbdFixtures.write(loose, model);
        var other = directory.resolve("Other.cbd");
        CoreCbdFixtures.write(other, with(model, "unit", "other", "module", "Other",
            "bindings", list(with(binding("other:Other.entry", literal(9)), "source", "span")),
            "sourceFiles", list(map("id", "source", "path", "Other.hs", "content", "other\n"))));
        for (String backend : List.of("ast", "bytecode")) {
            com.oracle.truffle.api.source.SourceSection previous = null;
            for (boolean notes : new boolean[]{true, true, false}) try (var context = executionContext()) {
                var request = CoreModules.request(List.of(loose.toString(), other.toString(), "@" + manifest), "main:Main.entry", true, false, backend, notes, false, null, true, false);
                @SuppressWarnings("unchecked") var input = (Map<String,Object>) Json.parse(request);
                context.initialize("thc"); context.enter(); var owner = Language.currentState(null); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    try (var program = new CoreUnitProgram(language, Objects.requireNonNull(CoreModules.unitDirectory(input)), input, "main:Main.entry", backend, true, owner)) {
                        var firstRoot = program.entryTarget("main:Main.entry").getRootNode();
                        var secondRoot = program.entryTarget("main:Main.second").getRootNode();
                        var otherRoot = program.entryTarget("other:Other.entry").getRootNode();
                        assertEquals(0L, statistic(program, "coreCompactDebugBytesRead"), "Preparation leaves CBD source tables cold");
                        if (notes && backend.equals("bytecode")) for (var root : List.of(firstRoot, secondRoot, otherRoot))
                            ((thc.runtime.BytecodeRoot) root).getBytecodeNode().ensureSourceInformation();
                        var a = firstRoot.getSourceSection();
                        var b = secondRoot.getSourceSection();
                        var c = otherRoot.getSourceSection();
                        if (notes) {
                            assertNotNull(a); assertEquals("entry", a.getCharacters().toString());
                            assertEquals(a, b);
                            if (backend.equals("ast")) assertSame(a, b, "Demanded bindings must retain one immutable module source section");
                            assertNotNull(c); assertEquals("other", c.getCharacters().toString()); assertNotSame(a, c);
                            assertNotSame(previous, a, "Resolved source-note tables belong to one context"); previous = a;
                        } else { assertNull(a); assertNull(b); assertNull(c); }
                        assertEquals(7L, thc.runtime.Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("main:Main.entry"), new Object[]{0L}}));
                        assertEquals(8L, thc.runtime.Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("main:Main.second"), new Object[]{0L}}));
                        assertEquals(2L, statistic(program, "coreCompactModuleOpens"), "two explicit CBD modules, no package opened");
                    }
                } finally { owner.getThreads().leaveCurrent(thc.runtime.GuestThreadStatus.FINISHED); context.leave(); }
            }
        }
    }
}
