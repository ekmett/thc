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
public class FloatForeignNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/float-foreign");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private List<List<?>> calls(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(calls(child));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.getLast() instanceof Map<?, ?> metadata && metadata.get("foreignCall") instanceof Map<?, ?>) result.add(list);
            for (var child : list) result.addAll(calls(child));
        }
        return result;
    }
    private Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        var copy = new LinkedHashMap<>(source); copy.put(key, value); return copy;
    }
    @Test public void originalFloatingDeclarationsRejectForgedAbiAndDefinedHeads() throws Exception {
        var seen = new LinkedHashSet<String>();
        for (var call : calls(json(new File(directory, "post.json")))) {
            var metadata = (Map<String, Object>) call.getLast(); var descriptor = (Map<String, Object>) metadata.get("foreignCall");
            var target = (Map<String, Object>) descriptor.get("target"); seen.add((String) target.get("symbol"));
            var operands = new ArrayList<Object>();
            for (var operand : (List<List<?>>) call.get(2)) { var proof = CoreRepresentations.INSTANCE.metadata(operand); operands.add(proof == null ? null : proof.get("rep")); }
            var operation = CoreFloatForeign.validate(metadata, operands, (List<?>) call.get(3), metadata.get("rep")); assertNotNull(operation);
            CoreFloatForeign.validateHead((List<?>) call.get(1), false);
            assertThrows(RuntimeFault.class, () -> CoreFloatForeign.validateHead((List<?>) call.get(1), true));
            var badDescriptors = List.of(with(descriptor, "target", with(target, "unit", "ghc-9.14.1-inplace")),
                with(descriptor, "target", with(target, "isFunction", false)), with(descriptor, "convention", "capi"),
                with(descriptor, "safety", "safe"), with(descriptor, "arity", 1L), with(descriptor, "suppliedArity", 1L));
            for (var bad : badDescriptors) assertThrows(RuntimeFault.class,
                () -> CoreFloatForeign.validate(with(metadata, "foreignCall", bad), operands, (List<?>) call.get(3), metadata.get("rep")));
            var wrong = new CoreRepresentation(operation.getSingle() ? CoreKind.DOUBLE : CoreKind.FLOAT, true, true,
                List.of(operation.getSingle() ? "DoubleRep" : "FloatRep"), null, null, null, null, null);
            assertThrows(RuntimeFault.class, () -> CoreFloatForeign.validateOperand(operation, 0, wrong, null));
        }
        var symbols = new LinkedHashSet<String>(); for (var operation : FloatForeignOp.values()) symbols.add(operation.getSymbol()); assertEquals(symbols, seen);
    }
    @Test public void originalFloatClassificationsAndRoundingMatchNativeRawBitsOnFirstCompiledCalls() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes", "interfaceHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet()) {
            File file = new File(hash.getKey()); if (!file.isAbsolute()) file = new File(root, hash.getKey());
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), "Stale original floating fixture " + hash.getKey());
        }
        var rows = new LinkedHashMap<String, List<List<String>>>(); int count = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); count++;
        }
        assertEquals(12, rows.size()); assertEquals(384, count);
        for (String stage : List.of("pre", "post")) {
            var module = json(new File(directory, stage + ".json"));
            for (var group : rows.entrySet()) {
                String entry = group.getKey(); var cases = group.getValue(); assertEquals(true, json(new File(directory, stage + "-" + entry + ".audit.json")).get("accepted"));
                for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var linked = CoreModules.reachable(CoreModules.merge(List.of(module)), entry, true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var callable = context.asValue(new EntryValue(program, entry, 1));
                        for (var row : cases) assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), stage + "/" + backend + "/" + entry + "/" + row.get(1));
                        assertTrue(callable.invokeMember("compile").asBoolean());
                        for (var row : cases.reversed()) {
                            long before = (Long) program.diagnostics().get("compiledEntries");
                            assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), stage + "/" + backend + "/" + entry + "/" + row.get(1));
                            assertTrue((Long) program.diagnostics().get("compiledEntries") > before);
                        }
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
