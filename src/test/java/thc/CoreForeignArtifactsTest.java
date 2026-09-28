// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.Program;
import thc.runtime.BytecodeProgram;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreForeignArtifactsTest {
    private static final String CAPI_UNIT = "time-1.15-inplace";
    private static final String CAPI_MODULE = "Data.Time.Clock.Internal.CTimespec";
    private static final String CAPI_SYMBOL = "ghczuwrapperZC0ZCtimezm1zi15zminplaceZCDataziTimeziClockziInternalziCTimespecZCHSzuCLOCKzuREALTIME";
    private Map<String,Object> capiCall() {
        return map("schema", 1L, "target", map("kind", "static", "unit", CAPI_UNIT, "symbol", CAPI_SYMBOL, "isFunction", true),
            "convention", "capi", "safety", "unsafe", "arity", 1L, "suppliedArity", 1L,
            "argumentReps", list(scalar(null, false)), "resultRep", tuple("Int32Rep"));
    }
    // An already admitted header, never executable test bytes. Source/header/
    // bitcode validation remains independently exercised below.
    private ForeignBitcode capiLink(String unit, String module) {
        return new ForeignBitcode(unit, module, "model", Set.of(CAPI_SYMBOL), Map.of(CAPI_SYMBOL, "time-clock-id"), new byte[]{0x42, 0x43});
    }
    @Test void demandedCrossModuleCapiResolvesOnlyItsOriginalDeclarationOnce() {
        var selected = map("id", CAPI_UNIT + ":Data.Time.Clock.Internal.SystemTime.getSystemTime2",
            "expr", list("app", map("foreignCall", capiCall()), map("foreignCall", capiCall()), list("var", "unopened:Cold.body")));
        var original = map("foreignLinks", List.of(), "bindings", list(selected));
        var lookups = new ArrayList<List<String>>();
        var link = capiLink(CAPI_UNIT, CAPI_MODULE);
        var result = CoreCapiProvenance.supplement(original, selected, (unit, module) -> {
            lookups.add(List.of(unit, module)); return link;
        });
        assertEquals(List.of(List.of(CAPI_UNIT, CAPI_MODULE)), lookups);
        assertEquals(List.of(link), result.get("foreignLinks"));
        assertSame(original.get("bindings"), result.get("bindings"));
        assertEquals(List.of(), original.get("foreignLinks"));
        assertEquals(result, CoreCapiProvenance.supplement(result, selected, (unit, module) -> { throw new AssertionError("Already admitted owner reopened"); }));
    }
    @Test void crossModuleCapiKeepsExactAbiOwnerAndMissingLinkRejections() {
        var input = map("foreignLinks", List.of());
        for (var bad : List.of(with(capiCall(), "safety", "safe"), with(capiCall(), "arity", 2L),
                with(capiCall(), "resultRep", scalar("Int32Rep", false)),
                with(capiCall(), "target", with((Map<?,?>) capiCall().get("target"), "symbol", CAPI_SYMBOL.replace("HSzuCLOCKzuREALTIME", "notDeclared")))))
            assertThrows(IllegalArgumentException.class, () -> CoreCapiProvenance.supplement(input, map("foreignCall", bad), (unit, module) -> capiLink(CAPI_UNIT, CAPI_MODULE)));
        for (var bad : Arrays.asList(null, capiLink("other", CAPI_MODULE), capiLink(CAPI_UNIT, "Other")))
            assertThrows(IllegalArgumentException.class, () -> CoreCapiProvenance.supplement(input, map("foreignCall", capiCall()), (unit, module) -> bad));
        assertThrows(IllegalArgumentException.class, () -> CoreCapiProvenance.supplement(
            map("foreignLinks", list(capiLink(CAPI_UNIT, CAPI_MODULE), capiLink(CAPI_UNIT, CAPI_MODULE))),
            map("foreignCall", capiCall()), (unit, module) -> { throw new AssertionError("Duplicate owner must fail first"); }));
    }
    @Test void unrelatedOrUnknownCallsDoNotOpenCapiMetadata() {
        var original = map("foreignLinks", List.of());
        for (var binding : list(list("var", "unopened:Cold.body"),
                map("foreignCall", with(capiCall(), "convention", "ccall")),
                map("foreignCall", with(capiCall(), "target", map("unit", "another-unit", "symbol", CAPI_SYMBOL))),
                map("foreignCall", with(capiCall(), "target", map("unit", CAPI_UNIT, "symbol", "unknown")))))
            assertEquals(original, CoreCapiProvenance.supplement(original, binding, (unit, module) -> { throw new AssertionError("Cold header opened"); }));
    }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Map<String, Object> scalar(String primitive, boolean evaluated) {
        return map("kind", primitive == null ? "void" : primitive.equals("AddrRep") ? "address" : "long",
            "primReps", primitive == null ? List.of() : list(primitive), "evaluated", evaluated);
    }
    private Map<String, Object> tuple(String primitive) { return map("kind", "unknown", "primReps", list(primitive), "aggregate", "unboxed-tuple", "components", list(scalar(null, true), scalar(primitive, true)), "evaluated", false); }
    @Test void linkedCapiNeedsExactArchivedSourceSymbolsAndNoCallbacks() throws Exception {
        var unit = "base-test-unit"; var name = "System.CPUTime.Posix.ClockGetTime";
        var symbols = List.of("exact_generated_wrapper_0", "exact_generated_wrapper_1", "exact_generated_wrapper_2");
        var source = "/* original C source */\n"; var bytecode = new byte[]{0x42, 0x43};
        var abi = new ArrayList<Map<String, Object>>(); var calls = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < symbols.size(); index++) {
            var symbol = symbols.get(index); boolean zero = index == 0;
            abi.add(map("symbol", symbol, "kind", zero ? "clock-id" : "clock-buffer"));
            calls.add(map("foreignCall", map("schema", 1L, "target", map("kind", "static", "isFunction", true, "unit", unit, "symbol", symbol),
                "convention", "capi", "safety", "unsafe", "arity", zero ? 1L : 3L, "suppliedArity", zero ? 1L : 3L,
                "argumentReps", zero ? list(scalar(null, false)) : list(scalar("Word64Rep", false), scalar("AddrRep", false), scalar(null, false)),
                "resultRep", tuple(zero ? "Word64Rep" : "Int32Rep"))));
        }
        var archive = map("schema", 1L, "execution", "not-linked", "stubs", map("header", "", "source", source, "initializers", List.of(), "finalizers", List.of()), "files", List.of());
        var target = System.getProperty("os.name").startsWith("Mac") ? (System.getProperty("os.arch").equals("aarch64") ? "arm64" : "x86_64") + "-apple-darwin"
            : (System.getProperty("os.arch").equals("amd64") ? "x86_64" : "aarch64") + "-unknown-linux-gnu";
        var link = map("schema", 2L, "format", "llvm-bitcode", "unit", unit, "module", name, "target", target, "symbols", symbols, "abi", abi,
            "sourceSha256", sha(source.getBytes(StandardCharsets.UTF_8)), "bitcodeSha256", sha(bytecode), "bitcodeHex", "4243");
        var linked = map("schema", 2L, "unit", unit, "module", name, "foreign", archive, "foreignLink", link, "bindings", calls);
        assertEquals(new HashSet<>(symbols), Objects.requireNonNull(CoreForeignArtifacts.linked(linked, true)).getSymbols());
        var header = Objects.requireNonNull(CoreForeignArtifacts.linked(with(linked, "bindings", List.of()), false));
        CoreForeignArtifacts.validateCalls(calls.subList(0, 1), header, false);
        assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.linked(with(linked, "bindings", calls.subList(0, 1)), true));
        var unknown = with((Map<?, ?>) calls.getFirst().get("foreignCall"), "target", map("kind", "static", "unit", unit, "symbol", "unknown", "isFunction", true));
        assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.validateCalls(list(map("foreignCall", unknown)), header, false));
        CoreForeignArtifacts.requireExecutable(linked);
        var originalStubs = (Map<?, ?>) archive.get("stubs"); var initializer = map("isInitializer", true, "unit", unit, "module", name, "name", "boot");
        var withInitializer = with(linked, "foreign", with(archive, "stubs", with(originalStubs, "initializers", list(initializer))));
        var wrongAbi = new ArrayList<Map<String, Object>>(); var extraAbi = new ArrayList<Map<String, Object>>(); var invalidAbi = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < abi.size(); index++) {
            wrongAbi.add(with(abi.get(index), "kind", index == 1 ? "clock-id" : "clock-buffer"));
            extraAbi.add(index == 0 ? with(abi.get(index), "extra", "field") : abi.get(index));
            invalidAbi.add(index == 0 ? with(abi.get(index), "kind", 1) : abi.get(index));
        }
        var swappedCall = with((Map<?, ?>) calls.get(1).get("foreignCall"), "target", map("kind", "static", "isFunction", true, "unit", unit, "symbol", symbols.getFirst()));
        var swappedCalls = new ArrayList<>(calls); swappedCalls.add(map("foreignCall", swappedCall));
        for (var bad : List.of(with(linked, "foreignLink", with(link, "bitcodeSha256", "0".repeat(64))),
                with(linked, "foreignLink", with(link, "target", 17L)), with(linked, "foreignLink", with(link, "target", "riscv64-unknown-linux-gnu")),
                with(linked, "foreignLink", with(link, "symbols", symbols.subList(0, 2))), with(linked, "foreignLink", with(link, "abi", wrongAbi)),
                with(linked, "foreignLink", with(link, "abi", extraAbi)), with(linked, "foreignLink", with(link, "abi", invalidAbi)),
                with(linked, "bindings", swappedCalls), with(linked, "foreignLink", with(link, "sourceSha256", "0".repeat(64))), withInitializer))
            assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutable(bad));
    }
    private final Map<String, Object> label = map("isInitializer", false, "unit", "pkg", "module", "M", "name", "exit");
    private final Map<String, Object> stubs = map("header", "", "source", "", "initializers", List.of(), "finalizers", list(label));
    private final Map<String, Object> foreign = map("schema", 1L, "execution", "not-linked", "stubs", stubs, "files", List.of());
    private final Map<String, Object> module = map("schema", 2L, "ghc", "9.14.1", "unit", "pkg", "module", "M", "foreign", foreign, "bindings", List.of(), "constructors", List.of());
    @Test void finalizersAndRawObjectContentsAreArchiveOnly() {
        CoreForeignArtifacts.validateArchive(module);
        var file = map("language", "RawObject", "source", "\0\u00ff\u03bb\n", "extension", ".o");
        var withFile = with(module, "foreign", with(foreign, "stubs", null, "files", list(file)));
        var roundTrip = (Map<?, ?>) Json.parse(Json.stringify(withFile)); assertEquals(withFile, roundTrip); CoreForeignArtifacts.validateArchive(roundTrip);
        for (var value : List.of(module, withFile)) {
            var error = assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(value)));
            assertTrue(error.getMessage().contains("Unsupported foreign code/registration for pkg:M"));
        }
    }
    @Test void onlyReachableForeignArchivesAreRequiredButRegistrationsAlwaysAre() {
        var entry = map("schema", 1L, "ghc", "9.14.1", "unit", "main", "module", "Entry", "bindings", list(
            map("id", "main:Entry.main", "name", "main", "expr", list("lit", "int", "7")), map("id", "main:Entry.other", "name", "other", "expr", list("lit", "int", "8"))), "constructors", List.of());
        var inactiveStubs = with(stubs, "source", "int foreign_stub(void) { return 1; }", "finalizers", List.of());
        var archive = with(module, "foreign", with(foreign, "stubs", inactiveStubs), "bindings", list(map("id", "pkg:M.cold", "name", "cold", "expr", list("lit", "int", "9"))));
        var merged = CoreModules.merge(List.of(entry, archive)); assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutableInput(merged));
        var live = CoreModules.reachable(merged, List.of("main:Entry.main", "main:Entry.other"), true);
        assertEquals(List.of("main:Entry.main", "main:Entry.other"), ((List<?>) live.get("bindings")).stream().map(it -> ((Map<?, ?>) it).get("id")).toList());
        var needed = with(entry, "bindings", list(map("id", "main:Entry.main", "name", "main", "expr", list("var", "pkg:M.cold"))));
        var failure = assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(CoreModules.merge(List.of(needed, archive)), "main:Entry.main", true));
        assertTrue(failure.getMessage().contains("Core schema 2 is archive-only"));
        var registration = map("isInitializer", true, "unit", "pkg", "module", "M", "name", "start");
        var withInitializer = with(archive, "foreign", with(foreign, "stubs", with(inactiveStubs, "initializers", list(registration))));
        var startup = assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(entry, withInitializer)));
        assertTrue(startup.getMessage().contains("Core schema 2 is archive-only"));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(merged, List.of("main:Entry.main", "missing:entry"), true));
    }
    @Test void malformedOrDowngradedForeignArtifactsFailClosed() {
        var variants = List.of(with(module, "schema", 1L), without(module, "foreign"), with(module, "schema", 2.5),
            with(module, "foreign", with(foreign, "execution", "linked")), with(module, "foreign", with(foreign, "schema", 1.5)),
            with(module, "foreign", with(foreign, "unknown", true)), with(module, "foreign", with(foreign, "stubs", null)),
            with(module, "foreign", with(foreign, "files", list("missing content"))),
            with(module, "foreign", with(foreign, "stubs", with(stubs, "finalizers", list(with(label, "isInitializer", true))))));
        for (var value : variants) assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.validateArchive(value));
    }
    @Test void ordinarySchemaOneHasNoForeignRegistrationObligations() {
        for (Object schema : list(1, 1L)) CoreForeignArtifacts.requireExecutable(map("schema", schema));
        assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.validateArchive(map("schema", 1L, "foreign", null)));
    }
    @Test void directBackendConstructorsRejectBeforeInitializingBindingsEvenInDiagnosticMode() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean diagnostic : new boolean[]{false, true}) for (boolean async : new boolean[]{false, true})
                    for (var data : List.of(module, with(module, "bindings", "must not inspect"))) {
                        // Invalid bindings make foreign-guard initialization order observable.
                        var input = with(data, "diagnosticUnsupported", diagnostic);
                        var ast = assertThrows(IllegalArgumentException.class, () -> new Program(language, input, async, false));
                        var bytecode = assertThrows(IllegalArgumentException.class, () -> new BytecodeProgram(language, input, async));
                        for (var failure : List.of(ast, bytecode)) assertTrue(failure.getMessage().contains("Unsupported foreign code/registration for pkg:M"));
                    }
                var synthetic = map("bindings", list(map("id", "entry", "name", "entry", "type", "Int#", "arity", 0L, "lifted", false, "expr", list("lit", "int", "7"))), "constructors", List.of());
                assertEquals(7L, new Program(language, synthetic, false, false).entryValue("entry")); assertEquals(7L, new BytecodeProgram(language, synthetic, false).entryValue("entry"));
            } finally { context.leave(); }
        }
    }
    @Test void reachabilityCannotPruneForeignRegistrationObligations() {
        var failure = assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(module, "entry", false));
        assertTrue(failure.getMessage().contains("Unsupported foreign code/registration for pkg:M"));
        assertThrows(IllegalArgumentException.class, () -> CoreForeignArtifacts.requireExecutableInput(without(module, "schema")));
    }
}
