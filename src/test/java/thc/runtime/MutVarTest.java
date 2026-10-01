// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.*;
import thc.runtime.Unit;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

@SuppressWarnings("unchecked")
class MutVarTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("stRef", "lazyRef", "closureRef", "orderedRef", "unliftedRef", "stLoop"),
                               equalityNames = List.of("stRefEquality", "lazyRefEquality"),
                               lazyIONames = List.of("lazyIORef"), swapNames = List.of("swapRef", "lazySwapRef"),
                               modifyNames = List.of("modifyRef", "lazyModifyRef", "lazyBottomModifierRef");
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/mutvar/manifest.json").toPath()));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths)
            modules.add(thc.CoreCbdFixtures.read(new File(root, path).toPath()));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private long mathematical(String name, long seed) {
        var x = BigInteger.valueOf(seed);
        BigInteger result;
        switch (name) {
            case "stRefEquality", "lazyRefEquality" -> {
                var score = BigInteger.valueOf(seed < 0 ? 5 : 1);
                if (name.equals("lazyRefEquality"))
                    result = x.add(score.multiply(BigInteger.valueOf(17)));
                else {
                    var left = seed < 0 ? x.add(BigInteger.valueOf(17)) : x;
                    var right = seed < 0 ? x : x.add(BigInteger.valueOf(17));
                    result = left.multiply(BigInteger.valueOf(257))
                                 .add(right.multiply(BigInteger.valueOf(65537)))
                                 .add(score.multiply(BigInteger.valueOf(17)));
                }
            }
            case "lazyRef" -> result = x.add(BigInteger.valueOf(5));
            case "lazyIORef" -> result = x.add(BigInteger.valueOf(17));
            case "closureRef" -> result = x.multiply(BigInteger.valueOf(4)).add(BigInteger.valueOf(11));
            case "swapRef" ->
                result = x.add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(257)))
                             .add(x.multiply(BigInteger.valueOf(3)).multiply(BigInteger.valueOf(65537)));
            case "modifyRef" ->
                result = x.add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(257)))
                             .add(x.multiply(BigInteger.valueOf(3)).multiply(BigInteger.valueOf(65537)))
                             .add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(16777259)));
            case "lazyModifyRef", "lazyBottomModifierRef" -> result = x;
            case "lazySwapRef" -> result = x.multiply(BigInteger.valueOf(258)).add(BigInteger.valueOf(7));
            case "unliftedRef" -> result = x.multiply(BigInteger.valueOf(258)).add(BigInteger.ONE);
            case "stLoop" -> {
                var value = x;
                for (int n = x.abs().mod(BigInteger.valueOf(33)).intValue(); n >= 1; n--)
                    value = value.multiply(BigInteger.valueOf(3)).add(BigInteger.valueOf(n));
                result = value;
            }
            default -> {
                var last = (name.equals("stRef") ? x.add(BigInteger.valueOf(17)) : x).multiply(BigInteger.valueOf(3));
                result = x.add(x.add(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(257)))
                             .add(last.multiply(BigInteger.valueOf(65537)))
                             .add(x.add(BigInteger.valueOf(71)).multiply(BigInteger.valueOf(16777259)));
            }
        }
        return result.longValue();
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
    @Test
    void nativeSTRefWithInliningAndBoundedGraalSpeculationWarmup() throws Exception {
        verifyNative(true, !"true".equals(System.getenv("THC_MUTVAR_REQUIRE_INITIAL_STABILITY")), names);
    }
    @Test
    void nativeSTRefAcrossResidualCalls() throws Exception {
        verifyNative(false, false, names);
    }
    @Test
    void publicSTRefEqualityWithInlining() throws Exception {
        verifyNative(true, false, equalityNames);
    }
    @Test
    void publicSTRefEqualityAcrossResidualCalls() throws Exception {
        verifyNative(false, false, equalityNames);
    }
    @Test
    void publicLazyIORefWithInlining() throws Exception {
        verifyNative(true, false, lazyIONames);
    }
    @Test
    void publicLazyIORefAcrossResidualCalls() throws Exception {
        verifyNative(false, false, lazyIONames);
    }
    @Test
    void nativeAtomicSwapWithInlining() throws Exception {
        verifyNative(true, false, swapNames);
    }
    @Test
    void nativeAtomicSwapAcrossResidualCalls() throws Exception {
        verifyNative(false, false, swapNames);
    }
    @Test
    void nativeLazyAtomicModifyWithInlining() throws Exception {
        verifyNative(true, false, modifyNames);
    }
    @Test
    void nativeLazyAtomicModifyAcrossResidualCalls() throws Exception {
        verifyNative(false, false, modifyNames);
    }
    private List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>();if(value instanceof Map<?,?> map){
            for (var item : map.values()) result.addAll(nodes(item));
        } else if (value instanceof List<?> list) {
            result.add((List<Object>) list);
            for (var item : list) result.addAll(nodes(item));
        }
        return result;
    }
    private <T> T single(List<T> values) {
        if (values.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return values.getFirst();
    }
    private boolean primitive(List<?> node, String name) {
        if (node.isEmpty() || !"app".equals(node.getFirst()) || node.size() < 2 || !(node.get(1) instanceof List<?> fn))
            return false;
        return fn.subList(0, Math.min(2, fn.size())).equals(List.of("prim", name));
    }
    @Test
    void genuineLazyReturnedActionsKeepExactMutVarProofs() throws Exception {
        for (var stage : ((Map<String, List<String>>) manifest().get("stages")).entrySet()) {
            var originals = new ArrayList<Map<String, Object>>();
            for (var path : stage.getValue()) {
                var module = thc.CoreCbdFixtures.read(new File(root, path).toPath());
                if ("MutVarAudit".equals(module.get("module")))
                    originals.add(module);
            }
            var original = single(originals);
            var helpers = new ArrayList<Map<String, Object>>();
            for (var binding : (List<Map<String, Object>>) original.get("bindings"))
                if ("main:MutVarAudit.freshActions".equals(binding.get("id")))
                    helpers.add(binding);
            var helper = single(helpers);
            var calls = new ArrayList<List<Object>>();
            for (var node : nodes(helper.get("expr")))
                if (!node.isEmpty() && "app".equals(node.getFirst()) && node.size() > 1
                    && node.get(1) instanceof List<?> fn && !fn.isEmpty() && "prim".equals(fn.getFirst())
                    && MutVarOp.named(fn.size() > 1 && fn.get(1) instanceof String s ? s : "") != null)
                    calls.add(node);
            var primitiveNames = new HashSet<Object>();
            for (var call : calls) primitiveNames.add(((List<?>) call.get(1)).get(1));
            assertEquals(Set.of("newMutVar#", "readMutVar#", "writeMutVar#"), primitiveNames);
            for (var call : calls) {
                var name = (String) ((List<?>) call.get(1)).get(1);
                var args = new ArrayList<CoreRepresentation>();
                for (var arg : (List<List<Object>>) call.get(2)) args.add(CoreRepresentations.expression(arg));
                Objects.requireNonNull(MutVarOp.named(name))
                    .validate(args, (List<?>) call.get(3), CoreRepresentations.expression(call));
            }
            var equalityCalls = new ArrayList<List<Object>>();
            for (var node : nodes(original))
                if (primitive(node, "reallyUnsafePtrEquality#"))
                    equalityCalls.add(node);
            assertTrue(!equalityCalls.isEmpty(), stage.getKey() + " retains genuine STRef identity comparison");
            for (var call : equalityCalls) {
                assertEquals(List.of(false, false), call.get(3));
                var arguments = (List<List<Object>>) call.get(2);
                assertEquals(2, arguments.size());
                for (var argument : arguments) {
                    var rep = CoreRepresentations.expression(argument);
                    assertEquals(CoreKind.OBJECT, rep.getKind());
                    assertEquals(List.of("BoxedRep (Just Unlifted)"), rep.getPrimReps());
                }
                var result = CoreRepresentations.expression(call);
                assertEquals(CoreKind.LONG, result.getKind());
                assertEquals(List.of("IntRep"), result.getPrimReps());
            }
            var audit = (Map<String, Object>) Json.parse(
                Files.readString(new File(root, "build/mutvar/" + stage.getKey() + "/lazyIORef.audit.json").toPath()));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            assertEquals(List.of(), audit.get("issues"));
        }
    }
    private long compiled(ExecutableProgram p) {
        return ((Number) p.diagnostics().get("compiledEntries")).longValue();
    }
    private Set<RootCallTarget> activeTargets(RootCallTarget host, RootCallTarget original) {
        var active = new LinkedHashSet<RootCallTarget>();
        for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
            if (call.getCallTarget() == original)
                active.add((RootCallTarget) call.getCurrentCallTarget());
        if (active.isEmpty())
            active.add(original);
        return active;
    }
    private void check(long[] row, Value function, String label) {
        assertEquals(row[1], function.execute(row[0]).asLong(), label + "(" + row[0] + ")");
    }
    private void verifyNative(boolean inlining, boolean recoverLoop, List<String> entryNames) throws Exception {
        var manifest = manifest();
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(new File(root, item.getKey()).toPath())));
                assertEquals(
                    item.getValue(), actual, "Stale MutVar fixture: " + item.getKey() + "; rerun thc-fixtures mutvar");
            }
        var rows = new LinkedHashMap<String, List<List<String>>>();
        for (var line : Files.readAllLines(new File(root, "build/mutvar/oracle.tsv").toPath())) {
            var fields = Arrays.asList(line.split("\t", -1));
            rows.computeIfAbsent(fields.getFirst(), k -> new ArrayList<>()).add(fields);
        }
        var allNames = new HashSet<>(names);
        allNames.addAll(equalityNames);
        allNames.addAll(lazyIONames);
        allNames.addAll(swapNames);
        allNames.addAll(modifyNames);
        assertEquals(allNames, rows.keySet());
        int rowCount = 0;
        for (var list : rows.values()) rowCount += list.size();
        assertEquals(((Number) manifest.get("nativeRows")).intValue(), rowCount);
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var module = merged(stage.getValue());
            for (var name : entryNames) {
                var audit = (Map<String, Object>) Json.parse(Files.readString(
                    new File(root, "build/mutvar/" + stage.getKey() + "/" + name + ".audit.json").toPath()));
                assertEquals(true, audit.get("accepted"), stage.getKey() + "/" + name + " strict Core audit");
                assertEquals(List.of(), audit.get("issues"), stage.getKey() + "/" + name + " audit issues");
                assertEquals(List.of(), audit.get("missingGlobals"), stage.getKey() + "/" + name + " missing globals");
                var primitives = new HashSet<Object>();
                for (var p : (List<Map<String, Object>>) audit.get("primitives")) primitives.add(p.get("name"));
                Set<String> required = switch (name) {
                    case "swapRef", "lazySwapRef" -> Set.of("newMutVar#", "readMutVar#", "atomicSwapMutVar#");
                    case "modifyRef" -> Set.of("newMutVar#", "readMutVar#", "atomicModifyMutVar2#");
                    case "lazyModifyRef", "lazyBottomModifierRef" -> Set.of("newMutVar#", "atomicModifyMutVar2#");
                    case "lazyRefEquality" -> Set.of("newMutVar#", "writeMutVar#", "reallyUnsafePtrEquality#");
                    case "stRefEquality" ->
                        Set.of("newMutVar#", "readMutVar#", "writeMutVar#", "reallyUnsafePtrEquality#");
                    default -> Set.of("newMutVar#", "readMutVar#", "writeMutVar#");
                };
                assertTrue(primitives.containsAll(required),
                    stage.getKey() + "/" + name + " lost primitive evidence: " + required);
                var reachable = new ArrayList<String>();
                for (var b : (List<Map<String, Object>>) audit.get("reachableBindings"))
                    reachable.add((String) b.get("id"));
                if (name.equals("stRef"))
                    assertTrue(hasPrefix(reachable, "main:MutVarAudit.bump"));
                if (equalityNames.contains(name))
                    for (var helper : List.of("sameRef", "writeAndScore"))
                        assertTrue(hasPrefix(reachable, "main:MutVarAudit." + helper),
                            stage.getKey() + "/" + name + "/" + helper);
                var cases = new ArrayList<long[]>();
                for (var row : Objects.requireNonNull(rows.get(name)))
                    cases.add(new long[] {Long.parseLong(row.get(1)), Long.parseLong(row.get(2))});
                for (var row : cases)
                    assertEquals(mathematical(name, row[0]), row[1], "Native " + name + "(" + row[0] + ")");
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var label = stage.getKey() + "/" + backend + "/" + name + "/inlining=" + inlining;
                            var linked = new LinkedHashMap<>(CoreModules.reachable(module, "main:MutVarAudit." + name));
                            linked.put("instrument", true);
                            var p = program(language, linked, backend);
                            var host = p.hostEntryTarget(1);
                            var function = context.asValue(new EntryValue(p, "main:MutVarAudit." + name, 1));
                            for (var row : cases) check(row, function, label);
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                            var original = p.entryTarget("main:MutVarAudit." + name);
                            var active = activeTargets(host, original);
                            if (recoverLoop && name.equals("stLoop")) {
                                // Graal speculates an initial countdown > 0 (AST),
                                // or > 1 after peeling (bytecode). Interpreter warmup
                                // cannot train a compiler speculation. Probe these
                                // two known boundaries, then recompile at most once.
                                // The environment switch preserves the original raw
                                // first-install stability diagnostic without probes.
                                for (long input : new long[] {99, 1_000_000_000_000L}) {
                                    var probes = new ArrayList<long[]>();
                                    for (var row : cases)
                                        if (row[0] == input)
                                            probes.add(row);
                                    var probe = single(probes);
                                    long before = compiled(p);
                                    var hostWasValid = host.getClass().getMethod("isValidLastTier").invoke(host);
                                    check(probe, function, label);
                                    assertTrue(compiled(p) > before,
                                        label + " initial loop probe " + input + " entered compiled guest code");
                                    assertEquals(active, activeTargets(host, original),
                                        label + " active guest identities remain unchanged");
                                    valid(original, label + " original guest survives initial loop probe " + input);
                                    for (var target : active)
                                        valid(target, label + " active guest survives initial loop probe " + input);
                                    System.err.println("MUTVAR_SPECULATION_WARMUP " + label + " input=" + input
                                        + " hostWasValid=" + hostWasValid
                                        + " hostIsValid=" + host.getClass().getMethod("isValidLastTier").invoke(host)
                                        + " compiledGuestEntries=" + (compiled(p) - before));
                                }
                                if (!Boolean.TRUE.equals(host.getClass().getMethod("isValidLastTier").invoke(host))) {
                                    System.err.println("MUTVAR_RECOVERY " + label
                                        + " observed host loop speculation deopt; one explicit recompile");
                                    assertTrue(
                                        function.invokeMember("compile").asBoolean(), label + " recovery installation");
                                }
                                assertEquals(active, activeTargets(host, original),
                                    label + " active guest identities remain unchanged");
                                valid(host, label + " host installed after bounded initial loop probes");
                            }
                            for (var row : cases.reversed()) {
                                long before = compiled(p);
                                check(row, function, label);
                                assertTrue(
                                    compiled(p) > before, label + "(" + row[0] + ") must enter installed guest code");
                                assertEquals(active, activeTargets(host, original),
                                    label + " active guest identities remain unchanged");
                                valid(host, label + "/" + row[0] + " host remains installed");
                                for (var target : active)
                                    valid(target, label + "/" + row[0] + " active target remains installed");
                            }
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(
                                    0L, ((Number) p.diagnostics().get(counter)).longValue(), label + "/" + counter);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth(),
                                label + " releases tuple results");
                            if (name.equals("orderedRef"))
                                assertEquals(0L, language.getHandoffState().get().getResults().getAllocations(),
                                    label + " saturated primitives write directly into locals");
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    private boolean hasPrefix(List<String> values, String prefix) {
        for (var value : values)
            if (value.startsWith(prefix))
                return true;
        return false;
    }
    @Test
    void managedReferencePreservesAliasesAndDoesNotEvaluateOrCacheContents() throws Exception {
        var first = new Object();
        var second = new Object();
        var reference = new ManagedMutVar(first);
        assertSame(first, reference.getValue());
        var alias = ManagedMutVar.require(reference);
        alias.setValue(second);
        assertSame(second, reference.getValue());
        assertNotSame(reference, new ManagedMutVar(second));
        assertThrows(RuntimeFault.class, () -> ManagedMutVar.require(new Object[] {first}));
        var field = ManagedMutVar.class.getDeclaredField("value");
        assertFalse(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
        assertTrue(
            java.lang.reflect.Modifier.isVolatile(field.getModifiers()), "MutVar writes must publish to other threads");
        assertNull(field.getAnnotation(CompilerDirectives.CompilationFinal.class));
    }
    private RootCallTarget force(Language language, Metrics metrics, boolean async) {
        return new RootNode(language, FrameDescriptor.newBuilder().build()) {
            @Child private Force evaluator = new Force(metrics, async);
            @Override
            public Object execute(VirtualFrame frame) {
                return evaluator.execute(frame, frame.getArguments()[0]);
            }
        }.getCallTarget();
    }
    private Expr constant(Object value) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        };
    }
    @Test
    void atomicModifyReturnsOneSharedLazyApplicationAndSelector() {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var layout =
                    new DataLayout(language, "main:ModifyPair", "ModifyPair", new String[] {"LiftedRep", "IntRep"});
                var calls = new AtomicInteger();
                var old = new Object();
                var replacement = new Object();
                var modifier = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        assertSame(old, frame.getArguments()[1]);
                        calls.incrementAndGet();
                        return layout.create(new Object[] {replacement, 7L});
                    }
                };
                var metrics = new Metrics(false);
                var force = force(language, metrics, false);
                var cell = new ManagedMutVar(old);
                var function = new Closure(null, 1, modifier.getCallTarget());
                var builder = FrameDescriptor.newBuilder();
                var slots = new int[] {builder.addSlot(FrameSlotKind.Object, "old", null),
                    builder.addSlot(FrameSlotKind.Object, "result", null)};
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
                var invalidState = MutVarOp.expression(MutVarOp.MODIFY2, CoreRepresentation.UNKNOWN,
                    new Expr[] {constant(cell), constant(function), constant(1L)}, language, metrics, false);
                assertThrows(RuntimeFault.class, () -> invalidState.executeTuple(frame, slots, 0));
                assertSame(old, cell.getValue());
                assertEquals(0, calls.get());
                var site = new MutVarModifySite(language, metrics, false);
                var modified = cell.modify(function, site);
                assertSame(old, modified.getOld());
                assertEquals(0, calls.get());
                var selected = cell.getValue();
                assertTrue(selected instanceof Thunk);
                var second = new ManagedMutVar(old);
                var secondModification = second.modify(function, site);
                assertSame(modified.getResult().getTarget(), secondModification.getResult().getTarget(),
                    "atomic updates at one primitive site share the application call target");
                assertSame(((Thunk) selected).getTarget(), ((Thunk) second.getValue()).getTarget(),
                    "atomic updates at one primitive site share the selector call target");
                assertSame(replacement, Calls.target(force, new Object[] {selected}));
                assertEquals(1, calls.get());
                assertSame(layout, ((DataValue) Calls.target(force, new Object[] {modified.getResult()})).getLayout());
                assertEquals(1, calls.get(), "selector and returned record share the modifier application");
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void atomicModifySharesTheModifierFailureWithoutReplayingIt() {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var calls = new AtomicInteger();
                var payload = new Object();
                var old = new Object();
                var modifier = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        calls.incrementAndGet();
                        throw new GuestException(payload, this);
                    }
                };
                var metrics = new Metrics(false);
                var force = force(language, metrics, false);
                var cell = new ManagedMutVar(old);
                var modified = cell.modify(
                    new Closure(null, 1, modifier.getCallTarget()), new MutVarModifySite(language, metrics, false));
                assertSame(old, modified.getOld());
                assertEquals(0, calls.get());
                for (var value :
                    List.of(modified.getResult(), cell.getValue(), modified.getResult(), cell.getValue())) {
                    var failure = assertThrows(GuestException.class, () -> Calls.target(force, new Object[] {value}));
                    assertSame(payload, failure.getPayload());
                }
                assertEquals(1, calls.get(), "a failed modifier is memoized by the shared application thunk");
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void atomicModifyDoesNotEnterABottomModifierBeforeReturningOld() {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var calls = new AtomicInteger();
                var payload = new Object();
                var old = new Object();
                var bottom = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        calls.incrementAndGet();
                        throw new GuestException(payload, this);
                    }
                };
                var metrics = new Metrics(false);
                var modifier = new Thunk(bottom.getCallTarget(), null);
                var cell = new ManagedMutVar(old);
                var modified = cell.modify(modifier, new MutVarModifySite(language, metrics, false));
                assertSame(old, modified.getOld());
                assertEquals(0, calls.get(), "atomicModifyMutVar2# must not force f before the swap");
                var force = force(language, metrics, false);
                for (var value : List.of(modified.getResult(), cell.getValue())) {
                    var failure = assertThrows(GuestException.class, () -> Calls.target(force, new Object[] {value}));
                    assertSame(payload, failure.getPayload());
                }
                assertEquals(1, calls.get());
            } finally {
                context.leave();
            }
        }
    }
    private Thunk leaf(Language language, Object value) {
        return new Thunk(new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
            @Override
            public long bloom(VirtualFrame frame) {
                return (Long) frame.getArguments()[0];
            }
            @Override
            public Object execute(VirtualFrame frame) {
                return value;
            }
        }.getCallTarget(), null);
    }
    private GuestRoot pausing(Language language, Thunk child, AtomicInteger counter, Object expected) {
        return new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
            @Override
            public long bloom(VirtualFrame frame) {
                return (Long) frame.getArguments()[0];
            }
            @Override
            public Object execute(VirtualFrame frame) {
                if (expected != null)
                    assertSame(expected, frame.getArguments()[1]);
                counter.incrementAndGet();
                CompilerDirectives.transferToInterpreter();
                return new AstCapture(new ThunkSuspended(child), SynchronousMasking.current(this))
                    .append((resumed, input) -> {
                        if (!(input instanceof ChildResume answer))
                            throw new RuntimeFault("Missing child resume");
                        if (answer.getFailure() != null)
                            throw answer.getFailure();
                        return answer.getValue();
                    })
                    .freeze(this, frame.materialize());
            }
        };
    }
    private List<Integer> counts(AtomicInteger[] counts) {
        var result = new ArrayList<Integer>();
        for (var count : counts) result.add(count.get());
        return result;
    }
    private Object step(Context context, RootCallTarget force, Thunk selected) throws Exception {
        var answer = new CompletableFuture<Object>();
        var thread = new Thread(() -> {
            context.enter();
            try {
                answer.complete(Calls.target(force, new Object[] {selected}));
            } catch (ThunkSuspended suspension) {
                answer.complete(suspension);
            } catch (Throwable failure) {
                answer.completeExceptionally(failure);
            } finally {
                context.leave();
            }
        });
        thread.start();
        try {
            return answer.get(10, TimeUnit.SECONDS);
        } finally {
            thread.join(5000);
            assertFalse(thread.isAlive());
        }
    }
    @Test
    void atomicModifyResumesModifierCallAndSelectedFieldWithoutRepeatingTheSwap() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            final ManagedMutVar cell;
            final Thunk selected;
            final ModifiedMutVar modified;
            final RootCallTarget force;
            var replacement = new Object();
            var counts = new AtomicInteger[] {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(false);
                var old = new Object();
                var layout = new DataLayout(
                    language, "main:PausedModifyPair", "PausedModifyPair", new String[] {"LiftedRep", "IntRep"});
                var field =
                    new Thunk(pausing(language, leaf(language, replacement), counts[2], null).getCallTarget(), null);
                var record = layout.create(new Object[] {field, 7L});
                var body = pausing(language, leaf(language, record), counts[1], old);
                var closure = new Closure(null, 1, body.getCallTarget());
                var modifier =
                    new Thunk(pausing(language, leaf(language, closure), counts[0], null).getCallTarget(), null);
                cell = new ManagedMutVar(old);
                modified = cell.modify(modifier, new MutVarModifySite(language, metrics, true));
                selected = (Thunk) cell.getValue();
                assertSame(old, modified.getOld());
                assertEquals(List.of(0, 0, 0), counts(counts));
                force = force(language, metrics, true);
            } finally {
                context.leave();
            }
            // Each cut changes the Java carrier thread. The selector remains the
            // sole published MutVar value while all three continuations resume.
            for (int round = 0; round <= 2; round++) {
                var result = step(context, force, selected);
                if (!(result instanceof ThunkSuspended suspension))
                    throw new AssertionError("Expected owned selector cut " + round);
                assertSame(selected, suspension.getThunk());
                assertSame(selected, cell.getValue(), "CAS must not replay while the selector is suspended");
                var expected = new ArrayList<Integer>();
                for (int i = 0; i <= 2; i++) expected.add(i <= round ? 1 : 0);
                assertEquals(expected, counts(counts));
            }
            assertSame(replacement, step(context, force, selected));
            assertSame(selected, cell.getValue());
            assertEquals(List.of(1, 1, 1), counts(counts));
            context.enter();
            try {
                var record = (DataValue) Calls.target(force, new Object[] {modified.getResult()});
                assertSame(replacement, Calls.target(force, new Object[] {record.getLayout().readFirstLifted(record)}));
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void concurrentAtomicModifyBuildsOneLazyUpdateChain() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var counter = new DataLayout(language, "main:AtomicCounter", "AtomicCounter", new String[] {"IntRep"});
                var pair =
                    new DataLayout(language, "main:AtomicPair", "AtomicPair", new String[] {"LiftedRep", "IntRep"});
                var metrics = new Metrics(false);
                var modifier = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Child private Force force = new Force(metrics);
                    @Override
                    public long bloom(VirtualFrame frame) {
                        return (Long) frame.getArguments()[0];
                    }
                    @Override
                    public DataValue execute(VirtualFrame frame) {
                        var previous = (DataValue) force.execute(frame, frame.getArguments()[1]);
                        long n = counter.readLong(previous, 0);
                        return pair.create(new Object[] {counter.createLong(n + 1), n});
                    }
                };
                var closure = new Closure(null, 1, modifier.getCallTarget());
                var cell = new ManagedMutVar(counter.createLong(0));
                var site = new MutVarModifySite(language, metrics, false);
                var start = new CountDownLatch(1);
                var workers = Executors.newFixedThreadPool(4);
                try {
                    var futures = new ArrayList<Future<?>>();
                    for (int i = 0; i < 4; i++)
                        futures.add(workers.submit(() -> {
                            start.await();
                            for (int j = 0; j < 8; j++) cell.modify(closure, site);
                            return null;
                        }));
                    start.countDown();
                    for (var future : futures) future.get(10, TimeUnit.SECONDS);
                } finally {
                    workers.shutdownNow();
                    assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                }
                var force = force(language, metrics, false);
                var last = (DataValue) Calls.target(force, new Object[] {cell.getValue()});
                assertEquals(32L, counter.readLong(last, 0), "each CAS publishes one selector");
            } finally {
                context.leave();
            }
        }
    }
    private CoreRepresentation proof(CoreKind kind, List<String> reps) {
        return new CoreRepresentation(kind, false, true, reps, null, null, null, null, null);
    }
    private CoreRepresentation tuple(List<String> reps, List<CoreRepresentation> fields) {
        return new CoreRepresentation(CoreKind.UNKNOWN, false, true, reps, fields, null, null, null, null);
    }
    @Test
    void exactContractsAcceptLiftedAndUnliftedBoxedPayloadCarriers() {
        var state = proof(CoreKind.VOID, List.of());
        var reference = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)"));
        for (var kind : List.of(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT))
            for (var levity : List.of("Lifted", "Unlifted")) {
                var payload = proof(kind, List.of("BoxedRep (Just " + levity + ")"));
                for (var operation : List.of(MutVarOp.NEW, MutVarOp.READ, MutVarOp.SWAP, MutVarOp.WRITE)) {
                    var arguments = switch (operation) {
                        case NEW -> List.of(payload, state);
                        case READ -> List.of(reference, state);
                        case SWAP, WRITE -> List.of(reference, payload, state);
                        default -> throw new IllegalStateException("Separate atomic contract in BoxedCasTest");
                    };
                    var returned = operation == MutVarOp.NEW ? reference : payload;
                    var result = operation.getTuple() ? tuple(returned.getPrimReps(), List.of(state, returned)) : state;
                    var flags = new ArrayList<Boolean>();
                    for (var argument : arguments)
                        flags.add(List.of("BoxedRep (Just Lifted)").equals(argument.getPrimReps()));
                    operation.validate(arguments, flags, result);
                    var scalar = proof(CoreKind.LONG, List.of("IntRep"));
                    if (operation == MutVarOp.READ) {
                        var wrongResult = result.copy(result.getKind(), result.getEvaluated(), result.getPresent(),
                            scalar.getPrimReps(), List.of(state, scalar), result.getVector(), result.getAlternatives(),
                            result.getTagSlot(), result.getAlternativeSlots());
                        assertThrows(RuntimeFault.class, () -> operation.validate(arguments, flags, wrongResult));
                    } else {
                        int payloadIndex = operation == MutVarOp.NEW ? 0 : 1;
                        var wrongArguments = new ArrayList<>(arguments);
                        wrongArguments.set(payloadIndex, scalar);
                        assertThrows(RuntimeFault.class, () -> operation.validate(wrongArguments, flags, result));
                    }
                }
            }
    }
    private static class PublishedValue {
        int sequence;
        int complement;
    }
    private PublishedValue value(int sequence) {
        var value = new PublishedValue();
        value.sequence = sequence;
        value.complement = ~sequence;
        return value;
    }
    @Test
    void concurrentReadersSeeCompletePublishedValuesInWriteOrder() throws Exception {
        var reference = new ManagedMutVar(value(0));
        var done = new Object();
        var start = new CountDownLatch(1);
        var firstRead = new CountDownLatch(2);
        var workers = Executors.newFixedThreadPool(3);
        try {
            var readers = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                readers.add(workers.submit(() -> {
                    start.await();
                    int previous = 0;
                    boolean sawWrite = false;
                    while (true) {
                        if (Thread.currentThread().isInterrupted())
                            throw new InterruptedException("Reader cancelled");
                        var current = reference.getValue();
                        if (current == done)
                            break;
                        var published = (PublishedValue) current;
                        assertEquals(
                            ~published.sequence, published.complement, "Reader observed a partially published value");
                        assertTrue(published.sequence >= previous, "A reader went backward in the write order");
                        previous = published.sequence;
                        if (previous > 0 && !sawWrite) {
                            sawWrite = true;
                            firstRead.countDown();
                        }
                        Thread.onSpinWait();
                    }
                    assertTrue(sawWrite, "Reader missed every published write");
                    return previous;
                }));
            var writer = workers.submit(() -> {
                start.await();
                reference.setValue(value(1));
                assertTrue(firstRead.await(10, TimeUnit.SECONDS), "Readers did not observe the first write");
                for (int sequence = 2; sequence <= 25_000; sequence++) reference.setValue(value(sequence));
                reference.setValue(done);
                return null;
            });
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            for (var reader : readers) reader.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "MutVar workers did not terminate");
        }
    }
    @Test
    void atomicModifyRequiresLiftedFunctionAndTwoLiftedResults() {
        var state = proof(CoreKind.VOID, List.of());
        var reference = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)"));
        var lifted = List.of("BoxedRep (Just Lifted)");
        var function = proof(CoreKind.CLOSURE, lifted);
        var old = proof(CoreKind.DATA, lifted);
        var record = proof(CoreKind.DATA, lifted);
        var both = new ArrayList<>(lifted);
        both.addAll(lifted);
        var tuple = tuple(both, List.of(state, old, record));
        var args = List.of(reference, function, state);
        MutVarOp.MODIFY2.validate(args, List.of(false, true, false), tuple);
        assertThrows(RuntimeFault.class, () -> MutVarOp.MODIFY2.validate(args, List.of(false, false, false), tuple));
        var wrong = tuple.copy(tuple.getKind(), tuple.getEvaluated(), tuple.getPresent(), tuple.getPrimReps(),
            List.of(state, old), tuple.getVector(), tuple.getAlternatives(), tuple.getTagSlot(),
            tuple.getAlternativeSlots());
        assertThrows(RuntimeFault.class, () -> MutVarOp.MODIFY2.validate(args, List.of(false, true, false), wrong));
    }
    @Test
    void concurrentAtomicExchangesNeitherLoseNorDuplicateReferences() throws Exception {
        var initial = new Object();
        var replacements = new Object[8_000];
        for (int i = 0; i < replacements.length; i++) replacements[i] = new Object();
        var reference = new ManagedMutVar(initial);
        var displaced = new ConcurrentLinkedQueue<Object>();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker <= 3; worker++) {
                int first = worker;
                tasks.add(workers.submit(() -> {
                    start.await();
                    for (int index = first; index < replacements.length; index += 4)
                        displaced.add(Objects.requireNonNull(reference.exchange(replacements[index])));
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(replacements.length, displaced.size());
            var counts = new IdentityHashMap<Object, Integer>();
            for (var value : displaced) counts.put(value, counts.getOrDefault(value, 0) + 1);
            var last = Objects.requireNonNull(reference.getValue());
            counts.put(last, counts.getOrDefault(last, 0) + 1);
            assertEquals(replacements.length + 1, counts.size());
            var values = new ArrayList<Object>();
            values.add(initial);
            values.addAll(Arrays.asList(replacements));
            for (var value : values) assertEquals(1, counts.get(value));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "MutVar exchange workers did not terminate");
        }
    }
    private Expr operand(String name, Supplier<Object> action, List<String> events) {
        return new Expr() {
            @Override
            public Object execute(VirtualFrame frame) {
                events.add(name);
                return action.get();
            }
        };
    }
    @Test
    void stateFailurePrecedesMutationAndTuplePublication() throws Exception {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Object, "destination", null);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], builder.build());
        var events = new ArrayList<String>();
        var original = new Object();
        var replacement = new Object();
        var cell = new ManagedMutVar(original);
        var unknown = CoreRepresentation.UNKNOWN;
        var write = MutVarOp.expression(MutVarOp.WRITE, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> {
                    assertSame(original, cell.getValue());
                    return Unit.INSTANCE;
                }, events)});
        assertSame(Unit.INSTANCE, write.execute(frame));
        assertSame(replacement, cell.getValue());
        assertEquals(List.of("cell", "value", "state"), events);
        cell.setValue(original);
        var failingWrite = MutVarOp.expression(MutVarOp.WRITE, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> 1L, events)});
        assertThrows(RuntimeFault.class, () -> failingWrite.execute(frame));
        assertSame(original, cell.getValue());
        frame.setObject(slot, replacement);
        var failingSwap = MutVarOp.expression(MutVarOp.SWAP, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> 1L, events)});
        assertThrows(RuntimeFault.class, () -> failingSwap.executeTuple(frame, new int[] {slot}, 0));
        assertSame(original, cell.getValue());
        assertSame(replacement, frame.getObject(slot));
        var swap = MutVarOp.expression(MutVarOp.SWAP, unknown,
            new Expr[] {operand("cell", () -> cell, events), operand("value", () -> replacement, events),
                operand("state", () -> Unit.INSTANCE, events)});
        assertNull(swap.executeTuple(frame, new int[] {slot}, 0));
        assertSame(original, frame.getObject(slot));
        assertSame(replacement, cell.getValue());
        for (var operation : List.of(MutVarOp.NEW, MutVarOp.READ)) {
            frame.setObject(slot, replacement);
            var failing = MutVarOp.expression(operation, unknown,
                new Expr[] {operand("value", () -> operation == MutVarOp.READ ? cell : original, events),
                    operand("state", () -> { throw new RuntimeFault("state failed"); }, events)});
            assertThrows(RuntimeFault.class, () -> failing.executeTuple(frame, new int[] {slot}, 0));
            assertSame(replacement, frame.getObject(slot));
        }
    }
    @Test
    void bothBackendsRejectBadStateBeforeWritingAndKeepLiftedContentsLazy() {
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var reference = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var lifted = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var ids = List.of("cell", "value", "state");
        var proofs = List.of(reference, lifted, state);
        var parameters = new ArrayList<Map<String, Object>>();
        var args = new ArrayList<Object>();
        for (int i = 0; i < ids.size(); i++) {
            parameters.add(Map.of("id", ids.get(i), "lifted", ids.get(i).equals("value"), "rep", proofs.get(i)));
            args.add(List.of("var", ids.get(i), Map.of("rep", proofs.get(i))));
        }
        var body = List.of("app", List.of("prim", "writeMutVar#"), args, List.of(false, true, false), false, false,
            Map.of("rep", state));
        Map<String, Object> module = Map.of("bindings",
            List.of(Map.of("id", "write", "name", "write", "lifted", true, "rep", closure, "arity", 3, "expr",
                List.of("lam", parameters, body, Map.of("rep", closure, "resultRep", state)))));
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var p = program(language, module, backend);
                    var original = new Object();
                    var cell = new ManagedMutVar(original);
                    // A lifted value may be an unevaluated thunk. A raw sentinel is
                    // sufficient here: forcing it as a guest value would fail.
                    var replacement = new Object();
                    assertThrows(RuntimeFault.class,
                        ()
                            -> Calls.target(p.hostEntryTarget(3),
                                new Object[] {p.entryValue("write"), new Object[] {cell, replacement, 1L}}),
                        backend);
                    assertSame(original, cell.getValue(), backend + " must check State before mutation");
                    assertSame(Unit.INSTANCE,
                        Calls.target(p.hostEntryTarget(3),
                            new Object[] {p.entryValue("write"), new Object[] {cell, replacement, Unit.INSTANCE}}),
                        backend);
                    assertSame(replacement, cell.getValue(), backend + " must store the lifted operand unchanged");
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
            for (var item : list) result.addAll(applications(item));}else if(value instanceof Map<?,?> map)
            for (var item : map.values()) result.addAll(applications(item));
        return result;
    }
    private List<Object> application(Object module, MutVarOp operation) {
        for (var app : applications(module)) {
            var fn = (List<?>) app.get(1);
            if (fn.subList(0, Math.min(2, fn.size())).equals(List.of("prim", operation.getPrimitive())))
                return app;
        }
        throw new NoSuchElementException();
    }
    private Map<String, Object> configured(Map<String, Object> module, boolean diagnostic) {
        var result = new LinkedHashMap<>(module);
        result.put("diagnosticUnsupported", diagnostic);
        return result;
    }
    @Test
    void exactShapesAritySaturationAndLevityAreRequiredInBothLoadModes() throws Exception {
        var paths = Objects.requireNonNull(((Map<String, List<String>>) manifest().get("stages")).get("pre"));
        var operations = new ArrayList<MutVarOp>();
        for (var operation : MutVarOp.values())
            if (operation != MutVarOp.CAS && operation != MutVarOp.MODIFY)
                operations.add(operation);
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int mutation = 0; mutation <= 6; mutation++)
                            for (boolean diagnostic : new boolean[] {false, true}) {
                                var module = CoreModules.reachable(merged(paths),
                                    operation == MutVarOp.SWAP          ? "main:MutVarAudit.swapRef"
                                        : operation == MutVarOp.MODIFY2 ? "main:MutVarAudit.modifyRef"
                                                                        : "main:MutVarAudit.orderedRef");
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
                                    case 3 -> {
                                        var proof = (Map<String, Object>) metadata.get("rep");
                                        proof.put("primReps", List.of("IntRep"));
                                        proof.put("kind", "long");
                                        proof.remove("aggregate");
                                        proof.remove("components");
                                    }
                                    case 4 -> flags.set(0, !Boolean.TRUE.equals(flags.getFirst()));
                                    case 5 ->
                                        ((Map<String, Object>) CoreRepresentations
                                                .metadata((List<Object>) args.getFirst())
                                                .get("rep"))
                                            .put("kind", "unknown");
                                    case 6 -> {
                                        if (operation.getTuple()) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            var children = (List<Object>) proof.get("components");
                                            children.set(0,
                                                Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components",
                                                    List.of(), "primReps", List.of(), "evaluated", true));
                                        } else
                                            ((Map<String, Object>) CoreRepresentations
                                                    .metadata((List<Object>) args.getFirst())
                                                    .get("rep"))
                                                .put("primReps", List.of("BoxedRep (Just Lifted)"));
                                    }
                                }
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> program(language, configured(module, diagnostic), backend),
                                    backend + "/" + operation.getPrimitive() + "/mutation" + mutation + "/"
                                        + diagnostic);
                            }
                    for (var operation : operations) {
                        var module = CoreModules.reachable(merged(paths),
                            operation == MutVarOp.SWAP          ? "main:MutVarAudit.swapRef"
                                : operation == MutVarOp.MODIFY2 ? "main:MutVarAudit.modifyRef"
                                                                : "main:MutVarAudit.orderedRef");
                        var app = application(module, operation);
                        var primitive = new ArrayList<>((List<?>) app.get(1));
                        app.clear();
                        app.addAll(primitive);
                        assertThrows(UnsupportedCore.class, () -> program(language, module, backend));
                    }
                } finally {
                    context.leave();
                }
            }
    }
}
