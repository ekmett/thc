// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import thc.runtime.Unit;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ResizeByteArrayTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("resizedBytes", "resizedTwiceWrites");
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.Inlining", Boolean.toString(inlining))
            .build();
    }
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/resize-bytearrays/manifest.json").toPath()));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths)
            modules.add((Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
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
        visit(entry, seen, result);
        return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target))
            return;
        var body = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(body);
        if (body instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode();
                        if (node != null)
                            nodes.add(node);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot)
                    visit(active, seen, result);
        result.add(target);
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().retainedReferences());
    }
    @Test
    void nativeResizePrefixAndRepeatedWritesWithInlining() throws Exception {
        verifyNative(true);
    }
    @Test
    void nativeResizePrefixAndRepeatedWritesAcrossResidualCalls() throws Exception {
        verifyNative(false);
    }
    private record Row(String name, long raw, long code, long expected) {}
    private record Input(long raw, long code) {}
    private record Sizes(int old, int size) {}
    @Test
    void resizeFixtureEvidenceRejectsMissingAndChangedProvenance() throws Exception {
        ByteArrayFixtureEvidence.rejectionControls(root, "resize-bytearrays");
    }
    private final Comparator<Input> inputOrder = Comparator.comparingLong(Input::raw).thenComparingLong(Input::code);
    private List<Input> inputs() {
        var pairs = new HashSet<Input>();
        for (long i = 0; i < 17 * 17; i++) pairs.add(new Input(i * 0x123456789abcdefL, i));
        for (long seed = 0; seed <= 255; seed++)
            for (var size :
                List.of(new Sizes(0, 16), new Sizes(16, 0), new Sizes(16, 8), new Sizes(8, 16), new Sizes(8, 8)))
                pairs.add(new Input(seed, size.old() + 17L * size.size()));
        for (long seed : new long[] {Long.MIN_VALUE, -257, -1, 0, 255, 256, Long.MAX_VALUE})
            for (long code : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) pairs.add(new Input(seed, code));
        var result = new ArrayList<>(pairs);
        result.sort(inputOrder);
        return result;
    }
    private Sizes sizes(long code) {
        int key = (int) (code & 1023);
        return new Sizes(key % 17, key / 17 % 17);
    }
    private int octet(long seed, int index) {
        return (int) ((seed + 17L * index) & 255);
    }
    private List<Integer> resize(List<Integer> source, int length, long seed) {
        var result = new ArrayList<Integer>();
        for (int index = 0; index < length; index++)
            result.add(index < source.size() ? source.get(index) : octet(seed, index));
        return result;
    }
    // Defined-domain list semantics: retain the prefix and initialize every grown
    // byte. Do not model retired aliases or promise zero-filled native growth.
    private List<Integer> byteModel(String name, long raw, long code) {
        if (!names.contains(name))
            throw new IllegalArgumentException("Unknown resize entry: " + name);
        var size = sizes(code);
        var original = new ArrayList<Integer>();
        for (int i = 0; i < size.old(); i++) original.add(octet(raw, i));
        var result = resize(original, size.size(), raw + 91);
        if (name.equals("resizedBytes"))
            return result;
        var twice = resize(result, (size.size() + 7) % 17, raw + 133);
        if (!twice.isEmpty())
            twice.set(twice.size() - 1, (int) ((raw + 211) & 255));
        return twice;
    }
    private long model(String name, long raw, long code) {
        var bytes = byteModel(name, raw, code);
        long answer = bytes.size();
        for (int b : bytes) answer = answer * 257 + b;
        return answer;
    }
    private List<Row> expectedRows() {
        var result = new ArrayList<Row>();
        for (var name : names)
            for (var input : inputs())
                result.add(new Row(name, input.raw(), input.code(), model(name, input.raw(), input.code())));
        return result;
    }
    private List<Row> verifyRows(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var fields = line.split("\t", -1);
            if (fields.length != 4)
                throw new IllegalArgumentException("Resize oracle requires name/raw/code/result");
            rows.add(
                new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3])));
        }
        if (!rows.equals(expectedRows()))
            throw new IllegalArgumentException("Resize native/model mismatch, missing, duplicated or reordered row");
        return rows;
    }
    @Test
    void independentModelCoversEveryLengthBytePatternAndExtreme() {
        var inputs = inputs();
        var inventory = new HashSet<>(inputs);
        assertEquals(1596, inputs.size());
        assertEquals(inputs.size(), inventory.size());
        var sorted = new ArrayList<>(inputs);
        sorted.sort(inputOrder);
        assertEquals(sorted, inputs);
        var wanted = new HashSet<Sizes>();
        for (int old = 0; old <= 16; old++)
            for (int size = 0; size <= 16; size++) wanted.add(new Sizes(old, size));
        var actual = new HashSet<Sizes>();
        for (var input : inputs) actual.add(sizes(input.code()));
        assertEquals(wanted, actual);
        for (var size :
            List.of(new Sizes(0, 16), new Sizes(16, 0), new Sizes(16, 8), new Sizes(8, 16), new Sizes(8, 8)))
            for (long seed = 0; seed <= 255; seed++)
                assertTrue(inventory.contains(new Input(seed, size.old() + 17L * size.size())));
        for (long seed : new long[] {Long.MIN_VALUE, -257, -1, 0, 255, 256, Long.MAX_VALUE})
            for (long code : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE})
                assertTrue(inventory.contains(new Input(seed, code)));
        for (var name : names)
            for (var input : inputs) {
                long raw = input.raw(), code = input.code();
                var size = sizes(code);
                var bytes = byteModel(name, raw, code);
                int length = name.equals("resizedBytes") ? size.size() : (size.size() + 7) % 17;
                assertEquals(length, bytes.size());
                for (int index = 0; index < bytes.size(); index++) {
                    long expected = name.equals("resizedTwiceWrites") && index == length - 1 ? raw + 211
                        : index >= size.size()                                               ? raw + 133 + 17 * index
                        : index >= size.old()                                                ? raw + 91 + 17 * index
                                                                                             : raw + 17 * index;
                    assertEquals((int) (expected & 255), bytes.get(index), name + "/" + raw + "/" + code + "/" + index);
                }
            }
        assertEquals(0L, model("resizedBytes", Long.MIN_VALUE, 0));
        assertEquals(468L, model("resizedTwiceWrites", 0, 11 * 17));
        assertThrows(IllegalArgumentException.class, () -> byteModel("unknown", 0, 0));
    }
    private String text(List<Row> rows) {
        var text = new StringBuilder();
        for (var row : rows)
            text.append(row.name())
                .append('\t')
                .append(row.raw())
                .append('\t')
                .append(row.code())
                .append('\t')
                .append(row.expected())
                .append('\n');
        return text.toString();
    }
    private List<Row> replaced(List<Row> rows, Row first) {
        var result = new ArrayList<>(rows);
        result.set(0, first);
        return result;
    }
    @Test
    void independentOracleRejectsMissingDuplicateReorderedMalformedAndWrongRows() {
        var expected = expectedRows();
        var valid = text(expected);
        assertEquals(3192, expected.size());
        assertEquals(expected, verifyRows(valid));
        var first = expected.getFirst();
        var duplicate = new ArrayList<>(expected);
        duplicate.add(first);
        var bad = List.of("", text(expected.subList(1, expected.size())), text(duplicate), text(expected.reversed()),
            text(replaced(expected, expected.get(1))),
            text(replaced(expected, new Row(first.name(), first.raw(), first.code(), first.expected() + 1))),
            text(replaced(expected, new Row(first.name(), first.raw() + 1, first.code(), first.expected()))),
            text(replaced(expected, new Row(first.name(), first.raw(), first.code() + 1, first.expected()))),
            text(replaced(expected, new Row("unknown", first.raw(), first.code(), first.expected()))),
            valid.replaceFirst("\t", " "), valid.replaceFirst("\t", "\textra\t"),
            "resizedBytes\t0\t0\tnot-an-integer\n", "resizedBytes\t9223372036854775808\t0\t0\n", valid + "\n");
        for (int i = 0; i < bad.size(); i++) {
            var corrupt = bad.get(i);
            assertThrows(IllegalArgumentException.class, () -> verifyRows(corrupt), "mutation " + i);
        }
    }
    private void check(Row row, Value function, String label, Language language) {
        assertEquals(row.expected(), function.execute(row.raw(), row.code()).asLong(),
            label + "/" + row.raw() + "/" + row.code());
        released(language);
    }
    private long count(ExecutableProgram p) {
        return ((Number) p.diagnostics().get("compiledEntries")).longValue();
    }
    private void verifyNative(boolean inlining) throws Exception {
        var manifest = manifest();
        ByteArrayFixtureEvidence.verify(root, "resize-bytearrays", manifest);
        assertEquals(names, manifest.get("entries"));
        var actualInputs = new ArrayList<Input>();
        for (var input : (List<List<Number>>) manifest.get("inputs"))
            actualInputs.add(new Input(input.get(0).longValue(), input.get(1).longValue()));
        assertEquals(inputs(), actualInputs);
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : verifyRows(Files.readString(new File(root, "build/resize-bytearrays/oracle.tsv").toPath())))
            rows.computeIfAbsent(row.name(), k -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(names), rows.keySet());
        assertEquals(3192, ((Number) manifest.get("nativeRows")).intValue());
        int rowCount = 0;
        for (var list : rows.values()) rowCount += list.size();
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rowCount);
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet())
            for (var name : names) {
                var cases = Objects.requireNonNull(rows.get(name));
                var audit = (Map<String, Object>) Json.parse(Files.readString(
                    new File(root, "build/resize-bytearrays/" + stage.getKey() + "-" + name + ".audit.json").toPath()));
                assertEquals(true, audit.get("accepted"));
                boolean worker = false, primitive = false;
                for (var b : (List<Map<String, Object>>) audit.get("reachableBindings"))
                    if (((String) b.get("id")).endsWith(".resizeWorker"))
                        worker = true;
                for (var p : (List<Map<String, Object>>) audit.get("primitives"))
                    if ("resizeMutableByteArray#".equals(p.get("name")))
                        primitive = true;
                assertTrue(worker);
                assertTrue(primitive);
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var module = new LinkedHashMap<>(CoreModules.reachable(merged(stage.getValue()), name));
                            module.put("instrument", true);
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, name, 2));
                            var host = p.hostEntryTarget(2);
                            var original = p.entryTarget(name);
                            var label = stage.getKey() + "/" + backend + "/" + name + "/inlining=" + inlining;
                            for (var row : cases) check(row, function, label, language);
                            var targets = activeTargets(host);
                            assertTrue(targets.size() > 1, label);
                            for (var target : targets)
                                if (target != host)
                                    compile(target);
                            assertTrue(function.invokeMember("compile").asBoolean());
                            long allocations = language.getHandoffState().get().getResults().getAllocations();
                            assertTrue(
                                allocations > 0, label + " native retained resizeWorker returns a reference tuple");
                            for (var row : cases.reversed()) {
                                long before = count(p);
                                check(row, function, label, language);
                                assertTrue(count(p) > before, label + " compiled guest");
                                assertEquals(targets, activeTargets(host), label + " active identities");
                                valid(original, label);
                                for (var target : targets) valid(target, label);
                            }
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, ((Number) p.diagnostics().get(counter)).longValue(), label);
                            System.out.println("ResizeByteArray PASS " + label + " rows=" + cases.size());
                        } finally {
                            context.leave();
                        }
                    }
            }
    }
    private final long[] invalidSizes = {Long.MIN_VALUE, -1, Integer.MAX_VALUE + 1L, 1L << 32, Long.MAX_VALUE};
    @Test
    void managedResizePreservesThePrefixAndRejectsFullWidthInvalidLengths() {
        for (int old = 0; old <= 16; old++)
            for (int size = 0; size <= 16; size++)
                for (int seed = 0; seed <= 255; seed++) {
                    var original = new byte[old];
                    for (int i = 0; i < old; i++) original[i] = (byte) (seed + 17 * i);
                    var result = ManagedByteArray.resize(original, size);
                    assertEquals(size, result.length);
                    for (int i = 0; i < Math.min(old, size); i++) assertEquals((byte) (seed + 17 * i), result[i]);
                    if (size == old)
                        assertSame(original, result);
                    // Do not turn JVM-zeroed growth into a guest contract: initialize it.
                    for (int i = old; i < size; i++) result[i] = (byte) (seed + 91 + 17 * i);
                    if (size > 0) {
                        ManagedByteArray.write(result, size - 1L, 511);
                        assertEquals(255L, (long) ManagedByteArray.read(result, size - 1L));
                    }
                    long outside = size;
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.read(result, outside));
                }
        for (long size : invalidSizes) {
            var original = new byte[] {1, 2, 3};
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.resize(original, size));
            assertArrayEquals(new byte[] {1, 2, 3}, original);
        }
    }
    private Expr operand(String name, Object value, List<String> events) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return value;
            }
        };
    }
    @Test
    void stateIsEvaluatedBeforeAllocationAndFailureDoesNotPublish() {
        var builder = FrameDescriptor.newBuilder();
        builder.addSlot(FrameSlotKind.Object, null, null);
        builder.addSlot(FrameSlotKind.Object, null, null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        for (boolean fail : new boolean[] {false, true}) {
            var sentinel = new Object();
            FrameAccess.write(frame, 1, sentinel);
            var source = new byte[] {3, 5, 7};
            var events = new ArrayList<String>();
            var state = new Expr() {
                @Override
                public Object execute(VirtualFrame f) {
                    events.add("state");
                    assertSame(sentinel, FrameAccess.read(f, 1));
                    assertArrayEquals(new byte[] {3, 5, 7}, source);
                    if (fail)
                        throw new RuntimeFault("State failed");
                    return Unit.INSTANCE;
                }
            };
            var expr = ByteArrayOp.expression(ByteArrayOp.RESIZE, CoreRepresentation.UNKNOWN,
                new Expr[] {operand("array", source, events), operand("size", 5L, events), state});
            if (fail) {
                assertThrows(RuntimeFault.class, () -> expr.executeTuple(frame, new int[] {0, 1}, 1));
                assertSame(sentinel, FrameAccess.read(frame, 1));
            } else {
                expr.executeTuple(frame, new int[] {0, 1}, 1);
                var result = (byte[]) FrameAccess.read(frame, 1);
                assertEquals(5, result.length);
                assertArrayEquals(new byte[] {3, 5, 7}, Arrays.copyOf(result, 3));
            }
            assertEquals(List.of("array", "size", "state"), events);
        }
    }
    private Map<String, Object> synthetic() {
        var array = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var number = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", array.get("primReps"),
            "components", List.of(state, array), "evaluated", true);
        var reps = List.of(array, number, state);
        var params = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < reps.size(); i++) params.add(Map.of("id", "p" + i, "lifted", false, "rep", reps.get(i)));
        var args = new ArrayList<Object>();
        for (var p : params) args.add(List.of("var", p.get("id"), Map.of("rep", p.get("rep"))));
        var app = List.of("app", List.of("prim", "resizeMutableByteArray#"), args, List.of(false, false, false), false,
            false, Map.of("rep", tuple));
        var binders =
            List.of(Map.of("id", "s", "lifted", false, "rep", state), Map.of("id", "a", "lifted", false, "rep", array));
        var body = List.of("case", app, "pair",
            List.of(List.of("data", "T2", List.of("s", "a"), List.of("var", "a", Map.of("rep", array)),
                Map.of("binders", binders))),
            Map.of("rep", array, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
        return Map.of("instrument", true, "constructors",
            List.of(Map.of("id", "T2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)), "bindings",
            List.of(Map.of("id", "resize", "name", "resize", "arity", 3, "lifted", true, "rep", closure, "expr",
                List.of("lam", params, body, Map.of("rep", closure, "resultRep", array)))));
    }
    private byte[] call(RootCallTarget entry, Object array, long size, Object state) {
        return (byte[]) Calls.target(entry, new Object[] {0L, array, size, state});
    }
    private void positive(boolean compiled, ExecutableProgram p, RootCallTarget entry, Language language,
        String backend) throws Exception {
        for (int old = 0; old <= 16; old++)
            for (int size = 0; size <= 16; size++) {
                var source = new byte[old];
                for (int i = 0; i < old; i++) source[i] = (byte) (128 + 17 * i);
                long before = count(p);
                var result = call(entry, source, size, Unit.INSTANCE);
                assertEquals(size, result.length);
                for (int i = 0; i < Math.min(old, size); i++) assertEquals((byte) (128 + 17 * i), result[i]);
                if (compiled) {
                    assertEquals(before + 1, count(p));
                    valid(entry, backend);
                }
                released(language);
            }
    }
    @Test
    void compiledBackendsReturnTheExactStorageAndGuardStateAndInvalidCarriers() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var p = program(language, synthetic(), backend);
                    var entry = p.entryTarget("resize");
                    positive(false, p, entry, language, backend);
                    compile(entry);
                    positive(true, p, entry, language, backend);
                    for (long size : invalidSizes) {
                        var source = new byte[] {7, 8};
                        assertThrows(RuntimeFault.class, () -> call(entry, source, size, Unit.INSTANCE));
                        assertArrayEquals(new byte[] {7, 8}, source);
                        released(language);
                    }
                    var failure =
                        assertThrows(RuntimeFault.class, () -> call(entry, new byte[] {7}, Long.MAX_VALUE, 17L));
                    assertTrue(Objects.toString(failure.getMessage(), "").contains("zero-width scalar carrier"),
                        failure.getMessage());
                    for (var bad : List.of(new Object(), new Object[] {1}, new long[] {1}))
                        assertThrows(RuntimeFault.class, () -> call(entry, bad, 0, Unit.INSTANCE));
                    assertEquals(1, call(entry, new byte[] {7, 8}, 1, Unit.INSTANCE).length);
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
            for (var item : list) result.addAll(applications(item));}
        else if(value instanceof Map<?,?> map)
            for (var item : map.values()) result.addAll(applications(item));
        return result;
    }
    private List<Object> resizeApplication(Object value) {
        for (var app : applications(value)) {
            var function = (List<?>) app.get(1);
            if (function.subList(0, Math.min(2, function.size())).equals(List.of("prim", "resizeMutableByteArray#")))
                return app;
        }
        throw new NoSuchElementException();
    }
    @Test
    void exactSaturationStateReferenceAndLogicalPairProofsAreRequired() throws Exception {
        var paths = Objects.requireNonNull(((Map<String, List<String>>) manifest().get("stages")).get("pre"));
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (int mutation = 0; mutation <= 10; mutation++)
                        for (boolean diagnostic : new boolean[] {false, true}) {
                            var module = CoreModules.reachable(merged(paths), "resizedBytes");
                            var app = resizeApplication(module);
                            var args = (List<Object>) app.get(2);
                            var flags = (List<Object>) app.get(3);
                            var meta = CoreRepresentations.metadata(app);
                            var empty = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(),
                                "primReps", List.of(), "evaluated", true);
                            switch (mutation) {
                                case 0 -> {
                                    args.remove(2);
                                    flags.remove(2);
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
                                    ((Map<String, Object>) CoreRepresentations.metadata((List<Object>) args.get(0))
                                            .get("rep"))
                                        .put("primReps", List.of("BoxedRep (Just Lifted)"));
                                case 5, 6 ->
                                    ((Map<String, Object>) CoreRepresentations.metadata((List<Object>) args.get(1))
                                            .get("rep"))
                                        .put("primReps", List.of(mutation == 5 ? "WordRep" : "Int64Rep"));
                                case 7 -> CoreRepresentations.metadata((List<Object>) args.get(2)).put("rep", empty);
                                case 8 ->
                                    ((List<Object>) ((Map<String, Object>) meta.get("rep")).get("components"))
                                        .set(0, empty);
                                case 9 ->
                                    ((List<Object>) ((Map<String, Object>) meta.get("rep")).get("components"))
                                        .remove(0);
                                case 10 -> {
                                    var rep = (Map<String, Object>) meta.get("rep");
                                    rep.put("primReps", List.of());
                                    rep.put("components", List.of());
                                }
                            }
                            var configured = new LinkedHashMap<>(module);
                            configured.put("diagnosticUnsupported", diagnostic);
                            String label = backend + "/" + mutation + "/" + diagnostic;
                            if (mutation == 5 || mutation == 6)
                                assertDoesNotThrow(() -> program(language, configured, backend), label);
                            else
                                assertThrows(RuntimeFault.class, () -> program(language, configured, backend), label);
                        }
                    var module = CoreModules.reachable(merged(paths), "resizedBytes");
                    var app = resizeApplication(module);
                    var bare = new ArrayList<>((List<?>) app.get(1));
                    app.clear();
                    app.addAll(bare);
                    assertThrows(UnsupportedCore.class, () -> program(language, module, backend));
                } finally {
                    context.leave();
                }
            }
    }
}
