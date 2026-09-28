// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.Unit;
import thc.*;
import java.nio.file.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class BoxedArrayTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("boxedSTRecursive", "boxedZero", "boxedSnapshot", "boxedClosure");
    private Map<String, Object> manifest() throws Exception {
        return json("build/boxed-arrays/manifest.json");
    }
    private Map<String, Object> json(String path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve(path)));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (String path : paths) modules.add(json(path));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.CompilationTimeout", "30")
            .option("compiler.MaximumGraalGraphSize", "100000")
            .option("compiler.Inlining", Boolean.toString(inlining))
            .build();
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true,
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target),
            label);
    }
    private long model(String name, long input) {
        var x = BigInteger.valueOf(input);
        return (switch (name) {
            case "boxedSTRecursive" -> x.multiply(BigInteger.valueOf(44)).add(BigInteger.valueOf(350));
            case "boxedSnapshot" -> x.multiply(BigInteger.valueOf(15)).add(BigInteger.valueOf(207));
            case "boxedClosure" -> x.multiply(BigInteger.TWO).add(BigInteger.ONE);
            default -> x;
        }).longValue();
    }
    private Map<String, Object> changed(Map<String, Object> source, String key, Object value) {
        var copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }
    private record Row(long input, long expected) {}
    @Test
    void publicSTArrayAndLazyElementsWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    void publicSTArrayAndLazyElementsWithoutInlining() throws Exception {
        nativeChecks(false);
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest();
        for (String key : List.of("inputHashes", "artifactHashes"))
            for (var entry : ((Map<String, String>) manifest.get(key)).entrySet()) {
                String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(entry.getKey()))));
                assertEquals(entry.getValue(), hash, "Stale boxed-array fixture " + entry.getKey());
            }
        var rows = new LinkedHashMap<String, List<String[]>>();
        for (String line : Files.readAllLines(root.resolve("build/boxed-arrays/oracle.tsv"))) {
            var fields = line.split("\t", -1);
            if (names.contains(fields[0]))
                rows.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(fields);
        }
        assertEquals(new HashSet<>(names), rows.keySet());
        assertEquals(((Number) manifest.get("supportedNativeRows")).intValue(),
            rows.values().stream().mapToInt(List::size).sum());
        for (var stagePaths : ((Map<String, List<String>>) manifest.get("stages")).entrySet())
            for (String name : names) {
                String stage = stagePaths.getKey();
                var paths = stagePaths.getValue();
                var cases = rows.get(name)
                                .stream()
                                .map(row -> new Row(Long.parseLong(row[1]), Long.parseLong(row[3])))
                                .toList();
                for (var row : cases)
                    assertEquals(model(name, row.input), row.expected, "native/model " + name + "(" + row.input + ")");
                for (String backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var guest = program(language,
                                changed(CoreModules.reachable(merged(paths), name), "instrument", true), backend);
                            var function = context.asValue(new EntryValue(guest, name, 1));
                            var host = guest.hostEntryTarget(1);
                            String label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                            class Checks {
                                void check(Row row) {
                                    assertEquals(
                                        row.expected, function.execute(row.input).asLong(), label + "/" + row.input);
                                }
                            }
                            var checks = new Checks();
                            for (var row : cases) checks.check(row);
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " install");
                            var original = guest.entryTarget(name);
                            Supplier<List<RootCallTarget>> activeTargets = ()
                                -> NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class)
                                       .stream()
                                       .filter(call -> call.getCallTarget() == original)
                                       .map(call -> (RootCallTarget) call.getCurrentCallTarget())
                                       .toList();
                            var active = activeTargets.get();
                            assertTrue(!active.isEmpty(), label + " observed warmed host-to-entry DirectCallNode");
                            for (var row : cases.reversed()) {
                                long before = ((Number) guest.diagnostics().get("compiledEntries")).longValue();
                                checks.check(row);
                                assertTrue(((Number) guest.diagnostics().get("compiledEntries")).longValue() > before,
                                    label + " compiled guest entry");
                                assertEquals(active, activeTargets.get(), label + " active identities");
                                valid(host, label + " host");
                                valid(original, label + " original");
                                for (var target : active) valid(target, label + " active");
                            }
                            for (String counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(
                                    0L, ((Number) guest.diagnostics().get(counter)).longValue(), label + "/" + counter);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0L, language.getHandoffState().get().getResults().getAllocations(),
                                label + " saturated primitive results write directly to locals");
                        } finally {
                            context.leave();
                        }
                    }
            }
    }

    @Test
    void storageSharesInitialReferencesSnapshotsAndFreezeIdentityWithCheckedDomains() {
        var initial = new Object();
        var replacement = new Object();
        for (long size : List.of(0L, 1L, 2L, 7L, 256L)) {
            var array = ManagedArray.allocate(size, initial);
            assertEquals(Object[].class, array.getClass());
            assertEquals(size, (long) array.length);
            assertSame(array, ManagedArray.freeze(array));
            assertNotSame(array, ManagedArray.allocate(size, initial));
            for (long i = 0; i < size; i++) assertSame(initial, ManagedArray.read(array, i));
            if (size > 0) {
                var alias = array;
                var old = ManagedArray.read(array, 0);
                ManagedArray.write(alias, 0, replacement);
                assertSame(initial, old);
                assertSame(replacement, ManagedArray.read(array, 0));
            }
            for (long index : List.of(Long.MIN_VALUE, -1L, size, Long.MAX_VALUE)) {
                var before = array.clone();
                assertThrows(RuntimeFault.class, () -> ManagedArray.read(array, index));
                assertThrows(RuntimeFault.class, () -> ManagedArray.write(array, index, replacement));
                assertArrayEquals(before, array);
            }
        }
        for (long size : List.of(Long.MIN_VALUE, -1L, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE))
            assertThrows(RuntimeFault.class, () -> ManagedArray.allocate(size, initial));
        for (var value :
            List.of(new Object(), new byte[] {1}, new long[] {1}, new String[] {"wrong JVM component type"}))
            assertThrows(RuntimeFault.class, () -> ManagedArray.require(value));
    }

    @Test
    void stateIsEvaluatedBeforeEffectsAndFailuresDoNotPublishDestinations() throws Exception {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Object, "out", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        var old = new Object();
        var replacement = new Object();
        var storage = ManagedArray.allocate(1, old);
        var events = new ArrayList<String>();
        class Operands {
            Expr operand(String name, Supplier<Object> value) {
                return new Expr() {
                    @Override
                    public Object execute(VirtualFrame ignored) {
                        events.add(name);
                        return value.get();
                    }
                };
            }
            Expr write(Supplier<Object> state) {
                return ArrayOp.expression(ArrayOp.WRITE, CoreRepresentation.UNKNOWN,
                    new Expr[] {operand("array", () -> storage), operand("index", () -> 0L),
                        operand("value", () -> replacement), operand("state", state)});
            }
        }
        var operands = new Operands();
        assertThrows(RuntimeFault.class,
            ()
                -> operands
                    .write(() -> {
                        assertSame(old, storage[0]);
                        return 0L;
                    })
                    .execute(frame));
        assertSame(old, storage[0]);
        assertEquals(List.of("array", "index", "value", "state"), events);
        events.clear();
        assertSame(Unit.INSTANCE,
            operands
                .write(() -> {
                    assertSame(old, storage[0]);
                    return Unit.INSTANCE;
                })
                .execute(frame));
        assertSame(replacement, storage[0]);
        assertEquals(List.of("array", "index", "value", "state"), events);
        for (var op : List.of(ArrayOp.NEW, ArrayOp.READ, ArrayOp.FREEZE)) {
            frame.setObject(slot, old);
            var args = new ArrayList<Expr>();
            switch (op) {
                case NEW -> {
                    args.add(operands.operand("size", () -> 1L));
                    args.add(operands.operand("initial", () -> replacement));
                }
                case READ -> {
                    args.add(operands.operand("array", () -> storage));
                    args.add(operands.operand("index", () -> 0L));
                }
                default -> args.add(operands.operand("array", () -> storage));
            }
            args.add(operands.operand("state", () -> { throw new RuntimeFault("state failure"); }));
            assertThrows(RuntimeFault.class,
                ()
                    -> ArrayOp.expression(op, CoreRepresentation.UNKNOWN, args.toArray(Expr[] ::new))
                        .executeTuple(frame, new int[] {slot}, 0));
            assertSame(old, frame.getObject(slot), op + " must not publish before State");
        }
    }

    @Test
    void bothBackendsRejectBadStateAndBoundsBeforeWritingWithoutForcingPayloads() {
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var array = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var lifted = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var ids = List.of("array", "index", "value", "state");
        var proofs = List.of(array, integer, lifted, state);
        var params = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++)
            params.add(Map.of("id", ids.get(i), "lifted", ids.get(i).equals("value"), "rep", proofs.get(i)));
        var body = List.of("app", List.of("prim", "writeArray#"),
            params.stream().map(param -> List.of("var", param.get("id"), Map.of("rep", param.get("rep")))).toList(),
            List.of(false, false, true, false), false, false, Map.of("rep", state));
        Map<String, Object> module = Map.of("bindings",
            List.of(Map.of("id", "write", "name", "write", "lifted", true, "rep", closure, "arity", 4, "expr",
                List.of("lam", params, body, Map.of("rep", closure, "resultRep", state)))));
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var guest = program(language, module, backend);
                    var original = new Object();
                    int[] entered = {0};
                    var replacement = new Thunk(new RootNode(null) {
                        @Override
                        public Object execute(VirtualFrame frame) {
                            entered[0]++;
                            throw new RuntimeFault("stored bottom entered");
                        }
                    }.getCallTarget(), null);
                    var storage = ManagedArray.allocate(1, original);
                    class CallsHere {
                        Object call(Object value, long index, Object stateValue) {
                            return Calls.target(guest.hostEntryTarget(4),
                                new Object[] {
                                    guest.entryValue("write"), new Object[] {value, index, replacement, stateValue}});
                        }
                    }
                    var call = new CallsHere();
                    assertThrows(RuntimeFault.class, () -> call.call(storage, 0, 1L));
                    assertSame(original, storage[0]);
                    for (long index : List.of(Long.MIN_VALUE, -1L, 1L, Long.MAX_VALUE)) {
                        assertThrows(RuntimeFault.class, () -> call.call(storage, index, Unit.INSTANCE));
                        assertSame(original, storage[0]);
                    }
                    assertThrows(RuntimeFault.class, () -> call.call(new byte[] {1}, 0, Unit.INSTANCE));
                    assertSame(Unit.INSTANCE, call.call(storage, 0, Unit.INSTANCE));
                    assertSame(replacement, storage[0]);
                    assertEquals(0, entered[0], backend + " must never enter the written thunk");
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
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
            for (var item : list) result.addAll(applications(item)); }
        else if (value instanceof Map<?, ?> map)
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
    void exactShapesLevityAndSaturationAreRequiredInBothLoadModes() throws Exception {
        var paths = ((Map<String, List<String>>) manifest().get("stages")).get("pre");
        for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation :
                        List.of(ArrayOp.NEW, ArrayOp.READ, ArrayOp.WRITE, ArrayOp.FREEZE, ArrayOp.INDEX))
                        for (int mutation = 0; mutation <= 7; mutation++)
                            for (boolean diagnostic : List.of(false, true)) {
                                var module = CoreModules.reachable(merged(paths), "boxedSTRecursive");
                                var app = application(module, operation);
                                var args = (List<Object>) app.get(2);
                                var flags = (List<Object>) app.get(3);
                                var metadata = CoreRepresentations.metadata(app);
                                switch (mutation) {
                                    case 0 -> {
                                        args.removeLast();
                                        flags.removeLast();
                                        metadata.remove("callDemand");
                                    }
                                    case 1 -> {
                                        args.add(args.getFirst());
                                        flags.add(false);
                                        metadata.remove("callDemand");
                                    }
                                    case 2 -> metadata.remove("rep");
                                    case 3 -> flags.set(0, !(Boolean) flags.get(0));
                                    case 4 ->
                                        ((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.get(0))
                                                .get("rep"))
                                            .put("kind", "unknown");
                                    case 5 -> {
                                        var proof = (Map<String, Object>) metadata.get("rep");
                                        proof.clear();
                                        proof.putAll(Map.of("kind", "unknown", "aggregate", "unboxed-tuple",
                                            "components", List.of(), "primReps", List.of(), "evaluated", true));
                                    }
                                    case 6 -> {
                                        var proof = (Map<String, Object>) metadata.get("rep");
                                        if (operation.getTuple()) {
                                            var fields = (List<Object>) proof.get("components");
                                            if (operation == ArrayOp.INDEX)
                                                fields.addFirst(
                                                    Map.of("kind", "void", "primReps", List.of(), "evaluated", true));
                                            else
                                                fields.removeFirst();
                                        } else
                                            proof.put("primReps", List.of("IntRep"));
                                    }
                                    case 7 -> {
                                        var proof = (Map<String, Object>) metadata.get("rep");
                                        if (operation.getTuple()) {
                                            var fields = (List<Map<String, Object>>) proof.get("components");
                                            fields.getLast().put("primReps", List.of("BoxedRep Nothing"));
                                            proof.put("primReps", List.of("BoxedRep Nothing"));
                                        } else
                                            ((Map<String, Object>) CoreRepresentations
                                                    .metadata((List<Object>) args.get(2))
                                                    .get("rep"))
                                                .put("primReps", List.of("BoxedRep (Just Unlifted)"));
                                    }
                                }
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> program(
                                            language, changed(module, "diagnosticUnsupported", diagnostic), backend),
                                    backend + "/" + operation.getPrimitive() + "/" + mutation + "/" + diagnostic);
                            }
                    for (var operation :
                        List.of(ArrayOp.NEW, ArrayOp.READ, ArrayOp.WRITE, ArrayOp.FREEZE, ArrayOp.INDEX)) {
                        var module = CoreModules.reachable(merged(paths), "boxedSTRecursive");
                        var app = application(module, operation);
                        var primitive = new ArrayList<>((List<?>) app.get(1));
                        app.clear();
                        app.addAll(primitive);
                        assertThrows(UnsupportedCore.class, () -> program(language, module, backend));
                    }
                    for (String name : (List<String>) manifest().get("frontiers"))
                        assertThrows(UnsupportedCore.class,
                            ()
                                -> program(language, CoreModules.reachable(merged(paths), name), backend),
                            backend + " frontier " + name);
                } finally {
                    context.leave();
                }
            }
    }
}