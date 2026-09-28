// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class AstStackNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/deep-evaluation");
    private record Row(long input, long expected) {}
    private List<Row> rows() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        assertEquals("9.14.1", manifest.get("ghc"));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            var file = new File(root, item.getKey());
            assertTrue(file.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
            assertEquals(item.getValue(), hash, kind + "/" + item.getKey());
        }
        var rows = new ArrayList<Row>(); var inputs = new ArrayList<Long>();
        for (var line : Files.readAllLines(new File(root, (String) manifest.get("oracle")).toPath())) {
            var row = line.split("\t", -1); var input = Long.parseLong(row[0]);
            rows.add(new Row(input, Long.parseLong(row[1]))); inputs.add(input);
        }
        assertEquals(List.of(0L, 100L, 1000L, 5000L, 20000L), inputs);
        return rows;
    }
    private Map<String, Object> source(String stage) throws Exception {
        var audit = (Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "/audit.json").toPath()));
        assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
        var modules = new ArrayList<Map<String, Object>>();
        for (var name : List.of("DeepEvaluation", "THC.InterfaceClosure"))
            modules.add((Map<String, Object>) Json.parse(Files.readString(new File(directory, stage + "/core/" + name + ".json").toPath())));
        var source = new LinkedHashMap<>(CoreModules.reachable(CoreModules.merge(modules), "probe", true)); source.put("instrument", true); return source;
    }
    @Test public void genuineDeepLazyEvaluationMatchesNativeWithoutGuestCompilation() throws Exception {
        var rows = rows();
        for (var stage : List.of("pre", "post")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, source(stage), true);
                var state = Language.currentState();
                state.getThreads().enterCurrent(null, false, true, null);
                try {
                    var scope = state.getThreadPollState().get().getAstStack$org_intelligence_thc();
                    for (var row : rows) {
                        var before = scope.getSpills();
                        assertEquals(row.expected(), Calls.target(program.hostEntryTarget(1), new Object[]{program.entryValue("probe"), new Object[]{row.input()}}), stage + "/" + row.input());
                        if (row.input() >= 5000) assertTrue(scope.getSpills() > before);
                        assertEquals(0, scope.getDepth()); assertFalse(scope.getDriving());
                    }
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { state.getThreads().leaveCurrent(); }
            } finally { context.leave(); }
        }
    }
    @Test public void firstCompiledPublicEntryRetainsItsDeepResult() throws Exception {
        rows();
        for (var stage : List.of("pre", "post")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = new Program(language, source(stage), true);
                var function = context.asValue(new EntryValue(program, "probe", 1));
                for (int i = 0; i < 10; i++) assertEquals(100L, function.execute(100L).asLong());
                assertTrue(function.invokeMember("compile").asBoolean());
                var before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(5000L, function.execute(5000L).asLong(), stage);
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                assertEquals(20000L, function.execute(20000L).asLong(), stage + "/larger input");
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
}
