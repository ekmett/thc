// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import thc.CoreCbdFixtures;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class CompilerRtsNativeTest {
    private Map<String, Object> cbd(File file) throws Exception { return CoreCbdFixtures.read(file.toPath()); }
    private String entryId(String name) { return "main:CompilerRtsAudit." + name; }
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/compiler-rts");
    private Map<String, Object> json(File file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
    private Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private void calls(Object value, List<List<?>> found) {
        if (value instanceof Map<?, ?> map) for (Object child : map.values()) calls(child, found);
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.getLast() instanceof Map<?, ?> metadata && metadata.get("foreignCall") instanceof Map<?, ?>)
                found.add(list);
            for (Object child : list) calls(child, found);
        }
    }
    private Map<String, Object> with(Map<String, Object> map, String key, Object value) {
        var copy = new LinkedHashMap<>(map); copy.put(key, value); return copy;
    }
    private Object validate(String symbol, Map<String, Object> updated, List<Object> operands, List<?> flags, Object result) {
        return symbol.equals("keepCAFsForGHCi") ? CoreStringRtsForeign.validate(updated, operands, flags, result)
            : CoreSharedCAFStores.validate(updated, operands, flags, result);
    }
    @Test public void genuineDeclarationsRejectOtherUnitsSafetyAndArity() throws Exception {
        var actual = new ArrayList<List<?>>(); calls(cbd(new File(directory, "post.cbd")), actual);
        assertFalse(actual.isEmpty());
        var seen = new LinkedHashSet<String>();
        for (var call : actual) {
            var metadata = (Map<String, Object>) call.getLast();
            var descriptor = (Map<String, Object>) metadata.get("foreignCall");
            var target = (Map<String, Object>) descriptor.get("target");
            String symbol = (String) target.get("symbol"); seen.add(symbol);
            var operands = new ArrayList<Object>();
            for (var operand : (List<List<Object>>) call.get(2)) {
                var proof = CoreRepresentations.metadata(operand);
                operands.add(proof == null ? null : proof.get("rep"));
            }
            var flags = (List<?>) call.get(3); Object result = metadata.get("rep");
            assertNotNull(validate(symbol, metadata, operands, flags, result));
            for (var bad : List.of(with(descriptor, "target", with(target, "unit", "ghc-internal")),
                    with(descriptor, "safety", "safe"), with(descriptor, "arity", 99L),
                    with(descriptor, "target", with(target, "isFunction", false))))
                assertThrows(RuntimeFault.class, () -> validate(symbol, with(metadata, "foreignCall", bad), operands, flags, result));
        }
        assertEquals(Set.of("keepCAFsForGHCi", "getOrSetLibHSghcFastStringTable", "getOrSetLibHSghcGlobalHasPprDebug",
            "getOrSetLibHSghcGlobalHasNoDebugOutput", "getOrSetLibHSghcGlobalHasNoStateHack"), seen);
    }
    @Test public void actualCompilerDeclarationsMatchNativeBeforeAndAfterCompilation() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes", "interfaceHashes"))
            for (var hash : ((Map<String, String>) manifest.get(group)).entrySet()) {
                File file = new File(hash.getKey()); if (!file.isAbsolute()) file = new File(root, hash.getKey());
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
                assertEquals(hash.getValue(), actual, "Stale compiler RTS fixture " + hash.getKey());
            }
        var rows = new LinkedHashMap<String, List<String[]>>();
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1); rows.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row);
        }
        assertEquals(Set.of("originalKeep", "originalFast", "originalPpr", "originalNoDebug", "originalNoState", "uniqueCells"), rows.keySet());
        for (String stage : List.of("pre", "post")) {
            var module = cbd(new File(directory, stage + ".cbd"));
            for (var group : rows.entrySet()) {
                String entry = group.getKey(); var cases = group.getValue();
                assertEquals(true, json(new File(directory, stage + "-" + entry + ".audit.json")).get("accepted"));
                for (String backend : List.of("ast", "bytecode")) try (Context context = context()) {
                    context.initialize("thc"); context.enter();
                    try {
                        Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var linked = CoreModules.reachable(CoreModules.merge(List.of(module)),entryId(entry), true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var callable = context.asValue(new EntryValue(program,entryId(entry), 1));
                        for (String[] row : cases) assertEquals(Long.parseLong(row[2]), callable.execute(Long.parseLong(row[1])).asLong(), stage + "/" + backend + "/" + entry);
                        assertTrue(callable.invokeMember("compile").asBoolean());
                        for (String[] row : cases.reversed()) {
                            long before = (Long) program.diagnostics().get("compiledEntries");
                            assertEquals(Long.parseLong(row[2]), callable.execute(Long.parseLong(row[1])).asLong());
                            assertTrue((Long) program.diagnostics().get("compiledEntries") > before);
                        }
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    } finally { context.leave(); }
                }
            }
        }
    }
}
