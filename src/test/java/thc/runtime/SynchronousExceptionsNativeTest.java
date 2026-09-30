// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
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
public class SynchronousExceptionsNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/synchronous-exceptions");
    private final List<String> supported = List.of("preciseCatch", "erasedNestedCatch", "actionHeadCatch", "ignoredBottomPayload", "nestedRethrow",
        "unusedHandler", "lazyResultBoundary", "restoreAndRethrow", "handlerMaskState", "maskNested", "maskRethrowRestore", "noDuplicateProbe");
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private record Row(long input, long expected) {}
    @Test public void genuineNativeExceptionsMatchBothBackends() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        for (var key : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<?, ?>) manifest.get(key)).entrySet()) {
            var path = (String) item.getKey();
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, path).toPath())));
            assertEquals(item.getValue(), hash, "Stale exception fixture: " + path);
        }
        var statuses = (Map<String, Map<String, Object>>) manifest.get("auditStatus");
        boolean accepted = true; for (var status : statuses.values()) accepted &= Boolean.TRUE.equals(status.get("accepted"));
        assertTrue(accepted);
        var inputs = new ArrayList<Long>(); for (var input : (List<?>) manifest.get("inputs")) inputs.add(((Number) input).longValue());
        var cases = new LinkedHashMap<String, List<Row>>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var fields = line.split("\t", -1);
            cases.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(new Row(Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        assertEquals(169, inputs.size());
        int count = 0; for (var rows : cases.values()) count += rows.size();
        assertEquals(supported.size() * inputs.size(), count);
        for (var stage : List.of("pre", "post")) {
            var paths = ((Map<String, List<String>>) manifest.get("stages")).get(stage);
            var modules = new ArrayList<Map<String, Object>>();
            for (var path : paths) modules.add(CoreCbdFixtures.read(new File(root, path).toPath()));
            var module = CoreModules.merge(modules);
            for (var backend : List.of("ast", "bytecode")) for (var name : supported) {
                var selected = cases.get(name); var actualInputs = new ArrayList<Long>(); for (var row : selected) actualInputs.add(row.input());
                assertEquals(inputs, actualInputs);
                try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:SynchronousExceptionsAudit." + name)); linked.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var function = context.asValue(new EntryValue(program, "main:SynchronousExceptionsAudit." + name, 1));
                        var label = stage + "/" + backend + "/" + name;
                        for (var row : selected) assertEquals(row.expected(), function.execute(row.input()).asLong(), label + "/" + row.input());
                        compile(program.entryTarget("main:SynchronousExceptionsAudit." + name));
                        assertTrue(function.invokeMember("compile").asBoolean(), label + " host compilation");
                        for (var row : selected.subList(0, Math.min(2, selected.size()))) {
                            var before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.expected(), function.execute(row.input()).asLong(), label + " compiled/" + row.input());
                            var after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertTrue(after > before, label + " entered compiled guest code");
                        }
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(program.entryTarget("main:SynchronousExceptionsAudit." + name).getRootNode()), label + " restored caller masking state");
                        if (name.equals("handlerMaskState")) {
                            var node = program.entryTarget("main:SynchronousExceptionsAudit." + name).getRootNode();
                            // Preserve outer guest lifetime while testing nested restoration.
                            var threads = Language.currentState(node).getThreads();
                            threads.enterCurrent(null, false, true, null);
                            try {
                                SynchronousMasking.set(node, MaskingState.MASKED_UNINTERRUPTIBLE);
                                assertEquals(34L, function.execute(0L).asLong(), label + " nested unmask");
                                assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(node), label + " restored masked caller");
                            } finally { threads.leaveCurrent(); }
                            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(node), label + " completed outer guest entry");
                        }
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue(), label);
                        assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue(), label);
                    } finally { context.leave(); }
                }
            }
        }
    }
}
