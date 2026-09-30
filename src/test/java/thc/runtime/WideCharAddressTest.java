// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class WideCharAddressTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<Long> inputs = List.of(
        0L, 1L, 127L, 128L, 255L, 256L, 32767L, 32768L, 55295L, 55296L, 57343L, 57344L, 65535L, 65536L, 1114111L);
    private record Row(long input, long result) {}
    private Map<String, Object> source(String stage) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var file : List.of("WideCharAddressAudit.cbd", "THC.InterfaceClosure.cbd"))
            modules.add(thc.CoreCbdFixtures.read(new File(root, "build/wide-char-address/" + stage + "/core/" + file).toPath()));
        return CoreModules.merge(modules);
    }
    private List<List<Object>> calls(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map)
            for (var child : map.values()) result.addAll(calls(child));
        else if (value instanceof List<?> list) {
            var head = list.size() > 1 && list.get(1) instanceof List<?> h ? h : null;
            if (!list.isEmpty() && "app".equals(list.getFirst()) && head != null && !head.isEmpty()
                && "prim".equals(head.getFirst()))
                result.add((List<Object>) list);
            for (var child : list) result.addAll(calls(child));
        }
        return result;
    }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        var found = new ArrayList<RootCallTarget>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        visit(entry, seen, found);
        return found;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> found) {
        if (!seen.add(target))
            return;
        var rootNode = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(rootNode);
        if (rootNode instanceof BytecodeRoot bytecode)
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
                    visit(active, seen, found);
        found.add(target);
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(
            true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName());
    }
    private List<Row> rows() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(
            Files.readString(new File(root, "build/wide-char-address/manifest.json").toPath()));
        assertEquals("9.14.1", manifest.get("ghc"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var file = new File(root, item.getKey());
                assertTrue(file.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()));
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
                assertEquals(item.getValue(), actual, kind + " " + item.getKey());
            }
        var pairs = new ArrayList<Row>();
        var actualInputs = new ArrayList<Long>();
        for (var line : Files.readAllLines(new File(root, (String) manifest.get("oracle")).toPath())) {
            var fields = line.split("\t", -1);
            assertEquals(2, fields.length);
            var row = new Row(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
            pairs.add(row);
            actualInputs.add(row.input());
        }
        assertEquals(inputs, actualInputs);
        for (var row : pairs) assertEquals(row.input() * 4294967296L + row.input(), row.result());
        return pairs;
    }
    private CoreRepresentation copy(CoreRepresentation original, CoreKind kind, List<String> reps) {
        return original.copy(kind, original.getEvaluated(), original.getPresent(), reps, original.getComponents(),
            original.getVector(), original.getAlternatives(), original.getTagSlot(), original.getAlternativeSlots());
    }
    @Test
    void originalWideCharCoreHasThreeExactPrimitiveContracts() throws Exception {
        rows();
        var names = Set.of("indexWideCharOffAddr#", "readWideCharOffAddr#", "writeWideCharOffAddr#");
        for (var stage : List.of("pre", "post")) {
            var module = source(stage);
            var uses = new ArrayList<List<Object>>();
            var actualNames = new HashSet<Object>();
            for (var app : calls(CoreModules.reachable(module, "main:WideCharAddressAudit.wideCharRoundtrip"))) {
                var head = (List<?>) app.get(1);
                if (head.size() > 1 && names.contains(head.get(1))) {
                    uses.add(app);
                    actualNames.add(head.get(1));
                }
            }
            assertEquals(names, actualNames);
            assertEquals(3, uses.size());
            for (var app : uses) {
                var op = Objects.requireNonNull(PinnedMemoryOp.named((String) ((List<?>) app.get(1)).get(1)));
                var arguments = new ArrayList<CoreRepresentation>();
                for (var argument : (List<List<Object>>) app.get(2))
                    arguments.add(CoreRepresentations.expression(argument));
                var result = CoreRepresentations.expression(app);
                op.validate(arguments, (List<?>) app.get(3), result);
                assertThrows(RuntimeFault.class, () -> {
                    var changed = new ArrayList<>(arguments);
                    changed.set(0, copy(changed.getFirst(), changed.getFirst().getKind(), List.of("IntRep")));
                    op.validate(changed, (List<?>) app.get(3), result);
                });
                assertThrows(RuntimeFault.class,
                    () -> op.validate(arguments, Collections.nCopies(arguments.size(), true), result));
                assertThrows(RuntimeFault.class,
                    ()
                        -> op.validate(
                            arguments, (List<?>) app.get(3), copy(result, CoreKind.DOUBLE, List.of("DoubleRep"))));
            }
            var audit = (Map<String, Object>) Json.parse(
                Files.readString(new File(root, "build/wide-char-address/" + stage + "/audit.json").toPath()));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("missingGlobals"));
        }
    }
    private void check(Row row, RootCallTarget entry, Language language, String label) {
        assertEquals(row.result(), Calls.target(entry, new Object[] {0L, row.input()}), label + "/" + row.input());
        assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
    }
    @Test
    void nativeWideCharRowsMatchInstalledCompiledAstAndBytecode() throws Exception {
        var oracle = rows();
        for (var stage : List.of("pre", "post"))
            for (var backend : List.of("ast", "bytecode"))
                for (boolean inlining : new boolean[] {false, true})
                    try (var context = Context.newBuilder("thc")
                             .allowExperimentalOptions(true)
                             .option("compiler.Inlining", Boolean.toString(inlining))
                             .option("engine.BackgroundCompilation", "false")
                             .option("engine.MultiTier", "false")
                             .option("engine.CompilationFailureAction", "Throw")
                             .option("engine.SingleTierCompilationThreshold", "10000000")
                             .build()) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var module = new LinkedHashMap<>(CoreModules.reachable(source(stage), "main:WideCharAddressAudit.wideCharRoundtrip"));
                            module.put("instrument", true);
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, module)
                                                                              : new BytecodeProgram(language, module);
                            var entry = program.entryTarget("main:WideCharAddressAudit.wideCharRoundtrip");
                            var label = stage + "/" + backend + "/" + inlining;
                            for (int i = 0; i < 3; i++)
                                for (var row : oracle) check(row, entry, language, label);
                            var active = targets(entry);
                            assertTrue(active.size() >= 2, "Retain the real runRW action");
                            for (var target : active) {
                                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                                valid(target);
                            }
                            var sequence = new ArrayList<>(oracle.reversed());
                            sequence.addAll(oracle);
                            for (var row : sequence) {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                check(row, entry, language, label);
                                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() - before
                                    >= active.size());
                                for (var target : active) valid(target);
                            }
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        } finally {
                            context.leave();
                        }
                    }
    }
    @Test
    void wideCharUsesFourBytesAndRejectsInvalidStorageWithoutMutation() {
        var base = ManagedAddress.fromAllocation(PinnedMemory.allocate(32, 1));
        var interior = base.plus(12);
        for (long value : inputs) {
            interior.writeNativeScalar(-1, 4, value);
            assertEquals(value, ManagedAddressRead.WIDE_CHAR.read(interior, -1));
            assertEquals(value, ManagedAddressRead.WIDE_CHAR.read(base, 2));
            for (int b = 0; b < 4; b++) {
                int shift = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? b * 8 : (3 - b) * 8;
                assertEquals((value >>> shift) & 255L, base.readWord8(8L + b));
            }
        }
        assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WIDE_CHAR.read(base, -1));
        assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WIDE_CHAR.read(base, Long.MAX_VALUE));
        assertThrows(RuntimeFault.class, () -> base.writeNativeScalar(-1, 4, 7));
        assertThrows(RuntimeFault.class, () -> base.writeNativeScalar(8, 4, 7));
        assertEquals(inputs.getLast().longValue(), ManagedAddressRead.WIDE_CHAR.read(base, 2));
        var target = base.plus(24);
        base.writeAddressElementIndex(2, target); // pointer cell at bytes 16..23
        assertThrows(RuntimeFault.class, () -> base.writeNativeScalar(4, 4, 7));
        assertThrows(RuntimeFault.class, () -> ManagedAddressRead.WIDE_CHAR.read(base, 4));
        assertSame(target, base.readAddressElementIndex(2));
    }
}
