// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SmallArrayTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> manifest() throws Exception {
        return module("build/small-arrays/manifest.json");
    }
    private Map<String, Object> module(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve(path)));
    }
    private Context context() {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .build();
    }
    private ExecutableProgram program(Language language, Map<String, Object> linked, String backend) {
        return backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private long call(ExecutableProgram program, String entry, long input) {
        return (Long) Calls.target(
            program.hostEntryTarget(1), new Object[] {program.entryValue("main:SmallArrayAudit." + entry), new Object[] {input}});
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()))
                result.add((List<Object>) list);
            for (var item : list) result.addAll(applications(item)); }
        else if (value instanceof Map<?, ?> map)
            for (var item : map.values()) result.addAll(applications(item));
        return result;
    }
    private record Row(long input, long existing, long safe) {}

    @Test
    void nativeCompositeUsesExactSmallArrayShapesAndRunsCompiledInBothBackends() throws Exception {
        var manifest = manifest();
        for (String field : List.of("inputHashes", "artifactHashes"))
            for (var hash : ((Map<String, String>) manifest.get(field)).entrySet()) {
                String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(hash.getKey()))));
                assertEquals(hash.getValue(), actual, "Stale SmallArray evidence: " + hash.getKey());
            }
        var rows = Files.readAllLines(root.resolve("build/small-arrays/oracle.tsv"))
                       .stream()
                       .map(line -> line.split("\t", -1))
                       .toList();
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rows.size());
        var cases = rows.stream()
                        .map(row -> {
                            assertEquals(3, row.length);
                            return new Row(Long.parseLong(row[0]), Long.parseLong(row[1]), Long.parseLong(row[2]));
                        })
                        .toList();
        for (var row : cases) {
            assertEquals(68 * row.input + 702, row.existing, "native existing model " + row.input);
            assertEquals(17 * row.input + 224, row.safe, "native safe-slice model " + row.input);
        }
        // CAS and shrink have native corpora in BoxedCasTest and ScalarMemoryUtilitiesTest.
        var names = new HashSet<>(Arrays.stream(SmallArrayOp.values())
                .filter(op -> op != SmallArrayOp.CAS && op != SmallArrayOp.SHRINK)
                .map(SmallArrayOp::getPrimitive)
                .toList());
        for (var stagePath : ((Map<String, String>) manifest.get("stages")).entrySet()) {
            String stage = stagePath.getKey();
            String path = stagePath.getValue();
            String auditPath = ((Map<String, String>) manifest.get("audits")).get(stage);
            assertEquals(true, module(auditPath).get("accepted"));
            var entries = List.of("smallComposite", (String) manifest.get("safeEntry"));
            var linked = new LinkedHashMap<String, Map<String, Object>>();
            for (String entry : entries) linked.put(entry, CoreModules.reachable(thc.CoreCbdFixtures.read(root.resolve(path)), "main:SmallArrayAudit." + entry));
            var apps = linked.values()
                           .stream()
                           .flatMap(source -> applications(source).stream())
                           .filter(app
                               -> app.get(1) instanceof List<?> callee && !callee.isEmpty()
                                   && "prim".equals(callee.getFirst()))
                           .toList();
            var direct = apps.stream().map(app -> (String) ((List<?>) app.get(1)).get(1)).toList();
            assertTrue(direct.containsAll(names), stage + " missing SmallArray primitive");
            var safeApps = applications(linked.get("safeSliceComposite"))
                               .stream()
                               .filter(app
                                   -> app.get(1) instanceof List<?> callee && !callee.isEmpty()
                                       && "prim".equals(callee.getFirst()))
                               .map(app -> (String) ((List<?>) app.get(1)).get(1))
                               .toList();
            assertTrue(safeApps.containsAll(List.of("freezeSmallArray#", "thawSmallArray#")),
                stage + " safe-slice root lost its copying operations");
            for (var app : apps.stream().filter(value -> names.contains(((List<?>) value.get(1)).get(1))).toList()) {
                String name = (String) ((List<?>) app.get(1)).get(1);
                var operation = Objects.requireNonNull(SmallArrayOp.named(name));
                var args = (List<Object>) app.get(2);
                operation.validate(
                    args.stream().map(arg -> CoreRepresentations.expression((List<Object>) arg)).toList(),
                    (List<?>) app.get(3), CoreRepresentations.expression(app));
                if (name.equals("indexSmallArray#")) {
                    var result = CoreRepresentations.expression(app);
                    assertEquals(
                        1, Objects.requireNonNull(result.getComponents()).size(), stage + " indexed element tuple");
                    assertEquals(List.of("BoxedRep (Just Lifted)"), result.getComponents().getFirst().getPrimReps());
                }
            }
            for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (String entry : entries) {
                            var guest = program(language, linked.get(entry), backend);
                            for (var row : cases) {
                                long expected = entry.equals("smallComposite") ? row.existing : row.safe;
                                assertEquals(expected, call(guest, entry, row.input),
                                    stage + "/" + backend + "/" + entry + "/interpreted/" + row.input);
                            }
                            var target = guest.entryTarget("main:SmallArrayAudit." + entry);
                            compile(target);
                            for (var row : cases) {
                                long expected = entry.equals("smallComposite") ? row.existing : row.safe;
                                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                                assertEquals(expected, call(guest, entry, row.input),
                                    stage + "/" + backend + "/" + entry + "/compiled/" + row.input);
                                assertTrue(((Number) guest.diagnostics().get("compiledEntries")).longValue() > before,
                                    stage + "/" + backend + "/" + entry + "/" + row.input
                                        + " executed compiled guest code");
                                valid(target);
                            }
                            assertEquals(0L, ((Number) guest.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    } finally {
                        context.leave();
                    }
                }
        }
    }

    @Test
    void smallStorageKeepsLiftedReferencesAndRejectsOrdinaryArrayAliases() {
        int[] entered = {0};
        var bottom = new Thunk(new RootNode(null) {
            @Override
            public Object execute(VirtualFrame frame) {
                entered[0]++;
                throw new RuntimeFault("SmallArray initializer entered");
            }
        }.getCallTarget(), null);
        var replacement = new Object();
        var small = ManagedSmallArray.allocate(2, bottom);
        assertEquals(2L, ManagedSmallArray.size(small));
        assertSame(bottom, ManagedSmallArray.read(small, 0));
        assertSame(bottom, ManagedSmallArray.read(small, 1));
        ManagedSmallArray.write(small, 1, replacement);
        assertSame(bottom, ManagedSmallArray.read(small, 0));
        assertSame(replacement, ManagedSmallArray.read(ManagedSmallArray.freeze(small), 1));
        assertSame(small, ManagedSmallArray.freeze(small));
        var cloned = ManagedSmallArray.slice(small, 0, 2);
        assertNotSame(small, cloned);
        assertSame(bottom, ManagedSmallArray.read(cloned, 0));
        ManagedSmallArray.write(cloned, 1, bottom);
        assertSame(replacement, ManagedSmallArray.read(small, 1));
        var destination = ManagedSmallArray.allocate(2, null);
        ManagedSmallArray.copy(small, 0, destination, 0, 2, false);
        assertSame(bottom, ManagedSmallArray.read(destination, 0));
        assertSame(replacement, ManagedSmallArray.read(destination, 1));
        ManagedSmallArray.copy(destination, 0, destination, 1, 1, true);
        assertSame(bottom, ManagedSmallArray.read(destination, 1));
        assertThrows(RuntimeFault.class, () -> ManagedSmallArray.copy(small, 0, small, 0, 0, false));
        for (long[] range : List.of(new long[] {-1, 0}, new long[] {0, -1}, new long[] {2, 1},
                 new long[] {Long.MAX_VALUE, 0}, new long[] {0, Long.MAX_VALUE})) {
            long offset = range[0], count = range[1];
            assertThrows(RuntimeFault.class, () -> ManagedSmallArray.slice(small, offset, count));
            var before = destination.getElements().clone();
            assertThrows(RuntimeFault.class, () -> ManagedSmallArray.copy(small, offset, destination, 0, count, false));
            assertArrayEquals(before, destination.getElements());
        }
        assertEquals(0, entered[0], "SmallArray storage must not enter lifted elements");
        assertThrows(RuntimeFault.class, () -> ManagedArray.require(small));
        assertThrows(RuntimeFault.class, () -> ManagedSmallArray.require(ManagedArray.allocate(1, bottom)));
        for (long index : List.of(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault.class, () -> ManagedSmallArray.read(small, index));
            assertThrows(RuntimeFault.class, () -> ManagedSmallArray.write(small, index, replacement));
        }
        for (long size : List.of(-1L, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE))
            assertThrows(RuntimeFault.class, () -> ManagedSmallArray.allocate(size, bottom));
    }

    @Test
    void transferChecksStateBeforeMutating() {
        var source = ManagedSmallArray.allocate(1, new Object());
        var original = new Object();
        var destination = ManagedSmallArray.allocate(1, original);
        var events = new ArrayList<String>();
        class Operands {
            Expr operand(String label, Object value) {
                return new Expr() {
                    @Override
                    public Object execute(VirtualFrame frame) {
                        events.add(label);
                        return value;
                    }
                };
            }
        }
        var op = new Operands();
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        for (var operation : List.of(SmallArrayOp.COPY, SmallArrayOp.COPY_MUTABLE)) {
            events.clear();
            var expr = SmallArrayOp.expression(operation, CoreRepresentation.UNKNOWN,
                new Expr[] {op.operand("source", source), op.operand("from", 0L),
                    op.operand("destination", destination), op.operand("to", 0L), op.operand("count", 1L),
                    op.operand("state", 9L)});
            assertThrows(RuntimeFault.class, () -> expr.execute(frame));
            assertEquals(List.of("source", "from", "destination", "to", "count", "state"), events);
            assertSame(original, ManagedSmallArray.read(destination, 0));
        }
    }

    @Test
    void safeSlicesKeepLazyElementsButDoNotShareMutableStorage() {
        int[] entered = {0};
        var bottom = new Thunk(new RootNode(null) {
            @Override
            public Object execute(VirtualFrame frame) {
                entered[0]++;
                throw new RuntimeFault("Safe slice forced a lifted element");
            }
        }.getCallTarget(), null);
        var source = ManagedSmallArray.allocate(3, bottom);
        var original = new Object();
        var changed = new Object();
        ManagedSmallArray.write(source, 1, original);
        var frozen = ManagedSmallArray.slice(source, 1, 2);
        assertNotSame(source, frozen);
        assertSame(original, ManagedSmallArray.read(frozen, 0));
        assertSame(bottom, ManagedSmallArray.read(frozen, 1));
        ManagedSmallArray.write(source, 1, changed);
        assertSame(original, ManagedSmallArray.read(frozen, 0));
        var thawed = ManagedSmallArray.slice(frozen, 0, 2);
        assertNotSame(frozen, thawed);
        ManagedSmallArray.write(thawed, 0, changed);
        assertSame(original, ManagedSmallArray.read(frozen, 0));
        assertSame(changed, ManagedSmallArray.read(thawed, 0));
        assertEquals(0L, ManagedSmallArray.size(ManagedSmallArray.slice(source, 0, 0)));
        assertEquals(0L, ManagedSmallArray.size(ManagedSmallArray.slice(frozen, 2, 0)));
        assertEquals(0, entered[0]);
    }
}