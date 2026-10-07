// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Genuine original declarations with an explicit CBD inline-consumer harness. */
@SuppressWarnings("unchecked")
class CoreBoxedForeignAdmissionTest {
    @TempDir Path directory;
    private static final String ENTRY = "inline:InlineCarrier.entry";
    private record Original(Path path, String hash, Map<String,Object> facts, Map<String,Object> record) {}
    private Original original(String variable, String module) throws Exception {
        String input = System.getenv(variable); assumeTrue(input != null, "requires genuine original-unit stock import proof");
        String hash = System.getenv(variable + "_SHA256"); assertNotNull(hash); var path = Path.of(input);
        assertEquals(hash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        try (var file = new CoreCompactFile(path, hash, true)) {
            var facts = new LinkedHashMap<>(new CoreCompactRecords(file, hash).header()); var header = file.header();
            assertEquals("ghc-internal", facts.get("unit")); assertEquals(module, facts.get("module"));
            assertEquals("verified", ((Map<?,?>) facts.get("staticForeignImports")).get("status"));
            var record = Map.<String,Object>of("name", module, "boundary", facts.get("boundary"), "sha256", hash,
                "compact", Map.of("path", path.toString(), "sha256", hash, "format", CoreCompactFormat.NAME),
                "containsDelimitedControl", header.getContainsDelimitedControl(), "registrationObligations", header.getRegistrationObligations(),
                "mainAlias", header.getMainAlias(), "packageScalarDeclarations", header.getPackageScalarDeclarations());
            return new Original(path, hash, facts, record);
        }
    }
    private Map<?,?> descriptor(Original original, String symbol) {
        var proof = (Map<?,?>) original.facts().get("staticForeignImports");
        return (Map<?,?>) ((List<?>) proof.get("expectedCalls")).stream().filter(raw -> raw instanceof Map<?,?> call &&
            call.get("target") instanceof Map<?,?> target && symbol.equals(target.get("symbol"))).findFirst().orElseThrow();
    }
    private Path consumer(Original original, String symbol) throws Exception {
        var call = descriptor(original, symbol); int arity = ((Number) call.get("arity")).intValue();
        // The carrier harness changes operands, not the genuine original FCall descriptor.
        var application = List.of("app", List.of("var", "foreign", Map.of("rep", OriginalStdioFixtures.closure())),
            List.of(), java.util.Collections.nCopies(arity, false), false, false, Map.of("foreignCall", call, "rep", call.get("resultRep")));
        var module = new LinkedHashMap<>(OriginalStdioChecks.rawModule(application, Map.of("sourceFiles", List.of(), "sourceSpans", List.of())));
        module.remove("instrument");
        var constructors = ((List<Map<String,Object>>) module.get("constructors")).stream().map(raw -> {
            var constructor = new LinkedHashMap<>(raw); int fields = ((Number) raw.get("arity")).intValue();
            constructor.put("name", raw.get("id")); constructor.put("strictFields", java.util.Collections.nCopies(fields, false));
            constructor.put("fieldLifted", java.util.Collections.nCopies(fields, false));
            var components = (List<Map<String,Object>>) ((Map<?,?>) call.get("resultRep")).get("components");
            constructor.put("fieldTypes", components); constructor.put("fieldReps", components.stream().map(rep -> rep.get("primReps")).toList());
            return constructor;
        }).toList(); module.put("constructors", constructors);
        var binding = new LinkedHashMap<>((Map<String,Object>) ((List<?>) module.get("bindings")).getFirst()); binding.put("id", ENTRY);
        module.put("bindings", List.of(binding)); module.put("schema", 1L); module.put("ghc", "9.14.1"); module.put("unit", "inline"); module.put("module", "InlineCarrier");
        module.put("boundary", "optimized-Core-after-Tidy-before-CorePrep");
        return CoreCbdFixtures.write(directory.resolve(symbol + ".cbd"), module);
    }
    private Path manifest(Map<String,Object> record) throws Exception {
        var path = directory.resolve("packages.json");
        Files.writeString(path, Json.stringify(Map.of("format", "thc-core-packages", "schema", 1L, "ghc", "9.14.1", "units",
            List.of(Map.of("id", "ghc-internal", "depends", List.of(), "modules", List.of(record))))));
        return path;
    }
    private CoreUnitProgram program(Language language, String backend, Path consumer, Map<String,Object> record) throws Exception {
        var input = (Map<String,Object>) Json.parse(CoreModules.request(List.of(consumer.toString(), "@" + manifest(record)), ENTRY, true, false, backend, false));
        return new CoreUnitProgram(language, CoreModules.unitDirectory(input), input, ENTRY, backend, false, Language.currentState());
    }
    private Map<String,Object> broken(Original original, String kind) throws Exception {
        var facts = (Map<String,Object>) CoreCbdFixtures.snapshot(original.facts()); facts.put("bindings", List.of());
        if (kind.equals("missing")) { facts.remove("staticForeignImports"); facts.remove("staticForeignImportStubs"); facts.remove("packageNativeArchive"); }
        else {
            var proof = (Map<?,?>) facts.get("staticForeignImports");
            var declaration = (Map<String,Object>) ((List<?>) proof.get("imports")).stream().filter(raw -> raw instanceof Map<?,?> entry &&
                "rts_getThreadId".equals(entry.get("symbol"))).findFirst().orElseThrow();
            var type = new LinkedHashMap<>((Map<String,Object>) declaration.get(kind));
            type.put("argument", Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", "GHC.Internal.Prim",
                "occurrence", "ByteArray#", "namespace", "type"), "arguments", List.of())); declaration.put(kind, type);
        }
        return CoreCbdFixtures.module(directory.resolve(kind + ".cbd"), facts);
    }
    @Test void inlineUnitRetainsGenuineThreadNominalProofWithoutDemandingItsBody() throws Exception {
        var original = original("THC_TEST_THREAD_ID_CBD", "GHC.Internal.Conc.Sync");
        for (var symbol : List.of("rts_getThreadId", "eq_thread", "cmp_thread")) {
            var consumer = consumer(original, symbol);
            for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var threads = Language.currentState().getThreads(); threads.enterCurrent();
                    try (var program = program(language, backend, consumer, original.record())) {
                        var target = program.entryTarget(ENTRY); var self = threads.currentIdentity();
                        Object[] arguments = symbol.equals("rts_getThreadId") ? new Object[]{0L, self, Unit.INSTANCE} : new Object[]{0L, self, self, Unit.INSTANCE};
                        long expected = symbol.equals("rts_getThreadId") ? self.getLogicalId() : symbol.equals("eq_thread") ? 1L : 0L;
                        assertEquals(expected, ((Number) Calls.target(target, arguments)).longValue());
                        assertEquals(0L, program.diagnostics().get("coreCompactDecodedBindings"), "original declaration lookup must not decode an original binding");
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true); target.getClass().getMethod("waitForCompilation").invoke(target); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(expected, ((Number) Calls.target(target, arguments)).longValue()); assertEquals(before + 1, program.diagnostics().get("compiledEntries"));
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    } finally { threads.leaveCurrent(); }
                } finally { context.leave(); }
            }
        }
    }
    @Test void inlineUnitRejectsMissingOrForgedOriginalNominalsBeforeEffects() throws Exception {
        var original = original("THC_TEST_THREAD_ID_CBD", "GHC.Internal.Conc.Sync"); var consumer = consumer(original, "rts_getThreadId");
        for (var kind : List.of("missing", "declaredType", "normalizedType")) {
            var record = broken(original, kind);
            for (var backend : List.of("ast", "bytecode")) {
                var output = new ByteArrayOutputStream();
                try (var context = Context.newBuilder("thc").err(output).build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var threads = Language.currentState().getThreads(); var before = threads.mainThreadRegistration();
                        try (var program = program(language, backend, consumer, record)) { assertThrows(RuntimeException.class, () -> program.entryTarget(ENTRY)); }
                        assertEquals(0, output.size()); assertSame(before, threads.mainThreadRegistration());
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void inlineUnitRetainsGenuineWeakMainProofAndRejectsExpiredHandleBeforeRegistration() throws Exception {
        var original = original("THC_TEST_MAIN_THREAD_CBD", "GHC.Internal.TopHandler"); var consumer = consumer(original, "rts_setMainThread");
        for (var backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); var threads = state.getThreads(); threads.enterCurrent();
                try (var program = program(language, backend, consumer, original.record())) {
                    var target = program.entryTarget(ENTRY); var key = threads.currentIdentity();
                    var first = state.getWeaks().make(key, new Object(), null, null); var second = state.getWeaks().make(key, new Object(), null, null);
                    assertEquals(0L, Calls.target(target, new Object[]{0L, first, Unit.INSTANCE}));
                    assertEquals(key.getJavaId(), threads.mainThreadRegistration().liveJavaId());
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); target.getClass().getMethod("waitForCompilation").invoke(target); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(0L, Calls.target(target, new Object[]{0L, second, Unit.INSTANCE})); assertEquals(before + 1, program.diagnostics().get("compiledEntries"));
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    var registered = threads.mainThreadRegistration(); state.getWeaks().finalize(first);
                    assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, first, Unit.INSTANCE})); assertSame(registered, threads.mainThreadRegistration());
                    assertEquals(0L, program.diagnostics().get("coreCompactDecodedBindings"));
                    var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                    assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                } finally { threads.leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
}
