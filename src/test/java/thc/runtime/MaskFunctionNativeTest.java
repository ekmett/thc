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
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.Main;
import thc.UnsupportedCore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class MaskFunctionNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/mask-functions");
    private final Map<String, String> primitives = new LinkedHashMap<>();
    private final Map<String, Long> offsets = new LinkedHashMap<>();
    {
        primitives.put("maskedFunction", "maskAsyncExceptions#"); primitives.put("unmaskedFunction", "unmaskAsyncExceptions#"); primitives.put("uninterruptibleFunction", "maskUninterruptible#");
        int i = 0; long[] values = {121L, 101L, 212L};
        for (var name : primitives.keySet()) offsets.put(name, values[i++]);
        offsets.put("lazyFunctions", 0L); offsets.put("bareMasks", 10L);
    }
    private Map<String, Object> source(String stage) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(directory.resolve(stage + "/core/MaskFunctionAudit.json"))); }
    private static List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(nodes(child));
        else if (value instanceof List<?> list) { result.add((List<Object>) list); for (var child : list) result.addAll(nodes(child)); }
        return result;
    }
    private static List<List<Object>> calls(Object value, String tag, String name) {
        var result = new ArrayList<List<Object>>();
        for (var node : nodes(value)) if (!node.isEmpty() && node.get(0).equals("app") && node.get(1) instanceof List<?> head &&
            !head.isEmpty() && tag.equals(head.get(0)) && head.size() > 1 && name.equals(head.get(1))) result.add(node);
        return result;
    }
    private record Row(long input, long output) {}
    private Map<String, List<Row>> rows() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(directory.resolve("manifest.json")));
        assertEquals("9.14.1", manifest.get("ghc")); assertEquals(new ArrayList<>(offsets.keySet()), manifest.get("entries"));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var entry : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            var file = root.resolve(entry.getKey()).toFile();
            assertTrue(file.getCanonicalFile().toPath().startsWith(root.toFile().getCanonicalFile().toPath()));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
            assertEquals(entry.getValue(), hash, kind + "/" + entry.getKey());
        }
        var rows = new ArrayList<String[]>();
        for (var line : Files.readAllLines(root.resolve((String) manifest.get("oracle")))) rows.add(line.split("\t", -1));
        assertEquals(15, rows.size());
        var grouped = new LinkedHashMap<String, List<String[]>>();
        for (var row : rows) grouped.computeIfAbsent(row[0], ignored -> new ArrayList<>()).add(row);
        var result = new LinkedHashMap<String, List<Row>>();
        for (var entry : grouped.entrySet()) {
            var pairs = new ArrayList<Row>();
            for (var row : entry.getValue()) pairs.add(new Row(Long.parseLong(row[1]), Long.parseLong(row[2])));
            var inputs = new ArrayList<Long>(); for (var row : pairs) inputs.add(row.input());
            assertEquals(List.of(-17L, 0L, 23L), inputs);
            for (var row : pairs) assertEquals(row.input() + offsets.get(entry.getKey()), row.output());
            result.put(entry.getKey(), pairs);
        }
        return result;
    }
    private static <T> T single(List<T> list) {
        if (list.isEmpty()) throw new NoSuchElementException("List is empty.");
        if (list.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        return list.getFirst();
    }
    private static List<CoreRepresentation> proofs(List<List<Object>> arguments) {
        var result = new ArrayList<CoreRepresentation>();
        for (var argument : arguments) result.add(CoreRepresentations.INSTANCE.expression(argument));
        return result;
    }
    @Test void genuinePartialMasksBecomeTypedLambdasWithSaturatedBodies() throws Exception {
        rows();
        for (var stage : List.of("pre", "post")) {
            var module = source(stage);
            for (var entry : primitives.entrySet()) {
                var name = entry.getKey(); var primitive = entry.getValue();
                var linked = CoreModules.reachable(module, name, true); var opaque = single(calls(linked, "var", "main:MaskFunctionAudit.applyLater"));
                var lambda = ((List<List<Object>>) opaque.get(2)).get(0);
                assertEquals("lam", lambda.get(0), stage + "/" + name + " passes the mask as a function");
                var binder = single((List<Map<String, Object>>) lambda.get(1));
                assertEquals(CoreKind.VOID, CoreRepresentations.INSTANCE.binder(binder).getKind());
                assertEquals(List.of(), CoreRepresentations.INSTANCE.binder(binder).getPrimReps());
                var body = (List<Object>) lambda.get(2); assertEquals(primitive, ((List<?>) body.get(1)).get(1));
                var arguments = (List<List<Object>>) body.get(2);
                assertEquals(binder.get("id"), arguments.get(1).get(1), "The state parameter is applied later");
                assertEquals(CoreKind.CLOSURE, CoreRepresentations.INSTANCE.expression(arguments.get(0)).getKind());
                assertEquals(CoreRepresentations.INSTANCE.expression(body), CoreRepresentations.INSTANCE.lambdaResult(lambda));
                CoreSynchronousExceptions.validate(primitive, proofs(arguments), (List<?>) body.get(3), CoreRepresentations.INSTANCE.expression(body));
                // This is only evidence about the retained GHC dump; execution
                // and proofs above consume the structural export exclusively.
                assertTrue(Pattern.compile("applyLater\\s+\\(" + Pattern.quote(primitive) + "\\s+@LiftedRep\\s+@Payload")
                    .matcher((String) module.get("sourceCore")).find(), stage + "/" + name + " original one-action Core");
            }
            var bare = CoreModules.reachable(module, "bareMasks", true); var wrappers = new ArrayList<List<Object>>();
            for (var call : calls(bare, "var", "main:MaskFunctionAudit.applyMask")) wrappers.add(((List<List<Object>>) call.get(2)).get(0));
            assertEquals(3, wrappers.size()); var actual = new LinkedHashSet<Object>();
            for (var wrapper : wrappers) {
                assertEquals("lam", wrapper.get(0)); assertEquals(2, ((List<?>) wrapper.get(1)).size());
                actual.add(((List<?>) ((List<?>) wrapper.get(2)).get(1)).get(1));
            }
            assertEquals(new LinkedHashSet<>(primitives.values()), actual);
            for (var entry : offsets.keySet()) {
                var audit = (Map<String, Object>) Json.parse(Files.readString(directory.resolve(stage + "/" + entry + "-audit.json")));
                assertEquals(true, audit.get("accepted"), stage + "/" + entry); assertEquals(List.of(), audit.get("missingGlobals")); assertEquals(List.of(), audit.get("issues"));
            }
        }
    }
    private static ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private static void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> found) {
        if (!seen.add(target)) return;
        var node = target.getRootNode(); var roots = new ArrayList<Node>(); roots.add(node);
        if (node instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) roots.add(cached); }
        var calls = new ArrayList<DirectCallNode>();
        for (var root : roots) calls.addAll(NodeUtil.findAllNodeInstances(root, DirectCallNode.class));
        var targets = new ArrayList<RootCallTarget>();
        for (var call : calls) if (call.getCurrentCallTarget() instanceof RootCallTarget current) targets.add(current);
        var guests = new ArrayList<RootCallTarget>();
        for (var current : targets) if (current.getRootNode() instanceof GuestRoot) guests.add(current);
        for (var current : guests) visit(current, seen, found);
        found.add(target);
    }
    private static List<RootCallTarget> targets(RootCallTarget entry) {
        var found = new ArrayList<RootCallTarget>(); var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); visit(entry, seen, found); return found;
    }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName()); }
    private static void check(Row row, RootCallTarget target, Language language, String label) {
        assertEquals(row.output(), Calls.target(target, new Object[]{0L, row.input()}), label);
        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()), label);
        assertEquals(0, language.getHandoffState().get().getArguments().getDepth(), label); assertEquals(0, language.getHandoffState().get().getResults().getDepth(), label);
    }
    @Test void nativeFunctionsMatchFirstInstalledAstAndBytecodeEntries() throws Exception {
        var oracle = rows();
        for (var stage : List.of("pre", "post")) for (var backend : List.of("ast", "bytecode")) for (var entryRows : oracle.entrySet())
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", "false")
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
                var entry = entryRows.getKey(); var selected = entryRows.getValue();
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = new LinkedHashMap<>(CoreModules.reachable(source(stage), entry, true)); linked.put("instrument", true);
                    var program = program(language, linked, backend); var target = program.entryTarget(entry); var label = stage + "/" + backend + "/" + entry;
                    for (int i = 0; i < 3; i++) for (var row : selected) check(row, target, language, label);
                    var active = targets(target); assertTrue(active.size() >= 2, label + " retains opaque guest calls");
                    for (var current : active) { current.getClass().getMethod("compile", boolean.class).invoke(current, true); valid(current); }
                    var installedRows = new ArrayList<>(selected.reversed()); installedRows.addAll(selected);
                    for (var row : installedRows) {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check(row, target, language, label);
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() - before >= active.size(), label + " first installed call must enter all retained compiled roots");
                        for (var current : active) valid(current);
                    }
                    assertEquals("reject-at-load", program.diagnostics().get("unsupportedPolicy"));
                    assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue(), label); assertEquals(0L, ((Number) program.diagnostics().get("blackholes")).longValue(), label);
                } finally { context.leave(); }
            }
    }
    private static Object rewrite(Object value, String primitive, Function<List<Object>, List<Object>> change) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<Object, Object>();
            for (var entry : map.entrySet()) result.put(entry.getKey(), rewrite(entry.getValue(), primitive, change)); return result;
        }
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0).equals("app") && list.get(1) instanceof List<?> head &&
                head.subList(0, Math.min(2, head.size())).equals(List.of("prim", primitive))) return change.apply((List<Object>) list);
            var result = new ArrayList<Object>(); for (var child : list) result.add(rewrite(child, primitive, change)); return result;
        }
        return value;
    }
    private static Map<String, Object> plus(Map<String, Object> source, String key, Object value) { var result = new LinkedHashMap<>(source); result.put(key, value); return result; }
    @Test void malformedMaskArityAndProofsStillFailInBothPolicies() throws Exception {
        for (var stage : List.of("pre", "post")) for (var entryPrimitive : primitives.entrySet()) {
            var entry = entryPrimitive.getKey(); var primitive = entryPrimitive.getValue();
            var linked = CoreModules.reachable(source(stage), entry, true); var app = single(calls(linked, "prim", primitive));
            var arguments = proofs((List<List<Object>>) app.get(2)); var flags = (List<?>) app.get(3); var result = CoreRepresentations.INSTANCE.expression(app);
            assertThrows(RuntimeFault.class, () -> CoreSynchronousExceptions.validate(primitive, arguments.subList(0, Math.min(1, arguments.size())), List.of(true), result));
            assertThrows(RuntimeFault.class, () -> {
                var extraArguments = new ArrayList<>(arguments); extraArguments.add(arguments.getLast()); var extraFlags = new ArrayList<Object>(flags); extraFlags.add(false);
                CoreSynchronousExceptions.validate(primitive, extraArguments, extraFlags, result);
            });
            assertThrows(RuntimeFault.class, () -> CoreSynchronousExceptions.validate(primitive, arguments, flags,
                result.copy(result.getKind(), result.getEvaluated(), result.getPresent(), result.getPrimReps(), new ArrayList<>(result.getComponents().reversed()),
                    result.getVector(), result.getAlternatives(), result.getTagSlot(), result.getAlternativeSlots())));
            List<Function<List<Object>, List<Object>>> mutations = List.of(
                call -> { var changed = new ArrayList<>(call); var args = (List<?>) changed.get(2); changed.set(2, new ArrayList<>(args.subList(0, Math.min(1, args.size())))); changed.set(3, List.of(true)); return changed; },
                call -> { var changed = new ArrayList<>(call); changed.set(3, List.of(true, true)); return changed; },
                call -> { var changed = new ArrayList<>(call); var metadata = (Map<String, Object>) changed.getLast(); var proof = (Map<String, Object>) metadata.get("rep");
                    changed.set(changed.size() - 1, plus(metadata, "rep", plus(proof, "components", new ArrayList<>(((List<?>) proof.get("components")).reversed())))); return changed; });
            for (var backend : List.of("ast", "bytecode")) for (boolean diagnostic : List.of(false, true)) try (var context = Main.executionContext()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var input = plus(linked, "diagnosticUnsupported", diagnostic);
                    program(language, input, backend);
                    for (int index = 0; index < mutations.size(); index++) {
                        var broken = (Map<String, Object>) rewrite(input, primitive, mutations.get(index));
                        assertThrows(RuntimeFault.class, () -> program(language, broken, backend), stage + "/" + backend + "/" + diagnostic + "/" + primitive + "/mutation=" + index);
                    }
                    // Missing aggregate evidence follows the existing general
                    // policy: strict loading rejects; diagnostic loading keeps
                    // an explicit trap that must fail when this action runs.
                    var missing = (Map<String, Object>) rewrite(input, primitive, call -> {
                        var changed = new ArrayList<>(call); var metadata = (Map<String, Object>) changed.getLast();
                        var proof = new LinkedHashMap<>((Map<String, Object>) metadata.get("rep")); proof.remove("components");
                        changed.set(changed.size() - 1, plus(metadata, "rep", proof)); return changed;
                    });
                    if (!diagnostic) assertThrows(UnsupportedCore.class, () -> program(language, missing, backend));
                    else {
                        var deferred = program(language, missing, backend); assertTrue(!((List<?>) deferred.diagnostics().get("deferredUnsupported")).isEmpty());
                        assertEquals(0L, deferred.diagnostics().get("unsupportedTraps"));
                        assertThrows(RuntimeFault.class, () -> Calls.target(deferred.entryTarget(entry), new Object[]{0L, 0L}));
                        assertEquals(1L, deferred.diagnostics().get("unsupportedTraps"));
                    }
                } finally { context.leave(); }
            }
        }
    }
}
