// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class UnsafeEqualityTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("compiler.Inlining", Boolean.toString(inlining)).build();
    }
    private Map<String, Object> json(String path) throws Exception { return object(Json.parse(Files.readString(root.resolve(path)))); }
    private Map<String, Object> module(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var name : list("UnsafeEqualityAudit", "THC.InterfaceClosure")) modules.add(CoreCbdFixtures.read(root.resolve("build/unsafe-equality/" + stage + "-core/" + name + ".cbd")));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, "installed");
    }
    private long count(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences());
    }
    private void verifyEvidence() throws Exception {
        var evidence = json("build/unsafe-equality/provenance.json");
        for (var kind : list("sources", "artifacts")) for (var record : objects(evidence.get(kind))) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve((String) record.get("path")))));
            assertEquals(record.get("sha256"), hash, "Stale unsafe-equality evidence: " + record.get("path"));
        }
        for (var stage : list("pre", "post")) assertEquals(true, json("build/unsafe-equality/" + stage + "-audit.json").get("accepted"));
    }
    @Test void nativeCasesWithInlining() throws Exception { nativeCases(true); }
    @Test void nativeCasesAcrossResidualCalls() throws Exception { nativeCases(false); }
    private void nativeCases(boolean inlining) throws Exception {
        verifyEvidence(); var rows = new LinkedHashMap<String, List<List<String>>>(); int size = 0;
        for (var line : Files.readAllLines(root.resolve("build/unsafe-equality/oracle.tsv"))) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); size++;
        }
        assertEquals(49, size);
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) for (var group : rows.entrySet())
            try (var context = context(inlining)) {
                var name = group.getKey(); var entry = "main:UnsafeEqualityAudit." + name; var selected = group.getValue(); context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = program(language, with(CoreModules.reachable(module(stage), entry), "instrument", true), backend);
                    var function = context.asValue(new EntryValue(program, entry, 1)); var host = program.hostEntryTarget(1);
                    var original = program.entryTarget(entry); var label = stage + "/" + backend + "/" + name + "/inline=" + inlining;
                    CheckedConsumer<List<String>> check = row -> assertEquals(Long.parseLong(row.get(2)), function.execute(Long.parseLong(row.get(1))).asLong(), label + "/" + row.get(1));
                    for (var row : selected) check.accept(row);
                    var targets = activeTargets(host); assertTrue(targets.size() > 1, label + " actual adopted guest target");
                    for (var target : targets) if (target != host) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean());
                    for (var row : selected.reversed()) {
                        long before = count(program); check.accept(row);
                        long expectedEntries = name.equals("unusedCase") && Long.parseLong(row.get(1)) >= 0 ? 1L : 2L;
                        assertEquals(before + expectedEntries, count(program), label + "/" + row.get(1) + " exact compiled guest entries");
                        assertEquals(targets, activeTargets(host), label + " active target identities"); valid(original, label + " original");
                        for (var target : targets) valid(target, label + " active"); released(language);
                    }
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                } finally { context.leave(); }
            }
    }
    @Test void nonmatchingFirstClassProofsRemainStrictFrontiers() throws Exception {
        verifyEvidence();
        for (var stage : list("pre", "post")) for (var name : list("firstClassProof", "liveBinder")) {
            var audit = json("build/unsafe-equality/" + stage + "-" + name + "-audit.json");
            assertEquals(false, audit.get("accepted"));
            assertEquals(list("ghc-internal:GHC.Internal.Unsafe.Coerce.unsafeEqualityProof"), objects(audit.get("missingGlobals")).stream().map(item -> item.get("id")).toList());
            for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    assertThrows(UnsupportedCore.class, () -> program(language, CoreModules.reachable(module(stage), "main:UnsafeEqualityAudit." + name), backend));
                } finally { context.leave(); }
            }
        }
    }
    @Test void wrongCalleeAndDemandedBottomThrowFromCompiledCodeWithoutPublishingResults() throws Exception {
        verifyEvidence();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) for (boolean inlining : list(true, false))
            for (var name : list("wrongCalleeCase", "demandedBottomCase")) try (var context = context(inlining)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var entry = "main:UnsafeEqualityAudit." + name;
                    var program = program(language, with(CoreModules.reachable(module(stage), entry), "instrument", true), backend);
                    var host = program.hostEntryTarget(1); var original = program.entryTarget(entry);
                    java.util.function.Supplier<GuestException> fail = () -> assertThrows(GuestException.class,
                        () -> Calls.target(host, new Object[]{program.entryValue(entry), new Object[]{9L}}));
                    var initial = fail.get(); released(language);
                    assertEquals("main:UnsafeEqualityAudit.Failure", ((DataValue) initial.getPayload()).getLayout().getId());
                    var active = activeTargets(host); for (var target : active) if (target != host) compile(target);
                    compile(original); compile(host); valid(original, name); for (var target : active) valid(target, name);
                    long before = count(program); var raised = fail.get(); released(language);
                    assertSame(initial.getPayload(), raised.getPayload(), "Preserve the real guest exception payload");
                    var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                    assertTrue(count(program) > before, label + " first installed call entered guest code");
                    valid(original, label + " original");
                    if (name.equals("wrongCalleeCase")) {
                        // Direct raise# is expected control flow and preserves callers.
                        for (var target : active) valid(target, label + " active");
                    } else {
                        // Demanding the already failed bottom rethrows a memoized
                        // thunk failure; Force.rethrowFailure deliberately invalidates.
                        var validity = new ArrayList<Boolean>();
                        for (var target : active) validity.add((Boolean) target.getClass().getMethod("isValidLastTier").invoke(target));
                        assertTrue(validity.contains(false), label + " memoized bottom deopt");
                    }
                    assertEquals(active, activeTargets(host));
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue());
                } finally { context.leave(); }
            }
    }
}
