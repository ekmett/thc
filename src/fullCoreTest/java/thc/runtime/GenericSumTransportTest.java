// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class GenericSumTransportTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/generic-sum-transport");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private Map<String, Object> evidence() throws Exception {
        var manifest = json(new File(directory, "manifest.json")); assertEquals(true, manifest.get("strictAccepted"));
        for (String group : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale generic sum input " + hash.getKey());
        return manifest;
    }
    private List<Map<String, Object>> modules(String stage) throws Exception {
        var files = new File(directory, stage + "/core").listFiles(); assertNotNull(files); var result = new ArrayList<Map<String, Object>>();
        for (File file : files) if (file.getName().endsWith(".cbd")) result.add(CoreCbdFixtures.read(file.toPath())); return result;
    }
    @FunctionalInterface private interface Action { void run(Context context, Language language) throws Exception; }
    private void entered(boolean inline, Action action) throws Exception {
        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inline))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(context, TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private ExecutableProgram program(Language language, String stage, String backend, String entry) throws Exception {
        var linked = CoreModules.reachable(CoreModules.merge(modules(stage)), "main:GenericSumTransport." + entry, true);
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.toString()); }
    private void compile(RootCallTarget target) throws Exception { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target); }
    private void released(Language language) {
        var state = language.getHandoffState().get(); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().retainedReferences()); assertEquals(0, state.getArguments().retainedReferences());
    }
    private CoreRepresentation result(String name) throws Exception {
        var module = CoreCbdFixtures.read(new File(directory, "pre/core/GenericSumTransport.cbd").toPath()); Map<String, Object> binding = null;
        for (var candidate : (List<Map<String, Object>>) module.get("bindings")) if (("main:GenericSumTransport." + name).equals(candidate.get("id"))) { assertNull(binding); binding = candidate; }
        assertNotNull(binding); return CoreRepresentations.lambdaResult((List<?>) binding.get("expr"));
    }
    private List<List<?>> walk(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof List<?> list) { result.add(list); for (var child : list) result.addAll(walk(child)); }
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(walk(child));
        return result;
    }
    private void observe(List<String> row, Value callable, ExecutableProgram program, Language language) {
        assertEquals(Long.parseLong(row.get(3)), callable.execute(Long.parseLong(row.get(1)), Long.parseLong(row.get(2))).asLong(), row.toString());
        assertEquals(0L, program.diagnostics().get("blackholes")); released(language);
    }
    @TestFactory public List<DynamicTest> genuineActiveValuesMatchNativeOnFirstInstalledCall() throws Exception {
        var manifest = evidence(); var rows = new LinkedHashMap<String, List<List<String>>>(); int count = 0;
        for (String line : Files.readAllLines(new File(directory, "oracle.tsv").toPath(), StandardCharsets.UTF_8)) {
            var row = Arrays.asList(line.split("\t", -1)); rows.computeIfAbsent(row.getFirst(), ignored -> new ArrayList<>()).add(row); count++;
        }
        assertEquals(manifest.get("entries"), new ArrayList<>(rows.keySet())); assertEquals(1440, count); var tests = new ArrayList<DynamicTest>();
        for (String stage : List.of("pre", "post")) {
            assertEquals(true, json(new File(directory, stage + "/audit.json")).get("accepted"));
            for (String backend : List.of("ast", "bytecode")) for (var group : rows.entrySet()) for (boolean inline : new boolean[] {true, false}) {
                String entry = group.getKey(); var cases = group.getValue();
                tests.add(DynamicTest.dynamicTest(stage + "/" + backend + "/" + entry + "/inline=" + inline, () -> entered(inline, (context, language) -> {
                    var program = program(language, stage, backend, entry); String name = "main:GenericSumTransport." + entry;
                    var callable = context.asValue(new EntryValue(program, name, 2)); for (var row : cases) observe(row, callable, program, language);
                    assertTrue(callable.invokeMember("compile").asBoolean());
                    for (var row : cases.reversed()) {
                        long before = (Long) program.diagnostics().get("compiledEntries"); observe(row, callable, program, language);
                        assertTrue((Long) program.diagnostics().get("compiledEntries") > before); valid(program.entryTarget(name)); valid(program.hostEntryTarget(2));
                    }
                })));
            }
        }
        return tests;
    }
    @Test public void originalNativeProjectionAndManagedStorageAreDifferentProofs() throws Exception {
        evidence(); var address = result("addressMake"); assertEquals(List.of("WordRep", "WordRep"), address.getPrimReps());
        assertEquals(List.of(List.of(1), List.of(1)), address.getAlternativeSlots()); var kinds = new ArrayList<CoreKind>();
        for (var storage : SumShape.storage(address)) kinds.add(storage.getKind()); assertEquals(List.of(CoreKind.LONG, CoreKind.LONG, CoreKind.ADDRESS), kinds);
        assertEquals(List.of(List.of(2), List.of(1)), SumShape.transport(address).getProjections()); var nested = result("nestedMake");
        assertEquals(7, Objects.requireNonNull(nested.getPrimReps()).size()); assertEquals(8, SumShape.storage(nested).size());
        // Canonical storage puts the managed address after every word slot, including the neighbour.
        assertEquals(List.of(List.of(2), List.of(2, 3, 5, 4), List.of(2, 6, 7), List.of(2, 1, 3)), SumShape.transport(nested).getProjections());
        var around = result("aroundMake"); TupleShape.validate(around); assertEquals(9, Objects.requireNonNull(around.getPrimReps()).size()); assertEquals(10, TupleShape.flatten(around).size());
        var inner = Objects.requireNonNull(Objects.requireNonNull(around.getComponents()).get(1).getComponents());
        assertEquals(CoreKind.VOID, inner.getFirst().getKind()); assertEquals(List.of(), inner.get(1).getComponents());
        // A physical-width edit cannot erase the authenticated logical tree.
        assertThrows(RuntimeFault.class, () -> SumShape.validate(address.copy(address.getKind(), address.getEvaluated(), address.getPresent(), List.of("WordRep", "AddrRep"), address.getComponents(), address.getVector(), address.getAlternatives(), address.getTagSlot(), address.getAlternativeSlots())));
        assertThrows(RuntimeFault.class, () -> SumShape.validate(nested.copy(nested.getKind(), nested.getEvaluated(), nested.getPresent(), nested.getPrimReps(), nested.getComponents(), nested.getVector(), nested.getAlternatives(), nested.getTagSlot(), SumShape.transport(nested).getProjections())));
        assertThrows(RuntimeFault.class, () -> SumShape.validate(nested.copy(nested.getKind(), nested.getEvaluated(), nested.getPresent(), nested.getPrimReps(), nested.getComponents(), nested.getVector(), nested.getAlternatives(), 1, nested.getAlternativeSlots())));
        for (long tag : new long[] {Long.MIN_VALUE, 0, 5, 0x100000001L, Long.MAX_VALUE}) assertThrows(RuntimeFault.class, () -> SumShape.checkedTag(tag, 4));
    }
    private void observeAddress(long selector, long bits, TupleShape shape, VirtualFrame frame, RootCallTarget target, ManagedAddress address, int[] slots, Language language) throws Exception {
        shape.consume(frame, ScalarTestCalls.callScalarTestTarget(target, new Object[] {0L, selector, address, bits}), slots, 0); assertEquals(selector + 1, frame.getLong(slots[0]));
        if (selector == 0L) { assertSame(address, frame.getObject(slots[2])); assertEquals(0L, frame.getLong(slots[1])); }
        else { assertSame(ManagedAddress.nullAddress(), frame.getObject(slots[2])); assertEquals(bits, frame.getLong(slots[1])); }
        released(language);
    }
    @Test public void addressIdentityAndPrimitiveBitsSurviveReuseWithoutRetainingInactiveBacking() throws Exception {
        evidence();
        for (String backend : List.of("ast", "bytecode")) entered(false, (context, language) -> {
            var program = program(language, "pre", backend, "addressMake"); var target = program.entryTarget("main:GenericSumTransport.addressMake");
            var shape = new TupleShape(result("addressMake"), language); assertEquals(List.of("long", "long", "reference"), shape.getLayout().getReps());
            var frameLayout = new FrameLayout(); int[] slots = new int[shape.getWidth()]; for (int i = 0; i < slots.length; i++) slots[i] = frameLayout.bind("field" + i);
            var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], frameLayout.build()); byte[] bytes = {7, 19, 31};
            var address = ManagedAddress.fromNativeImageSource(bytes, 1);
            observeAddress(0, Long.MIN_VALUE, shape, frame, target, address, slots, language); observeAddress(1, Long.MAX_VALUE, shape, frame, target, address, slots, language); compile(target);
            for (long bits : new long[] {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE}) for (long selector : new long[] {0L, 1L}) {
                long before = (Long) program.diagnostics().get("compiledEntries"); observeAddress(selector, bits, shape, frame, target, address, slots, language);
                assertTrue((Long) program.diagnostics().get("compiledEntries") > before); valid(target);
            }
        });
    }
    @Test public void exactVectorSpeciesAndManagedAddressCarriersRemainChecked() throws Exception { entered(true, (context, language) -> {
        evidence(); var vectors = result("vectorMake"); var shape = new TupleShape(vectors, language); assertEquals(List.of("long", "Vector long 2", "Vector float 4"), shape.getLayout().getReps());
        var alternatives = Objects.requireNonNull(vectors.getAlternatives()); var longVector = new VectorLayout(alternatives.getFirst()).getSpecies().zero(); assertSame(longVector, shape.checkedReference(1, longVector));
        var floatVector = new VectorLayout(alternatives.get(1)).getSpecies().zero(); assertThrows(RuntimeFault.class, () -> shape.checkedReference(1, floatVector));
        assertThrows(RuntimeFault.class, () -> shape.checkedReference(2, longVector)); assertThrows(RuntimeFault.class, () -> shape.checkedReference(1, null));
        var original = alternatives.getFirst(); var wrong = original.copy(original.getKind(), original.getEvaluated(), original.getPresent(), original.getPrimReps(), original.getComponents(), CoreVector.INT32X4, original.getAlternatives(), original.getTagSlot(), original.getAlternativeSlots());
        var changed = new ArrayList<>(alternatives); changed.set(0, wrong);
        assertThrows(RuntimeFault.class, () -> SumShape.validate(vectors.copy(vectors.getKind(), vectors.getEvaluated(), vectors.getPresent(), vectors.getPrimReps(), vectors.getComponents(), vectors.getVector(), changed, vectors.getTagSlot(), vectors.getAlternativeSlots())));
        var address = new TupleShape(result("addressMake"), language); assertThrows(RuntimeFault.class, () -> address.checkedReference(2, 7L));
    }); }
    @Test public void malformedVectorPayloadLevityRejectsOnBothBackends() throws Exception { entered(true, (context, language) -> {
        evidence();
        for (String backend : List.of("ast", "bytecode")) for (Object flag : List.of(true, "false")) {
            var source = modules("pre"); Map<String, Object> module = null;
            for (var candidate : source) if ("GenericSumTransport".equals(candidate.get("module"))) { assertNull(module); module = candidate; } assertNotNull(module);
            Map<String, Object> maker = null; for (var candidate : (List<Map<String, Object>>) module.get("bindings")) if ("main:GenericSumTransport.vectorMake".equals(candidate.get("id"))) { assertNull(maker); maker = candidate; } assertNotNull(maker);
            List<Object> constructor = null;
            for (var expression : walk(maker.get("expr"))) {
                if (expression.isEmpty() || !"app".equals(expression.getFirst()) || !(expression.get(1) instanceof List<?> head) || head.isEmpty() || !"con".equals(head.getFirst()) || !CoreRepresentations.expression(expression).isSum()) continue;
                var arguments = (List<List<?>>) expression.get(2); assertEquals(1, arguments.size());
                if (CoreRepresentations.expression(arguments.getFirst()).isVector()) { constructor = (List<Object>) expression; break; }
            }
            assertNotNull(constructor); assertEquals(List.of(false), constructor.get(3)); constructor.set(3, List.of(flag));
            var linked = CoreModules.reachable(CoreModules.merge(source), "main:GenericSumTransport.vectorCase", true);
            assertThrows(RuntimeFault.class, () -> { if (backend.equals("ast")) new Program(language, linked); else new BytecodeProgram(language, linked); }, backend + "/flag=" + flag);
        }
    }); }
    private DataValue withTags(DataLayout layout, DataValue value, long outer) {
        var changed = layout.allocate(); for (int i = 0; i < layout.getArity(); i++) {
            if (i == 1) layout.initializeLong(changed, i, outer); else if (i == 3) layout.initializeLong(changed, i, 0L); else layout.copyCompactScalar(value, changed, i);
        }
        return changed;
    }
    @Test public void nestedHeapReferenceActivityFollowsBothTagsAndPhysicalNeighbors() throws Exception {
        evidence();
        for (String backend : List.of("ast", "bytecode")) entered(true, (context, language) -> {
            var program = program(language, "pre", backend, "save"); var target = program.entryTarget("main:GenericSumTransport.save");
            for (long selector = 0; selector <= 15; selector++) {
                var value = (DataValue) Calls.target(target, new Object[] {0L, selector, Long.MIN_VALUE, thc.runtime.Unit.INSTANCE}); var layout = value.getLayout();
                assertEquals(3, layout.getLogicalArity()); assertEquals(10, layout.getArity()); assertEquals(Long.MIN_VALUE, layout.readLong(value, 0)); assertEquals(Long.MAX_VALUE, layout.readLong(value, 9));
                assertTrue(layout.compactPointer(2)); boolean active = (selector & 3L) == 3L && (selector & 4L) == 0L;
                assertEquals(!active, layout.inactiveSumReference(value, 2), "selector=" + selector); if (active) assertNotNull(layout.read(value, 2)); else assertNull(layout.read(value, 2));
                if (selector == 3L) {
                    // Only the selected nested sum's tag is meaningful. An
                    // inactive nested tag can be zero; the active one cannot.
                    var malformed = withTags(layout, value, 4L); assertThrows(RuntimeFault.class, () -> layout.inactiveSumReference(malformed, 2));
                    var inactive = withTags(layout, value, 3L); assertTrue(layout.inactiveSumReference(inactive, 2));
                }
                released(language);
            }
        });
    }
}
