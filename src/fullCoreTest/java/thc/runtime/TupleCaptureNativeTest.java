// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class TupleCaptureNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/tuple-capture");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString()); }
    @TestFactory public List<DynamicTest> originalTupleCapturesInline() throws Exception { return nativeTests(true); }
    @TestFactory public List<DynamicTest> originalTupleCapturesResidual() throws Exception { return nativeTests(false); }
    private Object retain(ExecutableProgram program, String name, long x) {
        var data = (DataValue) Calls.target(program.hostEntryTarget(1), new Object[] {program.entryValue("main:TupleCaptureAudit." + name), new Object[] {x}});
        return data.getLayout().read(data, 0);
    }
    private <T> List<T> instances(List<Object> fields, Class<T> type) { var result = new ArrayList<T>(); for (var field : fields) if (type.isInstance(field)) result.add(type.cast(field)); return result; }
    private <T> T single(List<T> values) { assertEquals(1, values.size()); return values.getFirst(); }
    private boolean containsIdentity(Object[] values, Object expected) { for (var value : values) if (value == expected) return true; return false; }
    @TestFactory public List<DynamicTest> originalCapturedPropertiesRemainLazyAndSurviveTheirCreator() {
        var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/owned storage", () -> {
            verifyEvidence(); var source = json(new File(directory, stage + "/core/TupleCaptureAudit.json"));
            var linked = new LinkedHashMap<>(CoreModules.reachable(source, List.of("main:TupleCaptureAudit.escaped", "main:TupleCaptureAudit.thunk", "main:TupleCaptureAudit.emptyCapture"), true)); linked.put("instrument", true);
            try (Context context = Context.newBuilder("thc").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var closure = (Closure) retain(program, "retainedClosure", 17L); var empty = (Closure) retain(program, "retainedEmpty", 0L);
                    assertNull(empty.environment, "A zero-storage tuple does not allocate capture properties"); assertEquals(0, ClosureInspection.image(empty).getPointers().length);
                    var environment = Objects.requireNonNull(closure.environment); assertEquals(6, environment.getLayout().getStorageSize());
                    var fields = new ArrayList<Object>(); for (int i = 0; i < environment.getLayout().getStorageSize(); i++) fields.add(environment.getLayout().inspect(environment, i));
                    assertEquals(3.75f, single(instances(fields, Float.class))); assertEquals(-5.25, single(instances(fields, Double.class)));
                    assertEquals(1, instances(fields, ManagedAddress.class).size()); var lazy = single(instances(fields, Thunk.class)); assertEquals(0, lazy.getState());
                    var image = ClosureInspection.image(closure); assertEquals(56, image.getBytes().length); assertEquals(2, image.getPointers().length); assertTrue(containsIdentity(image.getPointers(), lazy));
                    retain(program, "retainedClosure", -29L); assertEquals(4L * 17 + 3L * 13 + 143, Calls.target(program.hostEntryTarget(1), new Object[] {closure, new Object[] {13L}}));
                    assertEquals(0, lazy.getState(), "Inspecting and invoking the capture must not force its ignored field");
                    var thunk = (Thunk) retain(program, "retainedThunk", 17L); assertEquals(0, thunk.getState()); var thunkImage = ClosureInspection.image(thunk);
                    assertEquals(2, thunkImage.getPointers().length); assertTrue(containsIdentity(thunkImage.getPointers(), lazy));
                    long before = (Long) program.diagnostics().get("thunkEvaluations"); var first = (DataValue) Calls.target(program.hostEntryTarget(0), new Object[] {thunk});
                    assertEquals(before + 1, program.diagnostics().get("thunkEvaluations")); assertEquals(214L, first.getLayout().read(first, 0));
                    assertSame(first, Calls.target(program.hostEntryTarget(0), new Object[] {thunk})); assertEquals(before + 1, program.diagnostics().get("thunkEvaluations"));
                    assertEquals(0, lazy.getState()); assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences()); assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                } finally { context.leave(); }
            }
        }));
        return tests;
    }
    private void verifyEvidence() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (String group : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale tuple capture fixture " + hash.getKey());
    }
    private List<DynamicTest> nativeTests(boolean inlining) throws Exception {
        verifyEvidence(); var rows = new LinkedHashMap<String, List<List<String>>>(); int count = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); count++;
        }
        assertEquals(8, rows.size()); assertEquals(296, count); var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted")); var source = json(new File(directory, stage + "/core/TupleCaptureAudit.json"));
            for (var group : rows.entrySet()) {
                String entry = group.getKey(), name = "main:TupleCaptureAudit." + entry; var cases = group.getValue();
                var linked = new LinkedHashMap<>(CoreModules.reachable(source, name, true)); linked.put("instrument", true);
                for (String backend : List.of("ast", "bytecode")) tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry + "/inlining=" + inlining, () -> {
                    try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
                        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                        context.initialize("thc"); context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                            var callable = context.asValue(new EntryValue(program, name, 1));
                            for (var row : cases) assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), row.get(1));
                            assertTrue(callable.invokeMember("compile").asBoolean());
                            for (var row : cases.reversed()) {
                                long before = (Long) program.diagnostics().get("compiledEntries");
                                assertEquals(Long.parseLong(row.get(2)), callable.execute(Long.parseLong(row.get(1))).asLong(), row.get(1));
                                assertTrue((Long) program.diagnostics().get("compiledEntries") > before); valid(program.entryTarget(name)); valid(program.hostEntryTarget(1));
                            }
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().getDepth());
                            assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().retainedReferences());
                        } finally { context.leave(); }
                    }
                }));
            }
        }
        return tests;
    }
}
