// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public final class NarrowLiteralProofTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/narrow-literal-proofs");
    private final List<String> kinds = List.of("Int8", "Word8", "Int16", "Word16", "Int32", "Word32");
    private final Map<String, Object> unknown = unknown();

    private Map<String, Object> unknown() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "unknown");
        result.put("primReps", null);
        result.put("evaluated", false);
        return result;
    }

    @BeforeEach void provenance() throws Exception {
        var manifest = (Map<String, Object>) Json.INSTANCE.parse(Files.readString(directory.resolve("manifest.json")));
        for (String section : List.of("sources", "artifacts")) for (var record : (List<Map<String, String>>) manifest.get(section)) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(record.get("path")))));
            assertEquals(record.get("sha256"), hash, "Stale narrow literal input: " + record.get("path"));
        }
        assertEquals(54L, manifest.get("nativeRows"));
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "initial installation");
    }
    private void visitTarget(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return;
        List<Node> nodes = new ArrayList<>();
        nodes.add(target.getRootNode());
        if (target.getRootNode() instanceof BytecodeRoot bytecode) {
            for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments()) {
                if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                    Node cached = argument.asCachedNode();
                    if (cached != null) nodes.add(cached);
                }
            }
        }
        for (Node node : nodes) for (DirectCallNode call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) {
            if (call.getCurrentCallTarget() instanceof RootCallTarget active && active.getRootNode() instanceof GuestRoot)
                visitTarget(active, seen, result);
        }
        result.add(target);
    }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        Set<RootCallTarget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<RootCallTarget> result = new ArrayList<>();
        visitTarget(entry, seen, result);
        return result;
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module);
    }
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) Json.INSTANCE.parse(Files.readString(directory.resolve(stage + "-core/NarrowLiteralProofAudit.json")));
    }
    private int visitWrites(Object value, Consumer<List<Object>> action) {
        int count = 0;
        if (value instanceof List<?> values && !values.isEmpty() && "app".equals(values.getFirst())) {
            var function = (List<?>) values.get(1);
            if (!function.isEmpty() && "prim".equals(function.getFirst()) && kinds.stream().anyMatch(k -> ("write" + k + "Array#").equals(function.get(1)))) {
                var literal = (List<Object>) ((List<?>) values.get(2)).get(2);
                assertEquals("lit", literal.get(0));
                assertTrue(kinds.stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).equals(literal.get(1))));
                action.accept(literal);
                count++;
            }
        }
        if (value instanceof Map<?, ?> fields) for (Object item : fields.values()) count += visitWrites(item, action);
        else if (value instanceof List<?> values) for (Object item : values) count += visitWrites(item, action);
        return count;
    }
    private Map<String, Object> project(String stage, String variant, Map<String, Object> proof) throws Exception {
        var module = module(stage);
        assertEquals(24, visitWrites(module, literal -> {
            var original = (Map<String, Object>) ((Map<String, Object>) literal.get(3)).get("rep");
            var names = kinds.stream().filter(k -> k.toLowerCase(Locale.ROOT).equals(literal.get(1))).toList();
            assertEquals(1, names.size());
            assertEquals(Map.of("kind", "long", "primReps", List.of(names.getFirst() + "Rep"), "evaluated", true), original);
            switch (variant) {
                case "absent" -> literal.subList(3, literal.size()).clear();
                case "unknown" -> ((Map<String, Object>) literal.get(3)).put("rep", unknown);
                case "bad" -> ((Map<String, Object>) literal.get(3)).put("rep", proof);
                case "exact" -> { }
                default -> throw new IllegalStateException(variant);
            }
        }));
        return module;
    }
    @Test void directWritesPreserveIntrinsicRepresentationsWithInlining() throws Exception { nativeChecks(true); }
    @Test void directWritesPreserveIntrinsicRepresentationsAcrossResidualCalls() throws Exception { nativeChecks(false); }

    private void check(Language language, Value function, long[] values, String label, String[] row) {
        long input = Long.parseLong(row[1]), expected = values[(int) (input & 3)];
        assertEquals(expected, Long.parseLong(row[2]), label + " independent sign/index model");
        assertEquals(expected, function.execute(input).asLong(), label + "/" + input);
        var state = language.getHandoffState$org_intelligence_thc().get();
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getArguments().retainedReferences$org_intelligence_thc());
        assertEquals(0, state.getResults().retainedReferences());
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var rows = Files.readAllLines(directory.resolve("oracle.tsv")).stream().map(line -> line.split("\t")).toList();
        assertEquals(54, rows.size());
        var integral = kinds.stream().map(k -> k + "Rep").toList();
        List<String> variants = new ArrayList<>(List.of("exact", "absent", "unknown"));
        variants.addAll(integral);
        for (String stage : List.of("pre", "post")) for (String variant : variants) {
            var projected = integral.contains(variant)
                ? project(stage, "bad", Map.of("kind", "long", "primReps", List.of(variant), "evaluated", true))
                : project(stage, variant, null);
            for (String kind : kinds) for (String backend : List.of("ast", "bytecode")) try (Context context = context(inlining)) {
                context.initialize("thc");
                context.enter();
                try {
                    Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    String name = "write" + kind + "Literal";
                    Map<String, Object> module = new LinkedHashMap<>(CoreModules.INSTANCE.reachable(projected, name, false));
                    module.put("instrument", true);
                    var program = program(language, module, backend);
                    Value function = context.asValue(new EntryValue(program, name, 1, null, null, null, null, null, false, null));
                    RootCallTarget host = program.hostEntryTarget(1), original = program.entryTarget(name);
                    var selected = rows.stream().filter(r -> r[0].equals(name)).toList();
                    int bits = Integer.parseInt(kind.replaceAll("\\D", ""));
                    long[] values = kind.startsWith("Int") ? new long[] {-(1L << (bits - 1)), -1, 0, (1L << (bits - 1)) - 1}
                        : new long[] {0, (1L << (bits - 1)) - 1, 1L << (bits - 1), (1L << bits) - 1};
                    String label = stage + "/" + backend + "/" + kind + "/" + variant + "/inlining=" + inlining;
                    for (String[] row : selected) check(language, function, values, label, row);
                    var active = targets(host);
                    assertTrue(active.size() > 1, label + " observed host-to-guest path");
                    for (RootCallTarget target : active) if (target != host) compile(target);
                    assertTrue(function.invokeMember("compile").asBoolean());
                    for (String[] row : selected.reversed()) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        check(language, function, values, label, row);
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label + " compiled guest entry");
                        assertEquals(active, targets(host), label + " active identities");
                        valid(original, label + " original");
                        for (RootCallTarget target : active) valid(target, label + " active");
                    }
                    for (String counter : List.of("unsupportedTraps", "blackholes"))
                        assertEquals(0L, ((Number) program.diagnostics().get(counter)).longValue(), label + "/" + counter);
                    System.out.println("NarrowLiteralProof PASS " + label + " rows=" + selected.size());
                } finally { context.leave(); }
            }
        }
    }

    @Test void differentCarriersAndMalformedPresentProofsFailBothLoaders() throws Exception {
        List<Map<String, Object>> bad = new ArrayList<>(List.of(Map.of(), Map.of("kind", "long", "primReps", List.of("Int8Rep")),
            Map.of("kind", "unknown", "primReps", "Int8Rep", "evaluated", true),
            Map.of("kind", "unknown", "primReps", List.of("Int8Rep"), "evaluated", true),
            Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true),
            Map.of("kind", "void", "primReps", List.of(), "evaluated", true),
            Map.of("kind", "unknown", "primReps", List.of(), "evaluated", true, "aggregate", "unboxed-tuple", "components", List.of())));
        for (String rep : List.of("IntRep", "WordRep", "Int64Rep", "Word64Rep"))
            bad.add(Map.of("kind", "long", "primReps", List.of(rep), "evaluated", true));
        for (String backend : List.of("ast", "bytecode")) try (Context context = context(true)) {
            context.initialize("thc");
            context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String stage : List.of("pre", "post")) for (var proof : bad) for (String kind : kinds) {
                    var module = CoreModules.INSTANCE.reachable(project(stage, "bad", proof), "write" + kind + "Literal", false);
                    assertThrows(RuntimeFault.class, () -> program(language, module, backend), stage + "/" + backend + "/" + kind + "/" + proof);
                }
            } finally { context.leave(); }
        }
    }

    @Test void intrinsicProofDoesNotAdmitInvalidValuesOrBroadenOtherLiteralKinds() throws Exception {
        for (String kind : kinds) {
            int bits = Integer.parseInt(kind.replaceAll("\\D", ""));
            long[] invalid = kind.startsWith("Int") ? new long[] {-(1L << (bits - 1)) - 1, 1L << (bits - 1)} : new long[] {-1, 1L << bits};
            for (String backend : List.of("ast", "bytecode")) try (Context context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (long value : invalid) {
                        var module = CoreModules.INSTANCE.reachable(project("pre", "absent", null), "write" + kind + "Literal", false);
                        assertEquals(4, visitWrites(module, literal -> literal.set(2, Long.toString(value))));
                        assertThrows(RuntimeFault.class, () -> program(language, module, backend));
                    }
                } finally { context.leave(); }
            }
        }
        for (String kind : List.of("int", "word", "int64", "word64", "float", "double", "char", "string-bytes", "null-addr")) {
            for (boolean metadata : new boolean[] {false, true}) {
                List<Object> literal = new ArrayList<>(List.of("lit", kind, "0"));
                if (metadata) literal.add(Map.of("rep", unknown));
                var proof = CoreRepresentations.INSTANCE.expression(literal);
                assertEquals(CoreKind.UNKNOWN, proof.getKind(), kind);
                assertNull(proof.getPrimReps(), kind);
            }
        }
    }
}
