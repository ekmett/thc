// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.Unit;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ArraySliceTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<ArrayOp> operations = List.of(ArrayOp.CLONE, ArrayOp.FREEZE_COPY, ArrayOp.THAW);
    private final List<String> names =
        List.of("sliceSnapshots", "lazySlices", "closureSlices", "zeroSlices", "publicSlices");
    private Context context() {
        return context(true);
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.Inlining", Boolean.toString(inlining))
            .build();
    }
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve(path)));
    }
    private Map<String, Object> manifest() throws Exception {
        return json("build/array-slices/manifest.json");
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (String path : paths) modules.add(json(path));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Map<String, Object> changed(Map<String, Object> source, String key, Object value) {
        var result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "installed");
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var result = new ArrayList<RootCallTarget>();
        class Visitor {
            void visit(RootCallTarget target) {
                if (!seen.add(target))
                    return;
                var body = target.getRootNode();
                var nodes = new ArrayList<Node>();
                nodes.add(body);
                if (body instanceof BytecodeRoot bytecode)
                    for (var instruction : bytecode.getBytecodeNode().getInstructions())
                        for (var argument : instruction.getArguments())
                            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                                var cached = argument.asCachedNode();
                                if (cached != null)
                                    nodes.add(cached);
                            }
                for (var node : nodes)
                    for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                        if (call.getCurrentCallTarget() instanceof RootCallTarget active
                            && active.getRootNode() instanceof GuestRoot)
                            visit(active);
                result.add(target);
            }
        }
        new Visitor().visit(entry);
        return result;
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private record Row(long input, long expected) {}
    @Test
    void nativeSlicesAndPublicArrayConsumersWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    void nativeSlicesAndPublicArrayConsumersAcrossResidualCalls() throws Exception {
        nativeChecks(false);
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest();
        for (String kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(item.getKey()))));
                assertEquals(item.getValue(), hash, "Stale array-slice input " + item.getKey());
            }
        var rows = new LinkedHashMap<String, List<String[]>>();
        for (String line : Files.readAllLines(root.resolve("build/array-slices/oracle.tsv"))) {
            var fields = line.split("\t", -1);
            if (names.contains(fields[0]))
                rows.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(fields);
        }
        assertEquals(((Number) manifest.get("supportedNativeRows")).intValue(),
            rows.values().stream().mapToInt(List::size).sum());
        for (var stagePaths : ((Map<String, List<String>>) manifest.get("stages")).entrySet())
            for (String name : names) {
                String stage = stagePaths.getKey();
                var paths = stagePaths.getValue();
                var cases = rows.get(name)
                                .stream()
                                .map(row -> new Row(Long.parseLong(row[1]), Long.parseLong(row[2])))
                                .toList();
                var coefficients =
                    Map.of("sliceSnapshots", new long[] {56, 1119}, "lazySlices", new long[] {8, 116}, "closureSlices",
                        new long[] {2, 1}, "zeroSlices", new long[] {1, 0}, "publicSlices", new long[] {15, 207});
                long a = coefficients.get(name)[0], b = coefficients.get(name)[1];
                for (var row : cases)
                    assertEquals(a * row.input + b, row.expected, "Native/model " + name + "(" + row.input + ")");
                var audit = json("build/array-slices/" + stage + "-" + name + ".audit.json");
                assertEquals(true, audit.get("accepted"));
                for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var guest = program(language,
                                changed(CoreModules.reachable(merged(paths), name), "instrument", true), backend);
                            var function = context.asValue(new EntryValue(guest, name, 1));
                            var host = guest.hostEntryTarget(1);
                            var original = guest.entryTarget(name);
                            String label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                            class Checks {
                                void check(Row row) {
                                    assertEquals(
                                        row.expected, function.execute(row.input).asLong(), label + "/" + row.input);
                                    released(language);
                                }
                            }
                            var checks = new Checks();
                            for (var row : cases) checks.check(row);
                            var targets = activeTargets(host);
                            assertTrue(targets.size() > 1, label);
                            for (var target : targets)
                                if (target != host)
                                    compile(target);
                            assertTrue(function.invokeMember("compile").asBoolean());
                            long allocations = language.getHandoffState().get().getResults().getAllocations();
                            assertEquals(
                                0L, allocations, label + " saturated primitive results use direct destinations");
                            for (var row : cases.reversed()) {
                                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                                checks.check(row);
                                assertTrue(((Number) guest.diagnostics().get("compiledEntries")).longValue() > before,
                                    label + " compiled guest");
                                assertEquals(targets, activeTargets(host), label + " active identities");
                                valid(original, label);
                                for (var target : targets) valid(target, label);
                            }
                            assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations(),
                                label + " pool reused");
                            for (String counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) guest.diagnostics().get(counter)).longValue(), label);
                            System.out.println("ArraySlice PASS " + label + " rows=" + cases.size());
                        } finally {
                            context.leave();
                        }
                    }
            }
    }
    @Test
    void slicesCopyReferencesIntoIndependentStorageWithFullWidthBounds() {
        var a = new Object();
        byte[] unlifted = {7};
        var b = new Object();
        Object[] source = {a, null, unlifted, b, a};
        for (int start = 0; start <= source.length; start++)
            for (int count = 0; count <= source.length - start; count++) {
                var copy = ManagedArray.slice(source, start, count);
                assertNotSame(source, copy);
                assertEquals(count, copy.length);
                assertEquals(Object[].class, copy.getClass());
                for (int i = 0; i < copy.length; i++) assertSame(source[start + i], copy[i]);
                if (count > 0) {
                    var old = source[start];
                    copy[0] = new Object();
                    assertSame(old, source[start]);
                }
            }
        assertNotSame(ManagedArray.slice(source, 5, 0), ManagedArray.slice(source, 5, 0));
        for (var range : invalidRanges()) {
            var before = source.clone();
            assertThrows(RuntimeFault.class, () -> ManagedArray.slice(source, range[0], range[1]));
            assertArrayEquals(before, source);
        }
    }
    private List<long[]> invalidRanges() {
        return List.of(new long[] {Long.MIN_VALUE, 0}, new long[] {-1, 0}, new long[] {6, 0}, new long[] {0, -1},
            new long[] {0, Long.MIN_VALUE}, new long[] {0, Long.MAX_VALUE}, new long[] {Long.MAX_VALUE, 1},
            new long[] {1L << 32, 0}, new long[] {1, Long.MAX_VALUE}, new long[] {4, 2}, new long[] {5, 1});
    }
    @Test
    void stateFailureCannotPublishCopyAndOperandsAreEvaluatedInOrder() throws Exception {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Object, "destination", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        var sentinel = new Object();
        Object[] source = {new Object()};
        var events = new ArrayList<String>();
        class Operands {
            Expr operand(String name, Object value) {
                return new Expr() {
                    @Override
                    public Object execute(VirtualFrame frame) {
                        events.add(name);
                        return value;
                    }
                };
            }
        }
        var operands = new Operands();
        for (var operation : List.of(ArrayOp.FREEZE_COPY, ArrayOp.THAW)) {
            frame.setObject(slot, sentinel);
            events.clear(); var expression = ArrayOp.expression(operation, CoreRepresentation.UNKNOWN, new Expr[]{operands.operand("array", source), operands.operand("offset", Long.MAX_VALUE), operands.operand("count", Long.MAX_VALUE), new Expr() { @Override public Object execute(VirtualFrame frame) { events.add("state"); throw new RuntimeFault("state first");
        }
    }
});
var failure = assertThrows(RuntimeFault.class, () -> expression.executeTuple(frame, new int[] {slot}, 0));
assertEquals("state first", failure.getMessage());
assertSame(sentinel, frame.getObject(slot));
assertEquals(List.of("array", "offset", "count", "state"), events);
}
}
private Map<String, Object> synthetic(ArrayOp operation) {
    Map<String, Object> array = Map.of(
                            "kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true),
                        integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true),
                        state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true),
                        closure =
                            Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    var proofs = new ArrayList<>(List.of(array, integer, integer));
    if (operation.getTuple())
        proofs.add(state);
    var params = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < proofs.size(); i++) params.add(Map.of("id", "p" + i, "lifted", false, "rep", proofs.get(i)));
    var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(state, array), "primReps",
        array.get("primReps"), "evaluated", true);
    var app = List.of("app", List.of("prim", operation.getPrimitive()),
        params.stream().map(param -> List.of("var", param.get("id"), Map.of("rep", param.get("rep")))).toList(),
        Collections.nCopies(params.size(), false), false, false, Map.of("rep", operation.getTuple() ? tuple : array));
    var body = !operation.getTuple()
        ? app
        : List.of("case", app, "pair",
              List.of(List.of("data", "Tuple2", List.of("state", "copy"), List.of("var", "copy", Map.of("rep", array)),
                  Map.of("binders",
                      List.of(Map.of("id", "state", "lifted", false, "rep", state),
                          Map.of("id", "copy", "lifted", false, "rep", array))))),
              Map.of("rep", array, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
    return Map.of("instrument", true, "constructors",
        List.of(Map.of("id", "Tuple2", "kind", "unboxed-tuple", "arity", 2, "fieldReps",
            List.of(List.of(), List.of("BoxedRep (Just Unlifted)")), "fieldLifted", List.of(false, false),
            "strictFields", List.of(false, false))),
        "bindings",
        List.of(Map.of("id", "copy", "name", "copy", "arity", params.size(), "lifted", true, "rep", closure, "expr",
            List.of("lam", params, body, Map.of("rep", closure, "resultRep", array)))));
}
@Test
void bothBackendsPreserveLazyReferenceIdentityAndRejectInvalidStateAndSlices() throws Exception {
    for (String backend : List.of("ast", "bytecode"))
        for (var operation : operations) try (var context = context()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var guest = program(language, synthetic(operation), backend);
                    var entry = guest.entryTarget("copy");
                    int[] entered = {0};
                    var bottom = new Thunk(new RootNode(null) {
                        @Override
                        public Object execute(VirtualFrame frame) {
                            entered[0]++;
                            throw new RuntimeFault("copied thunk entered");
                        }
                    }.getCallTarget(), null);
                    Object[] source = {bottom, new Object(), new byte[] {1}, bottom, null};
                    class CallsHere {
                        Object call(long offset, long count) {
                            return call(offset, count, Unit.INSTANCE, source);
                        }
                        Object call(long offset, long count, Object state, Object storage) {
                            return Calls.target(entry,
                                operation.getTuple() ? new Object[] {0L, storage, offset, count, state}
                                                     : new Object[] {0L, storage, offset, count});
                        }
                    }
                    var calls = new CallsHere();
                    for (int start = 0; start <= 5; start++)
                        for (int count = 0; count <= 5 - start; count++) calls.call(start, count);
                    compile(entry);
                    for (int start = 0; start <= 5; start++)
                        for (int count = 0; count <= 5 - start; count++) {
                            long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                            var result = ManagedArray.require(calls.call(start, count));
                            assertNotSame(source, result);
                            assertEquals(count, result.length);
                            for (int i = 0; i < result.length; i++) assertSame(source[start + i], result[i]);
                            assertEquals(before + 1, ((Number) guest.diagnostics().get("compiledEntries")).longValue());
                            valid(entry, backend + "/" + operation);
                            released(language);
                        }
                    for (var range : invalidRanges())
                        assertThrows(RuntimeFault.class, () -> calls.call(range[0], range[1]));
                    if (operation.getTuple()) {
                        var error = assertThrows(
                            RuntimeFault.class, () -> calls.call(Long.MAX_VALUE, Long.MAX_VALUE, 17L, source));
                        assertTrue(Objects.toString(error.getMessage(), "").contains("zero-width scalar carrier"),
                            error.getMessage());
                    }
                    for (var bad : List.of(new Object(), new byte[] {1}, new String[] {"bad component type"}))
                        assertThrows(RuntimeFault.class, () -> calls.call(0, 0, Unit.INSTANCE, bad));
                    assertEquals(0, entered[0]);
                    released(language);
                } finally {
                    context.leave();
                }
            }
}
private List<List<Object>> applications(Object value) {
    var result = new ArrayList<List<Object>>();
    if (value instanceof List<?> list) {
        if (!list.isEmpty() && "app".equals(list.getFirst()))
            result.add((List<Object>) list);
        for (var item : list) result.addAll(applications(item)); } else if (value instanceof Map<?, ?> map)
        for (var item : map.values()) result.addAll(applications(item));
    return result;
}
private List<Object> application(Map<String, Object> module, ArrayOp operation) {
    return applications(module)
        .stream()
        .filter(app -> {
            var callee = (List<?>) app.get(1);
            return callee.subList(0, Math.min(2, callee.size())).equals(List.of("prim", operation.getPrimitive()));
        })
        .findFirst()
        .orElseThrow();
}
@Test
void exactSliceSignaturesAndColdPublicFrontierRemainEnforced() throws Exception {
    var paths = ((Map<String, List<String>>) manifest().get("stages")).get("pre");
    for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var operation : operations)
                    for (int mutation = 0; mutation <= 10; mutation++)
                        for (boolean diagnostic : List.of(false, true)) {
                            var module = CoreModules.reachable(merged(paths), "sliceSnapshots");
                            var app = application(module, operation);
                            var args = (List<Object>) app.get(2);
                            var flags = (List<Object>) app.get(3);
                            var meta = CoreRepresentations.metadata(app);
                            switch (mutation) {
                                case 0 -> {
                                    args.removeLast();
                                    flags.removeLast();
                                    meta.remove("callDemand");
                                }
                                case 1 -> {
                                    args.add(args.getFirst());
                                    flags.add(false);
                                    meta.remove("callDemand");
                                }
                                case 2 -> meta.remove("rep");
                                case 3 -> flags.set(0, true);
                                case 4 ->
                                    ((Map<String, Object>) CoreRepresentations
                                            .metadata((List<Object>) args.get(0))
                                            .get("rep"))
                                        .put("primReps", List.of("BoxedRep (Just Lifted)"));
                                case 5, 6, 7, 8 ->
                                    ((Map<String, Object>) CoreRepresentations
                                            .metadata((List<Object>) args.get(mutation % 2 == 0 ? 2 : 1))
                                            .get("rep"))
                                        .put("primReps", List.of(mutation < 7 ? "WordRep" : "Int64Rep"));
                                case 9 ->
                                    meta.put("rep",
                                        Map.of("kind", "object", "primReps", List.of("BoxedRep Nothing"), "evaluated",
                                            true));
                                case 10 -> {
                                    if (operation.getTuple()) {
                                        var proof = (Map<String, Object>) meta.get("rep");
                                        ((List<Object>) proof.get("components"))
                                            .set(0,
                                                Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps",
                                                    List.of(), "components", List.of(), "evaluated", true));
                                    } else
                                        meta.put("rep",
                                            Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
                                                List.of(meta.get("rep")), "primReps",
                                                List.of("BoxedRep (Just Unlifted)"), "evaluated", true));
                                }
                            }
                            assertThrows(RuntimeFault.class,
                                ()
                                    -> program(language, changed(module, "diagnosticUnsupported", diagnostic), backend),
                                operation + "/" + mutation + "/" + backend + "/" + diagnostic);
                        }
                for (var operation : operations) {
                    var module = CoreModules.reachable(merged(paths), "sliceSnapshots");
                    var app = application(module, operation);
                    var primitive = new ArrayList<>((List<?>) app.get(1));
                    app.clear();
                    app.addAll(primitive);
                    assertThrows(UnsupportedCore.class, () -> program(language, module, backend));
                }
                assertThrows(UnsupportedCore.class,
                    () -> program(language, CoreModules.reachable(merged(paths), "publicFreezeThaw"), backend));
            } finally {
                context.leave();
            }
        }
}
}