// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class GcStatsNativeTest {
    private Map<String, Object> cbd(File file) throws Exception { return CoreCbdFixtures.read(file.toPath()); }
    private String entryId(String name) { return "main:GcStatsAudit." + name; }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/gc-stats");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private Context context() { return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private ExecutableProgram program(Language language, String backend, String entry, String stage) throws Exception {
        var module = ForeignExceptionFixtureSupport.nativeModules(List.of(cbd(new File(directory, entry + "-" + stage + ".cbd"))));
        // Exercise the safe-return poll on both backends, with AST's explicit opt-in.
        return backend.equals("ast") ? new Program(language, module, true) : new BytecodeProgram(language, module);
    }
    private List<List<?>> calls(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(calls(child));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.getLast() instanceof Map<?, ?> metadata && metadata.get("foreignCall") instanceof Map<?, ?>) result.add(list);
            for (var child : list) result.addAll(calls(child));
        }
        return result;
    }
    private Map<String, Object> with(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
    }
    @Test public void originalDeclarationsKeepTheirUnitSafetyStateAndResultAbi() throws Exception {
        var entries = (List<List<String>>) json(new File(directory, "manifest.json")).get("entries"); var seen = new LinkedHashSet<String>();
        for (var pair : entries) for (String stage : List.of("pre", "post")) {
            String entry = pair.getFirst(); var actual = calls(cbd(new File(directory, entry + "-" + stage + ".cbd"))); assertEquals(entry.equals("clock") ? 2 : 1, actual.size());
            for (var call : actual) {
                var metadata = (Map<String, Object>) call.getLast(); var descriptor = (Map<String, Object>) metadata.get("foreignCall");
                var target = (Map<String, Object>) descriptor.get("target"); seen.add((String) target.get("symbol")); var operands = new ArrayList<Object>();
                for (var operand : (List<List<?>>) call.get(2)) { var proof = CoreRepresentations.metadata(operand); operands.add(proof == null ? null : proof.get("rep")); }
                assertNotNull(CoreGcForeign.validate(metadata, operands, (List<?>) call.get(3), metadata.get("rep")));
                CoreGcForeign.validateHead((List<?>) call.get(1), false); assertThrows(RuntimeFault.class, () -> CoreGcForeign.validateHead((List<?>) call.get(1), true));
                for (var bad : List.of(with(descriptor, "target", with(target, "unit", "main")), with(descriptor, "target", with(target, "isFunction", false)),
                    with(descriptor, "safety", entry.equals("clock") ? "safe" : "unsafe"), with(descriptor, "arity", 99L), with(descriptor, "suppliedArity", 0L),
                    with(descriptor, "resultRep", Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", false))))
                    assertThrows(RuntimeFault.class, () -> CoreGcForeign.validate(with(metadata, "foreignCall", bad), operands, (List<?>) call.get(3), metadata.get("rep")));
                assertThrows(RuntimeFault.class, () -> CoreGcForeign.validate(metadata, operands.subList(0, Math.max(0, operands.size() - 1)), (List<?>) call.get(3), metadata.get("rep")));
            }
        }
        var expected = new LinkedHashSet<String>(); for (var operation : GcForeignOp.values()) if (operation.getUnit().equals("ghc-internal")) expected.add(operation.getSymbol()); assertEquals(expected, seen);
    }
    @Test public void originalGcAndClockCallsMatchNativeFromTheFirstCompiledInvocation() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes", "interfaceHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet()) {
            File file = new File(hash.getKey()); if (!file.isAbsolute()) file = new File(root, hash.getKey());
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), "Stale GC/stats fixture " + hash.getKey());
        }
        assertEquals(false, manifest.get("statsEnabled")); var entries = new LinkedHashMap<String, String>();
        for (var pair : (List<List<String>>) manifest.get("entries")) entries.put(pair.getFirst(), pair.get(1));
        var rows = new LinkedHashMap<String, List<List<Object>>>(); int count = 0;
        for (var row : (List<List<Object>>) json(new File(directory, "oracle.json")).get("rows")) { rows.computeIfAbsent((String) row.getFirst(), ignored -> new ArrayList<>()).add(row); count++; }
        assertEquals(Set.of("enabled", "minor", "major", "blocking", "clock"), rows.keySet()); assertEquals(15, count);
        for (String stage : List.of("pre", "post")) for (var group : rows.entrySet()) for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            String entry = group.getKey(); var cases = group.getValue(); context.initialize("thc"); context.enter();
            try {
                assertEquals(true, json(new File(directory, entry + "-" + stage + ".audit.json")).get("accepted"));
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var program = program(language, backend, entry, stage);
                String name = entries.get(entry); var callable = context.asValue(new EntryValue(program,entryId(name), 1));
                for (var row : cases) {
                    long input = ((Number) row.get(1)).longValue(), answer = ((Number) row.get(2)).longValue();
                    assertEquals(input + (entry.equals("enabled") ? 0L : 1L), answer); assertEquals(answer, callable.execute(input).asLong()); released(language);
                }
                assertTrue(callable.invokeMember("compile").asBoolean()); var target = program.entryTarget(entryId(name));
                for (var row : cases.reversed()) {
                    long before = (Long) program.diagnostics().get("compiledEntries");
                    assertEquals(((Number) row.get(2)).longValue(), callable.execute(((Number) row.get(1)).longValue()).asLong(), stage + "/" + backend + "/" + entry);
                    assertEquals(before + 1, program.diagnostics().get("compiledEntries"), stage + "/" + backend + "/" + entry + " exact entry");
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); released(language);
                }
            } finally { context.leave(); }
        }
    }
    @Test public void directStatsLeafIsUnavailableAndDoesNotWriteItsBuffer() throws Exception {
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var program = program(language, backend, "stats", stage);
                var address = ManagedAddress.fromByteArray(new byte[] {11, 22, 33, 44});
                var failure = assertThrows(RuntimeFault.class, () -> Calls.target(program.hostEntryTarget(2), new Object[] {program.entryValue(entryId("originalStats")), new Object[] {address, 1L}}));
                assertEquals("GHC RTS statistics are unavailable on the JVM; getRTSStatsEnabled is false", failure.getMessage());
                var actual = new ArrayList<Long>(); for (long i = 0; i <= 3; i++) actual.add(address.readWord8(i)); assertEquals(List.of(11L, 22L, 33L, 44L), actual); released(language);
            } finally { context.leave(); }
        }
    }
}
